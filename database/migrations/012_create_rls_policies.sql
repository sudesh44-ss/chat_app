-- ============================================================================
-- Migration: 012_create_rls_policies.sql
-- Description: Supabase Row-Level Security (RLS) policies for user data isolation
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- 1. Profiles Table RLS
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public profiles are viewable by authenticated users"
ON public.profiles FOR SELECT
TO authenticated
USING (true);

CREATE POLICY "Users can only update their own profile"
ON public.profiles FOR UPDATE
TO authenticated
USING (auth.uid() = id)
WITH CHECK (auth.uid() = id);

-- 2. Devices Table RLS
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view their own devices"
ON public.devices FOR SELECT
TO authenticated
USING (auth.uid() = user_id);

CREATE POLICY "Users can modify their own devices"
ON public.devices FOR ALL
TO authenticated
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

-- 3. NX Identities Table RLS
ALTER TABLE public.nx_identities ENABLE ROW LEVEL SECURITY;

CREATE POLICY "NX identities are publicly readable for verification"
ON public.nx_identities FOR SELECT
TO authenticated
USING (true);

-- 4. Contacts Table RLS
ALTER TABLE public.contacts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can only view their own contacts"
ON public.contacts FOR SELECT
TO authenticated
USING (auth.uid() = owner_id);

CREATE POLICY "Users can only insert or delete their own contacts"
ON public.contacts FOR ALL
TO authenticated
USING (auth.uid() = owner_id)
WITH CHECK (auth.uid() = owner_id);

-- 5. Prekeys Table RLS
ALTER TABLE public.prekeys ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Prekeys are readable by authenticated users for handshakes"
ON public.prekeys FOR SELECT
TO authenticated
USING (true);

CREATE POLICY "Users can only manage their own prekeys"
ON public.prekeys FOR ALL
TO authenticated
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

-- 6. Phone Verifications & Sessions: Locked to Service Role Only
ALTER TABLE public.phone_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_metadata ENABLE ROW LEVEL SECURITY;
-- Default deny for public role; backend service_role bypasses RLS
