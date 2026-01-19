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
- **Atomic Identity Invalidation**: Updated `TokenManager` to detect `cloudUserId` changes. Upon a user swap, cached `UserProfile` data (name/email) is atomically wiped.
- **Persistence Boundary Guard**: `saveUserProfile` now validates that the incoming profile's ID matches the active session, blocking mismatched identity persistence.- **Atomic Logout Reset**: Verified that `logout()` performs a destructive identity reset, clearing all tokens and the persisted profile cache simultaneously.

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

### 4. CodeRabbit/ChatGPT Safety Audit & Crash-Hardening
Following a comprehensive architectural audit, the promotion and hydration subsystems were further hardened against failure-path inconsistencies and crash windows:
- **Semantic Error Differentiation**: Enhanced `LedgerSetComparator` with a distinct `Error` state to distinguish between transient failures (network/deserialization) and genuine ledger mismatches.
- **Transient-Safe Promotion**: Instrumented `PromotionCoordinator` to treat comparison errors as retryable, preventing transient network glitches from incorrectly triggering terminal `FAILED_PERMANENTLY` transitions.
- **Atomic-Commit Gap Recovery**: Hardened `recoverPromotionIfNeeded` to detect and repair the "zombie" state `(PromotionState.COMPLETED + DeviceRole.REPLICA)` by re-driving the promotion through `promoteToWriter`. This closes the crash window between the two persistent writes.
- **Mutex Lifecycle Protection**: Replaced implicit lock-release logic in `HydrationCoordinator.hydrate()` with explicit lock-state tracking (`val locked = tryLock()`) to prevent `IllegalStateException` during cleanup.
- **Repository Invariant Alignment**: Enforced `canWrite()` guards in `GroupRepository.leaveGroup()` to ensure membership mutations are consistently gated by device role, matching the existing security model for group creation and user removal.
- **Test Integrity**: Renamed test cases in `PromotionCoordinatorTest` to accurately reflect their assertions, ensuring the executable documentation correctly describes the promotion state machine's invariants.
- **Fail-Closed Default Role**: Updated `DeviceRoleManager` to map invalid or corrupted role strings to `REPLICA` instead of `PRIMARY`. This ensures the system fails-closed upon data corruption, preventing unintended privilege escalation.
- **Permission Flow Integrity**: Refactored `GroupRepository` to move write-permission guards outside of broad `try/catch` blocks. This prevents `WritePermissionDeniedException` from being swallowed and misclassified as generic operational errors.
- **Permission Model Documentation**: Corrected misleading comments in `LedgerSyncScheduler` to accurately reflect that `PROMOTED` is a writable role, while only `REPLICA` and `IN_PROGRESS` are blocked from pushing.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and KSP processing.
- **Tests**:
    - **`HydrationCoordinatorTest`**: Updated to verify `PROMOTED` guard and new dependency injection.
    - **Sync Verification**: Verified that `LedgerSyncScheduler` correctly skips pushes on non-writable devices.
- **Integrity**: Confirmed that all mutation-capable repositories now hold the `LedgerWriteGate` and that the database enforces operation uniqueness.

---

*Verified: Sprint 19 Core Integrated.*

---

# Sprint 20: Explicit Conflict Detection & Visibility (No Resolution)

## Overview
This sprint introduces explicit, deterministic conflict detection for the multi-device ledger system. It transitions the system from silent "last-write-wins" outcomes to observable "facts" by detecting where more than one device has mutated a logical entity. In accordance with the sprint objective, no resolution or data mutation is performed; results are surfaced as read-only diagnostic metadata.

## Key Changes

### 1. Domain Models & Taxonomy
- **LedgerConflict**: Introduced the primary domain model for conflicts, containing the `entityId`, `entityType`, and a strictly ordered set of `opRefs` (deviceId, logicalClock).
- **ConflictType**: Categorized conflicts into:
    - `POST_DELETE_MUTATION`: Multi-device mutation where at least one is a DELETE and one is Non-DELETE (including CREATE).
    - `HARD_DELETE_CLASH`: More than one device deleted the same entity.
    - `MULTIPLE_WRITERS`: Standard fallback for multi-device updates.
- **Structural Integrity**: Encapsulated the input contract using a `LedgerPrefix` value type, ensuring it can only be constructed by the `ReplayEngine` after proven convergence.

### 2. Deterministic Identity & Fingerprinting
- **Canonical Fingerprint**: Implemented `conflictId` generation using `SHA-256` hashing over a stable delimiter-defined string: `entityType.name|entityId|sorted(deviceId:logicalClock,...)`.
- **Identity Invariant**: Any change in the involved operations produces a new `conflictId`, ensuring the history is strictly append-only and audit-safe.
- **Payload Immutability**: Enforced the contract that for a given `conflictId`, the payload must be byte-for-byte identical across all devices and executions.

