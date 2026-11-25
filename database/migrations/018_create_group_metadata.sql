-- Migration 018: Create Group Metadata Tables
-- Purpose: Address book metadata for group chat membership and roles.
-- STRICT PRIVACY NOTICE: Zero chat messages or plaintext are stored in this schema.
-- Only group identifiers, roles, and membership state are tracked.

CREATE TABLE IF NOT EXISTS groups (
    group_id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    avatar_url TEXT,
    membership_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS group_members (
    id BIGSERIAL PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    user_nx_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER', -- 'ADMIN', 'MEMBER'
    membership_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'LEFT', 'REMOVED'
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_group_member UNIQUE (group_id, user_nx_id)
);

CREATE INDEX IF NOT EXISTS idx_group_members_group_active ON group_members(group_id, membership_state);
CREATE INDEX IF NOT EXISTS idx_group_members_user_active ON group_members(user_nx_id, membership_state);

-- Enable RLS
ALTER TABLE groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_members ENABLE ROW LEVEL SECURITY;

-- Group read policy: members can view their groups
CREATE POLICY group_members_view_groups ON groups
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM group_members gm
            WHERE gm.group_id = groups.group_id
            AND gm.user_nx_id = auth.uid()::text
            AND gm.membership_state = 'ACTIVE'
        )
    );

-- Group members read policy: members can see member list of groups they belong to
CREATE POLICY group_members_view_members ON group_members
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM group_members my_gm
            WHERE my_gm.group_id = group_members.group_id
            AND my_gm.user_nx_id = auth.uid()::text
            AND my_gm.membership_state = 'ACTIVE'
        )
    );
