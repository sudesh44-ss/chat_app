-- ============================================================================
-- Migration: 011_create_constraints.sql
-- Description: Referential integrity constraints, uniqueness and validation rules
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- Ensure verified phone numbers are unique among active verified accounts
CREATE UNIQUE INDEX IF NOT EXISTS uq_verified_phone_number
ON public.profiles(phone_number)
WHERE phone_verified = TRUE AND phone_number IS NOT NULL;

-- Enforce maximum of 5 active devices per user account
CREATE OR REPLACE FUNCTION check_device_count()
RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT COUNT(*) FROM public.devices WHERE user_id = NEW.user_id AND is_revoked = FALSE) >= 5 THEN
        RAISE EXCEPTION 'Account cannot have more than 5 active devices';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_limit_devices ON public.devices;
CREATE TRIGGER trg_limit_devices
BEFORE INSERT ON public.devices
FOR EACH ROW EXECUTE FUNCTION check_device_count();
