package auth

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

// TestMsg91Adapter_Success tests successful acceptance by MSG91 API using mock transport
func TestMsg91Adapter_Success(t *testing.T) {
	mockTransport := roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.Header.Get("authkey") != "test_secret_auth_key" {
			t.Errorf("expected authkey header 'test_secret_auth_key', got '%s'", r.Header.Get("authkey"))
		}
		if r.URL.Query().Get("template_id") != "test_template_123" {
			t.Errorf("template_id mismatch: %s", r.URL.Query().Get("template_id"))
		}
		if r.URL.Query().Get("mobile") != "919876543210" {
			t.Errorf("mobile mismatch: %s", r.URL.Query().Get("mobile"))
		}
		if len(r.URL.Query().Get("otp")) != 6 {
			t.Errorf("expected 6-digit OTP in query, got: %s", r.URL.Query().Get("otp"))
		}

		respBody := `{"message":"OTP sent successfully","type":"success"}`
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(bytes.NewBufferString(respBody)),
			Header:     make(http.Header),
		}, nil
	})

	provider := NewMsg91Provider("test_secret_auth_key", "test_template_123")
	provider.SetHttpClient(&http.Client{Transport: mockTransport})

	err := provider.SendOtp("+919876543210", "482910")
	if err != nil {
		t.Fatalf("expected SendOtp to succeed, got: %v", err)
	}
}

// TestMsg91Adapter_Rejection tests provider error response (e.g., invalid template or auth key)
func TestMsg91Adapter_Rejection(t *testing.T) {
	mockTransport := roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		respBody := `{"message":"Authentication failed: Auth key is invalid","type":"error"}`
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(bytes.NewBufferString(respBody)),
			Header:     make(http.Header),
		}, nil
	})

	provider := NewMsg91Provider("invalid_key", "test_template_123")
	provider.SetHttpClient(&http.Client{Transport: mockTransport})

	err := provider.SendOtp("+919876543210", "482910")
	if err == nil {
		t.Fatal("expected SendOtp to fail on provider rejection, got nil")
	}
	if !strings.Contains(err.Error(), "Auth key is invalid") {
		t.Fatalf("expected error message to contain reason, got: %v", err)
	}
}

// TestMsg91Adapter_Timeout tests handling of network timeouts
func TestMsg91Adapter_Timeout(t *testing.T) {
	mockTransport := roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		return nil, errors.New("net/http: request canceled (Client.Timeout exceeded while awaiting headers)")
	})

	provider := NewMsg91Provider("key", "tmpl")
	provider.SetHttpClient(&http.Client{Transport: mockTransport})

	err := provider.SendOtp("+919876543210", "123456")
	if err == nil {
		t.Fatal("expected SendOtp to fail due to timeout, got nil")
	}
}

// TestProductionProvider_NoMockInProduction verifies MockSmsProvider is NEVER used in production
func TestProductionProvider_NoMockInProduction(t *testing.T) {
	os.Setenv("APP_ENV", "production")
	os.Unsetenv("MSG91_AUTH_KEY")
	os.Unsetenv("MSG91_TEMPLATE_ID")
	os.Unsetenv("TWILIO_ACCOUNT_SID")
	defer os.Unsetenv("APP_ENV")

	provider := GetDefaultSmsProvider()
	if provider.Name() == "MockSmsProvider" {
		t.Fatal("SECURITY FAILURE: GetDefaultSmsProvider returned MockSmsProvider in production!")
	}
	if provider.Name() != "Unavailable" {
		t.Fatalf("expected Unavailable provider, got: %s", provider.Name())
	}

	err := provider.SendOtp("+919876543210", "123456")
	if err == nil {
		t.Fatal("expected Unavailable provider to reject OTP send, got nil")
	}
}

// TestProductionProvider_MockSmsRejectsProduction verifies MockSmsProvider refuses to operate in production
func TestProductionProvider_MockSmsRejectsProduction(t *testing.T) {
	os.Setenv("APP_ENV", "production")
	defer os.Unsetenv("APP_ENV")

	mock := NewMockSmsProvider()
	err := mock.SendOtp("+919876543210", "123456")
	if err == nil {
		t.Fatal("expected MockSmsProvider.SendOtp to reject execution when APP_ENV=production")
	}
}

