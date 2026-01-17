# Sprint 17: Supabase Ledger Mirror (Push-Only)

## Overview
This sprint implements a Supabase-backed, append-only mirror of the local `LedgerOperation` system. This provides a durable cloud persistence layer for the ledger without changing the application's offline-first behavior or making Supabase an authoritative source of truth.

## Key Changes

### 1. Supabase Infrastructure
- **Schema Design**: Created `ledger_operations` table in Supabase mirroring the local schema, including `operation_id` and `entity_id` as UUIDs.
- **Security**: Implemented Row Level Security (RLS) policies allowing only authenticated inserts (write-only for the client).
- **API Integration**: Added `POST /ledger_operations` to `SplitEaseApi` with `Prefer: resolution=ignore-duplicates` for idempotent batch inserts.

### 2. Synchronization Layer
- **LedgerPushWorker**: A WorkManager `CoroutineWorker` that fetches pending local ledger operations and pushes them to Supabase.
- **Batching & Idempotency**: Supports batching (50 ops/batch) and uses the `operation_id` as a deduplication key on the server.
- **Incremental Progress**: Uses `LedgerSyncStore` (DataStore) to track the `lastPushedClock` cursor, ensuring only new operations are pushed.
- **LedgerSyncScheduler**: A singleton helper to abstract WorkManager triggering, ensuring a push is enqueued after every local modification.

### 3. Core Ledger Fixes & Enhancements
- **Atomic Clock Allocation**: Fixed a transactional livelock bug in `AppDatabase.commitLedgerOp`. Monotonic logical clocks are now allocated using an atomic SQL subquery (`INSERT ... SELECT COALESCE(MAX(logicalClock), 0) + 1`), preventing race conditions under SQLite's snapshot isolation.
- **Currency Support**: Added `currency` field to `Settlement` entity and `SettlementSnapshot` to preserve ledger integrity across different group contexts.
- **Deterministic Snapshots**: Refactored `Expense` and `Settlement` snapshots to use canonical `String` representations for `BigDecimal` amounts, ensuring deterministic serialization and hash stability.

### 4. Repository Integration
- Instrumented `ExpenseRepository`, `GroupRepository`, and `SettlementRepository` to automatically trigger the `LedgerSyncScheduler` after successful database transactions.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and KSP processing.
- **Tests**: Standard unit tests passed. Verification walkthrough provided for manual Supabase population testing.
- **Architectural Adherence**: Room remains the single source of truth; no reads from Supabase were implemented.

# Sprint 18: Read-Only Pull & Deterministic Hydration

## Overview
This sprint enables a read-only device to pull ledger operations from Supabase and deterministically hydrate its local Room database. This creates a durable foundation for multi-device visibility, allowing a user to see their ledger state on a second device without enabling multi-device mutation or conflict resolution.

## Key Changes

### 1. Hydration Infrastructure
- **LedgerPullService**: Implemented a service for fetching ledger operations from Supabase via `SplitEaseApi.getLedgerOperations`. It enforces **authoritative local sorting** by `(deviceId, logicalClock)` to ensure deterministic replay, treating Supabase's ordering as a bandwidth hint only.
- **ReplayEngine**: Developed a **convergence-based** replay algorithm that retries operations with missing dependencies (e.g., an update waiting for a create) until all operations are applied or no further progress is made.
- **HydrationCoordinator**: Orchestrates the hydration flow including a **fresh-install guard** (aborts if any financial data exists locally), operation pull, deterministic replay, and final read-only lock.

### 2. Read-Only Mode Enforcement
- **ReadOnlyModeManager**: Created a persistent DataStore-backed flag to track if a device is in read-only mode. Once hydration completes, the device enters a permanent read-only state.
- **ReadOnlyViolationException**: Introduced a custom domain exception thrown whenever a mutation is attempted in read-only mode.
- **Repository Guards**: Instrumented `ExpenseRepository`, `GroupRepository`, and `SettlementRepository` to throw `ReadOnlyViolationException` for all mutation methods (`add`, `update`, `delete`, `removeMember`).
- **LedgerSyncScheduler**: Modified the scheduler to silently skip push synchronization requests if the device is in read-only mode, preventing hydrated devices from attempting to modify the cloud ledger.

### 3. Data Layer Enhancements
- **API Extension**: Added the `RemoteLedgerOperation` DTO and the `GET /ledger_operations` endpoint to `SplitEaseApi`.
- **DAO Count Methods**: Extended `ExpenseDao`, `GroupDao`, `SettlementDao`, and `LedgerDao` with synchronous count methods used by the `HydrationCoordinator` for the fresh-install validation.
- **Deterministic Replay Support**: All entity types (Expense, Group, Settlement, Member) are now supported for deterministic reconstruction from ledger snippets via the `ReplayEngine`.

### 4. Architectural Guardrails
- **Fail-Fast Hydration**: Hydration is designed as a single-pass, all-or-nothing process. If any ledger operation cannot be replayed after convergence, hydration fails explicitly to prevent silent state divergence.
- **Crash Safety by Idempotency**: Removed the need for complex checkpointing; on crash, hydration restarts from zero, relying on the idempotent nature of the `ReplayEngine` (using Room's `REPLACE` strategy) for safety.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and Hilt dependency injection processing.
- **Integrity**: Verified that all mutation paths in repositories are correctly guarded by the `ReadOnlyModeManager`.
- **Infrastructure**: All hydration components (PullService, ReplayEngine, Coordinator) are integrated into the Dagger-Hilt graph via `HydrationModule`.