### 3. Detection Engine
- **O(N) Complexity**: Specifically implemented a single-pass grouping and detection strategy to maintain performance as the ledger grows.
- **Output Stability**: Enforced lexicographical sorting of results by `(entityType.name, entityId, conflictId)` for cross-device list consistency.
- **Fail-Open Integration**: Hooked `ConflictDetector` into `ReplayEngine.replay()` with `runCatching` semantics, ensuring that detection failures logged as warnings never block financial replay or sync.

### 4. Data Layer & Persistence
- **Room Schema (v13)**: Introduced the `ledger_conflicts` table, keyed by the deterministic `conflictId`.
- **Strict Locality**: Committed to the invariant that conflicts are device-local and are never uploaded, mirrored, or referenced in remote systems.
- **Mapping & Visibility**: Implemented `ConflictMapper` for lossless persistence and `ConflictRepository` for read-only visibility surface.

### 5. Conflict Hardening & Audit (CodeRabbit Review)
Following an architectural audit, the conflict system was hardened against edge-case failures and logical collisions:
- **Collision-Resistant Fingerprinting**: Refactored `conflictId` generation in `ConflictDetector` to use **Length-Prefixing** (`len:value`) for all string components. This provides a mathematical guarantee against delimiter collisions (e.g., if an ID or device name contains `|` or `:`).
- **Graceful Deserialization Fail-Open**: Hardened `ConflictMapper.fromEntity` to treat persisted JSON as untrusted input. Implemented a `try-catch` boundary around `gson.fromJson` and added explicit recovery to an empty `opRefs` list. This ensures that corrupted diagnostic rows never crash the application or block repository flows.
- **Exhaustive Diagnostic Visibility**: Added explicit `Log.w` warnings to `ConflictMapper` for unknown `entityType` or `conflictType` strings. Previously swallowed silently, these failures are now logged with the `conflictId` to ensure visibility of cross-version ledger mismatches.
- **Fail-Open Logging**: Standardized all internal error reporting via `Log.w` for non-authoritative diagnostic failures, adhering to the sprint's "non-blocking visibility" mantra.
- **Architectural Alignment**: Resolved documentation-implementation contradictions regarding Supabase branding vs. Mock implementation status in the `README.md` diagrams and terminology.

## Verification Results
- **Comprehensive Unit Tests**:
    - Verified single-device exclusions (no conflict emitted).
    - Verified all 3 classification types.
    - Verified SHA-256 fingerprint stability across reordered inputs.
    - **Collision Regression**: Verified that ID overlaps (e.g., `123|A` vs `123`) produce unique fingerprints via length-prefixing.
    - Verified lexicographical output sorting for UI/audit stability.
    - Verified pre-detection deduplication of duplicate ledger ops.
- **Structural Integrity**: Verified `LedgerPrefix` encapsulation via internal private construction.
- **Performance**: Verified linear $O(N)$ execution path.


**Sprint 20 Status: Core Detection Integrated & Verified.**

---

# Sprint 21: Explicit Conflict Resolution (Suppressed History) & Cloud Mirroring

## Overview
This sprint implements the final layer of the conflict management system: explicit, user-driven resolution, followed by the initial deployment of the Supabase Ledger Mirror. The system now supports appending resolution "facts" to suppress conflicting operations during state derivation, ensuring deterministic convergence across devices. Additionally, a post-sprint grounding audit was performed to solidify the "Dumb Courier" architecture before enabling the Supabase push pipeline.

## Key Changes

### 1. Ledger & Operation Design
- **RESOLVE_CONFLICT Operation**: Introduced a new ledger operation type containing a `ConflictResolutionPayload`.
- **ResolutionPayload**: Encapsulates the `conflictId`, `resolutionType` (e.g. `KEEP_OPERATION`), and the specific `chosenOpRef` (deviceId:logicalClock).
- **Immutability**: Resolution operations are append-only. Once a resolution is accepted by the ledger, it becomes a permanent part of the causal history.

### 2. Write Path & Preconditions
- **ResolutionUseCase**: A strict service for creating resolution operations. It enforces the following invariants:
    - **Conflict Existence**: The `conflictId` must refer to a conflict already detected and stored locally.
    - **Set Membership**: The `chosenOpRef` must be one of the participants in the specified conflict.
    - **Historical Validity**: The `chosenOpRef` must refer to a ledger operation that has already been successfully replayed/hydrated.
    - **Write Authority**: Only `PRIMARY` or `PROMOTED` devices can append resolutions.
    - **Unresolved State**: Prevents duplicate resolution attempts for the same conflict.

