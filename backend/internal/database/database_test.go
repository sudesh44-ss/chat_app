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
	if len(migrations) < 2 {
		t.Fatalf("expected at least two migrations defined, got %d", len(migrations))
	}

	for _, m := range migrations {
		if m.Version <= 0 {
			t.Errorf("invalid migration version: %d", m.Version)
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
		}
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
