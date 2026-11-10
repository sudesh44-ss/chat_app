package database

import (
	"context"
	"fmt"
	"log"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Migration struct {
	Version int
	Name    string
	SQL     string
}

// migrations contains all ordered, idempotent schema migrations for NEXA production.
var migrations = []Migration{
	{
		Version: 1,
		Name:    "001_create_identity_schema",
		SQL: `
		CREATE TABLE IF NOT EXISTS accounts (
			id VARCHAR(64) PRIMARY KEY,
			nx_id VARCHAR(32) NOT NULL UNIQUE,
			public_key_hex VARCHAR(128) NOT NULL UNIQUE,
			dh_key_hex VARCHAR(128),
			phone_number VARCHAR(32),
			phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
			is_discoverable_by_phone BOOLEAN NOT NULL DEFAULT TRUE,
			google_id VARCHAR(128),
			supabase_user_id VARCHAR(128),
			email VARCHAR(255),
			profile_name VARCHAR(128) NOT NULL DEFAULT '',
			avatar_url TEXT,
			bio VARCHAR(512),
			gender VARCHAR(32),
			age INT DEFAULT 0,
			created_at BIGINT NOT NULL,
			updated_at BIGINT NOT NULL,
			CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')
		);

		CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_phone ON accounts(phone_number) 
			WHERE phone_number IS NOT NULL AND phone_number != '';
		CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_google_id ON accounts(google_id) 
			WHERE google_id IS NOT NULL AND google_id != '';
		CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_supabase_uid ON accounts(supabase_user_id) 
			WHERE supabase_user_id IS NOT NULL AND supabase_user_id != '';
		CREATE INDEX IF NOT EXISTS idx_accounts_pubkey ON accounts(public_key_hex);

		CREATE TABLE IF NOT EXISTS devices (
			device_id VARCHAR(128) PRIMARY KEY,
			account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			device_name VARCHAR(128) NOT NULL DEFAULT '',
			public_key_hex VARCHAR(128) NOT NULL,
			dh_key_hex VARCHAR(128),
			fcm_token TEXT,
			is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
			created_at BIGINT NOT NULL,
			last_active_at BIGINT NOT NULL
		);

		CREATE INDEX IF NOT EXISTS idx_devices_nx_id ON devices(nx_id);
		CREATE INDEX IF NOT EXISTS idx_devices_account_id ON devices(account_id);

		CREATE TABLE IF NOT EXISTS sessions (
			token VARCHAR(128) PRIMARY KEY,
			account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			device_id VARCHAR(128) NOT NULL,
			expires_at BIGINT NOT NULL,
			is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
			created_at BIGINT NOT NULL
		);

		CREATE INDEX IF NOT EXISTS idx_sessions_nx_id ON sessions(nx_id);
		CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token);
		CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);

		CREATE TABLE IF NOT EXISTS phone_verifications (
			id BIGSERIAL PRIMARY KEY,
			phone_number VARCHAR(32) NOT NULL,
			nonce VARCHAR(64) NOT NULL UNIQUE,
			session_id VARCHAR(128),
			verified BOOLEAN NOT NULL DEFAULT FALSE,
			expires_at BIGINT NOT NULL,
			created_at BIGINT NOT NULL
		);

		CREATE INDEX IF NOT EXISTS idx_phone_verifications_nonce ON phone_verifications(nonce);
		`,
	},
	{
		Version: 2,
		Name:    "002_create_connections_schema",
		SQL: `
		CREATE TABLE IF NOT EXISTS connection_requests (
			id VARCHAR(64) PRIMARY KEY,
			sender_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			recipient_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			intro_message VARCHAR(256),
			status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
			created_at BIGINT NOT NULL,
			updated_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			CONSTRAINT chk_no_self_request CHECK (sender_nx_id != recipient_nx_id)
		);

		CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_pending_request ON connection_requests(sender_nx_id, recipient_nx_id) 
			WHERE status = 'PENDING';
		CREATE INDEX IF NOT EXISTS idx_conn_req_sender ON connection_requests(sender_nx_id);
		CREATE INDEX IF NOT EXISTS idx_conn_req_recipient ON connection_requests(recipient_nx_id);
		CREATE INDEX IF NOT EXISTS idx_conn_req_status ON connection_requests(status);
		CREATE INDEX IF NOT EXISTS idx_conn_req_expires ON connection_requests(expires_at);

		CREATE TABLE IF NOT EXISTS connections (
			id VARCHAR(64) PRIMARY KEY,
			user_a_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			user_b_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
			created_at BIGINT NOT NULL,
			updated_at BIGINT NOT NULL,
			CONSTRAINT chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id),
			CONSTRAINT uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)
		);

		CREATE INDEX IF NOT EXISTS idx_connections_user_a ON connections(user_a_nx_id);
		CREATE INDEX IF NOT EXISTS idx_connections_user_b ON connections(user_b_nx_id);
		CREATE INDEX IF NOT EXISTS idx_connections_status ON connections(status);

		CREATE TABLE IF NOT EXISTS user_blocks (
			blocker_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			blocked_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			created_at BIGINT NOT NULL,
			PRIMARY KEY (blocker_nx_id, blocked_nx_id),
			CONSTRAINT chk_no_self_block CHECK (blocker_nx_id != blocked_nx_id)
		);

		CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker ON user_blocks(blocker_nx_id);
		CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON user_blocks(blocked_nx_id);
		`,
	},
	{
		Version: 3,
		Name:    "003_create_prekeys_schema",
		SQL: `
		CREATE TABLE IF NOT EXISTS identity_key_bindings (
			id VARCHAR(64) PRIMARY KEY,
			account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
			signing_public_key_hex VARCHAR(64) NOT NULL,
			dh_public_key_hex VARCHAR(64) NOT NULL,
			signature_hex VARCHAR(128) NOT NULL,
			verified BOOLEAN NOT NULL DEFAULT TRUE,
			created_at BIGINT NOT NULL,
			CONSTRAINT uq_ident_device UNIQUE (nx_id, device_id),
			CONSTRAINT chk_dh_pubkey CHECK (dh_public_key_hex ~ '^[0-9a-fA-F]{64}$'),
			CONSTRAINT chk_signing_pubkey CHECK (signing_public_key_hex ~ '^[0-9a-fA-F]{64}$')
		);

		CREATE TABLE IF NOT EXISTS signed_prekeys (
			id VARCHAR(64) PRIMARY KEY,
			account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
			key_id INT NOT NULL,
			public_key_hex VARCHAR(64) NOT NULL,
			signature_hex VARCHAR(128) NOT NULL,
			is_active BOOLEAN NOT NULL DEFAULT TRUE,
			created_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			CONSTRAINT chk_spk_pubkey CHECK (public_key_hex ~ '^[0-9a-fA-F]{64}$')
		);

		CREATE INDEX IF NOT EXISTS idx_spk_lookup ON signed_prekeys(nx_id, device_id, is_active);

		CREATE TABLE IF NOT EXISTS one_time_prekeys (
			id VARCHAR(64) PRIMARY KEY,
			account_id VARCHAR(64) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			device_id VARCHAR(128) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
			key_id INT NOT NULL,
			public_key_hex VARCHAR(64) NOT NULL,
			status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
			claim_id VARCHAR(64),
			claimed_by_nx_id VARCHAR(32) REFERENCES accounts(nx_id) ON DELETE SET NULL,
			claimed_by_device_id VARCHAR(128),
			claimed_at BIGINT,
			consumed_at BIGINT,
			expires_at BIGINT,
			created_at BIGINT NOT NULL,
			CONSTRAINT uq_device_opk_key_id UNIQUE (nx_id, device_id, key_id),
			CONSTRAINT chk_opk_status CHECK (status IN ('AVAILABLE', 'CLAIMED', 'CONSUMED', 'BURNED')),
			CONSTRAINT chk_opk_pubkey CHECK (public_key_hex ~ '^[0-9a-fA-F]{64}$')
		);

		CREATE INDEX IF NOT EXISTS idx_opk_alloc ON one_time_prekeys(nx_id, device_id, status);
		CREATE INDEX IF NOT EXISTS idx_opk_claim_lookup ON one_time_prekeys(claim_id);

		CREATE TABLE IF NOT EXISTS prekey_claims (
			claim_id VARCHAR(64) PRIMARY KEY,
			initiator_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			initiator_device_id VARCHAR(128) NOT NULL,
			target_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			target_device_id VARCHAR(128) NOT NULL,
			opk_id VARCHAR(64) REFERENCES one_time_prekeys(id) ON DELETE SET NULL,
			opk_key_id INT,
			spk_id VARCHAR(64) NOT NULL REFERENCES signed_prekeys(id) ON DELETE CASCADE,
			created_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL,
			status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
			CONSTRAINT chk_claim_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'EXPIRED'))
		);

		CREATE INDEX IF NOT EXISTS idx_claims_initiator ON prekey_claims(initiator_nx_id, initiator_device_id);
		CREATE INDEX IF NOT EXISTS idx_claims_target ON prekey_claims(target_nx_id, target_device_id);
		CREATE INDEX IF NOT EXISTS idx_claims_status_exp ON prekey_claims(status, expires_at);
		`,
	},
	{
		Version: 4,
		Name:    "004_create_relationship_graph_and_phone_privacy",
		SQL: `
		ALTER TABLE connections ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'NEXA_ID';

		CREATE TABLE IF NOT EXISTS phone_visibility_grants (
			id VARCHAR(64) PRIMARY KEY,
			granter_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			grantee_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
			is_active BOOLEAN NOT NULL DEFAULT TRUE,
			created_at BIGINT NOT NULL,
			updated_at BIGINT NOT NULL,
			CONSTRAINT uq_phone_grant UNIQUE(granter_nx_id, grantee_nx_id),
			CONSTRAINT chk_different_parties CHECK (granter_nx_id <> grantee_nx_id)
		);

		CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON phone_visibility_grants(granter_nx_id, is_active);
		CREATE INDEX IF NOT EXISTS idx_phone_grants_grantee ON phone_visibility_grants(grantee_nx_id, is_active);
		`,
	},
	{
		Version: 5,
		Name:    "005_create_message_idempotency_table",
		SQL: `
		CREATE TABLE IF NOT EXISTS message_idempotency (
			packet_id VARCHAR(128) PRIMARY KEY,
			sender_nx_id VARCHAR(32) NOT NULL,
			recipient_nx_id VARCHAR(32) NOT NULL,
			payload_sha256 VARCHAR(64) NOT NULL,
			first_seen_at BIGINT NOT NULL,
			expires_at BIGINT NOT NULL
		);

		CREATE INDEX IF NOT EXISTS idx_message_idempotency_sender ON message_idempotency(sender_nx_id);
		CREATE INDEX IF NOT EXISTS idx_message_idempotency_recipient ON message_idempotency(recipient_nx_id);
		CREATE INDEX IF NOT EXISTS idx_message_idempotency_expires ON message_idempotency(expires_at);
		`,
	},
	{
		Version: 6,
		Name:    "006_enforce_row_level_security_fail_closed",
		SQL: `
		-- 1. Explicitly enable Row-Level Security on all public tables (Fail-Closed Default)
		DO $$
		BEGIN
			-- Enable RLS on all 13 core tables
			EXECUTE 'ALTER TABLE accounts ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE devices ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE sessions ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE phone_verifications ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE connection_requests ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE connections ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE user_blocks ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE identity_key_bindings ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE signed_prekeys ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE one_time_prekeys ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE prekey_claims ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE phone_visibility_grants ENABLE ROW LEVEL SECURITY';
			EXECUTE 'ALTER TABLE message_idempotency ENABLE ROW LEVEL SECURITY';

			-- Revoke direct PostgREST anon and authenticated permissions if roles exist (Supabase environment)
			IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
				EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated';
				EXECUTE 'REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated';
				EXECUTE 'REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM anon, authenticated';
			END IF;
		END $$;
		`,
	},
}

// RunMigrations applies pending database migrations transactionally.
func RunMigrations(ctx context.Context, pool *pgxpool.Pool) error {
	// 1. Ensure schema_migrations table exists
	createTrackingTableSQL := `
	CREATE TABLE IF NOT EXISTS schema_migrations (
		version INT PRIMARY KEY,
		name VARCHAR(255) NOT NULL,
		applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
	);`
	if _, err := pool.Exec(ctx, createTrackingTableSQL); err != nil {
		return fmt.Errorf("failed to create schema_migrations table: %w", err)
	}

	// 2. Iterate and apply pending migrations
	for _, m := range migrations {
		var exists bool
		checkSQL := `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)`
		if err := pool.QueryRow(ctx, checkSQL, m.Version).Scan(&exists); err != nil {
			return fmt.Errorf("failed to check migration status for version %d: %w", m.Version, err)
		}

		if exists {
			continue
		}

		log.Printf("[Database] Applying migration %d: %s...", m.Version, m.Name)

		tx, err := pool.Begin(ctx)
		if err != nil {
			return fmt.Errorf("failed to begin transaction for migration %d: %w", m.Version, err)
		}

		if _, err := tx.Exec(ctx, m.SQL); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("migration %d (%s) failed: %w", m.Version, m.Name, err)
		}

		recordSQL := `INSERT INTO schema_migrations (version, name) VALUES ($1, $2)`
		if _, err := tx.Exec(ctx, recordSQL, m.Version, m.Name); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("failed to record migration %d: %w", m.Version, err)
		}

		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("failed to commit migration %d: %w", m.Version, err)
		}

		log.Printf("[Database] Migration %d (%s) applied successfully.", m.Version, m.Name)
	}

	return nil
}
