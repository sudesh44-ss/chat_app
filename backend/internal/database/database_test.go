package database

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestSanitizeDatabaseURL(t *testing.T) {
	testCases := []struct {
		name     string
		input    string
		expected string
	}{
		{
			name:     "Standard postgres URL with password",
			input:    "postgres://nexa_user:SuperSecretPassword123@dpg-render-postgres.render.com:5432/nexa_db?sslmode=require",
			expected: "postgres://nexa_user:xxxxx@dpg-render-postgres.render.com:5432/nexa_db?sslmode=require",
		},
		{
			name:     "PostgreSQL URL without password",
			input:    "postgres://nexa_user@localhost:5432/nexa_db",
			expected: "postgres://nexa_user@localhost:5432/nexa_db",
		},
		{
			name:     "Mask fallback for non-standard connection string",
			input:    "user=nexa password=secret host=localhost port=5432 dbname=nexa",
			expected: "user=nexa password=xxxxx host=localhost port=5432 dbname=nexa",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			sanitized := SanitizeDatabaseURL(tc.input)
			if strings.Contains(sanitized, "SuperSecretPassword123") {
				t.Errorf("SanitizeDatabaseURL leaked sensitive password in output: %s", sanitized)
			}
			if strings.Contains(sanitized, "secret") {
				t.Errorf("SanitizeDatabaseURL leaked sensitive credentials: %s", sanitized)
			}
		})
	}
}

func TestDefaultConfig(t *testing.T) {
	rawURL := "postgres://user:pass@localhost:5432/testdb"
	cfg := DefaultConfig(rawURL)

	if cfg.DatabaseURL != rawURL {
		t.Errorf("expected DatabaseURL %s, got %s", rawURL, cfg.DatabaseURL)
	}
	if cfg.MaxConns != 25 {
		t.Errorf("expected MaxConns 25, got %d", cfg.MaxConns)
	}
	if cfg.MinConns != 5 {
		t.Errorf("expected MinConns 5, got %d", cfg.MinConns)
	}
	if cfg.MaxConnLifetime != 1*time.Hour {
		t.Errorf("expected MaxConnLifetime 1h, got %v", cfg.MaxConnLifetime)
	}
	if cfg.MaxConnIdleTime != 30*time.Minute {
		t.Errorf("expected MaxConnIdleTime 30m, got %v", cfg.MaxConnIdleTime)
	}
	if cfg.ConnectTimeout != 5*time.Second {
		t.Errorf("expected ConnectTimeout 5s, got %v", cfg.ConnectTimeout)
	}
}

func TestMigrationSchemaIntegrity(t *testing.T) {
	if len(migrations) != 6 {
		t.Fatalf("expected exactly 6 migrations defined, got %d", len(migrations))
	}

	for i, m := range migrations {
		expectedVersion := i + 1
		if m.Version != expectedVersion {
			t.Errorf("migration index %d expected version %d, got %d", i, expectedVersion, m.Version)
		}
		if m.Name == "" {
			t.Errorf("migration version %d has empty name", m.Version)
		}
		if m.SQL == "" {
			t.Errorf("migration version %d (%s) has empty SQL", m.Version, m.Name)
		}

		switch m.Version {
		case 1:
			criticalTables := []string{"accounts", "devices", "sessions", "phone_verifications"}
			for _, tbl := range criticalTables {
				if !strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS "+tbl) {
					t.Errorf("migration %s missing table creation for %s", m.Name, tbl)
				}
			}
			if !strings.Contains(m.SQL, "chk_nx_id_format") {
				t.Errorf("migration %s missing NX-ID format constraint chk_nx_id_format", m.Name)
			}
		case 2:
			connectionTables := []string{"connection_requests", "connections", "user_blocks"}
			for _, tbl := range connectionTables {
				if !strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS "+tbl) {
					t.Errorf("migration %s missing table creation for %s", m.Name, tbl)
				}
			}
			if !strings.Contains(m.SQL, "chk_canonical_users") {
				t.Errorf("migration %s missing canonical user constraint chk_canonical_users", m.Name)
			}
			if !strings.Contains(m.SQL, "chk_no_self_request") {
				t.Errorf("migration %s missing no-self-request constraint chk_no_self_request", m.Name)
			}
		case 3:
			prekeyTables := []string{"identity_key_bindings", "signed_prekeys", "one_time_prekeys", "prekey_claims"}
			for _, tbl := range prekeyTables {
				if !strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS "+tbl) {
					t.Errorf("migration %s missing table creation for %s", m.Name, tbl)
				}
			}
		case 4:
			if !strings.Contains(m.SQL, "ALTER TABLE connections ADD COLUMN IF NOT EXISTS source") {
				t.Errorf("migration %s missing connections source column migration", m.Name)
			}
			if !strings.Contains(m.SQL, "granter_id") {
				t.Errorf("migration %s missing legacy column detection for granter_id", m.Name)
			}
			if !strings.Contains(m.SQL, "legacy_supabase_phone_visibility_grants") {
				t.Errorf("migration %s missing archive table name legacy_supabase_phone_visibility_grants", m.Name)
			}
			if !strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS phone_visibility_grants") {
				t.Errorf("migration %s missing phone_visibility_grants creation", m.Name)
			}
			if !strings.Contains(m.SQL, "granter_nx_id VARCHAR(32)") {
				t.Errorf("migration %s missing granter_nx_id column definition", m.Name)
			}
			if !strings.Contains(m.SQL, "DEFAULT gen_random_uuid()::text") {
				t.Errorf("migration %s missing default UUID generator for id column", m.Name)
			}
			if !strings.Contains(m.SQL, "uq_phone_grants_nx_pair") {
				t.Errorf("migration %s missing unique constraint uq_phone_grants_nx_pair", m.Name)
			}
			if !strings.Contains(m.SQL, "idx_phone_grants_granter_nx") || !strings.Contains(m.SQL, "idx_phone_grants_grantee_nx") {
				t.Errorf("migration %s missing performance indexes", m.Name)
			}
			if !strings.Contains(m.SQL, "legacy_uq_phone_grant") {
				t.Errorf("migration %s missing legacy constraint rename to legacy_uq_phone_grant (SQLSTATE 42P07 defense)", m.Name)
			}
		case 5:
			if !strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS message_idempotency") {
				t.Errorf("migration %s missing message_idempotency table creation", m.Name)
			}
			if !strings.Contains(m.SQL, "idx_message_idempotency_sender") {
				t.Errorf("migration %s missing idempotency sender index", m.Name)
			}
		case 6:
			if !strings.Contains(m.SQL, "ENABLE ROW LEVEL SECURITY") {
				t.Errorf("migration %s missing RLS enablement", m.Name)
			}
			if !strings.Contains(m.SQL, "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated") {
				t.Errorf("migration %s missing PostgREST permission revoking", m.Name)
			}
			if !strings.Contains(m.SQL, "legacy_supabase_phone_visibility_grants") {
				t.Errorf("migration %s missing RLS protection for archived legacy table", m.Name)
			}
		}
	}
}

