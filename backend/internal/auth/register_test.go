package auth

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func newRegisterTestHandler(t *testing.T) (*AuthHandler, *identity.Store, *OtpService) {
	t.Helper()
	t.Setenv("APP_ENV", "test")
	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	otpSvc := NewOtpService(NewMockSmsProvider())
	handler := NewAuthHandler(store, otpSvc, nil)
	return handler, store, otpSvc
}

func issueTestTicket(t *testing.T, otpSvc *OtpService, provider, boundUser, email, phone string) string {
	t.Helper()
	now := time.Now().UnixMilli()
	tokenBytes := make([]byte, 8)
	_, _ = rand.Read(tokenBytes)
	token := "ticket_test_" + hex.EncodeToString(tokenBytes)
	otpSvc.mu.Lock()
	otpSvc.tickets[token] = &AuthTicket{
		PhoneNumber: phone,
		BoundUser:   boundUser,
		Provider:    provider,
		Email:       email,
		VerifiedAt:  now,
		ExpiresAt:   now + 600_000,
		TicketToken: token,
	}
	otpSvc.mu.Unlock()
	return token
}

func postRegister(t *testing.T, handler *AuthHandler, req RegisterRequest) (*httptest.ResponseRecorder, map[string]interface{}) {
	t.Helper()
	body, _ := json.Marshal(req)
	httpReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	w := httptest.NewRecorder()
	handler.handleRegister(w, httpReq)

	var resp map[string]interface{}
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	return w, resp
}

func TestRegister_GoogleTicketBindsIdentityWithoutOverloadingPhone(t *testing.T) {
	handler, store, otpSvc := newRegisterTestHandler(t)

	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pubHex := hex.EncodeToString(pub)
	deviceID := "dev_google_1"
	ticket := issueTestTicket(t, otpSvc, "GOOGLE", "google_sub_123", "alice@example.com", "")

	w, resp := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket,
		AuthType:        "GOOGLE",
		PublicKeyHex:    pubHex,
		DeviceID:        deviceID,
		DeviceName:      "Pixel",
		ProfileName:     "Alice",
	})

	if w.Code != http.StatusOK {
		t.Fatalf("register failed: HTTP %d body=%s", w.Code, w.Body.String())
	}
	nxID, _ := resp["nx_id"].(string)
	acc, ok := store.GetAccountByNxID(nxID)
	if !ok {
		t.Fatalf("account %s not created", nxID)
	}
	if acc.GoogleID != "google_sub_123" {
		t.Fatalf("expected verified Google subject bound, got %q", acc.GoogleID)
	}
	if acc.Email != "alice@example.com" {
		t.Fatalf("expected email stored in email, got %q", acc.Email)
	}
	if acc.PhoneNumber != "" || acc.PhoneVerified {
		t.Fatalf("Google ticket must not be treated as a verified phone: %+v", acc)
	}
}

func TestRegister_SecondSignInRevokesPreviousDevice(t *testing.T) {
	handler, store, otpSvc := newRegisterTestHandler(t)

	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pubHex := hex.EncodeToString(pub)

	ticket1 := issueTestTicket(t, otpSvc, "PHONE_OTP", "", "", "+919876511111")
	w1, resp1 := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket1,
		AuthType:        "PHONE_OTP",
		PublicKeyHex:    pubHex,
		DeviceID:        "dev_old",
		DeviceName:      "Old Phone",
		ProfileName:     "Bob",
	})
	if w1.Code != http.StatusOK {
		t.Fatalf("first register failed: HTTP %d body=%s", w1.Code, w1.Body.String())
	}
	nxID, _ := resp1["nx_id"].(string)

	acc, _ := store.GetAccountByNxID(nxID)
	if acc.PhoneNumber != "+919876511111" || !acc.PhoneVerified {
		t.Fatalf("phone OTP ticket must set a verified phone: %+v", acc)
	}

	ticket2 := issueTestTicket(t, otpSvc, "PHONE_OTP", "", "", "+919876511111")
	w2, resp2 := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket2,
		AuthType:        "PHONE_OTP",
		PublicKeyHex:    pubHex,
		DeviceID:        "dev_new",
		DeviceName:      "New Phone",
		ProfileName:     "Bob",
	})
	if w2.Code != http.StatusOK {
		t.Fatalf("second register failed: HTTP %d body=%s", w2.Code, w2.Body.String())
	}
	if got, _ := resp2["nx_id"].(string); got != nxID {
		t.Fatalf("expected same account on re-registration, got %s want %s", got, nxID)
	}

	active := store.GetDevicesForAccount(nxID)
	if len(active) != 1 || active[0].DeviceID != "dev_new" {
		t.Fatalf("expected only the new device active, got %+v", active)
	}

	old, ok := store.GetDevice("dev_old")
	if !ok || !old.IsRevoked || old.RevokedAt == 0 {
		t.Fatalf("old device must be revoked with a timestamp: %+v", old)
	}

	all := store.GetAllDevicesForAccount(nxID)
	if len(all) != 2 {
		t.Fatalf("expected revoked device retained for history, got %d devices", len(all))
	}
}

