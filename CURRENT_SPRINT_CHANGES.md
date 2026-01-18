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
- **JWT Authorization**: Fixed a critical security vulnerability in `LedgerPullService`. Switched from the Supabase public "anon" key to the user's real JWT access token.
- **Identity Integrity Guard**: Implemented a "Zombie Session" guard that fails hydration if the user is logically authenticated but the access token is missing.
- **Bootstrapped Identity Restoration**: `AuthManager` now persists and restores the `UserProfile` from secure storage during cold start. This ensures the UI has immediate access to identity data before the authoritative network refresh completes.
- **Deterministic Auth Initialization**: Refactored `AuthManager` to remove non-deterministic background initialization. Introduced an explicit `suspend fun initialize()` called by `AppStartupInitializer` to ensure session recovery is complete before sync or identity flows begin.

### 3. Verification & Hardening (CodeRabbit/ChatGPT Audit)
Following a comprehensive architectural audit, the hydration and auth systems were hardened against subtle race conditions and state inconsistencies:
- **Identity Restoration Invariant**: Flagged that `Authenticated` state must imply identity-complete. Implemented `UserProfile` persistence and restoration during bootstrap.
- **Fail-Fast for Malformed Data**: Replaced deterministic sentinels (`0L`) with strict invariant enforcement (`HydrationInvariantException`).
- **Idempotent Storage Protection**: Updated `persistLedgerOperation` to distinguish between benign constraint violations (during retry) and fatal system failures.
- **Serialized Remediation**: `HydrationCoordinator.remediateInconsistency` is now protected by the global `actionMutex` to prevent racing with active replays.
- **Crash-Resumable Remediation**: Introduced a transient `remediationInProgress` flag to protect the multi-step DB wipe sequence, ensuring any crash during cleanup triggers a forced retry on next boot.
- **Structured Observability**: Added structured logging for the full remediation lifecycle and typed failure reports.

### 4. Identity Integrity & Leakage Protection
To prevent cross-user data contamination and stale identity visibility:
- **Atomic Identity Invalidation**: Updated `TokenManager` to detect `cloudUserId` changes. Upon a user swap, cached `UserProfile` data (name/email) is atomically wiped.- **Persistence Boundary Guard**: `saveUserProfile` now validates that the incoming profile's ID matches the active session, blocking mismatched identity persistence.
- **Atomic Logout Reset**: Verified that `logout()` performs a destructive identity reset, clearing all tokens and the persisted profile cache simultaneously.

### 5. Read-Only Mode Enforcement
- **ReadOnlyModeManager**: Created a persistent DataStore-backed flag. Once a device hydrates, it enters a permanent read-only state.
- **Mutation Guards**: Instrumented all repositories (`Expense`, `Group`, `Settlement`) to throw `ReadOnlyViolationException` for all mutation methods when in read-only mode.

### 6. Architectural Guardrails
- **Inconsistency Management**: Introduced `InconsistencyStatus` as a sealed interface to provide a single canonical representation of hydration failure states.
- **Dispatcher Injection**: Standardized the use of injected `@IoDispatcher` across all hydration components to ensure testability and correct threading.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and Hilt/KSP processing.
- **Tests**:
    - **`HydrationCoordinatorTest`**: Verified fast-fail admission (Mutex) and single-flight hydration logic.
    - **`ReplayEngineTest`**: Verified that ledger insertion correctly handles idempotency (resumption safety) and fatal storage errors.
- **Integrity**: Verified that all mutation paths in repositories are correctly guarded and that hydration pulls are SECURE via user-specific JWTs.

---

# Sprint 19: Multi-Device Write Promotion & Write Serialization

## Overview
This sprint implements explicit, crash-safe device role promotion and enforces strict per-device write serialization. This allows read-only replicas to become authoritative authors while preserving ledger integrity and preventing cross-device ordering conflicts.

## Key Changes

### 1. Promotion Infrastructure & Lifecycle
- **PromotionCoordinator**: Implemented a crash-safe state machine for transitioning devices from `REPLICA` to `PROMOTED`.
- **Startup Recovery**: Integrated `recoverPromotionIfNeeded()` into the startup flow to automatically resume interrupted promotions after a crash.
- **Safety Guards**: Implemented strict precondition checks (ledger set equality) and a final guard in `HydrationCoordinator` to prevent promoted authors from accidentally wiping their data via hydration.

### 2. Write Serialization & Hardening
- **LedgerWriteGate**: Introduced a canonical mutex-backed gate for all ledger mutations. This ensures that clock allocation, database insertion, and sync scheduling are strictly serialized per device.
- **Repository Enforcement**: Instrumented `ExpenseRepository`, `GroupRepository`, and `SettlementRepository` to hold the write lock during the entire mutation lifecycle.
- **Database Constraints**: Hardened the local schema with a composite unique index on `(deviceId, logicalClock)` to provide a final hardware-level safety net against ordering corruption.

### 3. Foundation Migration
- **DeviceRoleManager**: Retired the legacy `ReadOnlyModeManager` and migrated all permission logic to the unified `DeviceRoleManager`.
- **System-Wide Alignment**: Updated `LedgerOperationFactory`, `LedgerSyncScheduler`, and the Hydration system to observe the new role-based permission model.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and KSP processing.
- **Tests**:
    - **`HydrationCoordinatorTest`**: Updated to verify `PROMOTED` guard and new dependency injection.
    - **Sync Verification**: Verified that `LedgerSyncScheduler` correctly skips pushes on non-writable devices.
- **Integrity**: Confirmed that all mutation-capable repositories now hold the `LedgerWriteGate` and that the database enforces operation uniqueness.

---

*Verified: Sprint 19 Core Integrated.*
