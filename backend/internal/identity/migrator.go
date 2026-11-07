package identity

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sort"
	"strings"
	"time"
)

// MigrationResult summarizes the execution and verification of an identity migration.
type MigrationResult struct {
	SourceFile        string        `json:"source_file"`
	BackupPath        string        `json:"backup_path"`
	AccountsMigrated  int           `json:"accounts_migrated"`
	DevicesMigrated   int           `json:"devices_migrated"`
	SessionsMigrated  int           `json:"sessions_migrated"`
	SourceFingerprint string        `json:"source_fingerprint"`
	TargetFingerprint string        `json:"target_fingerprint"`
	FingerprintMatch  bool          `json:"fingerprint_match"`
	Duration          time.Duration `json:"duration"`
	Status            string        `json:"status"`
}

// Migrator handles safe, idempotent, non-destructive migration from JSON store to PostgreSQL.
type Migrator struct {
	sourcePath string
	targetRepo IdentityRepository
}

func NewMigrator(sourcePath string, targetRepo IdentityRepository) *Migrator {
	return &Migrator{
		sourcePath: sourcePath,
		targetRepo: targetRepo,
	}
}

// Migrate executes the full backup, validation, ingestion, and cryptographic verification flow.
// Guaranteed: Never deletes or modifies the source JSON file.
func (m *Migrator) Migrate(ctx context.Context) (*MigrationResult, error) {
	startTime := time.Now()

	result := &MigrationResult{
		SourceFile: m.sourcePath,
		Status:     "INITIALIZED",
	}

	// 1. Verify source file existence
	if m.sourcePath == "" {
		result.Status = "NO_SOURCE_PATH"
		return result, nil
	}

	sourceData, err := os.ReadFile(m.sourcePath)
	if err != nil {
		if os.IsNotExist(err) {
			result.Status = "SOURCE_FILE_NOT_FOUND"
			log.Printf("[Migrator] Source identity file %s not found; skipping migration.", m.sourcePath)
			return result, nil
		}
		return nil, fmt.Errorf("failed to read source identity file: %w", err)
	}

	if len(strings.TrimSpace(string(sourceData))) == 0 {
		result.Status = "SOURCE_FILE_EMPTY"
		log.Printf("[Migrator] Source identity file %s is empty; skipping migration.", m.sourcePath)
		return result, nil
	}

	// 2. Create timestamped immutable backup copy
	timestamp := time.Now().Format("20060102_150405")
	backupPath := fmt.Sprintf("%s.bak.%s", m.sourcePath, timestamp)
	if err := os.WriteFile(backupPath, sourceData, 0600); err != nil {
		return nil, fmt.Errorf("failed to write timestamped backup %s: %w", backupPath, err)
	}
	result.BackupPath = backupPath
	log.Printf("[Migrator] Created immutable source backup at %s (%d bytes)", backupPath, len(sourceData))

	// 3. Parse and validate JSON structure
	var snap storeSnapshot
	if err := json.Unmarshal(sourceData, &snap); err != nil {
		return nil, fmt.Errorf("source file %s contains invalid JSON: %w", m.sourcePath, err)
	}

	// 4. Validate accounts, detect duplicates, and compute source cryptographic fingerprint
	seenNx := make(map[string]bool)
	seenPubKey := make(map[string]string)
	seenPhone := make(map[string]string)
	fingerprintKeys := make([]string, 0, len(snap.Accounts))

	for _, acc := range snap.Accounts {
		if !IsValidNxID(acc.NxID) {
			return nil, fmt.Errorf("invalid NX-ID format in account: %s", acc.NxID)
		}
		if len(acc.PublicKeyHex) != 64 {
			return nil, fmt.Errorf("account %s has invalid 32-byte Ed25519 public key hex: %s", acc.NxID, acc.PublicKeyHex)
		}

		if seenNx[acc.NxID] {
			return nil, fmt.Errorf("duplicate NX-ID detected in source snapshot: %s", acc.NxID)
		}
		seenNx[acc.NxID] = true

		if priorNx, exists := seenPubKey[acc.PublicKeyHex]; exists {
			return nil, fmt.Errorf("duplicate public key %s shared by %s and %s in source snapshot", acc.PublicKeyHex, priorNx, acc.NxID)
		}
		seenPubKey[acc.PublicKeyHex] = acc.NxID

		if acc.PhoneNumber != "" {
			if priorNx, exists := seenPhone[acc.PhoneNumber]; exists {
				return nil, fmt.Errorf("duplicate phone %s shared by %s and %s in source snapshot", acc.PhoneNumber, priorNx, acc.NxID)
			}
			seenPhone[acc.PhoneNumber] = acc.NxID
		}

		fingerprintKeys = append(fingerprintKeys, fmt.Sprintf("%s:%s", acc.NxID, acc.PublicKeyHex))
	}

	// Deterministic cryptographic fingerprint of all accounts and public keys
	sort.Strings(fingerprintKeys)
	h := sha256.New()
	for _, entry := range fingerprintKeys {
		h.Write([]byte(entry + "\n"))
	}
	result.SourceFingerprint = hex.EncodeToString(h.Sum(nil))

	// 5. Ingest accounts idempotently into target repository
	now := time.Now().UnixMilli()
	for _, acc := range snap.Accounts {
		existing, found := m.targetRepo.GetAccountByNxID(acc.NxID)
		if found {
			if existing.PublicKeyHex != acc.PublicKeyHex {
				return nil, fmt.Errorf("integrity conflict: account %s already exists in target with differing public key (%s vs %s)",
					acc.NxID, existing.PublicKeyHex, acc.PublicKeyHex)
			}
			// Already exists consistently; skip
			continue
		}

		if err := m.targetRepo.CreateAccount(acc); err != nil {
			return nil, fmt.Errorf("failed to migrate account %s: %w", acc.NxID, err)
		}
		result.AccountsMigrated++
	}

	// 6. Ingest devices
	for _, dev := range snap.Devices {
		if dev.DeviceID == "" || dev.NxID == "" {
			continue
		}
		if dev.AccountID == "" {
			dev.AccountID = "usr_" + dev.NxID
		}
		if dev.CreatedAt == 0 {
			dev.CreatedAt = now
		}
		if dev.LastActiveAt == 0 {
			dev.LastActiveAt = now
		}
		if err := m.targetRepo.RegisterDevice(dev); err != nil {
			return nil, fmt.Errorf("failed to migrate device %s for NX-ID %s: %w", dev.DeviceID, dev.NxID, err)
		}
		result.DevicesMigrated++
	}

	// 7. Ingest active sessions
	for _, sess := range snap.Sessions {
		if sess.Token == "" || sess.IsRevoked || sess.ExpiresAt <= now {
			// Skip revoked or expired sessions
			continue
		}
		if sess.AccountID == "" {
			sess.AccountID = "usr_" + sess.NxID
		}
		if err := m.targetRepo.CreateSession(sess); err != nil {
			log.Printf("[Migrator] Warning: skipping invalid or conflicting session %s: %v", sess.Token, err)
			continue
		}
		result.SessionsMigrated++
	}

	// 8. Cryptographic verification & post-check
	targetFingerprintKeys := make([]string, 0, len(snap.Accounts))
	for _, acc := range snap.Accounts {
		targetAcc, found := m.targetRepo.GetAccountByNxID(acc.NxID)
		if !found {
			return nil, fmt.Errorf("post-migration verification failed: account %s missing in target repository", acc.NxID)
		}
		if targetAcc.PublicKeyHex != acc.PublicKeyHex {
			return nil, fmt.Errorf("post-migration key mismatch for account %s: expected %s, got %s",
				acc.NxID, acc.PublicKeyHex, targetAcc.PublicKeyHex)
		}
		targetFingerprintKeys = append(targetFingerprintKeys, fmt.Sprintf("%s:%s", targetAcc.NxID, targetAcc.PublicKeyHex))
	}

	sort.Strings(targetFingerprintKeys)
	targetH := sha256.New()
	for _, entry := range targetFingerprintKeys {
		targetH.Write([]byte(entry + "\n"))
	}
	result.TargetFingerprint = hex.EncodeToString(targetH.Sum(nil))
	result.FingerprintMatch = (result.SourceFingerprint == result.TargetFingerprint)

	if !result.FingerprintMatch {
		return nil, fmt.Errorf("cryptographic fingerprint mismatch after migration! Source: %s, Target: %s",
			result.SourceFingerprint, result.TargetFingerprint)
	}

	result.Duration = time.Since(startTime)
	result.Status = "COMPLETED_VERIFIED"

	log.Printf("[Migrator] SUCCESS: Migrated %d accounts, %d devices, %d sessions in %v. Fingerprint: %s",
		result.AccountsMigrated, result.DevicesMigrated, result.SessionsMigrated, result.Duration, result.TargetFingerprint)

	return result, nil
}
