-- ============================================================================
-- Migration: 004_create_phone_verifications.sql
-- Description: Server-controlled OTP generation, keyed hash & verification state
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.phone_verifications (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(20) NOT NULL,
    hashed_otp VARCHAR(128) NOT NULL,
    salt VARCHAR(64) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    is_consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_otp_phone CHECK (phone_number ~ '^\+[1-9]\d{7,14}$')
);

COMMENT ON TABLE public.phone_verifications IS 'Backend-controlled OTP ledger. Stores salted SHA-256 hashes only, strictly zero plaintext OTPs.';
