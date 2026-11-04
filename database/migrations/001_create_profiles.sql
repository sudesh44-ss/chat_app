-- ============================================================================
-- Migration: 001_create_profiles.sql
-- Description: Create user profiles table bound to Supabase auth.users
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    nx_id VARCHAR(11) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20),
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    bio VARCHAR(240),
    gender VARCHAR(20),
    profile_picture_url TEXT,
    is_discoverable_by_phone BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Enforce locked NX-ID specification: NX- followed by exactly 8 uppercase alphanumeric characters
    CONSTRAINT chk_nx_id_format CHECK (nx_id ~ '^NX-[A-Z0-9]{8}$'),
    -- Enforce E.164 phone number format if present
    CONSTRAINT chk_phone_format CHECK (phone_number IS NULL OR phone_number ~ '^\+[1-9]\d{7,14}$')
);

COMMENT ON TABLE public.profiles IS 'NEXA user profiles linked 1:1 with Supabase Auth authority. Holds public NX-ID handle and verification state.';
COMMENT ON COLUMN public.profiles.nx_id IS 'Immutable 11-char public handle in NX-XXXXXXXX format generated randomly by backend.';
