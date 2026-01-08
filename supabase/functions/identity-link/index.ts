// Supabase Edge Function: identity-link
// Path: POST /functions/v1/identity-link
//
// Links a local user identity (UUID) with a cloud user identity (Supabase auth.users.id).
// This is called by the mobile app after successful authentication.
//
// Authentication: Requires valid Supabase JWT (Bearer token)
// Idempotency: Safe to call multiple times - existing mappings are preserved
//
// Request Body:
// {
//   "localUserId": "uuid-local-user-id"
// }
//
// Response (Success):
// {
//   "status": "linked"
// }
//
// Error Codes:
// - 400: Missing localUserId in request body
// - 401: Invalid or missing JWT
// - 500: Database error

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

interface LinkIdentityRequest {
  localUserId: string
}

interface LinkIdentityResponse {
  status: string
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
        { 
          status: 401, 
          headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
        }
      )
    }

    // Create Supabase client with user's JWT
    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
      {
        global: {
          headers: { Authorization: authHeader },
        },
      }
    )

    // Verify JWT and get user
    const { data: { user }, error: authError } = await supabaseClient.auth.getUser()
    
    if (authError || !user) {
      console.error('Auth error:', authError?.message)
      return new Response(
        JSON.stringify({ error: 'Invalid or expired token' }),
        { 
          status: 401, 
          headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
        }
      )
    }

    const cloudUserId = user.id

    // Parse request body
    const body: LinkIdentityRequest = await req.json()
    
    if (!body.localUserId || typeof body.localUserId !== 'string') {
      return new Response(
        JSON.stringify({ error: 'Missing or invalid localUserId in request body' }),
        { 
          status: 400, 
          headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
        }
      )
    }

    const localUserId = body.localUserId

    // Use service role client for database operations
    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // Insert identity mapping (idempotent: ON CONFLICT DO NOTHING)
    // The table has a UNIQUE constraint on cloud_user_id
    // created_at uses database DEFAULT NOW()
    const { error: insertError } = await supabaseAdmin
      .from('identity_maps')
      .upsert(
        {
          cloud_user_id: cloudUserId,
          local_user_id: localUserId
        },
        {
          onConflict: 'cloud_user_id',
          ignoreDuplicates: true // Don't update if already exists
        }
      )

    if (insertError) {
      console.error('Database error:', insertError.message)
      return new Response(
        JSON.stringify({ error: 'Database error' }),
        { 
          status: 500, 
          headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
        }
      )
    }

    // Success response
    const response: LinkIdentityResponse = { status: 'linked' }
    
    console.log(`Identity linked: cloud=${cloudUserId}, local=${localUserId}`)
    
    return new Response(
      JSON.stringify(response),
      { 
        status: 200, 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
      }
    )

  } catch (error) {
    console.error('Unexpected error:', error)
    return new Response(
      JSON.stringify({ error: 'Internal server error' }),
      { 
        status: 500, 
        headers: { ...corsHeaders, 'Content-Type': 'application/json' } 
      }
    )
  }
})
