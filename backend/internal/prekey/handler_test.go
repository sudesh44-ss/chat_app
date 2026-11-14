package prekey

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

type mockIdentityRepo struct {
	sessions map[string]*identity.Session
	accounts map[string]*identity.UserAccount
	devices  map[string]*identity.Device
}

func newMockIdentityRepo() *mockIdentityRepo {
	return &mockIdentityRepo{
		sessions: make(map[string]*identity.Session),
		accounts: make(map[string]*identity.UserAccount),
		devices:  make(map[string]*identity.Device),
	}
}

func (m *mockIdentityRepo) AllocateNxID(pubKeyHex string) (string, error) { return "NX-ALLOC01", nil }
func (m *mockIdentityRepo) CreateAccount(a *identity.UserAccount) error   { return nil }
func (m *mockIdentityRepo) GetAccountByNxID(nxId string) (*identity.UserAccount, bool) {
	a, ok := m.accounts[nxId]
	return a, ok
}
func (m *mockIdentityRepo) GetAccountByPhone(p string) (*identity.UserAccount, bool) {
	return nil, false
}
func (m *mockIdentityRepo) GetAccountByGoogleID(g string) (*identity.UserAccount, bool) {
	return nil, false
}
func (m *mockIdentityRepo) GetAccountBySupabaseUID(s string) (*identity.UserAccount, bool) {
	return nil, false
}
func (m *mockIdentityRepo) GetAccountByPublicKey(pubKeyHex string) (*identity.UserAccount, bool) {
	return nil, false
}
func (m *mockIdentityRepo) UpdateAccount(a *identity.UserAccount) error { return nil }
func (m *mockIdentityRepo) LookupVerifiedPhoneNumbers(phoneNumbers []string) []*identity.ContactMatch {
	return nil
}
func (m *mockIdentityRepo) RegisterDevice(d *identity.Device) error { return nil }
func (m *mockIdentityRepo) RegisterDeviceReplacingOthers(d *identity.Device, reason string) ([]string, error) {
	return nil, nil
}
func (m *mockIdentityRepo) GetAllDevicesForAccount(nxId string) []*identity.Device { return nil }
func (m *mockIdentityRepo) RecordDeviceEvent(event *identity.DeviceEvent) error    { return nil }
func (m *mockIdentityRepo) GetDeviceEvents(nxID string, limit int) ([]*identity.DeviceEvent, error) {
	return nil, nil
}
func (m *mockIdentityRepo) GetDevice(deviceId string) (*identity.Device, bool) {
	d, ok := m.devices[deviceId]
	return d, ok
}
func (m *mockIdentityRepo) GetDevicesForAccount(nxId string) []*identity.Device      { return nil }
func (m *mockIdentityRepo) GetDevices(nxID string) ([]*identity.Device, error)       { return nil, nil }
func (m *mockIdentityRepo) RevokeDevice(devId string) error                          { return nil }
func (m *mockIdentityRepo) RevokeDeviceForUser(nxID string, deviceID string) error   { return nil }
func (m *mockIdentityRepo) UpdateDeviceFcmToken(deviceID string, token string) error { return nil }
func (m *mockIdentityRepo) CreateSession(s *identity.Session) error                  { return nil }
func (m *mockIdentityRepo) GetSession(token string) (*identity.Session, bool) {
	s, ok := m.sessions[token]
	return s, ok
}
func (m *mockIdentityRepo) RevokeSession(token string) error { return nil }
func (m *mockIdentityRepo) RevokeSessionsForDevice(nxID string, deviceID string, exceptToken string, createdBefore int64) (int, error) {
	return 0, nil
}
func (m *mockIdentityRepo) CountAccounts() (int, error) { return 1, nil }
func (m *mockIdentityRepo) Close() error                     { return nil }

