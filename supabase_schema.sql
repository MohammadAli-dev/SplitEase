-- ==============================================================================
-- SplitEase Supabase Ledger Mirror Schema
-- ==============================================================================
--
-- GUIDING PRINCIPLE: "Dumb Courier"
-- This schema serves purely as a durability and exchange layer.
-- It MUST NOT enforce business logic, conflict resolution, or semantic validation.
-- Local correctness MUST NOT depend on this schema.
--
-- ==============================================================================

-- 1. Create the single ledger table
create table public.ledger_operations (
  -- Primary Key: Opaque Text from client
  -- IMPORTANT: Supabase must NOT enforce UUID format (Client Authority)
  operation_id text not null primary key,
  
  -- Core Identity
  device_id uuid not null, -- Device IDs remain UUIDs as per local schema
  logical_clock bigint not null,
  
  -- Operation Metadata (Text to ensure forward compatibility/generality)
  entity_type text not null,
  entity_id text not null, 
  operation_type text not null,
  
  -- Payload (Wait-free, opaque JSON)
  -- Payload semantics must not rely on key order or formatting.
  -- Replay semantics rely only on parsed content.
  payload jsonb not null,
  
  -- Authorship
  author_local_user_id text not null,
  
  -- Diagnostics (Server-side timestamp)
  -- WARNING: Strictly for debugging / arrival inspection only.
  -- NEVER use for replay ordering or correctness.
  created_at timestamptz not null default now(),
  
  -- Constraints
  -- Ensure strict ordering per device (Idempotency Key)
  constraint ledger_operations_device_clock_key unique (device_id, logical_clock)
);

-- Lock ownership to prevent accidental privilege escalation
alter table public.ledger_operations owner to postgres;

-- 2. Indices for Performance & Diagnostics

-- Explicit Index for Replay Ordering
-- Required for deterministic ordering if ledger operations are ever pulled.
create index idx_ledger_operations_replay_order 
on public.ledger_operations (device_id, logical_clock);

-- Debugging Index (Diagnostics only)
create index idx_ledger_operations_created_at
on public.ledger_operations (created_at);

-- 3. Row Level Security (RLS) & Permissions

-- Enable RLS
alter table public.ledger_operations enable row level security;

-- ALLOW INSERT for authenticated users
create policy "Enable insert for authenticated users only"
on public.ledger_operations
for insert
to authenticated
with check (true);

-- ALLOW SELECT for authenticated users
create policy "Enable select for authenticated users only"
on public.ledger_operations
for select
to authenticated
using (true);

-- EXPLICITLY REVOKE MUTATION CAPABILITIES
-- The ledger is immutable history.

revoke update, delete, truncate on public.ledger_operations from authenticated;
revoke update, delete, truncate on public.ledger_operations from anon;

-- Grant minimal access to authenticated role
grant select, insert on public.ledger_operations to authenticated;

-- ==============================================================================
-- End of Schema
-- ==============================================================================
