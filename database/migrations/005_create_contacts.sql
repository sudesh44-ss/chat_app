-- ============================================================================
-- Migration: 005_create_contacts.sql
-- Description: Server-side mutual friend relationships between registered NEXA users
-- Schema: PostgreSQL 15+ / Supabase
--
-- STRICT PRIVACY MANDATE:
-- This table stores ONLY confirmed bilateral relationships between registered NEXA users.
-- The user's device phone address book is NEVER uploaded to or stored in this table or database.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.contacts (
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    contact_user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    contact_nx_id VARCHAR(11) NOT NULL,
    alias_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'MUTUAL_FRIEND',
    source_of_discovery VARCHAR(20) NOT NULL DEFAULT 'PHONE_LOOKUP', -- 'PHONE_LOOKUP', 'NX_ID_SEARCH', 'QR_SCAN'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (owner_id, contact_user_id),
    CONSTRAINT chk_no_self_contact CHECK (owner_id <> contact_user_id)
);

COMMENT ON TABLE public.contacts IS 'Confirmed pairwise relationships between registered NEXA accounts. Device address books are NEVER stored here.';
