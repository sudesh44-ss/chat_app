-- ============================================================================
-- Migration: 003_create_nx_identity.sql
-- Description: Registry binding public NX-ID to Supabase user & key fingerprint
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.nx_identities (
    nx_id VARCHAR(11) PRIMARY KEY REFERENCES public.profiles(nx_id) ON DELETE CASCADE,
    user_id UUID NOT NULL UNIQUE REFERENCES public.profiles(id) ON DELETE CASCADE,
    public_key_hex VARCHAR(64) NOT NULL,
    key_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_identity_nx_id CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$')
);

COMMENT ON TABLE public.nx_identities IS 'Public identity registry establishing immutable binding between NX-ID, Supabase user ID, and Ed25519 identity key.';
