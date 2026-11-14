//go:build integration

package database

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"nexa/wire/internal/identity"
)

// Live PostgreSQL integration tests.
//
// They are build-tagged and skipped unless TEST_DATABASE_URL points at a
// PostgreSQL instance where the test role may CREATE DATABASE. Each test gets a
// disposable database (created, migrated, then dropped) so it can never touch
// development or production data.
//
// Run with:
//   $env:TEST_DATABASE_URL = "postgres://nexa:nexa_test@127.0.0.1:55432/nexa_test?sslmode=disable"
//   go test -tags=integration ./internal/database -run TestIntegration -v

func integrationBaseURL(t *testing.T) string {
	t.Helper()

	raw := strings.TrimSpace(os.Getenv("TEST_DATABASE_URL"))
	if raw == "" {
		t.Skip("TEST_DATABASE_URL is not set; skipping live PostgreSQL integration tests")
	}

	parsed, err := url.Parse(raw)
	if err != nil || (parsed.Scheme != "postgres" && parsed.Scheme != "postgresql") {
		t.Skipf("TEST_DATABASE_URL must be a postgres:// connection URL, got %q", raw)
	}
	return raw
}

func randomSuffix(t *testing.T) string {
	t.Helper()
	buf := make([]byte, 6)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("random suffix: %v", err)
	}
	return hex.EncodeToString(buf)
}

// newIntegrationRepository creates a disposable database, runs the authoritative
// runtime migrations, and returns a repository bound to it.
func newIntegrationRepository(t *testing.T) (*pgxpool.Pool, *PostgresRepository) {
	t.Helper()

	baseURL := integrationBaseURL(t)

	connectCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	admin, err := NewPool(connectCtx, DefaultConfig(baseURL))
	if err != nil {
		t.Skipf("cannot connect to TEST_DATABASE_URL: %v", err)
	}
	if err := admin.Ping(connectCtx); err != nil {
		admin.Close()
		t.Skipf("PostgreSQL is not reachable: %v", err)
	}

	dbName := "nexa_it_" + randomSuffix(t)
	if _, err := admin.Exec(connectCtx, "CREATE DATABASE "+pgx.Identifier{dbName}.Sanitize()); err != nil {
		admin.Close()
		t.Skipf("test role cannot CREATE DATABASE (required for isolation): %v", err)
	}
	admin.Close()

	parsed, err := url.Parse(baseURL)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL: %v", err)
	}
	parsed.Path = "/" + dbName
	disposableURL := parsed.String()

	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cleanupCancel()

		cleanupPool, err := NewPool(cleanupCtx, DefaultConfig(baseURL))
		if err != nil {
			t.Logf("cleanup: cannot reconnect to drop %s: %v", dbName, err)
			return
		}
		defer cleanupPool.Close()

		dropSQL := "DROP DATABASE IF EXISTS " + pgx.Identifier{dbName}.Sanitize() + " WITH (FORCE)"
		if _, err := cleanupPool.Exec(cleanupCtx, dropSQL); err != nil {
			t.Logf("cleanup: cannot drop disposable database %s: %v", dbName, err)
		}
	})

	migrateCtx, migrateCancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer migrateCancel()

	pool, err := NewPool(migrateCtx, DefaultConfig(disposableURL))
	if err != nil {
		t.Fatalf("connect to disposable database %s: %v", dbName, err)
	}
	t.Cleanup(pool.Close)

	if err := RunMigrations(migrateCtx, pool); err != nil {
		t.Fatalf("RunMigrations on disposable database: %v", err)
	}

	return pool, NewPostgresRepository(pool)
}

func seedIntegrationAccount(t *testing.T, repo *PostgresRepository, nxID string) *identity.UserAccount {
	t.Helper()

	now := time.Now().UnixMilli()
	account := &identity.UserAccount{
		ID:                    "usr_" + strings.ToLower(nxID),
		NxID:                  nxID,
		PublicKeyHex:          strings.Repeat("a", 64),
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Integration User",
		CreatedAt:             now,
		UpdatedAt:             now,
	}
	if err := repo.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount(%s): %v", nxID, err)
	}
	return account
}

