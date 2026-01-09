-- SQL Schema for connection_invites table
-- Run this in Supabase SQL Editor
--
-- Purpose: Tracks invite lifecycle for phantom → real user connections.
-- This enables the "claim" flow where User B joins via invite link.

CREATE TABLE IF NOT EXISTS connection_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Unique invite token for sharing (format: inv_<random_hex>)
    invite_token TEXT NOT NULL UNIQUE,
    
    -- Local phantom user ID that this invite is for
    phantom_local_user_id TEXT NOT NULL,
    
    -- Cloud user who created this invite (inviter)
    created_by_cloud_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    
    -- Cloud user who claimed this invite (invitee)
    -- NULL until claimed
    claimed_by_cloud_user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    
    -- Display name of the claimer (cached for offline display)
    claimed_by_name TEXT,
    
    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    
    -- Ensure one invite per phantom per creator
    UNIQUE (phantom_local_user_id, created_by_cloud_user_id)
);

-- Index for quick lookups by invite token (used during claim)
CREATE INDEX IF NOT EXISTS idx_invites_token ON connection_invites(invite_token);

-- Index for lookups by phantom ID and creator (used during status check)
CREATE INDEX IF NOT EXISTS idx_invites_phantom_creator ON connection_invites(phantom_local_user_id, created_by_cloud_user_id);

-- RLS Policies
ALTER TABLE connection_invites ENABLE ROW LEVEL SECURITY;

-- Creators can read their own invites
CREATE POLICY "Creators can read own invites" ON connection_invites
    FOR SELECT
    USING (auth.uid() = created_by_cloud_user_id);

-- Creators can insert invites for themselves
CREATE POLICY "Creators can insert own invites" ON connection_invites
    FOR INSERT
    WITH CHECK (auth.uid() = created_by_cloud_user_id);

-- NOTE: Token lookups for claiming are performed via Edge Functions using the
-- service-role key, NOT via direct client queries with RLS.
-- This ensures invite tokens act as bearer secrets without exposing rows.

-- NOTE: Updates (claiming) are performed exclusively via Edge Functions using
-- the service-role key. There is NO client-side UPDATE policy.
-- This enforces that claiming logic (atomic checks, notification) must go through the API.

COMMENT ON TABLE connection_invites IS 'Tracks invite lifecycle for phantom → real user connections';
COMMENT ON COLUMN connection_invites.invite_token IS 'Shareable token (format: inv_<hex>). Acts as bearer auth for claiming.';
COMMENT ON COLUMN connection_invites.phantom_local_user_id IS 'Local phantom user ID on the inviter device';
