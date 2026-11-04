-- ============================================================================
-- Migration: 007_create_prekeys.sql
-- Description: X3DH Signed Prekeys and One-Time Prekey bundles for async handshakes
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.prekeys (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL REFERENCES public.devices(device_id) ON DELETE CASCADE,
    key_id INT NOT NULL,
    key_type VARCHAR(20) NOT NULL, -- 'SIGNED_PREKEY', 'ONE_TIME_PREKEY'
    public_key_hex VARCHAR(64) NOT NULL,
    signature_hex VARCHAR(128),
    is_consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_prekey_type CHECK (key_type IN ('SIGNED_PREKEY', 'ONE_TIME_PREKEY'))
);

COMMENT ON TABLE public.prekeys IS 'Public X25519 prekey bundles used by callers for X3DH initial secret computation.';
