-- ============================================================================
-- Migration: 002_create_devices.sql
-- Description: Create authorized devices table for multi-device routing
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.devices (
    device_id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_name VARCHAR(100) NOT NULL DEFAULT 'Android Device',
    platform VARCHAR(20) NOT NULL DEFAULT 'ANDROID',
    app_version VARCHAR(30),
    public_key_hex VARCHAR(64) NOT NULL,
    dh_public_key_hex VARCHAR(64),
    fcm_token TEXT,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMPTZ,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_pubkey_hex CHECK (public_key_hex ~ '^[0-9a-fA-F]{64}$')
);

COMMENT ON TABLE public.devices IS 'Registered and authorized hardware devices per account. Used for multi-device fanout and routing.';
