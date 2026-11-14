package identity

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestMigratorSuccessAndVerification(t *testing.T) {
	tempDir := t.TempDir()
	sourceFile := filepath.Join(tempDir, "identity_store.json")

	snap := storeSnapshot{
		Accounts: []*UserAccount{
			{
				ID:            "usr_NX-29AB731Q",
				NxID:          "NX-29AB731Q",
				PublicKeyHex:  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
				PhoneNumber:   "+919876543210",
				PhoneVerified: true,
				ProfileName:   "Alice",
				CreatedAt:     1700000000000,
				UpdatedAt:     1700000000000,
			},
			{
				ID:            "usr_NX-A7K92B4M",
				NxID:          "NX-A7K92B4M",
				PublicKeyHex:  "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
				PhoneNumber:   "+919876543211",
				PhoneVerified: true,
				ProfileName:   "Bob",
				CreatedAt:     1700000000000,
				UpdatedAt:     1700000000000,
			},
		},
		Devices: []*Device{
			{
				DeviceID:     "dev_alice_phone",
				AccountID:    "usr_NX-29AB731Q",
				NxID:         "NX-29AB731Q",
				DeviceName:   "Pixel 9",
				PublicKeyHex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
				CreatedAt:    1700000000000,
				LastActiveAt: 1700000000000,
			},
		},
		Sessions: []*Session{
			{
				Token:     "tok_alice_session_1",
				AccountID: "usr_NX-29AB731Q",
				NxID:      "NX-29AB731Q",
				DeviceID:  "dev_alice_phone",
				ExpiresAt: time.Now().UnixMilli() + 3600*1000, // Valid active session
				CreatedAt: 1700000000000,
			},
			{
				Token:     "tok_alice_expired",
				AccountID: "usr_NX-29AB731Q",
				NxID:      "NX-29AB731Q",
				DeviceID:  "dev_alice_phone",
				ExpiresAt: 1600000000000, // Expired, should not migrate
				CreatedAt: 1600000000000,
			},
		},
	}

	data, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		t.Fatalf("failed to marshal test snapshot: %v", err)
	}
	if err := os.WriteFile(sourceFile, data, 0600); err != nil {
		t.Fatalf("failed to write test source file: %v", err)
	}

	// Target memory store implementing IdentityRepository
	targetStore, err := NewStore("")
	if err != nil {
		t.Fatalf("failed to create target store: %v", err)
	}

	migrator := NewMigrator(sourceFile, targetStore)
	res, err := migrator.Migrate(context.Background())
	if err != nil {
		t.Fatalf("migration failed: %v", err)
	}

	// 1. Verify counts
	if res.AccountsMigrated != 2 {
		t.Errorf("expected 2 accounts migrated, got %d", res.AccountsMigrated)
	}
	if res.DevicesMigrated != 1 {
		t.Errorf("expected 1 device migrated, got %d", res.DevicesMigrated)
	}
	if res.SessionsMigrated != 1 {
		t.Errorf("expected 1 session migrated, got %d", res.SessionsMigrated)
	}

	// 2. Verify cryptographic fingerprint match
	if !res.FingerprintMatch {
		t.Errorf("expected cryptographic fingerprint match, got mismatch: %s vs %s",
			res.SourceFingerprint, res.TargetFingerprint)
	}
	if res.SourceFingerprint == "" {
		t.Error("expected non-empty cryptographic fingerprint")
	}

	// 3. Verify backup file was created
	if res.BackupPath == "" {
		t.Error("expected backup path to be set")
	}
	if _, err := os.Stat(res.BackupPath); err != nil {
		t.Errorf("backup file does not exist at %s: %v", res.BackupPath, err)
	}

	// 4. Verify source file is preserved (NOT deleted)
	if _, err := os.Stat(sourceFile); err != nil {
		t.Errorf("source file was unexpectedly modified or deleted: %v", err)
	}

	// 5. Verify records exist in target store
	alice, ok := targetStore.GetAccountByNxID("NX-29AB731Q")
	if !ok || alice.ProfileName != "Alice" {
		t.Errorf("target store missing Alice: %+v", alice)
	}
	bob, ok := targetStore.GetAccountByNxID("NX-A7K92B4M")
	if !ok || bob.ProfileName != "Bob" {
		t.Errorf("target store missing Bob: %+v", bob)
	}

	// 6. Test Idempotency (running migration again should be safe and skip existing consistent accounts)
	res2, err := migrator.Migrate(context.Background())
	if err != nil {
		t.Fatalf("second migration run failed: %v", err)
	}
	if res2.AccountsMigrated != 0 {
		t.Errorf("expected 0 new accounts migrated on second run, got %d", res2.AccountsMigrated)
	}
	if !res2.FingerprintMatch {
		t.Errorf("expected fingerprint match on second run")
	}
}

func TestMigratorRejectsDuplicatePublicKey(t *testing.T) {
	tempDir := t.TempDir()
	sourceFile := filepath.Join(tempDir, "dup_pubkey.json")

	snap := storeSnapshot{
		Accounts: []*UserAccount{
			{
				ID:           "usr_NX-29AB731Q",
				NxID:         "NX-29AB731Q",
				PublicKeyHex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			},
			{
				ID:           "usr_NX-A7K92B4M",
				NxID:         "NX-A7K92B4M",
				PublicKeyHex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", // Duplicate!
			},
		},
	}

	data, _ := json.Marshal(snap)
	_ = os.WriteFile(sourceFile, data, 0600)

	targetStore, _ := NewStore("")
	migrator := NewMigrator(sourceFile, targetStore)

	_, err := migrator.Migrate(context.Background())
	if err == nil {
		t.Fatal("expected error on duplicate public key, but got nil")
	}
}

func TestMigratorRejectsInvalidNxIDFormat(t *testing.T) {
	tempDir := t.TempDir()
	sourceFile := filepath.Join(tempDir, "invalid_nxid.json")

	snap := storeSnapshot{
		Accounts: []*UserAccount{
			{
				ID:           "usr_INVALID",
				NxID:         "INVALID-FORMAT",
				PublicKeyHex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			},
		},
	}

	data, _ := json.Marshal(snap)
	_ = os.WriteFile(sourceFile, data, 0600)

	targetStore, _ := NewStore("")
	migrator := NewMigrator(sourceFile, targetStore)

	_, err := migrator.Migrate(context.Background())
	if err == nil {
		t.Fatal("expected error on invalid NX-ID format, but got nil")
	}
}
