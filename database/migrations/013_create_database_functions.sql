-- ============================================================================
-- Migration: 013_create_database_functions.sql
-- Description: Server-side database functions for secure random NX-ID & lookup
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- Function to generate a random 8-character uppercase alphanumeric NX-ID
CREATE OR REPLACE FUNCTION public.generate_random_nx_id()
RETURNS VARCHAR(11) AS $$
DECLARE
    chars TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    result TEXT := 'NX-';
    i INT;
    candidate VARCHAR(11);
    collision BOOLEAN;
BEGIN
    LOOP
        result := 'NX-';
        FOR i IN 1..8 LOOP
            result := result || substr(chars, floor(random() * length(chars) + 1)::INT, 1);
        END LOOP;
        
        -- Check for uniqueness in profiles table
        SELECT EXISTS(SELECT 1 FROM public.profiles WHERE nx_id = result) INTO collision;
        IF NOT collision THEN
            RETURN result;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql VOLATILE;

-- Function for authenticated batch phone lookup (strictly verified & discoverable accounts)
CREATE OR REPLACE FUNCTION public.lookup_nexa_contacts(phone_list TEXT[])
RETURNS TABLE(
    phone_number VARCHAR(20),
    nx_id VARCHAR(11),
    full_name VARCHAR(100),
    public_key_hex VARCHAR(64)
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        p.phone_number,
        p.nx_id,
        p.full_name,
        d.public_key_hex
    FROM public.profiles p
    LEFT JOIN LATERAL (
        SELECT dev.public_key_hex
        FROM public.devices dev
        WHERE dev.user_id = p.id AND dev.is_revoked = FALSE
        ORDER BY dev.last_active_at DESC
        LIMIT 1
    ) d ON TRUE
    WHERE p.phone_verified = TRUE
      AND p.is_discoverable_by_phone = TRUE
      AND p.phone_number = ANY(phone_list);
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;