func TestIntegration_MigrationsCreateProtectedSchema(t *testing.T) {
	pool, _ := newIntegrationRepository(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	var applied int
	if err := pool.QueryRow(ctx, "SELECT COUNT(*) FROM schema_migrations").Scan(&applied); err != nil {
		t.Fatalf("count schema_migrations: %v", err)
	}
	if applied < 7 {
		t.Fatalf("expected at least 7 applied migrations, got %d", applied)
	}

	for _, column := range []string{"phone_hmac", "phone_encrypted"} {
		var exists bool
		if err := pool.QueryRow(ctx, `
			SELECT EXISTS (
				SELECT 1 FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'accounts' AND column_name = $1
			)`, column).Scan(&exists); err != nil {
			t.Fatalf("inspect accounts.%s: %v", column, err)
		}
		if !exists {
			t.Fatalf("accounts.%s was not created by migrations", column)
		}
	}

	var deviceEventsExists bool
	if err := pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM information_schema.tables
			WHERE table_schema = 'public' AND table_name = 'device_events'
		)`).Scan(&deviceEventsExists); err != nil {
		t.Fatalf("inspect device_events: %v", err)
	}
	if !deviceEventsExists {
		t.Fatal("device_events audit table was not created by migrations")
	}

	// Migrations must be idempotent: a restart re-runs RunMigrations.
	if err := RunMigrations(ctx, pool); err != nil {
		t.Fatalf("RunMigrations is not idempotent: %v", err)
	}
}

func TestIntegration_PhoneProtectionAndHMACLookup(t *testing.T) {
	pool, repo := newIntegrationRepository(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	phone := "+919876560001"
	now := time.Now().UnixMilli()
	account := &identity.UserAccount{
		ID:                    "usr_it_phone",
		NxID:                  "NX-ITPH0001",
		PublicKeyHex:          strings.Repeat("b", 64),
		PhoneNumber:           phone,
		PhoneVerified:         true,
		IsDiscoverableByPhone: true,
		ProfileName:           "Phone IT",
		CreatedAt:             now,
		UpdatedAt:             now,
	}
	if err := repo.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}

	got, ok := repo.GetAccountByPhone(phone)
	if !ok || got.NxID != account.NxID {
		t.Fatalf("HMAC lookup did not return the account: ok=%v got=%+v", ok, got)
	}

	var hmacValue, encryptedValue string
	if err := pool.QueryRow(ctx,
		"SELECT COALESCE(phone_hmac, ''), COALESCE(phone_encrypted, '') FROM accounts WHERE nx_id = $1",
		account.NxID,
	).Scan(&hmacValue, &encryptedValue); err != nil {
		t.Fatalf("read protected phone columns: %v", err)
	}

	wantHMAC, err := identity.PhoneHMAC(phone)
	if err != nil {
		t.Fatalf("PhoneHMAC: %v", err)
	}
	if hmacValue != wantHMAC {
		t.Fatalf("stored phone HMAC mismatch: got %q want %q", hmacValue, wantHMAC)
	}
	if encryptedValue == "" || encryptedValue == phone {
		t.Fatalf("phone must be stored as ciphertext, got %q", encryptedValue)
	}

	decrypted, err := identity.DecryptPhone(encryptedValue)
	if err != nil {
		t.Fatalf("DecryptPhone: %v", err)
	}
	if decrypted != phone {
		t.Fatalf("decrypted phone mismatch: got %q want %q", decrypted, phone)
	}

	if _, ok := repo.GetAccountByPhone("+919876560099"); ok {
		t.Fatal("unknown phone number must not match an account")
	}
}

func TestIntegration_DeviceReplacementRevokesSessionsAndAudits(t *testing.T) {
	_, repo := newIntegrationRepository(t)

	account := seedIntegrationAccount(t, repo, "NX-ITDEV001")
	now := time.Now().UnixMilli()

	deviceA := &identity.Device{
		DeviceID:     "dev_it_a",
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   "Device A",
		PublicKeyHex: strings.Repeat("c", 64),
		CreatedAt:    now,
		LastActiveAt: now,
	}
	if revoked, err := repo.RegisterDeviceReplacingOthers(deviceA, "initial"); err != nil || len(revoked) != 0 {
		t.Fatalf("initial device registration failed: revoked=%v err=%v", revoked, err)
	}

	if err := repo.CreateSession(&identity.Session{
		Token:     "sess_it_a",
		AccountID: account.ID,
		NxID:      account.NxID,
		DeviceID:  deviceA.DeviceID,
		ExpiresAt: now + time.Hour.Milliseconds(),
		CreatedAt: now,
	}); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}

	deviceB := &identity.Device{
		DeviceID:     "dev_it_b",
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   "Device B",
		PublicKeyHex: strings.Repeat("d", 64),
		CreatedAt:    now + 1,
		LastActiveAt: now + 1,
	}
	revoked, err := repo.RegisterDeviceReplacingOthers(deviceB, "replaced by integration test")
	if err != nil {
		t.Fatalf("replacement registration: %v", err)
	}
	if len(revoked) != 1 || revoked[0] != deviceA.DeviceID {
		t.Fatalf("expected device A to be revoked, got %v", revoked)
	}

	oldDevice, ok := repo.GetDevice(deviceA.DeviceID)
	if !ok || !oldDevice.IsRevoked || oldDevice.RevokedReason == "" {
		t.Fatalf("device A must carry revocation history: %+v", oldDevice)
	}
	if _, ok := repo.GetSession("sess_it_a"); ok {
		t.Fatal("replaced device session must be revoked in the same transaction")
	}

	active := repo.GetDevicesForAccount(account.NxID)
	if len(active) != 1 || active[0].DeviceID != deviceB.DeviceID {
		t.Fatalf("expected only device B active, got %+v", active)
	}
	if all := repo.GetAllDevicesForAccount(account.NxID); len(all) != 2 {
		t.Fatalf("revocation history must retain both devices, got %d", len(all))
	}

	events, err := repo.GetDeviceEvents(account.NxID, 50)
	if err != nil {
		t.Fatalf("GetDeviceEvents: %v", err)
	}
	replacedAudited := false
	for _, event := range events {
		if event.DeviceID == deviceA.DeviceID && event.EventType == "REVOKED_REPLACED" {
			replacedAudited = true
		}
	}
	if !replacedAudited {
		t.Fatalf("expected REVOKED_REPLACED audit event, got %+v", events)
	}
}

func TestIntegration_DeviceReplacementRollsBackOnFailure(t *testing.T) {
	_, repo := newIntegrationRepository(t)

	account := seedIntegrationAccount(t, repo, "NX-ITDEV002")
	now := time.Now().UnixMilli()

	deviceA := &identity.Device{
		DeviceID:     "dev_it_rollback_a",
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   "Device A",
		PublicKeyHex: strings.Repeat("e", 64),
		CreatedAt:    now,
		LastActiveAt: now,
	}
	if _, err := repo.RegisterDeviceReplacingOthers(deviceA, "initial"); err != nil {
		t.Fatalf("initial device registration: %v", err)
	}

	// Valid nx_id but a non-existent account_id: the transaction revokes A
	// first, then the FK violation must roll the whole replacement back.
	badDevice := &identity.Device{
		DeviceID:     "dev_it_rollback_b",
		AccountID:    "usr_does_not_exist",
		NxID:         account.NxID,
		DeviceName:   "Broken Device",
		PublicKeyHex: strings.Repeat("f", 64),
		CreatedAt:    now + 1,
		LastActiveAt: now + 1,
	}
	if _, err := repo.RegisterDeviceReplacingOthers(badDevice, "must roll back"); err == nil {
		t.Fatal("expected the broken replacement to fail")
	}

	stillActive, ok := repo.GetDevice(deviceA.DeviceID)
	if !ok || stillActive.IsRevoked {
		t.Fatalf("failed replacement must not revoke the existing device: %+v", stillActive)
	}
	if _, ok := repo.GetDevice(badDevice.DeviceID); ok {
		t.Fatal("failed replacement must not persist the new device")
	}
}

func TestIntegration_ConcurrentDeviceReplacementLeavesSingleActiveDevice(t *testing.T) {
	_, repo := newIntegrationRepository(t)

	account := seedIntegrationAccount(t, repo, "NX-ITDEV004")
	now := time.Now().UnixMilli()

	makeDevice := func(id string, suffix byte) *identity.Device {
		return &identity.Device{
			DeviceID:     id,
			AccountID:    account.ID,
			NxID:         account.NxID,
			DeviceName:   "Concurrent " + id,
			PublicKeyHex: strings.Repeat(string(suffix), 64),
			CreatedAt:    now,
			LastActiveAt: now,
		}
	}

	devices := []*identity.Device{
		makeDevice("dev_it_concurrent_a", 'a'),
		makeDevice("dev_it_concurrent_b", 'b'),
	}
	errs := make([]error, len(devices))

	var wg sync.WaitGroup
	for i, device := range devices {
		wg.Add(1)
		go func(idx int, d *identity.Device) {
			defer wg.Done()
			_, errs[idx] = repo.RegisterDeviceReplacingOthers(d, "concurrent registration")
		}(i, device)
	}
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Fatalf("concurrent registration %d failed: %v", i, err)
		}
	}

	active := repo.GetDevicesForAccount(account.NxID)
	if len(active) != 1 {
		t.Fatalf("advisory lock must leave exactly one active device, got %+v", active)
	}
	if all := repo.GetAllDevicesForAccount(account.NxID); len(all) != 2 {
		t.Fatalf("both devices must remain for audit history, got %d", len(all))
	}
}

func TestIntegration_SessionRotationCutoffAndExceptToken(t *testing.T) {
	_, repo := newIntegrationRepository(t)

	account := seedIntegrationAccount(t, repo, "NX-ITDEV003")
	now := time.Now().UnixMilli()

	create := func(token string, createdAt int64) {
		t.Helper()
		if err := repo.CreateSession(&identity.Session{
			Token:     token,
			AccountID: account.ID,
			NxID:      account.NxID,
			DeviceID:  "dev_it_rotate",
			ExpiresAt: now + time.Hour.Milliseconds(),
			CreatedAt: createdAt,
		}); err != nil {
			t.Fatalf("CreateSession(%s): %v", token, err)
		}
	}

	create("sess_it_old_1", now)
	create("sess_it_old_2", now+1)
	create("sess_it_replacement", now+2)
	create("sess_it_same_ms", now+2)

	revoked, err := repo.RevokeSessionsForDevice(account.NxID, "dev_it_rotate", "sess_it_replacement", now+2)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 2 {
		t.Fatalf("expected 2 prior sessions revoked, got %d", revoked)
	}

	if _, ok := repo.GetSession("sess_it_old_1"); ok {
		t.Fatal("old session 1 must be revoked")
	}
	if _, ok := repo.GetSession("sess_it_old_2"); ok {
		t.Fatal("old session 2 must be revoked")
	}
	if _, ok := repo.GetSession("sess_it_replacement"); !ok {
		t.Fatal("replacement token must stay active")
	}
	if _, ok := repo.GetSession("sess_it_same_ms"); !ok {
		t.Fatal("same-millisecond sibling must survive the cutoff")
	}
}