func TestRegister_RevokesPriorSessionForSameDevice(t *testing.T) {
	handler, store, otpSvc := newRegisterTestHandler(t)

	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pubHex := hex.EncodeToString(pub)
	phone := "+919876533333"
	deviceID := "dev_same"

	ticket1 := issueTestTicket(t, otpSvc, "PHONE_OTP", "", "", phone)
	w1, resp1 := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket1,
		AuthType:        "PHONE_OTP",
		PublicKeyHex:    pubHex,
		DeviceID:        deviceID,
		DeviceName:      "Same Phone",
		ProfileName:     "Carol",
	})
	if w1.Code != http.StatusOK {
		t.Fatalf("first register failed: HTTP %d body=%s", w1.Code, w1.Body.String())
	}
	nxID, _ := resp1["nx_id"].(string)
	firstToken, _ := resp1["session_auth_token"].(string)
	if firstToken == "" {
		t.Fatal("first registration did not return a session token")
	}

	now := time.Now().UnixMilli()
	if err := store.CreateSession(&identity.Session{
		Token:     "stale_same_device",
		AccountID: "acc_" + nxID,
		NxID:      nxID,
		DeviceID:  deviceID,
		ExpiresAt: now + time.Hour.Milliseconds(),
		CreatedAt: now,
	}); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}

	ticket2 := issueTestTicket(t, otpSvc, "PHONE_OTP", "", "", phone)
	w2, resp2 := postRegister(t, handler, RegisterRequest{
		AuthProofTicket: ticket2,
		AuthType:        "PHONE_OTP",
		PublicKeyHex:    pubHex,
		DeviceID:        deviceID,
		DeviceName:      "Same Phone",
		ProfileName:     "Carol",
	})
	if w2.Code != http.StatusOK {
		t.Fatalf("second register failed: HTTP %d body=%s", w2.Code, w2.Body.String())
	}
	secondToken, _ := resp2["session_auth_token"].(string)
	if secondToken == "" {
		t.Fatal("second registration did not return a session token")
	}

	if _, ok := store.GetSession(firstToken); ok {
		t.Fatal("re-registration must revoke the device's previous session")
	}
	if _, ok := store.GetSession("stale_same_device"); ok {
		t.Fatal("re-registration must revoke stale same-device sessions")
	}
	if _, ok := store.GetSession(secondToken); !ok {
		t.Fatal("re-registration session must be active")
	}
}

func TestRegister_RejectsAccountSecurityTickets(t *testing.T) {
	handler, store, otpSvc := newRegisterTestHandler(t)

	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pubHex := hex.EncodeToString(pub)

	cases := []struct {
		name     string
		provider string
		bound    string
		phone    string
	}{
		{
			name:     "phone change ticket",
			provider: "PHONE_OTP",
			bound:    phoneChangeBinding + "NX-EXIST01",
			phone:    "+919876522222",
		},
		{
			name:     "account deletion ticket",
			provider: "PHONE_OTP",
			bound:    deletionBinding + "NX-EXIST01",
			phone:    "+919876522223",
		},
		{
			name:     "unknown provider",
			provider: "",
			bound:    "",
			phone:    "+919876522224",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ticket := issueTestTicket(t, otpSvc, tc.provider, tc.bound, "", tc.phone)
			w, _ := postRegister(t, handler, RegisterRequest{
				AuthProofTicket: ticket,
				AuthType:        "PHONE_OTP",
				PublicKeyHex:    pubHex,
				DeviceID:        "dev_" + tc.name,
				DeviceName:      "Test",
				ProfileName:     "Attacker",
			})
			if w.Code != http.StatusUnauthorized {
				t.Fatalf("expected HTTP 401 for %s, got %d body=%s", tc.name, w.Code, w.Body.String())
			}
			if _, found := store.GetAccountByPhone(tc.phone); found {
				t.Fatalf("%s must not create a verified account", tc.name)
			}
		})
	}
}
