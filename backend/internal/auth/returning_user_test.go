package auth

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

type mockSessionRevoker struct {
	revokedSessions []string
}

func (m *mockSessionRevoker) RevokeDeviceSession(nxId string, deviceId string, reason string) error {
	m.revokedSessions = append(m.revokedSessions, deviceId)
	return nil
}

func TestReturningUser_GoogleAuthReturnsExistingProfileAndDeviceDetails(t *testing.T) {
	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("failed to create store: %v", err)
	}

	googleID := "google_returning_user_123"
	nxID := "NX-7F82K9A4"
	phone := "+919876543212"
	name := "Sudesh"
	avatar := "https://example.com/avatar.jpg"

	acc := &identity.UserAccount{
		ID:                    "usr_returning_1",
		NxID:                  nxID,
		PublicKeyHex:          "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
		PhoneNumber:           phone,
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		GoogleID:              googleID,
		ProfileName:           name,
		AvatarUrl:             avatar,
		CreatedAt:             time.Now().UnixMilli(),
		UpdatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(acc); err != nil {
		t.Fatalf("failed to seed account: %v", err)
	}

	// Register an existing active device
	oldDev := &identity.Device{
		DeviceID:     "dev_redmi_12",
		AccountID:    acc.ID,
		NxID:         acc.NxID,
		DeviceName:   "Redmi Note 12",
		PublicKeyHex: "pub_redmi_12",
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	if _, err := store.RegisterDeviceReplacingOthers(oldDev, "initial registration"); err != nil {
		t.Fatalf("failed to register old device: %v", err)
	}

	privKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate RSA key: %v", err)
	}

	gv := NewGoogleVerifier()
	gv.SetClientIDForTesting("test-client-id")
	gv.SetKeysForTesting(map[string]*rsa.PublicKey{"key-1": &privKey.PublicKey})

	otpService := NewOtpService(NewMockSmsProvider())
	handler := NewAuthHandler(store, otpService, gv)
	revoker := &mockSessionRevoker{}
	handler.SetSessionRevoker(revoker)

	token, err := createTestGoogleToken(privKey, "key-1", "https://accounts.google.com", "test-client-id", googleID, "sudesh@gmail.com", time.Now().Add(time.Hour).Unix(), time.Now().Unix(), "RS256")
	if err != nil {
		t.Fatalf("failed to create test token: %v", err)
	}

	// Call handleGoogleAuth from a new device
	reqBody, _ := json.Marshal(map[string]interface{}{
		"id_token":       token,
		"device_id":      "dev_pixel_9",
		"public_key_hex": "pub_pixel_9",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/google", bytes.NewReader(reqBody))
	rec := httptest.NewRecorder()

	handler.handleGoogleAuth(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if resp["status"] != "REQUIRE_SIM_VERIFICATION" {
		t.Fatalf("expected REQUIRE_SIM_VERIFICATION, got %v", resp["status"])
	}
	if resp["nx_id"] != nxID {
		t.Fatalf("expected nx_id %s, got %v", nxID, resp["nx_id"])
	}
	if resp["name"] != name {
		t.Fatalf("expected name %s, got %v", name, resp["name"])
	}
	if resp["avatar_url"] != avatar {
		t.Fatalf("expected avatar_url %s, got %v", avatar, resp["avatar_url"])
	}
	if resp["has_active_device"] != true {
		t.Fatalf("expected has_active_device true, got %v", resp["has_active_device"])
	}
	if resp["active_device_name"] != "Redmi Note 12" {
		t.Fatalf("expected active_device_name 'Redmi Note 12', got %v", resp["active_device_name"])
	}
	if resp["is_same_device"] != false {
		t.Fatalf("expected is_same_device false, got %v", resp["is_same_device"])
	}
}

func TestReturningUser_NewDeviceRegistrationRevokesOldDevice(t *testing.T) {
	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("failed to create store: %v", err)
	}

	acc := &identity.UserAccount{
		ID:                    "usr_device_rev",
		NxID:                  "NX-REVOK001",
		PublicKeyHex:          "pub_initial",
		PhoneNumber:           "+919876543210",
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Migration User",
		CreatedAt:             time.Now().UnixMilli(),
		UpdatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(acc); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}

	// 1. Old device active
	oldDev := &identity.Device{
		DeviceID:     "dev_old_phone",
		AccountID:    acc.ID,
		NxID:         acc.NxID,
		DeviceName:   "Old Device",
		PublicKeyHex: "pub_old",
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	if _, err := store.RegisterDeviceReplacingOthers(oldDev, "initial"); err != nil {
		t.Fatalf("RegisterDeviceReplacingOthers: %v", err)
	}

	oldSession := &identity.Session{
		Token:     "sess_old_token",
		AccountID: acc.ID,
		NxID:      acc.NxID,
		DeviceID:  oldDev.DeviceID,
		ExpiresAt: time.Now().Add(24 * time.Hour).UnixMilli(),
		CreatedAt: time.Now().UnixMilli(),
	}
	if err := store.CreateSession(oldSession); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}

	// 2. Register new replacement device
	newDev := &identity.Device{
		DeviceID:     "dev_new_phone",
		AccountID:    acc.ID,
		NxID:         acc.NxID,
		DeviceName:   "New Device",
		PublicKeyHex: "pub_new",
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	revoked, err := store.RegisterDeviceReplacingOthers(newDev, "device migration by user")
	if err != nil {
		t.Fatalf("replacement registration failed: %v", err)
	}

	if len(revoked) != 1 || revoked[0] != "dev_old_phone" {
		t.Fatalf("expected dev_old_phone to be revoked, got %v", revoked)
	}

	// 3. Verify old device and its session are marked revoked
	dev, found := store.GetDevice("dev_old_phone")
	if !found || !dev.IsRevoked {
		t.Fatalf("old device must be marked revoked")
	}

	sess, sFound := store.GetSession("sess_old_token")
	if sFound && !sess.IsRevoked {
		t.Fatalf("old device session must be marked revoked")
	}

	// 4. Verify new device is active
	active := store.GetDevicesForAccount(acc.NxID)
	if len(active) != 1 || active[0].DeviceID != "dev_new_phone" {
		t.Fatalf("expected only new device to be active, got %+v", active)
	}
}
