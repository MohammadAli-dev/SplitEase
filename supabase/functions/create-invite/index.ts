// Supabase Edge Function: create-invite
// Path: POST /functions/v1/create-invite
//
// Creates an invite for a phantom user to be claimed by a real user.
// Idempotent: Returns existing invite if one already exists.
//
// Authentication: Requires valid Supabase JWT
//
// Request Body:
// {
//   "phantomLocalUserId": "uuid-phantom-user"
// }
//
// Response (Success):
// {
//   "inviteToken": "inv_abc123...",
//   "expiresAt": "2026-12-31T23:59:59Z"
// }

import { serve } from "https://deno.land/std@0.224.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.89.0"
import { crypto } from "https://deno.land/std@0.224.0/crypto/mod.ts"

// Base headers without origin (origin added dynamically)
const baseHeaders = {
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

/**
 * Get CORS headers with validated origin.
 * Returns headers with matching origin if allowed, or null if origin not permitted.
 * 
 * ALLOWED_ORIGINS env var should be comma-separated list of origins.
 * If not set, defaults to allowing all origins (dev mode).
 */
function getCorsHeaders(req: Request): Record<string, string> | null {
  const origin = req.headers.get('Origin')
  const allowedOriginsEnv = Deno.env.get('ALLOWED_ORIGINS')
  
  // If ALLOWED_ORIGINS not configured, allow all (dev mode with warning)
  if (!allowedOriginsEnv) {
    console.warn('ALLOWED_ORIGINS not set - allowing all origins (dev mode)')
    return {
      ...baseHeaders,
      'Access-Control-Allow-Origin': origin || '*',
    }
  }
  
  // Parse allowed origins from env
  const allowedOrigins = allowedOriginsEnv.split(',').map(o => o.trim())
  
  // Check if request origin is in allowlist
  if (origin && allowedOrigins.includes(origin)) {
    return {
      ...baseHeaders,
      'Access-Control-Allow-Origin': origin,
    }
  }
  
  // Origin not allowed
  return null
}

/**
 * Get required environment variable or throw descriptive error.
 */
function getRequiredEnv(name: string): string {
  const value = Deno.env.get(name)
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`)
  }
  return value
}

interface CreateInviteRequest {
  phantomLocalUserId: string
}

interface CreateInviteResponse {
  inviteToken: string
  expiresAt: string
}

// Generate a secure random invite token
async function generateInviteToken(): Promise<string> {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('')
  return `inv_${hex}`
}

serve(async (req: Request) => {
  const corsHeaders = getCorsHeaders(req)
  
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    if (!corsHeaders) {
      return new Response('Forbidden', { status: 403 })
    }
    return new Response('ok', { headers: corsHeaders })
  }

  // Reject requests from disallowed origins
  if (!corsHeaders) {
    return new Response(
      JSON.stringify({ error: 'Origin not allowed' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } }
    )
  }

  try {
    // Validate required environment variables upfront
    const supabaseUrl = getRequiredEnv('SUPABASE_URL')
    const supabaseAnonKey = getRequiredEnv('SUPABASE_ANON_KEY')
    const supabaseServiceRoleKey = getRequiredEnv('SUPABASE_SERVICE_ROLE_KEY')

    // Verify Authorization header exists
    const authHeader = req.headers.get('Authorization')
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return new Response(
        JSON.stringify({ error: 'Missing or invalid Authorization header' }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Create Supabase client with user's JWT
    const supabaseClient = createClient(
      supabaseUrl,
      supabaseAnonKey,
      { global: { headers: { Authorization: authHeader } } }
    )

    // Verify JWT and get user
    const { data: { user }, error: authError } = await supabaseClient.auth.getUser()
    if (authError || !user) {
      console.error('Auth error:', authError?.message)
      return new Response(
        JSON.stringify({ error: 'Invalid or expired token' }),
        { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const cloudUserId = user.id

    // Parse request body
    const body: CreateInviteRequest = await req.json()
    if (!body.phantomLocalUserId || typeof body.phantomLocalUserId !== 'string') {
      return new Response(
        JSON.stringify({ error: 'Missing or invalid phantomLocalUserId' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Validate UUID format
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    if (!uuidRegex.test(body.phantomLocalUserId)) {
      return new Response(
        JSON.stringify({ error: 'phantomLocalUserId must be a valid UUID' }),
        { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    const { phantomLocalUserId } = body

    // Use service role client for database operations
    const supabaseAdmin = createClient(
      supabaseUrl,
      supabaseServiceRoleKey
    )

    // Check if invite already exists for this phantom + creator (idempotent)
    const { data: existingInvite, error: selectError } = await supabaseAdmin
      .from('connection_invites')
      .select('id, invite_token, expires_at')
      .eq('phantom_local_user_id', phantomLocalUserId)
      .eq('created_by_cloud_user_id', cloudUserId)
      .single()

    // PGRST116 = "no rows returned" which is expected when no invite exists
    // Any other error code indicates a real database problem
    if (selectError && selectError.code !== 'PGRST116') {
      console.error('Database error checking existing invite:', selectError.message)
      return new Response(
        JSON.stringify({ error: 'Database error' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    if (existingInvite) {
      // Check if existing invite is expired
      const expiresAt = new Date(existingInvite.expires_at)
      if (expiresAt > new Date()) {
        // Return existing non-expired invite (idempotent)
        console.log(`Returning existing invite for phantom=${phantomLocalUserId}`)
        const response: CreateInviteResponse = {
          inviteToken: existingInvite.invite_token,
          expiresAt: existingInvite.expires_at
        }
        return new Response(
          JSON.stringify(response),
          { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        )
      } else {
        // Existing invite is expired - delete it so we can create a new one
        console.log(`Existing invite expired for phantom=${phantomLocalUserId}, deleting`)
        const { error: deleteError } = await supabaseAdmin
          .from('connection_invites')
          .delete()
          .eq('id', existingInvite.id)

        if (deleteError) {
          console.error('Failed to delete expired invite:', deleteError.message)
          return new Response(
            JSON.stringify({ error: 'Failed to delete expired invite' }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
          )
        }
      }
    }

    // Generate new invite
    const inviteToken = await generateInviteToken()
    const expiresAt = new Date()
    expiresAt.setDate(expiresAt.getDate() + 30) // 30 day expiry

    const { error: insertError } = await supabaseAdmin
      .from('connection_invites')
      .insert({
        invite_token: inviteToken,
        phantom_local_user_id: phantomLocalUserId,
        created_by_cloud_user_id: cloudUserId,
        expires_at: expiresAt.toISOString()
      })

    if (insertError) {
      console.error('Insert error:', insertError.message)
      return new Response(
        JSON.stringify({ error: 'Failed to create invite' }),
        { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    console.log(`Created invite for phantom=${phantomLocalUserId}, creator=${cloudUserId}`)

    const response: CreateInviteResponse = {
      inviteToken,
      expiresAt: expiresAt.toISOString()
    }

    return new Response(
      JSON.stringify(response),
      { status: 201, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Unexpected error:', error)
    return new Response(
      JSON.stringify({ error: 'Internal server error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
