-- ============================================================================
-- Migration: 008_create_message_metadata.sql
-- Description: Ephemeral delivery metadata tracking (ZERO PLAINTEXT CLOUD STORAGE)
-- Schema: PostgreSQL 15+ / Supabase
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.message_metadata (
    packet_id VARCHAR(64) PRIMARY KEY,
    sender_nx_id VARCHAR(11) NOT NULL,
    recipient_nx_id VARCHAR(11) NOT NULL,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', -- 'ACCEPTED', 'QUEUED', 'DELIVERED', 'FAILED'
    transport_type VARCHAR(20) NOT NULL DEFAULT 'WEBSOCKET',
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),

    CONSTRAINT chk_meta_sender CHECK (sender_nx_id ~ '^NX-[A-Z0-9]{8}$'),
    CONSTRAINT chk_meta_recipient CHECK (recipient_nx_id ~ '^NX-[A-Z0-9]{8}$')
);

COMMENT ON TABLE public.message_metadata IS 'Strictly ephemeral wire metadata for routing and offline queue status. NEVER stores plaintext message contents.';
