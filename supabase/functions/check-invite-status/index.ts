// Supabase Edge Function: check-invite-status
// Path: POST /functions/v1/check-invite-status
//
// Checks the status of an invite for a phantom user.
// Security: Only the creator can check their own invites.
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
//   "status": "PENDING" | "CLAIMED",
//   "claimedBy": {
//     "cloudUserId": "uuid",
//     "name": "Bob"
//   } | null
// }

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

interface CheckStatusRequest {
  phantomLocalUserId: string
}

interface CheckStatusResponse {
  status: 'PENDING' | 'CLAIMED' | 'NOT_FOUND' | 'EXPIRED'
  claimedBy: {
    cloudUserId: string
    name: string
  } | null
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
    const body: CheckStatusRequest = await req.json()
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

    // SECURITY: Filter by BOTH phantom_local_user_id AND created_by_cloud_user_id
    // This ensures users can only check their own invites
    const { data: invite, error: selectError } = await supabaseAdmin
      .from('connection_invites')
      .select('claimed_by_cloud_user_id, claimed_by_name, expires_at')
      .eq('phantom_local_user_id', phantomLocalUserId)
      .eq('created_by_cloud_user_id', cloudUserId)
      .single()

    if (selectError || !invite) {
      // No invite found - don't reveal existence to non-owners
      const response: CheckStatusResponse = {
        status: 'NOT_FOUND',
        claimedBy: null
      }
      return new Response(
        JSON.stringify(response),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Check expiry
    if (new Date(invite.expires_at) < new Date()) {
      const response: CheckStatusResponse = {
        status: 'EXPIRED',
        claimedBy: null
      }
      return new Response(
        JSON.stringify(response),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Check if claimed
    if (invite.claimed_by_cloud_user_id) {
      const response: CheckStatusResponse = {
        status: 'CLAIMED',
        claimedBy: {
          cloudUserId: invite.claimed_by_cloud_user_id,
          name: invite.claimed_by_name || 'Unknown'
        }
      }
      return new Response(
        JSON.stringify(response),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      )
    }

    // Pending (not claimed yet)
    const response: CheckStatusResponse = {
      status: 'PENDING',
      claimedBy: null
    }
    return new Response(
      JSON.stringify(response),
      { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )

  } catch (error) {
    console.error('Unexpected error:', error)
    return new Response(
      JSON.stringify({ error: 'Internal server error' }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    )
  }
})
