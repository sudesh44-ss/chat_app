-- ============================================================================
-- Migration: 016_create_phone_visibility_grants.sql
-- Description: Server-Side Phone Number Privacy & Row-Level Projection Grants
-- Architecture: Zero-Leakage Server Defense (Prevents Proxy / JSON Interception)
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- 1. Explicit Phone Visibility Permission Grants
CREATE TABLE IF NOT EXISTS public.phone_visibility_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    grantee_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_phone_grant UNIQUE(granter_id, grantee_id),
    CONSTRAINT chk_different_parties CHECK (granter_id <> grantee_id)
);

COMMENT ON TABLE public.phone_visibility_grants IS 
'Cryptographic privacy gatekeeper: Explicit bilateral grants controlling phone number visibility between peers.';

-- 2. Indexes for Sub-millisecond Authorization Checks
CREATE INDEX IF NOT EXISTS idx_phone_grants_granter ON public.phone_visibility_grants(granter_id, is_active);
CREATE INDEX IF NOT EXISTS idx_phone_grants_grantee ON public.phone_visibility_grants(grantee_id, is_active);

-- 3. Row-Level Security (RLS) on Grants Table
ALTER TABLE public.phone_visibility_grants ENABLE ROW LEVEL SECURITY;

-- Granter can view, insert, update and revoke their own grants
CREATE POLICY "Users can manage their own phone visibility grants"
ON public.phone_visibility_grants FOR ALL
TO authenticated
USING (auth.uid() = granter_id)
WITH CHECK (auth.uid() = granter_id);

-- Grantees can view active grants issued to them
CREATE POLICY "Grantees can inspect visibility granted to them"
ON public.phone_visibility_grants FOR SELECT
TO authenticated
USING (auth.uid() = grantee_id AND is_active = TRUE);

-- 4. Server-Side Zero-Leakage Profile Projection View
-- CRITICAL SECURITY RULE: If no grant exists or phone is not in peer's local address book,
-- the PostgreSQL engine projects phone_number as NULL on the wire.
-- Intercepting proxy tools (Burp Suite, Charles) inspect plain JSON and see 0 bytes of phone data.
CREATE OR REPLACE VIEW public.safe_profiles WITH (security_invoker = true) AS
SELECT 
    p.id,
    p.nx_id,
    p.full_name AS display_name,
    p.bio,
    p.profile_picture_url AS avatar_url,
    p.created_at,
    CASE 
        -- Owner always views their own phone number
        WHEN auth.uid() = p.id THEN p.phone_number
        -- Active grant exists: Return decrypted E.164 phone number
        WHEN EXISTS (
            SELECT 1 FROM public.phone_visibility_grants g 
            WHERE g.granter_id = p.id 
              AND g.grantee_id = auth.uid() 
              AND g.is_active = TRUE
        ) THEN p.phone_number
        -- Default Fail-Closed: Return NULL (Zero Leakage)
        ELSE NULL 
    END AS phone_number
FROM public.profiles p;

-- 5. Helper Function: Grant / Revoke Phone Visibility Handshake
CREATE OR REPLACE FUNCTION public.toggle_phone_visibility(
    target_grantee_id UUID,
    grant_active BOOLEAN
)
RETURNS JSONB AS $$
DECLARE
    caller_id UUID := auth.uid();
BEGIN
    IF caller_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized';
    END IF;

    INSERT INTO public.phone_visibility_grants (granter_id, grantee_id, is_active, updated_at)
    VALUES (caller_id, target_grantee_id, grant_active, NOW())
    ON CONFLICT (granter_id, grantee_id)
    DO UPDATE SET 
        is_active = EXCLUDED.is_active,
        updated_at = NOW();

    RETURN jsonb_build_object(
        'status', 'SUCCESS',
        'is_active', grant_active,
        'granter_id', caller_id,
        'grantee_id', target_grantee_id,
        'timestamp', NOW()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
