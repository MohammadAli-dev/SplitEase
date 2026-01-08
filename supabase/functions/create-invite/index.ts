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

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { crypto } from "https://deno.land/std@0.168.0/crypto/mod.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
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
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
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
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
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

    const { phantomLocalUserId } = body

    // Use service role client for database operations
    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Check if invite already exists for this phantom + creator (idempotent)
    const { data: existingInvite, error: selectError } = await supabaseAdmin
      .from('connection_invites')
      .select('invite_token, expires_at')
      .eq('phantom_local_user_id', phantomLocalUserId)
      .eq('created_by_cloud_user_id', cloudUserId)
      .single()

    if (existingInvite && !selectError) {
      // Return existing invite (idempotent)
      console.log(`Returning existing invite for phantom=${phantomLocalUserId}`)
      const response: CreateInviteResponse = {
        inviteToken: existingInvite.invite_token,
        expiresAt: existingInvite.expires_at
      }
      return new Response(
        JSON.stringify(response),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
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

    console.log(`Created invite for phantom=${phantomLocalUserId}, token=${inviteToken.substring(0, 10)}...`)

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
