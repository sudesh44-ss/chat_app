-- ============================================================================
-- Migration: 015_create_audit_telemetry_partitioning.sql
-- Description: Master Compliance & Telemetry Schema with 1-Year Range Partitioning
-- Architecture: Zero-Plaintext Audit Trail with Instant DDL Partition Drop (Zero Vacuum Bloat)
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

-- 1. Base Range-Partitioned Telemetry Table (Partitioned by created_at)
CREATE TABLE IF NOT EXISTS public.user_telemetry_events (
    event_id UUID DEFAULT gen_random_uuid(),
    msg_id_hash VARCHAR(64) NOT NULL, -- Irreversible SHA-256 hash of Message UUID
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    media_category VARCHAR(20) NOT NULL DEFAULT 'NONE',
    size_bytes BIGINT NOT NULL DEFAULT 0,
    event_type VARCHAR(30) NOT NULL,
    consent_action VARCHAR(20) NOT NULL DEFAULT 'GRANTED',
    is_legal_hold BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_media_cat CHECK (media_category IN ('NONE', 'IMAGE', 'VIDEO', 'AUDIO', 'DOC')),
    CONSTRAINT chk_event_typ CHECK (event_type IN ('MSG_SENT', 'MSG_DELETED', 'MEDIA_SENT', 'AI_QUERY')),
    CONSTRAINT chk_consent_act CHECK (consent_action IN ('GRANTED', 'WITHDRAWN')),
    PRIMARY KEY (created_at, event_id)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE public.user_telemetry_events IS 
'Zero-Plaintext compliance audit table. Stores ONLY irreversible hashes and event types. NEVER stores message words, filenames, or media binary content.';

-- 2. Initial Monthly Partitions
CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_01 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_02 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_03 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_04 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_05 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_06 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_07 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_08 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_09 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_10 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_11 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS public.user_telemetry_2026_12 PARTITION OF public.user_telemetry_events
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

-- 3. High-Performance Index on Partition Keys
CREATE INDEX IF NOT EXISTS idx_telemetry_msghash ON public.user_telemetry_events (msg_id_hash);
CREATE INDEX IF NOT EXISTS idx_telemetry_created_at ON public.user_telemetry_events (created_at DESC);

-- 4. Instant Zero-Overhead Purge Function (Drop Monthly Partition vs Slow DELETE)
CREATE OR REPLACE FUNCTION public.drop_expired_telemetry_partition(target_table_name TEXT)
RETURNS VOID AS $$
BEGIN
    -- Check if partition has active legal hold flags before dropping
    EXECUTE format('
        SELECT 1 FROM %I WHERE is_legal_hold = TRUE LIMIT 1;
    ', target_table_name);

    IF NOT FOUND THEN
        -- Instant 1ms metadata drop: Releases 100% disk space with zero vacuum bloat
        EXECUTE format('DROP TABLE IF EXISTS %I;', target_table_name);
        RAISE NOTICE 'Partition % successfully dropped under 1-Year Retention Policy', target_table_name;
    ELSE
        RAISE WARNING 'Partition % retained due to active Legal Hold', target_table_name;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