### 3. Replay & Derivation (Effective State Projection)
- **Strict Execution Replay**: Refactored `ReplayEngine` to unconditionally apply all ledger operations. Removed pre-flight filtering to ensure the "Local State" remains a perfect mirror of applied history.
- **Post-Replay Conflict Detection**: Detection logic specifically transitioned to run *after* history has been fully applied.
- **Repository-Level Derivation**: Moved operation suppression to the read-path. Repositories (e.g., `ExpenseRepository`) now join conflict and resolution metadata to project the "Effective State".
- **Friend Ledger Alignment**: Updated `FriendTransactionsRepository` to use the filtered expense stream, ensuring "Zombie" entities are hidden from ledger views.
- **Zombie Suppression**: Implemented strict rules to hide "Zombie" entities (e.g., updates to a deleted entity) and ensure "lose" operations are invisible to the UI while remaining in the database for audit integrity.
- **Deterministic Convergence**: Ensures that regardless of the order in which resolution operations and conflicting data arrive, the projected state is identical across all devices.

### 4. Data Layer & DI
- **Room Schema (v14)**: Introduced the `conflict_resolutions` table.
- **Derived State Invariant**: The resolution table is strictly derived from the ledger. It is populated ONLY during replay via `INSERT OR IGNORE` (First-Resolution-Wins).
- **Reactive Derivation**: Added `observeAllResolutions()` Flow to `ConflictResolutionDao` to allow the Repository layer to reactively re-project state when resolutions arrive.
- **Referential Integrity**: Added `getOperationType` to `LedgerDao` to support derivation-time verification of resolution effects.
- **DI Provisioning**: Updated `DatabaseModule` to provide `LedgerConflictDao` and `ConflictResolutionDao`, resolving dependency graph errors.

### 5. Grounding Audit & System Verification
- **System Check Conducted**: Completed a comprehensive post-Sprint-21 audit to formalize "Ground Truth" before Supabase integration.
- **Invariant Freeze**: Hardened and verified the following invariants:
    - **Room as SSOT**: UI never observes network directly; always derives from local Room ledger.
    - **Ledger as Append-Only**: Immutable history of facts.
    - **Replay as Truth**: Unconditional, deterministic replay logic.
    - **Dumb Courier Principle**: Supabase holds facts but never meaning or authority.

### 6. Supabase Ledger Mirror (Phase 1: Push)
- **Schema Deployment**: Implemented the `ledger_operations` table in Supabase.
    - **Cloud Parity**: Matches local schema byte-for-byte; uses `TEXT` for `operation_id` and `entity_id` to ensure opaque format safety.
    - **Explicit Indexing**: Added `idx_ledger_operations_replay_order` on `(device_id, logical_clock)` for performant sync.
- **Access Control (RLS)**: Enforced strict append-only security:
    - Authenticated users can `INSERT` and `SELECT`.
    - `UPDATE`, `DELETE`, and `TRUNCATE` are explicitly revoked.
- **Client Infrastructure Polish**:
    - **Hilt/WorkManager Fix**: Resolved `NoSuchMethodException` crash by disabling default WorkManager initialization in `AndroidManifest.xml`, enabling customized `HiltWorkerFactory` injection.
    - **Network Alignment**: Corrected `NetworkModule` to use production Supabase Base URLs and updated `SplitEaseApi` to use `/rest/v1/` prefixes for PostgREST compatibility.
    - **Detailed Diagnostics**: Enhanced `NetworkResultMapper` to log full stack traces for `IOException`, accelerating network debugging.
- **End-to-End Verification**: Confirmed that local writes (Expenses/Groups) are successfully mirrored to the cloud `ledger_operations` table.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and KSP processing.
- **Unit Tests**:
    - Verified `ResolutionUseCase` precondition logic (Role-gating, Read-only guards).
    - Verified `ReplayEngine` strict execution: conflicting operations are applied and detected post-replay.
    - Verified `ExpenseRepositoryDerivationTest`: Confirmed that Zombies are hidden and resolved wins are shown correctly.
- **Manual Verification**: Verified live row ingestion in Supabase Table Editor.
- **Operational Safety**: Confirmed that `LedgerPushWorker` handles network failures gracefully using exponential backoff.

---

**Sprint 21 Status: Grounding, Resolution, and Cloud Mirror (Push) Complete.**

---

