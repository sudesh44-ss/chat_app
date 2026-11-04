-- ============================================================================
-- Migration: 010_create_indexes.sql
-- Description: Performance B-Tree indexes for fast lookup and foreign key joins
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- Profiles & Identity Indexes
CREATE INDEX IF NOT EXISTS idx_profiles_nx_id ON public.profiles(nx_id);
CREATE INDEX IF NOT EXISTS idx_profiles_phone_number ON public.profiles(phone_number) WHERE phone_verified = TRUE;
CREATE INDEX IF NOT EXISTS idx_profiles_created_at ON public.profiles(created_at DESC);

-- Devices Indexes
CREATE INDEX IF NOT EXISTS idx_devices_user_id ON public.devices(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_last_active ON public.devices(last_active_at DESC);

-- Phone Verification Indexes
CREATE INDEX IF NOT EXISTS idx_phone_verifications_phone ON public.phone_verifications(phone_number, expires_at) WHERE is_consumed = FALSE;

-- Contacts Indexes
CREATE INDEX IF NOT EXISTS idx_contacts_owner_id ON public.contacts(owner_id);
CREATE INDEX IF NOT EXISTS idx_contacts_contact_nx_id ON public.contacts(contact_nx_id);

-- Prekeys Indexes
CREATE INDEX IF NOT EXISTS idx_prekeys_user_device ON public.prekeys(user_id, device_id) WHERE is_consumed = FALSE;

-- Message Metadata Indexes
CREATE INDEX IF NOT EXISTS idx_message_metadata_recipient ON public.message_metadata(recipient_nx_id, delivery_status);
CREATE INDEX IF NOT EXISTS idx_message_metadata_expiry ON public.message_metadata(expires_at);

-- Device Sessions Indexes
CREATE INDEX IF NOT EXISTS idx_device_sessions_token_hash ON public.device_sessions(session_token_hash) WHERE is_revoked = FALSE;
