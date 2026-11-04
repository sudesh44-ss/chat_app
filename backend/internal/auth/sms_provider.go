package auth

import (
	"fmt"
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
		return fmt.Errorf("twilio delivery error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("twilio HTTP error: status %d", resp.StatusCode)
	}
	return nil
}

// Msg91Provider implements SMS delivery via MSG91 API (India)
type Msg91Provider struct {
	authKey    string
	templateID string
	httpClient *http.Client
}

func NewMsg91Provider(authKey, templateID string) *Msg91Provider {
	return &Msg91Provider{
		authKey:    authKey,
		templateID: templateID,
		httpClient: &http.Client{Timeout: 10 * time.Second},
	}
}

func (m *Msg91Provider) Name() string { return "MSG91" }

func (m *Msg91Provider) SendOtp(phone string, otp string) error {
	cleanPhone := strings.TrimPrefix(phone, "+")
	apiURL := fmt.Sprintf("https://api.msg91.com/api/v5/otp?template_id=%s&mobile=%s&authkey=%s&otp=%s",
		m.templateID, cleanPhone, m.authKey, otp)

	req, err := http.NewRequest(http.MethodPost, apiURL, nil)
	if err != nil {
		return err
	}

	resp, err := m.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("msg91 delivery error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("msg91 HTTP error: status %d", resp.StatusCode)
	}
	return nil
}

// MockSmsProvider for automated tests and local dev
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
	log.Printf("[MockSmsProvider] Dispatched test OTP to %s: %s", phone, otp)
	return nil
}

// GetDefaultSmsProvider returns the configured SMS provider based on environment variables
func GetDefaultSmsProvider() SmsProvider {
	env := os.Getenv("APP_ENV")
	if env == "" {
		env = os.Getenv("ENV")
	}

	twilioSid := os.Getenv("TWILIO_ACCOUNT_SID")
	twilioToken := os.Getenv("TWILIO_AUTH_TOKEN")
	twilioFrom := os.Getenv("TWILIO_FROM_NUMBER")
	if twilioSid != "" && twilioToken != "" && twilioFrom != "" {
		log.Println("[SmsProvider] Using Twilio SMS Provider")
		return NewTwilioProvider(twilioSid, twilioToken, twilioFrom)
	}

	msg91Key := os.Getenv("MSG91_AUTH_KEY")
	msg91Template := os.Getenv("MSG91_TEMPLATE_ID")
	if msg91Key != "" && msg91Template != "" {
		log.Println("[SmsProvider] Using MSG91 SMS Provider")
		return NewMsg91Provider(msg91Key, msg91Template)
	}

	if env == "production" {
		log.Println("[SmsProvider] WARNING: No production SMS provider configured! Check TWILIO_* or MSG91_* env vars.")
	}

	log.Println("[SmsProvider] Using MockSmsProvider (development/testing)")
	return NewMockSmsProvider()
}
