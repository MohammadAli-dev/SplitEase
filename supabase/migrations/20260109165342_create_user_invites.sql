-- 20260109165342_create_user_invites.sql
-- Migration for the `user_invites` table (Sprint 13E)

CREATE TABLE IF NOT EXISTS user_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,

    -- Shareable invite token – uniqueness enforced, format left to the Edge Function
    invite_token TEXT NOT NULL UNIQUE,

    -- Owner of the invite (the Supabase auth user)
    created_by_cloud_user_id UUID NOT NULL
        REFERENCES auth.users(id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,

    -- Ensure an invite cannot be created already expired
    CONSTRAINT chk_expires_after_created
        CHECK (expires_at > created_at)
);

-- One active invite per creator (still a simple unique index; if you later add a soft‑delete flag,
-- you can convert this to a partial index: WHERE revoked = FALSE)
CREATE UNIQUE INDEX IF NOT EXISTS one_user_invite_per_creator
    ON user_invites (created_by_cloud_user_id);

-- Enable Row‑Level Security for the table
ALTER TABLE user_invites ENABLE ROW LEVEL SECURITY;

-- READ policy – owners can only SELECT their own rows
CREATE POLICY "read own user invite"
    ON user_invites
    FOR SELECT
    USING (auth.uid() = created_by_cloud_user_id);

-- INSERT policy – owners can only INSERT rows that belong to them
CREATE POLICY "insert own user invite"
    ON user_invites
    FOR INSERT
    WITH CHECK (auth.uid() = created_by_cloud_user_id);

-- NOTE: No DELETE policy is added at this stage (revocation is out of scope for Sprint 13E).
-- NOTE: No token‑format CHECK constraint is added now; we rely on the Edge Function to generate
--       correctly‑shaped tokens and on the UNIQUE constraint to prevent duplicates.