func TestMigration004_SchemaReconciliationSQL(t *testing.T) {
	var m4 *Migration
	for _, m := range migrations {
		if m.Version == 4 {
			m4 = &m
			break
		}
	}

	if m4 == nil {
		t.Fatal("migration 4 not found in migrations slice")
	}

	// 1. Verify safe dropping of legacy dependent view and function
	if !strings.Contains(m4.SQL, "DROP VIEW IF EXISTS public.safe_profiles CASCADE") {
		t.Error("migration 4 must safely drop legacy view safe_profiles to prevent dependency blocking")
	}
	if !strings.Contains(m4.SQL, "DROP FUNCTION IF EXISTS public.toggle_phone_visibility() CASCADE") {
		t.Error("migration 4 must safely drop legacy function toggle_phone_visibility to prevent dependency blocking")
	}

	// 2. Verify table rename preserves legacy data
	if !strings.Contains(m4.SQL, "ALTER TABLE public.phone_visibility_grants RENAME TO legacy_supabase_phone_visibility_grants") {
		t.Error("migration 4 must rename legacy table to legacy_supabase_phone_visibility_grants rather than dropping it")
	}

	// 3. Verify zero DROP TABLE statements (destructive operations prohibited)
	if strings.Contains(strings.ToUpper(m4.SQL), "DROP TABLE") {
		t.Error("migration 4 violates data-preservation rule: DROP TABLE detected in SQL")
	}
	if strings.Contains(strings.ToUpper(m4.SQL), "TRUNCATE") {
		t.Error("migration 4 violates data-preservation rule: TRUNCATE detected in SQL")
	}

	// 4. Verify data preservation mapping query
	if !strings.Contains(m4.SQL, "INSERT INTO phone_visibility_grants") || !strings.Contains(m4.SQL, "SELECT") {
		t.Error("migration 4 must include data preservation transfer from legacy table to reconciled table")
	}

	// 5. Verify resolution of SQLSTATE 42P07 (relation "uq_phone_grant" already exists)
	if !strings.Contains(m4.SQL, "legacy_uq_phone_grant") {
		t.Error("migration 4 must safely rename legacy unique constraint/index uq_phone_grant to prevent 42P07 collisions")
	}
	if !strings.Contains(m4.SQL, "uq_phone_grants_nx_pair") {
		t.Error("migration 4 must name new unique constraint with distinct canonical name uq_phone_grants_nx_pair")
	}
	if !strings.Contains(m4.SQL, "legacy_chk_different_parties") {
		t.Error("migration 4 must safely rename legacy check constraint chk_different_parties")
	}
}

func TestNewPoolFailsGracefullyOnInvalidURL(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	cfg := Config{
		DatabaseURL:    "invalid-url-schema://localhost",
		ConnectTimeout: 1 * time.Second,
	}

	pool, err := NewPool(ctx, cfg)
	if err == nil {
		if pool != nil {
			pool.Close()
		}
		t.Fatal("expected error on invalid database URL, got nil")
	}
}

func TestNewPoolFailsGracefullyOnUnreachableHost(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	cfg := Config{
		DatabaseURL:    "postgres://user:pass@127.0.0.1:54399/nonexistent?sslmode=disable",
		ConnectTimeout: 1 * time.Second,
	}

	pool, err := NewPool(ctx, cfg)
	if err == nil {
		if pool != nil {
			pool.Close()
		}
		t.Fatal("expected connection failure on unreachable port, got nil")
	}
}
