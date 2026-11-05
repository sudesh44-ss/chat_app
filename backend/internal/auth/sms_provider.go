package auth

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

type SmsProvider interface {
	SendOtp(phoneNumber string, otpCode string) error
	Name() string
}

// maskPhone masks middle digits for secure logging without exposing full MSISDNs.
func maskPhone(phone string) string {
	if len(phone) <= 6 {
		return "***"
	}
	prefix := phone[:3]
	suffix := phone[len(phone)-4:]
	return fmt.Sprintf("%s****%s", prefix, suffix)
}

// TwilioProvider implements SMS delivery via Twilio API
type TwilioProvider struct {
	accountSid string
	authToken  string
	fromNumber string
	httpClient *http.Client
}

func NewTwilioProvider(sid, token, from string) *TwilioProvider {
	return &TwilioProvider{
		accountSid: sid,
		authToken:  token,
		fromNumber: from,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
}

func (t *TwilioProvider) Name() string { return "Twilio" }

func (t *TwilioProvider) SendOtp(phone string, otp string) error {
	apiURL := fmt.Sprintf("https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json", t.accountSid)
	msgBody := fmt.Sprintf("<#> Your NEXA verification code is: %s. Valid for 5 minutes. Do not share this code.", otp)

	data := url.Values{}
	data.Set("To", phone)
	data.Set("From", t.fromNumber)
	data.Set("Body", msgBody)

	req, err := http.NewRequest(http.MethodPost, apiURL, strings.NewReader(data.Encode()))
	if err != nil {
		return err
	}

	req.SetBasicAuth(t.accountSid, t.authToken)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := t.httpClient.Do(req)
	if err != nil {
		log.Printf("[TwilioProvider] Delivery error to %s: %v", maskPhone(phone), err)
		return fmt.Errorf("twilio delivery error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		bodyBytes, _ := io.ReadAll(resp.Body)
		log.Printf("[TwilioProvider] HTTP error %d: %s", resp.StatusCode, string(bodyBytes))
		return fmt.Errorf("twilio HTTP error: status %d", resp.StatusCode)
	}
	log.Printf("[TwilioProvider] SMS dispatched successfully to %s", maskPhone(phone))
	return nil
}

// Msg91Provider implements SMS delivery via MSG91 API (India)
type Msg91Provider struct {
	authKey    string
	templateID string
	apiURL     string
	httpClient *http.Client
}

type msg91Response struct {
	Message string `json:"message"`
	Type    string `json:"type"`
}

func NewMsg91Provider(authKey, templateID string) *Msg91Provider {
	return &Msg91Provider{
		authKey:    authKey,
		templateID: templateID,
		apiURL:     "https://api.msg91.com/api/v5/otp",
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
}

func (m *Msg91Provider) SetApiURL(apiURL string) {
	m.apiURL = apiURL
}

func (m *Msg91Provider) SetHttpClient(client *http.Client) {
	m.httpClient = client
}

func (m *Msg91Provider) Name() string { return "MSG91" }

func (m *Msg91Provider) SendOtp(phone string, otp string) error {
	cleanPhone := strings.TrimPrefix(strings.TrimSpace(phone), "+")
	
	// Construct request URL according to official MSG91 OTP v5 specification
	reqURL, err := url.Parse(m.apiURL)
	if err != nil {
		return fmt.Errorf("msg91 url parse error: %w", err)
	}

	q := reqURL.Query()
	q.Set("template_id", m.templateID)
	q.Set("mobile", cleanPhone)
	q.Set("otp", otp)
	reqURL.RawQuery = q.Encode()

	req, err := http.NewRequest(http.MethodPost, reqURL.String(), nil)
	if err != nil {
		return err
	}

	// Supply credentials via header and content headers
	req.Header.Set("authkey", m.authKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.httpClient.Do(req)
	if err != nil {
		log.Printf("[Msg91Provider] Network delivery error to %s: %v", maskPhone(phone), err)
		return fmt.Errorf("msg91 delivery error: %w", err)
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("msg91 response read error: %w", err)
	}

	// Parse MSG91 JSON response
	var msgResp msg91Response
	if err := json.Unmarshal(bodyBytes, &msgResp); err == nil {
		if strings.EqualFold(msgResp.Type, "error") {
			log.Printf("[Msg91Provider] Provider rejected dispatch to %s: %s", maskPhone(phone), msgResp.Message)
			return fmt.Errorf("msg91 rejected dispatch: %s", msgResp.Message)
		}
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		log.Printf("[Msg91Provider] HTTP error %d from provider", resp.StatusCode)
		return fmt.Errorf("msg91 HTTP error: status %d", resp.StatusCode)
	}

	log.Printf("[Msg91Provider] OTP accepted for cellular delivery to %s", maskPhone(phone))
	return nil
}

// UnavailableSmsProvider handles production scenarios where no SMS gateway credentials are configured
type UnavailableSmsProvider struct {
	reason string
}

func (u *UnavailableSmsProvider) Name() string { return "Unavailable" }

func (u *UnavailableSmsProvider) SendOtp(phone string, otp string) error {
	log.Printf("[UnavailableSmsProvider] Rejection: cannot dispatch OTP to %s (%s)", maskPhone(phone), u.reason)
	return fmt.Errorf("SMS delivery service is unavailable: %s", u.reason)
}

// MockSmsProvider for automated tests and explicit local development only
type MockSmsProvider struct {
	mu           sync.Mutex
	SentMessages map[string][]string // phone -> list of OTPs sent
}

func NewMockSmsProvider() *MockSmsProvider {
	return &MockSmsProvider{
		SentMessages: make(map[string][]string),
	}
}

func (m *MockSmsProvider) Name() string { return "MockSmsProvider" }

func (m *MockSmsProvider) SendOtp(phone string, otp string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.SentMessages[phone] = append(m.SentMessages[phone], otp)
	
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "production" {
		log.Printf("[MockSmsProvider] SECURITY ALERT: Mock provider invoked in production environment!")
		return fmt.Errorf("mock SMS provider is strictly prohibited in production")
	}

	// Safe development logging without exposing OTP unless explicitly requested
	if os.Getenv("DEBUG_SHOW_OTP") == "true" || env == "test" || env == "" {
		log.Printf("[MockSmsProvider] (DEV MODE) Dispatched test OTP to %s: %s", maskPhone(phone), otp)
	} else {
		log.Printf("[MockSmsProvider] (DEV MODE) Dispatched test OTP to %s", maskPhone(phone))
	}
	return nil
}

// GetLastOtp retrieves the most recently dispatched OTP for a phone number (used in tests)
func (m *MockSmsProvider) GetLastOtp(phone string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	list := m.SentMessages[phone]
	if len(list) == 0 {
		return ""
	}
	return list[len(list)-1]
}

// GetDefaultSmsProvider returns the configured SMS provider based on environment variables
func GetDefaultSmsProvider() SmsProvider {
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}

	// 1. Check MSG91 (Preferred for India DLT SMS delivery)
	msg91Key := strings.TrimSpace(os.Getenv("MSG91_AUTH_KEY"))
	msg91Template := strings.TrimSpace(os.Getenv("MSG91_TEMPLATE_ID"))
	if msg91Key != "" && msg91Template != "" {
		log.Println("[SmsProvider] Initialized production MSG91 SMS Provider")
		return NewMsg91Provider(msg91Key, msg91Template)
	}

	// 2. Check Twilio (Global / US fallback)
	twilioSid := strings.TrimSpace(os.Getenv("TWILIO_ACCOUNT_SID"))
	twilioToken := strings.TrimSpace(os.Getenv("TWILIO_AUTH_TOKEN"))
	twilioFrom := strings.TrimSpace(os.Getenv("TWILIO_FROM_NUMBER"))
	if twilioFrom == "" {
		twilioFrom = strings.TrimSpace(os.Getenv("TWILIO_PHONE_NUMBER"))
	}
	if twilioSid != "" && twilioToken != "" && twilioFrom != "" {
		log.Println("[SmsProvider] Initialized production Twilio SMS Provider")
		return NewTwilioProvider(twilioSid, twilioToken, twilioFrom)
	}

	// 3. Strict production safety check: Never permit mock fallback in production
	if env == "production" {
		log.Println("[SmsProvider] CRITICAL: Running in production with NO SMS provider configured! Mock fallback is DISABLED.")
		return &UnavailableSmsProvider{
			reason: "missing MSG91_AUTH_KEY/MSG91_TEMPLATE_ID or TWILIO credentials in production environment",
		}
	}

	log.Println("[SmsProvider] Initialized MockSmsProvider for development/testing environment")
	return NewMockSmsProvider()
}
