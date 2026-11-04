-- ============================================================================
-- Migration: 009_create_device_sessions.sql
-- Description: Device authentication sessions for API and WebSocket authorization
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.device_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL REFERENCES public.devices(device_id) ON DELETE CASCADE,
    session_token_hash VARCHAR(128) NOT NULL UNIQUE,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.device_sessions IS 'Authenticated device session records. Stores SHA-256 hashed session tokens.';
