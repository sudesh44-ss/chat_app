package auth

import (
	"errors"
	"testing"
	"time"
)

func TestOtpService_ProductionFailsClosedWithoutRedis(t *testing.T) {
	svc := NewOtpService(NewMockSmsProvider())
	svc.SetDistributedState(nil, true)

	phone := "+919876543210"

	if _, err := svc.RequestOtp(phone, "203.0.113.10"); !errors.Is(err, ErrOtpStateUnavailable) {
		t.Fatalf("expected RequestOtp to fail closed in production, got %v", err)
	}

	if _, err := svc.VerifyOtp(phone, "123456"); !errors.Is(err, ErrOtpStateUnavailable) {
		t.Fatalf("expected VerifyOtp to fail closed in production, got %v", err)
	}

	ticket := &AuthTicket{
		PhoneNumber: phone,
		Provider:    "PHONE_OTP",
		VerifiedAt:  time.Now().UnixMilli(),
		ExpiresAt:   time.Now().Add(10 * time.Minute).UnixMilli(),
		TicketToken: "ticket_prod_missing_state",
	}
	if err := svc.IssueTicket(ticket); !errors.Is(err, ErrOtpStateUnavailable) {
		t.Fatalf("expected IssueTicket to fail closed in production, got %v", err)
	}

	if _, err := svc.ConsumeTicket(ticket.TicketToken); !errors.Is(err, ErrOtpStateUnavailable) {
		t.Fatalf("expected ConsumeTicket to fail closed in production, got %v", err)
	}
}

func TestOtpService_DevFallbackKeepsWorkingWithoutRedis(t *testing.T) {
	mockSms := NewMockSmsProvider()
	svc := NewOtpService(mockSms)
	svc.SetDistributedState(nil, false)

	phone := "+919876543211"
	if _, err := svc.RequestOtp(phone, "203.0.113.11"); err != nil {
		t.Fatalf("dev fallback RequestOtp failed: %v", err)
	}

	code := mockSms.GetLastOtp(phone)
	if code == "" {
		t.Fatal("mock SMS provider did not receive an OTP")
	}

	ticketToken, err := svc.VerifyOtp(phone, code)
	if err != nil {
		t.Fatalf("dev fallback VerifyOtp failed: %v", err)
	}

	ticket, err := svc.ConsumeTicket(ticketToken)
	if err != nil {
		t.Fatalf("dev fallback ConsumeTicket failed: %v", err)
	}
	if ticket.PhoneNumber != phone || ticket.Provider != "PHONE_OTP" {
		t.Fatalf("unexpected ticket payload: %+v", ticket)
	}

	if _, err := svc.ConsumeTicket(ticketToken); err == nil {
		t.Fatal("auth ticket must be single use")
	}
}

func TestOtpService_IssueTicketMemorySingleUse(t *testing.T) {
	svc := NewOtpService(NewMockSmsProvider())

	now := time.Now().UnixMilli()
	ticket := &AuthTicket{
		BoundUser:   "google-subject-1",
		Provider:    "GOOGLE",
		Email:       "user@example.com",
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: "ticket_memory_single_use",
	}
	if err := svc.IssueTicket(ticket); err != nil {
		t.Fatalf("IssueTicket failed: %v", err)
	}

	got, err := svc.ConsumeTicket(ticket.TicketToken)
	if err != nil {
		t.Fatalf("first ConsumeTicket failed: %v", err)
	}
	if got.BoundUser != ticket.BoundUser || got.Email != ticket.Email {
		t.Fatalf("consumed ticket mismatch: %+v", got)
	}

	if _, err := svc.ConsumeTicket(ticket.TicketToken); err == nil {
		t.Fatal("auth ticket must not be consumable twice")
	}
}

func TestValidateOtpConfig(t *testing.T) {
	t.Run("production missing pepper fails", func(t *testing.T) {
		t.Setenv("OTP_SERVER_PEPPER", "")
		if err := ValidateOtpConfig(true); !errors.Is(err, ErrOtpSecretMissing) {
			t.Fatalf("expected ErrOtpSecretMissing, got %v", err)
		}
	})

	t.Run("production with pepper passes", func(t *testing.T) {
		t.Setenv("OTP_SERVER_PEPPER", "a-long-random-production-pepper")
		if err := ValidateOtpConfig(true); err != nil {
			t.Fatalf("expected valid config, got %v", err)
		}
	})

	t.Run("development allows missing pepper", func(t *testing.T) {
		t.Setenv("OTP_SERVER_PEPPER", "")
		if err := ValidateOtpConfig(false); err != nil {
			t.Fatalf("dev config should not fail: %v", err)
		}
	})
}
