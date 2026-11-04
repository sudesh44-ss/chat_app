package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"regexp"
	"testing"
)

func TestDeterministicNxIdDerivation(t *testing.T) {
	pub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate ed25519 key: %v", err)
	}

	nxId1 := DeriveNxId(pub, 0)
	nxId2 := DeriveNxId(pub, 0)

	if nxId1 != nxId2 {
		t.Fatalf("expected deterministic NX-ID, got %s and %s", nxId1, nxId2)
	}

	matched, _ := regexp.MatchString(`^NX-\d{4}-[A-Z0-9]{3}$`, nxId1)
	if !matched {
		t.Fatalf("NX-ID %s does not match expected pattern NX-XXXX-XXX", nxId1)
	}

	// Collision salt generates a distinct suffix
	nxIdSalted := DeriveNxId(pub, 1)
	if nxId1 == nxIdSalted {
		t.Fatalf("expected different NX-ID with salt=1, got same %s", nxId1)
	}
}

func TestLockedRandomNxIdSpecification(t *testing.T) {
	// Locked Spec: Fixed "NX-" prefix, exactly 8 uppercase random alphanumeric characters (A-Z0-9)
	for i := 0; i < 50; i++ {
		randomNxId, err := GenerateRandomNxID()
		if err != nil {
			t.Fatalf("failed to generate random NX-ID: %v", err)
		}

		if len(randomNxId) != 11 {
			t.Fatalf("expected length 11, got %d for %s", len(randomNxId), randomNxId)
		}

		if !IsValidNxID(randomNxId) {
			t.Fatalf("generated NX-ID %s failed IsValidNxID validation", randomNxId)
		}
	}
}

func TestIdentityStoreDeviceLifecycle(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nexa_test_identity_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "identity.json")
	store, err := NewStore(dbPath)
	if err != nil {
		t.Fatalf("failed to initialize identity store: %v", err)
	}

	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	pubHex := hex.EncodeToString(pub)

	user, session, err := store.RegisterUser("+1234567890", "", pubHex, "dev_pixel_9", "Pixel 9", "Alice")
	if err != nil {
		t.Fatalf("failed to register user: %v", err)
	}

	if user.NxID == "" || session.Token == "" {
		t.Fatalf("expected non-empty NX-ID and session token")
	}

	// Verify lookup by NX-ID
	fetchedUser, err := store.GetUser(user.NxID)
	if err != nil || fetchedUser == nil {
		t.Fatalf("failed to fetch user by NX-ID: %v", err)
	}

	// Add second device to account
	pub2, _, _ := ed25519.GenerateKey(rand.Reader)
	pub2Hex := hex.EncodeToString(pub2)

	dev2, sess2, err := store.RegisterDeviceWithParams(user.NxID, "dev_tablet_1", "Pixel Tablet", pub2Hex, "fcm_token_tablet_123")
	if err != nil {
		t.Fatalf("failed to register secondary device: %v", err)
	}

	if dev2.DeviceID != "dev_tablet_1" || sess2.Token == "" {
		t.Fatalf("invalid secondary device registered")
	}

	devices, err := store.GetDevices(user.NxID)
	if err != nil || len(devices) != 2 {
		t.Fatalf("expected 2 registered devices for user, got %d", len(devices))
	}

	// Revoke device
	if err := store.RevokeDeviceForUser(user.NxID, "dev_tablet_1"); err != nil {
		t.Fatalf("failed to revoke device: %v", err)
	}

	activeDevs, _ := store.GetDevices(user.NxID)
	if len(activeDevs) != 1 {
		t.Fatalf("expected 1 active device after revocation, got %d", len(activeDevs))
	}
}