func TestHandlerEndpoints(t *testing.T) {
	// Setup keys
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	userNxId := "NX-ALICE100"
	deviceId := "dev_alice_01"
	token := "bearer_token_123"

	idRepo := newMockIdentityRepo()
	idRepo.accounts[userNxId] = &identity.UserAccount{
		ID:           "acc_alice",
		NxID:         userNxId,
		PublicKeyHex: pubHex,
	}
	idRepo.devices[deviceId] = &identity.Device{
		DeviceID:     deviceId,
		AccountID:    "acc_alice",
		NxID:         userNxId,
		PublicKeyHex: pubHex,
		IsRevoked:    false,
	}
	idRepo.sessions[token] = &identity.Session{
		Token:     token,
		AccountID: "acc_alice",
		NxID:      userNxId,
		DeviceID:  deviceId,
		IsRevoked: false,
	}

	prekeyRepo := NewMemoryPrekeyRepository()
	handler := NewHandler(prekeyRepo, idRepo)

	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	// 1. Upload Identity DH Key
	dhPub := make([]byte, 32)
	_, _ = rand.Read(dhPub)
	dhPubHex := hex.EncodeToString(dhPub)
	now := time.Now().UnixMilli()

	canonicalBindingBytes, _ := EncodeIdentityBinding(userNxId, deviceId, pub, dhPub, now)
	sig := ed25519.Sign(priv, canonicalBindingBytes)

	identBody, _ := json.Marshal(UploadIdentityKeyRequest{
		DhPublicKey: dhPubHex,
		Signature:   hex.EncodeToString(sig),
		Timestamp:   now,
	})

	req := httptest.NewRequest("POST", "/api/v1/users/identity-key", bytes.NewReader(identBody))
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Upload identity key failed: %d - %s", rec.Code, rec.Body.String())
	}

	// 2. Upload Signed Prekey
	spkPub := make([]byte, 32)
	_, _ = rand.Read(spkPub)
	spkPubHex := hex.EncodeToString(spkPub)

	canonicalSpkBytes, _ := EncodeSignedPrekey(1, userNxId, deviceId, spkPub, now)
	spkSig := ed25519.Sign(priv, canonicalSpkBytes)

	spkBody, _ := json.Marshal(UploadSignedPrekeyRequest{
		KeyID:     1,
		PublicKey: spkPubHex,
		Signature: hex.EncodeToString(spkSig),
		Timestamp: now,
	})

	req = httptest.NewRequest("POST", "/api/v1/users/prekeys/signed", bytes.NewReader(spkBody))
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Upload signed prekey failed: %d - %s", rec.Code, rec.Body.String())
	}

	// 3. Upload One-Time Prekeys
	opk1Pub := hex.EncodeToString(make([]byte, 32))
	opk2Pub := hex.EncodeToString(make([]byte, 32))
	opkBody, _ := json.Marshal(UploadOneTimePrekeysRequest{
		Prekeys: []OneTimePrekeyItem{
			{KeyID: 1, PublicKey: opk1Pub},
			{KeyID: 2, PublicKey: opk2Pub},
		},
	})

	req = httptest.NewRequest("POST", "/api/v1/users/prekeys/one-time", bytes.NewReader(opkBody))
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("Upload one-time prekeys failed: %d - %s", rec.Code, rec.Body.String())
	}

	// 4. GET Public Prekey Bundle (Must be read-only, no OPK returned)
	req = httptest.NewRequest("GET", "/api/v1/users/"+userNxId+"/prekey-bundle", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET prekey bundle failed: %d - %s", rec.Code, rec.Body.String())
	}

	var bundle PrekeyBundle
	_ = json.NewDecoder(rec.Body).Decode(&bundle)
	if bundle.IdentityKeyHex != dhPubHex || bundle.SignedPrekeyHex != spkPubHex {
		t.Errorf("mismatched prekey bundle contents")
	}

	// 5. POST /api/v1/prekeys/claim (Atomic OPK Reservation)
	claimBody, _ := json.Marshal(ClaimRequest{
		RecipientNxID: userNxId,
		ClaimID:       "test_claim_uuid_999",
	})
	req = httptest.NewRequest("POST", "/api/v1/prekeys/claim", bytes.NewReader(claimBody))
	req.Header.Set("Authorization", "Bearer "+token)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("POST prekey claim failed: %d - %s", rec.Code, rec.Body.String())
	}

	var claimResp ClaimResponse
	_ = json.NewDecoder(rec.Body).Decode(&claimResp)
	if claimResp.OneTimePrekeyHex == nil || *claimResp.OneTimePrekeyID != 1 {
		t.Errorf("expected OPK 1 to be disclosed on claim")
	}
}
