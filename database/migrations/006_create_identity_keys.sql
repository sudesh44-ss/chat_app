-- ============================================================================
-- Migration: 006_create_identity_keys.sql
-- Description: Authenticated public identity keys with proof-of-possession signatures
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.identity_keys (
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL REFERENCES public.devices(device_id) ON DELETE CASCADE,
    public_key_hex VARCHAR(64) NOT NULL,
    signature_hex VARCHAR(128) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, device_id),
    CONSTRAINT chk_ident_pubkey CHECK (public_key_hex ~ '^[0-9a-fA-F]{64}$')
);

COMMENT ON TABLE public.identity_keys IS 'Cryptographically verified Ed25519 public keys per authorized device. Never stores private keys.';
