-- SQL Schema for identity_maps table
-- Run this in Supabase SQL Editor before deploying the Edge Function
--
-- Purpose: Maps cloud user IDs (from Supabase Auth) to local user IDs (generated on device)
-- This enables the "lazy upgrade" pattern where local identity is linked after authentication.

CREATE TABLE IF NOT EXISTS identity_maps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Cloud user ID from Supabase auth.users.id
    -- UNIQUE constraint ensures one mapping per cloud user (idempotent inserts)
    cloud_user_id UUID NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,
    
    -- Local user ID generated on device (UUID string)
    local_user_id TEXT NOT NULL,
    
    -- Timestamp when the mapping was created
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Optional: timestamp when the mapping was last verified/used
    last_verified_at TIMESTAMPTZ
);

-- Index for quick lookups by local_user_id
-- Useful for future sync operations where we need to find the cloud user for local data
CREATE INDEX IF NOT EXISTS idx_identity_maps_local_user_id ON identity_maps(local_user_id);

-- RLS Policies
-- Note: The Edge Function uses service role, so RLS doesn't apply there.
-- These policies are for direct client access if needed in the future.

ALTER TABLE identity_maps ENABLE ROW LEVEL SECURITY;

-- Users can only read their own mapping
CREATE POLICY "Users can read own mapping" ON identity_maps
    FOR SELECT
    USING (auth.uid() = cloud_user_id);

-- No direct insert/update/delete from clients (only via Edge Function)
-- If you need client-side access, add appropriate policies here.

COMMENT ON TABLE identity_maps IS 'Maps cloud user identities to local device identities for offline-first sync';
COMMENT ON COLUMN identity_maps.cloud_user_id IS 'Supabase auth.users.id - unique, one mapping per cloud user';
COMMENT ON COLUMN identity_maps.local_user_id IS 'Device-generated UUID stored as TEXT. NOT UNIQUE: supports shared/offline devices where multiple cloud users may attempt to link the same local ID. Conflict resolution: first link wins (upsert with ignoreDuplicates: true).';
