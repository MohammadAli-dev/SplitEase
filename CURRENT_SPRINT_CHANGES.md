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

---

# Sprint 18: Read-Only Pull & Deterministic Hydration

## Overview
This sprint enables a read-only device to pull ledger operations from Supabase and deterministically hydrate its local Room database. This creates a durable foundation for multi-device visibility, allowing a user to see their ledger state on a second device without enabling multi-device mutation or conflict resolution.

## Key Changes

### 1. Hydration Infrastructure
- **LedgerPullService**: Fetches ledger operations from Supabase. It enforces **authoritative local sorting** by `(deviceId, logicalClock)` to ensure deterministic replay, treating Supabase's ordering as a bandwidth hint only.
- **ReplayEngine**: Developed a **convergence-based** replay algorithm that retries operations with missing dependencies until all operations are applied or no further progress is made.
- **HydrationCoordinator**: Orchestrates the hydration flow including a **fresh-install guard**, operation pull, and final read-only lock. It uses a **Fast-Fail Admission** strategy (Mutex) to prevent concurrent hydration attempts.

### 2. Security & Token Management
- **JWT Authorization**: Fixed a critical security vulnerability in `LedgerPullService`. Switched from the Supabase public "anon" key to the user's real JWT access token retrieved via `TokenManager`.
- **Identity Integrity Guard**: Implemented a "Zombie Session" guard that fails hydration if the user is logically authenticated but the access token is missing from secure storage.
- **Deterministic Auth Initialization**: Refactored `AuthManager` to remove non-deterministic background initialization. Introduced an explicit `suspend fun initialize()` which is now called by the `AppStartupInitializer` during bootstrap. This ensures session recovery and token refreshes are completed BEFORE the app's sync or identity flows begin, eliminating race conditions.

### 3. Strict Hydration Enforcement (CodeRabbit Refinements)
- **Fail-Fast for Malformed Data**: Replaced deterministic sentinels (`0L`) with strict invariant enforcement. `LedgerPullService` now throws `HydrationInvariantException` if `createdAt` is missing. `ReplayEngine` throws if `joinedAt` is missing during member creation.
- **No Partial State**: Hydration fails atomically if ANY invariant is violated. No partial ledger or member state is committed.
- **Observable Failures**: Introduced `HydrationFailureReport` (with type-safe enums for `HydrationInvariant`, `HydrationInvariantCategory`, and `HydrationFailureLocation`) to capture the "what," "where," and "why" of failures for observability and future UX.
- **Structured Logging**: Added production-ready structured logging at the `HydrationCoordinator` boundary for all invariant violations.
- **Ledger Integrity Protection**: Updated `persistLedgerOperation` to distinguish between benign `SQLiteConstraintException` (idempotency during resumption) and fatal system failures (e.g., Disk Full), which are now logged and rethrown to trigger clean hydration failure.
- **Global Error Boundaries**: Hardened `HydrationCoordinator` by wrapping both `hydrate()` and `remediateInconsistency()` in global try-catch blocks. This ensures that unexpected IO or DataStore failures result in a descriptive `Failed` result rather than an application crash.
- **Enhanced Inconsistency Tracking**: Expanded the `InconsistencyStatus` sealed interface to include a `Failed` case, enabling robust error reporting during critical app startup remediation cycles.

### 4. Read-Only Mode Enforcement
- **ReadOnlyModeManager**: Created a persistent DataStore-backed flag. Once a device hydrates, it enters a permanent read-only state.
- **Mutation Guards**: Instrumented all repositories (`Expense`, `Group`, `Settlement`) to throw `ReadOnlyViolationException` for all mutation methods when in read-only mode.

### 5. Architectural Guardrails
- **Inconsistency Management**: Introduced `InconsistencyStatus` as a sealed interface to provide a single canonical representation of hydration failure states.
- **Dispatcher Injection**: Standardized the use of injected `@IoDispatcher` across all hydration components to ensure testability and correct threading.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and Hilt/KSP processing.
- **Tests**:
    - **`HydrationCoordinatorTest`**: Verified fast-fail admission (Mutex) and single-flight hydration logic.
    - **`ReplayEngineTest`**: Verified that ledger insertion correctly handles idempotency (resumption safety) and fatal storage errors.
- **Integrity**: Verified that all mutation paths in repositories are correctly guarded and that hydration pulls are SECURE via user-specific JWTs.

---

*Nothing here blocks or weakens Sprint 18.*