// TestOtpService_StateRollbackOnProviderFailure verifies OTP record is cleared if provider fails
func TestOtpService_StateRollbackOnProviderFailure(t *testing.T) {
	failingProvider := &UnavailableSmsProvider{reason: "network error"}
	svc := NewOtpService(failingProvider)

	phone := "+919999988888"
	_, err := svc.RequestOtp(phone, "127.0.0.1")
	if err == nil {
		t.Fatal("expected RequestOtp to fail when provider fails")
	}

	// Verify that the failed attempt did NOT leave a pending record
	_, err = svc.VerifyOtp(phone, "123456")
	if err == nil || !strings.Contains(err.Error(), "no pending OTP request") {
		t.Fatalf("expected 'no pending OTP' error, got: %v", err)
	}

	// Verify that a working provider succeeds
	workingMock := NewMockSmsProvider()
	svcWorking := NewOtpService(workingMock)
	_, err = svcWorking.RequestOtp(phone, "127.0.0.1")
	if err != nil {
		t.Fatalf("expected successful OTP request with working provider: %v", err)
	}
}

// TestOtpSecurity_ThreeAttemptsLockout verifies that 3 wrong attempts invalidate the code
func TestOtpSecurity_ThreeAttemptsLockout(t *testing.T) {
	mock := NewMockSmsProvider()
	svc := NewOtpService(mock)

	phone := "+919876543200"
	_, err := svc.RequestOtp(phone, "127.0.0.1")
	if err != nil {
		t.Fatalf("RequestOtp failed: %v", err)
	}

	correctOtp := mock.GetLastOtp(phone)

	// Attempt 1: wrong
	_, err = svc.VerifyOtp(phone, "000000")
	if err == nil {
		t.Fatal("expected wrong OTP to fail")
	}

	// Attempt 2: wrong
	_, err = svc.VerifyOtp(phone, "111111")
	if err == nil {
		t.Fatal("expected wrong OTP to fail")
	}

	// Attempt 3: wrong (max attempts reached)
	_, err = svc.VerifyOtp(phone, "222222")
	if err == nil {
		t.Fatal("expected wrong OTP to fail")
	}

	// Attempt 4: Even with correct OTP, it must now be rejected because it was deleted
	_, err = svc.VerifyOtp(phone, correctOtp)
	if err == nil {
		t.Fatal("expected OTP to be invalidated after 3 incorrect attempts")
	}
}

// TestOtpSecurity_ResendInvalidatesOldOtp verifies new OTP replaces old one
func TestOtpSecurity_ResendInvalidatesOldOtp(t *testing.T) {
	mock := NewMockSmsProvider()
	svc := NewOtpService(mock)

	phone := "+919876543211"
	_, err := svc.RequestOtp(phone, "127.0.0.1")
	if err != nil {
		t.Fatalf("first request failed: %v", err)
	}
	firstOtp := mock.GetLastOtp(phone)

	// Artificially age the last sent time to bypass cooldown
	svc.mu.Lock()
	if rec, ok := svc.records[phone]; ok {
		rec.LastSentAt = time.Now().UnixMilli() - 70000
	}
	svc.mu.Unlock()

	// Second request
	_, err = svc.RequestOtp(phone, "127.0.0.1")
	if err != nil {
		t.Fatalf("second request failed: %v", err)
	}
	secondOtp := mock.GetLastOtp(phone)

	// Old OTP must fail
	_, err = svc.VerifyOtp(phone, firstOtp)
	if err == nil {
		t.Fatal("expected old OTP to be rejected after resend")
	}

	// New OTP must succeed
	ticket, err := svc.VerifyOtp(phone, secondOtp)
	if err != nil {
		t.Fatalf("expected new OTP to succeed: %v", err)
	}
	if ticket == "" {
		t.Fatal("expected non-empty ticket")
	}
}

// TestUserBinding_VerificationTicketPreservesUser verifies user binding
func TestUserBinding_VerificationTicketPreservesUser(t *testing.T) {
	mock := NewMockSmsProvider()
	svc := NewOtpService(mock)

	phone := "+919876543222"
	googleUserUID := "google_sub_1029384756"

	_, err := svc.RequestOtpWithUser(phone, "127.0.0.1", googleUserUID)
	if err != nil {
		t.Fatalf("RequestOtpWithUser failed: %v", err)
	}

	otp := mock.GetLastOtp(phone)
	ticketToken, err := svc.VerifyOtp(phone, otp)
	if err != nil {
		t.Fatalf("VerifyOtp failed: %v", err)
	}

	ticket, err := svc.ConsumeTicket(ticketToken)
	if err != nil {
		t.Fatalf("ConsumeTicket failed: %v", err)
	}

	if ticket.BoundUser != googleUserUID {
		t.Fatalf("expected bound user %s, got %s", googleUserUID, ticket.BoundUser)
	}

	// Verify single-use consumption
	_, err = svc.ConsumeTicket(ticketToken)
	if err == nil {
		t.Fatal("expected ticket to be single-use and fail on second consumption")
	}
}
