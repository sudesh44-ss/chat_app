-- ============================================================================
-- Migration: 017_create_e2ee_prekey_claims.sql
-- Description: E2EE Key Bindings, Signed Prekeys, One-Time Prekeys, and Prekey Claims
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.identity_key_bindings (
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

CREATE TABLE IF NOT EXISTS public.signed_prekeys (
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

CREATE INDEX IF NOT EXISTS idx_spk_lookup ON public.signed_prekeys(nx_id, device_id, is_active);

CREATE TABLE IF NOT EXISTS public.one_time_prekeys (
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

CREATE INDEX IF NOT EXISTS idx_opk_alloc ON public.one_time_prekeys(nx_id, device_id, status);
CREATE INDEX IF NOT EXISTS idx_opk_claim_lookup ON public.one_time_prekeys(claim_id);

CREATE TABLE IF NOT EXISTS public.prekey_claims (
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

CREATE INDEX IF NOT EXISTS idx_claims_initiator ON public.prekey_claims(initiator_nx_id, initiator_device_id);
CREATE INDEX IF NOT EXISTS idx_claims_target ON public.prekey_claims(target_nx_id, target_device_id);
CREATE INDEX IF NOT EXISTS idx_claims_status_exp ON public.prekey_claims(status, expires_at);
