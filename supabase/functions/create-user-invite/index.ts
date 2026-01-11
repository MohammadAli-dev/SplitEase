/**
 * Edge Function: create-user-invite
 * ----------------------------------
 * Creates or returns an existing user invite token.
 *
 * Behavior:
 *  1. Validates the caller's JWT.
 *  2. If a non-expired invite exists for this user, returns it (reuse).
 *  3. Otherwise, deletes any expired invite and creates a fresh one.
 *
 * Request:
 *   POST /functions/v1/create-user-invite
 *   Headers: Authorization: Bearer <supabase-jwt>
 *
 * Response (200):
 *   { "inviteToken": "abc123...", "expiresAt": "2026-01-18T..." }
 *
 * Errors:
 *   401 – Missing/invalid auth
 *   500 – Insert failure
 */

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.43.4";

// ── Configuration ─────────────────────────────────────────────
const INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days

// ── Main Handler ──────────────────────────────────────────────
serve(async (req: Request): Promise<Response> => {
  // ── Auth guard ──────────────────────────────────────────────
  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return jsonError("Missing or invalid Authorization header", 401);
  }

  // ── Validate environment ─────────────────────────────────────
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

  if (!supabaseUrl || !serviceRoleKey) {
    console.error("Missing required environment variables: SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY");
    return jsonError("Server configuration error", 500);
  }

  // Create Supabase client with service-role key but forward user's JWT
  // so that auth.getUser() resolves the caller's identity.
  const supabase = createClient(supabaseUrl, serviceRoleKey, {
    global: { headers: { Authorization: authHeader } },
  });

  // ── Identify caller ─────────────────────────────────────────
  const {
    data: { user },
    error: authError,
  } = await supabase.auth.getUser();

  if (!user || authError) {
    return jsonError("Unauthorized", 401);
  }

  const now = new Date().toISOString();

  // ── Try to reuse a non-expired invite ───────────────────────
  const { data: existing, error: selectError } = await supabase
    .from("user_invites")
    .select("invite_token, expires_at")
    .eq("created_by_cloud_user_id", user.id)
    .gt("expires_at", now)
    .maybeSingle();

  if (selectError) {
    console.error("Select failed:", selectError.message);
    return jsonError("Failed to check existing invite", 500);
  }

  if (existing) {
    return Response.json({
      inviteToken: existing.invite_token,
      expiresAt: existing.expires_at,
    });
  }

  // ── Delete only *expired* invites before inserting ──────────
  // This is defensive: avoids deleting a valid invite if clock skew occurs.
  const { error: deleteError } = await supabase
    .from("user_invites")
    .delete()
    .eq("created_by_cloud_user_id", user.id)
    .lte("expires_at", now);

  if (deleteError) {
    console.error("Delete failed:", deleteError.message);
    // Non-fatal: continue to insert (unique constraint will catch duplicates)
  }

  // ── Generate new invite ─────────────────────────────────────
  const inviteToken = crypto.randomUUID().replaceAll("-", "");
  const expiresAt = new Date(Date.now() + INVITE_TTL_MS).toISOString();

  const { data, error: insertError } = await supabase
    .from("user_invites")
    .insert({
      invite_token: inviteToken,
      created_by_cloud_user_id: user.id,
      expires_at: expiresAt,
    })
    .select("invite_token, expires_at")
    .single();

  if (insertError) {
    console.error("Insert failed:", insertError.message);
    return jsonError("Invite creation failed", 500);
  }

  return Response.json({
    inviteToken: data.invite_token,
    expiresAt: data.expires_at,
  });
});

// ── Helper: JSON error response ───────────────────────────────
function jsonError(message: string, status: number): Response {
  return Response.json({ error: message }, { status });
}
