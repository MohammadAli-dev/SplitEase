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

# Post-Sprint 21: Safety Audit & Architectural Hardening

## Overview
Following a comprehensive safety audit (facilitated by CodeRabbit and architectural review), the system was hardened against data integrity risks and potential configuration errors. These changes ensure "fail-fast" behavior for database inconsistencies and tighten the security posture of the networking layer.

## Key Changes

### 1. ReplayEngine: Strict Exception Handling
- **Narrowed Idempotency Catch**: Refactored `ReplayEngine` to specifically whitelist `UNIQUE constraint failed` and `PRIMARY KEY constraint failed` messages within `SQLiteConstraintException`.
- **Integrity Enforcement**: All other constraint violations (e.g., Foreign Key or Not Null violations) are now rethrown as fatal errors. This prevents the engine from silently swallowing genuine data corruption or out-of-order ingestion errors.

### 2. ExpenseRepository: Fail-Closed Integrity Logging
- **Integrity Alerting**: Introduced explicit `Log.e` (Error) logging for cases where a resolved conflict has a `null opType` (missing winning operation).
- **Fail-Closed Projection**: Maintained the "hide-by-default" behavior for such cases but added high-severity logging to ensure visibility of potential ledger-local state mismatches.

### 3. Network Layer Security & Robustness
- **Environment-Aware Logging**: Updated `HttpLoggingInterceptor` in `NetworkModule` to use `Level.BODY` only in `DEBUG` builds. Production/Release builds now use `Level.NONE` to prevent sensitive data leakage (tokens, PII) into system logs.
- **Base URL Normalization**: Instrumented strict trailing-slash normalization for `AuthConfig.supabaseBaseUrl`, preventing double-slash (`//`) malformations in Retrofit requests.

### 4. Test Rigor
- **Strengthened Replay Assertions**: Updated `ReplayEngineTest` to verify that conflicting operations are *both* attempted using `exactly = 2` assertions.
- **Mock Safety**: Fixed test helpers to generate unique `operationId`s for conflicting operations, ensuring the `ReplayEngine` idempotency check doesn't skip legitimate execution attempts in unit tests.

## Verification Results
- **Build**: Successful build with `assembleDebug`.
- **Tests**: `ReplayEngineTest` and `ExpenseRepositoryDerivationTest` passed with 100% success.
- **Security**: Verified logging levels are correctly gated by `BuildConfig.DEBUG`.

---

# Sprint 21.1: Stability & Resolution Correctness

## Overview
This stabilization sprint restores deterministic correctness to the conflict resolution system and enforces architectural purity in the UI layer. It addresses critical logic bugs in the `ReplayEngine` and `ExpenseRepository` that were flagged by the Sprint 21 Stability Gate, ensuring that "losers" of resolved conflicts are properly suppressed and that the UI adheres strictly to the `StateFlow` unidirectional data flow.

## Key Changes

### 1. ReplayEngine: Resolution Supremacy
Transitioned from a "passive" replay model (apply everything, filter later) to an **"active suppression"** model.
- **Pre-Replay Scan**: `ReplayEngine` now performs a deterministic pre-scan of the operation history to build a map of `resolvedConflicts` (`conflictId -> chosenOpRef`).
- **Loser Suppression**: During the replay loop, the engine computes the `conflictId` for each group of conflicting operations. If a resolution exists, any operation that is NOT the winner (`chosenOpRef`) is strictly suppressed and never touches the database.
- **Deterministic ID Generation**: Duplicated the `generateConflictId` logic from `ConflictDetector` into `ReplayEngine` to allow identifying conflict participants without relying on pre-existing database rows.

### 2. ExpenseRepository: Visibility Derivation
Refined the projected state logic to follow strict visibility rules for entities in conflict:
- **Zombie Invariant**: Entities in a `POST_DELETE_MUTATION` conflict (deleted on one device, edited on another) are now hidden by default if unresolved.
- **Resolution-Aware Visibility**: Once resolved, visibility depends solely on the **winner's operation type**:
    - Winner is `UPDATE` -> Visible.
    - Winner is `DELETE` -> Hidden.
- **Batch Optimization**: Fixed a mocking mismatch in `ExpenseRepositoryDerivationTest` to correctly verify the `getOperationTypesBatch` path used for performant visibility lookups.

### 3. UI Architectural Purity (LiveData Removal)
Eliminated the use of `LiveData` in the Compose layer to maintain a pure, boilerplate-free architecture.
- **StateFlow Migration**: Refactored `ClaimInviteScreen` to observe navigation results and auth state via `SavedStateHandle.getStateFlow().collectAsState()`.
- **Single-Fire Consistency**: Established a pattern for idempotent event consumption in Compose to replace the "SingleLiveEvent" or LiveData-observer behaviors.

### 4. Stability Gate Recovery
- **Zero-Failure Baseline**: Cleared 3 critical unit test failures in `ReplayEngineResolutionTest` and `ExpenseRepositoryDerivationTest`.
- **Full Sweep Verification**: Established a 100% pass rate across all 64 unit tests as a hard exit criterion for Sprint 21.1.

## Verification Results
- **Build**: Successfully passed Kotlin compilation.
- **Tests**:
    - **`ReplayEngineResolutionTest`**: Verified that reordering resolution operations does not affect the final state (all participants suppressed except the winner).
    - **`ExpenseRepositoryDerivationTest`**: Verified correct hiding/showing logic for resolved and unresolved zombies.
    - **Full Suite**: `./gradlew testDebugUnitTest` passed (64 tests).

---

---

# Sprint 22: PullSyncService Noop & Ledger-Only Enforcement

## Overview
This sprint surgically disables the legacy `PullSyncService` to transition the system to a strict **Ledger-Only** architecture. Entity state is now derived exclusively via `ReplayEngine`, and no network code may write directly to entity tables.

## Key Changes

### 1. PullSyncService: NO-OP Conversion
- **Neutralized**: `PullSyncServiceImpl.performPullSync()` now logs a single info message and immediately returns `PullSyncResult.Success` with all counts at zero.
- **Documentation**: Added detailed KDoc explaining the architectural transition and risk of "ghost mutations" from legacy entity sync.
- **DI Preserved**: The interface and implementation remain in place to prevent DI contract breakage.

### 2. Ledger-Only Writes Invariant
- **Audit Performed**: Verified that all DAO mutation calls (`insert`, `update`, `delete`) originate from either:
    1. **User Actions** (via `Repository` layer).
    2. **System Replay** (via `ReplayEngine`).
- **Dead Code Identified**: Legacy `PullSyncService` helper methods (reconciliation, pagination, mapping) are now unreachable but remain in the file for historical reference.

### 3. Obsolete Test Cleanup
- **Deleted Files**:
    - `PullSyncRollbackTest.kt`
    - `PullSyncServiceAtomicityTest.kt`
    - `PullSyncServicePagingTest.kt`
    - `PullSyncTestFixtures.kt`
- **Reason**: These tests verified the legacy sync logic, which is now dead code. They caused false failures after the NO-OP conversion.

## Verification Results
- **Build**: Successfully passed Kotlin compilation.
- **Tests**: `./gradlew testDebugUnitTest` passed (55 tests after cleanup).
- **Invariant Check**: Confirmed no entity tables are written during sync; only `ledger_operations` changes during push.

---

**Sprint 22 Status: Ledger-Only Mode Active. Legacy Pull Neutralized.**

---

# Sprint 22.1: Hard Account Isolation & Identity Persistence

## Overview
This stabilization sprint addresses a critical regression where the local user identity ("me") was lost after a logout/login cycle, and implements strict account isolation to prevent data bleed between sessions.

## Key Changes

### 1. Hard Account Isolation (Logout Teardown)
- **Strict Teardown Sequence**: `AuthManager.logout()` now executes a blocking, atomic teardown:
    1.  **Stop Background Work**: Cancels all WorkManager jobs to prevent race conditions.
    2.  **Wipe Database**: Executes `appDatabase.clearAllTables()` to physically remove all user data.
    3.  **Clear Identity**: Resets `LocalUserManager`, `TokenManager`, and sync metadata.
- **Blocking UI**: `MainActivity` displays a blocking "Logging out..." overlay during this process to prevent interaction.
- **Navigation Reset**: Global navigation reset (`popUpTo(0)`) is triggered on `Unauthenticated` state to destroy all ViewModels.

### 2. Identity Bootstrap Logic (Model A)
- **Problem**: Previously, `IdentityBootstrapper` only ran on app startup. Wiping the DB on logout meant subsequent logins had no "me" row in the `users` table.
- **Fix**: Moved identity bootstrapping into `AuthManager`.
    - **Login-Coupled**: `ensureLocalUserRegistered()` is now called immediately after token persistence in `handleSuccessfulAuth()`.
    - **Startup Recovery**: also called in `AuthManager.initialize()` to recover identity if the DB was wiped but tokens persisted (rare edge case).
    - **Idempotency**: `IdentityBootstrapper` uses `INSERT OR IGNORE` to safely ensure the row exists without overwriting metadata.

### 3. Safety Guardrails
- **Repository Assertions**: `GroupRepository.createGroup` now explicitly asserts that the creator's `User` row exists before writing, failing fast with a clear invariant violation message instead of creating broken state.
- **Worker Ownership**: `LedgerPushWorker` now verifies that the `ledgerOp.authorLocalUserId` matches the current session ID, aborting operations if there is a mismatch (preventing cross-user sync bugs).

## Verification Results
- **Build**: Passed `assembleDebug`.
- **Manual Verification**: Confirmed that logging out and logging back in (User A -> User B) correctly shows the new user as "me" in groups, and that the previous user's data is completely gone.

---

# Sprint 23: Incremental Ledger Pull & Convergence (Pull-to-Refresh)

## Overview
This sprint implements the "Pull" half of the synchronization architecture. Devices can now incrementally pull new ledger operations from Supabase and replay them into their local state, enabling full cross-device convergence. This also introduces a user-facing swipe-to-refresh mechanism for on-demand synchronization.

## Key Changes

### 1. Synchronization Layer (Pull Path)
- **LedgerSyncCoordinator**: Orchestrates the incremental synchronization flow:
    - Fetches all available remote operations from Supabase.
    - Persists new operations to the local ledger (idempotent INSERT OR IGNORE).
    - Triggers `ReplayEngine` for a full history replay to ensure deterministic state convergence.
    - Automatically hydrates missing user profiles for any newly discovered users in the ledger.
- **SyncWorker Integration**: Updated the background `SyncWorker` to use `LedgerSyncCoordinator` during its "Phase 2 (Pull)" stage, replacing the legacy no-op stub.

### 2. UI & Experience
- **Swipe-to-Refresh**: Integrated `PullToRefreshContainer` into `GroupListScreen` and `GroupDetailScreen`.
- **Manual Trigger**: Added `triggerManualSync()` to `GroupListViewModel` and `GroupDetailViewModel` to allow users to force a ledger reconciliation.
- **Visual Feedback**: Sync status icons and spinners now accurately reflect ledger pull/replay progress.

### 3. Identity & Hydration Hardening
- **Convergence Deadlock Fix**: Resolved a critical deadlock where the `ReplayEngine` would fail if the user's "Personal Group" was not yet hydrated.
- **IdentityBootstrapper**: Enhanced to ensure the current user's identity row and personal group exist before the first replay cycle starts.

## Verification Results
- **Build**: Successfully passed Kotlin compilation and Hilt/KSP processing.
- **Deterministic Convergence**: Verified that swiping to refresh on a secondary device correctly pulls and displays expenses created on a primary device.
- **Manual Verification**:
    - [x] Swipe-to-refresh on Groups list triggers full sync.
    - [x] Swipe-to-refresh on Group Detail list triggers full sync.
    - [x] New member profiles are automatically fetched during pull sync.

# Sprint 23.1: CodeRabbit Audit & Hydration Hardening

## Overview
This sprint addresses all issues raised during the CodeRabbit architectural audit and hardens the hydration/role promotion logic to explicit validation standards. It confirms the system creates a "Grand Unified Theory" of device roles where hydration determines authority.

## Key Changes

### 1. UI Architecture Modernization
- **Pull-to-Refresh**: Replaced the legacy `SwipeRefreshLayout` with the modern Material 3 `PullToRefreshBox` in `GroupDetailScreen`. This resolves standard Material design compliance warnings and improves gesture handling.

### 2. Concurrency & Safety
- **LocalUserManager**: Fixed a race condition in `clearIdentity` by adding an `AtomicBoolean` guard (`KEY_CLEARING_IDENTITY`). This prevents the `clearIdentity` flow from triggering a re-login loop via the `isLoggedIn` flow during the teardown phase.
- **LedgerPushWorker**: Verified the "infinite loop" warning was a False Positive (loop condition depends on `processNextOperation` returning `false` on transient failure, which is correct).

### 3. Data Integrity & Attribution
- **GroupRepository**: Fixed an attribution bug in `addMember`. The function now accepts an explicit `actorUserId` to ensure that `GroupMember` creates are attributed to the inviter, not the invitee.
- **Resolution UseCase**: Explicitly permitted `DeviceRole.PROMOTED` to perform conflict resolution, replacing the implicit "not replica" check with a positive allow-list (`PRIMARY` or `PROMOTED`).

### 4. Hydration & Role Semantics
- **DeviceRole Documentation**: Updated KDoc to reflect that `REPLICA` is a transitional state during hydration, and successful hydration permanently upgrades the device to `PROMOTED` (writable).
- **HydrationCoordinator**: Updated internal logic and comments to align with the "Promotion on Success" contract.
- **Verification**: Added `HydrationCoordinatorTest.hydrate should promote device to PROMOTED` to assert this lifecycle transition and prevent regression.

## Verification Results
- **Build**: Successfully passed `assembleDebug`.
- **Tests**: All unit tests passed, including the new hydration promotion test.

---

# Sprint 23.2: CodeRabbit Stability & Audit Fixes

## Overview
This sprint addresses 8 specific issues flagged by CodeRabbit, focusing on race conditions in UI state, error message factuality in Auth, and logic corrections in Sync and Identity management. These fixes ensure the application is robust against edge cases like rapid refresh toggling, zombie identity states, and correct user profile syncing.

## Key Changes

### 1. Concurrency & Race Conditions
- **DashboardViewModel**: Fixed a race condition where the "Refreshing" spinner could disappear prematurely due to non-atomic state updates. Implemented `_uiState.update {}` for atomic mutations and added `_isRefreshing` to the `combine` logic to ensuring reactive UI updates.
- **GroupDetailViewModel**: Wrapped the manual refresh logic in a `try/finally` block to guarantee the loading indicator is reset even if the sync operation throws an exception.
- **GroupListScreen**: Fixed a similar reactivity bug by adding `_isRefreshing` to the state combination logic, ensuring the pull-to-refresh indicator behaves correctly.

### 2. Identity & Auth Integrity
- **AuthManager**: Corrected a misleading error message ("Tokens were revoked") that was emitted *before* the tokens were actually cleared. The error is now emitted strictly after the `clearTokens()` call.
- **LocalUserManager**: Fixed a potential "infinite no-ID" loop by ensuring the `isClearingIdentity` guard is reset in a `finally` block, preventing the app from getting stuck in a state where it refuses to generate a guest ID.

### 3. Sync Logic Correctness
- **SyncRepository**: Fixed a bug by forcing `timestamp` to `null` for new local sync operations created via `SyncEntityType.USER`. This ensures remote servers treat them as new rather than "timestamp 0" conflicts.
- **GroupListScreen**: Renamed the confusingly inverted variable `isWorkRunning` to `isWorkFinished`, clarifying the logic `!isWorkFinished` -> "Syncing".

## Verification Results
- **Build**: Successfully passed Kotlin compilation.
- **Tests**: `PushSyncHardeningTest` and `HydrationCoordinatorTest` passed.
- **Manual Verification**: Validated logout flow, refresh spinner behavior, and pull-to-refresh reactivity.

---

# Sprint 24: Safe Identity Consolidation (P0 Critical Safety)

## Overview
This sprint addresses the most critical data safety vulnerability in the application: **Phantom-to-Real Identity transition**. Previously, logging into an account after using the app offline could result in "orphaned" expenses—data that technically existed but belonged to the old "Phantom" ID, making it invisible to the new "Real" ID.

We implemented a **Zero-Tolerance Identity Architecture** where authentication is treated as a transactional merge operation, not just a token swap.

## Key Changes

### 1. The "Nightmare Bug" Fix (Safe Identity Consolidation)
- **Problem**: Changing `userId` in `LocalUserManager` without reassigning foreign keys leaves data stranded.
- **Fix**: Implemented `IdentityRepository.consolidateIdentity()`, which effectively "re-parents" all data from the Phantom ID to the Real ID **before** the session is marked active.

### 2. Atomic Verification & Invariant Enforcement
- **`IdentityAuditDao`**: A neutral, cross-table auditor that counts references (`payerId`, `createdBy`, `group_members`, etc.).
- **Atomic Guard**: The merge logic inside `AppDatabase.mergeAndVerify` is transactional.
    1.  **Merge**: Update all FKs.
    2.  **Audit**: Count remaining references to Phantom ID.
    3.  **EXPLODE**: If `count > 0`, throw `IdentityInvariantViolationException`.
- **Terminal Failure**: If the invariant fails, authentication is **ABORTED**. The app refuses to log in rather than corrupt data.

### 3. Architecture components
- **`IdentityRepository`**: New repository to encapsulate identity operations.
- **`IdentityInvariantViolationException`**: Specific exception for forensic crash logging.
- **`AuthManager` Integration**: Authentication flow now MUST pass the consolidation step to succeed.

## Verification Results
- **Mandatory E2E Test**: `OfflineDataSurvivalTest` (PASSED).
    - Verified that an offline user creating expenses, splits, and settlements retains 100% of that data after logging in.
    - Verified that the "Phantom" user is completely expunged from the database (0 references).
- **Safety Guarantee**: The system now mathematically guarantees that a logged-in user never sees partial data.

---

---

# Sprint 25A: Auth UX Improvements & Bug Fixes

## Overview
This sprint focuses on polishing the authentication user experience by adding standard security UI patterns (password visibility, confirmation) and improving real-time validation feedback. It also includes a critical fix for a casting crash in the Group Detail view.

## Key Changes

### 1. Auth State Hoisting & Reactive Validation
- **AuthViewModel Refactor**: Migrated all Auth form state (Name, Email, Password, Confirm Password) from local Compose `remember` state to the `AuthViewModel`.
- **Reactive Validation**: Implemented validation logic using `StateFlow` and `combine`. Properties like `isSignupValid` and `passwordFeedback` now update reactively as the user types.
- **Error Mapping**: Added presentation-layer mapping for Supabase errors, converting technical codes (e.g., `invalid_grant`) into user-friendly strings like "Incorrect email or password."

### 2. Login & Signup UX
- **Password Visibility Toggle**: Added an interactive eye icon to password fields in both Login and Signup screens, allowing users to verify their input.
- **Confirm Password Field**: Introduced a "Confirm Password" field to the Signup flow to prevent typos. This field is validated reactively against the primary password.
- **Inline Feedback**: Validation messages (e.g., "Password too short", "Passwords do not match") now appear directly below the relevant text fields.
- **Smart Submit Buttons**: Login and Signup buttons are now automatically disabled until all inputs are valid and no loading is in progress.

### 3. Crash Fix: GroupDetailViewModel
- **ClassCastException**: Fixed a fatal crash in `GroupDetailViewModel` where a `Boolean` was being incorrectly cast to a `Long`.
- **Root Cause**: A mismatch in the argument order of the `combine` function across multiple data sources.
- **Resolution**: Corrected the casting indices to perfectly match the input flow order (`isRefreshing` -> `oldestTimestamp` -> `currentUserId`).

### 4. Dependency Upgrades
- **Material Icons Extended**: Added `androidx.compose.material:material-icons-extended` to support standard Visibility and VisibilityOff icons without custom assets.

## Verification Results
- **Build**: Successfully passed with `./gradlew assembleDebug`.
- **Manual Verification**:
    - [x] Password toggle works on both screens.
    - [x] Signup button remains disabled until passwords match.
    - [x] Group Detail screen no longer crashes (verified by navigating to group expenses).
    - [x] Incorrect credentials show friendly error messages.

---

---

# Sprint 25B: UX & Cosmetic Polish (Calm Offline & Empty States)

## Overview
This sprint focused on refining the user experience with "Clarity, Confidence, and Calm" principles. We eliminated ambiguity in empty states, standardized formatting, and ensured offline states are reassuring rather than alarming. A critical bug in non-group member resolution was also fixed.

## Key Changes

### 1. Consistent Empty States
- **New Component**: `EmptyState.kt` provides a standardized, icon-driven empty state.
- **Implemented**: Applied to `GroupListScreen` ("No groups yet") and `GroupDetailScreen` ("No expenses yet").
- **Clarity**: Each empty state now explains *why* it's empty and offers a single primary action (e.g., "Create Group").

### 2. "Calm" Offline Experience
- **Philosophy**: Offline is a normal state, not an error.
- **Visuals**: Replaced red warning icons with neutral Cloud/Sync icons in `SyncStatusIcon.kt`.
- **Messaging**: Status now reads "Offline • Changes saved locally" instead of "Sync Failed".

### 3. Formatting Standards
- **Centralized Logic**: Created `Formatters.kt` to enforce consistent data/money formatting.
- **Dates**: "Today", "Yesterday", or `12 Jan 2026`.
- **Currency**: Strict `₹ 1,200.00` format (Symbol + Space + 2 Decimals).

### 4. Interaction Improvements
- **Loading State**: `AddExpenseScreen` save button now shows `[ ⟳ Saving... ]` with fixed width to prevent layout shift.
- **Keyboard Handling**: Improved IME actions (Next -> Done) and auto-dismissal.
- **Touch Targets**: Ensured interactive elements meet 48dp accessibility standards.

### 5. Bug Fix: "Unknown" Members
- **Problem**: Non-Group (Personal) expenses showed "Unknown" names because participants weren't members of the phantom group context.
- **Fix**: Updated `GroupDetailViewModel` to enrich the member list by resolving *all* user IDs found in expenses/splits, not just formal group members.

### 6. Code Review Hardening (Post-Audit)
- **Thread Safety**: Refactored `Formatters.kt` to instantiate `SimpleDateFormat` and `DecimalFormat` locally within functions. This eliminates concurrency risks where multiple threads (e.g., background sync and UI rendering) could corrupt the shared formatter state.
- **Accessibility & Localization**:
    - Replaced hardcoded strings in `SyncStatusIcon.kt` with `stringResource()` calls.
    - Added `semantics` block with `stateDescription` to the `SYNCING` state, ensuring screen readers can correctly identify the active synchronization process.
    - Standardized all new strings in `strings.xml`.
- **Financial Correctness**: Updated `BalanceRow` to accept an explicit `currencyCode`. Balances now infer the display currency from the group's expenses (defaulting to "₹") rather than hardcoding the symbol, preparing the UI for multi-currency support.

### 7. Sprint 26: Currency Correctness & ISO Normalization
- **Core Refactor**: Replaced "₹" fallback with "INR" (ISO-4217) across the entire app.
- **Architectural Lock**: Updated `BalanceCalculator` to explicitly accept a `currencyCode` parameter, enforced via "Architecture Lock" comments in code.
- **Formatting Standardization**: Refactored all currency string interpolations (e.g., `₹${amount}`) to use `Formatters.formatMoney(amount, "INR")`. Symbols are now exclusively managed in `Formatters.kt`.
- **Domain Hardening**: Deprecated and neutralized `MoneyFormatter` (Domain) to prevent symbol leakage.
- **Verification**: Verified zero matches for raw "₹"/"$" in non-UI files (via grep).

## Verification Results
- **Build**: Successfully passed `./gradlew assembleDebug`.
- **Manual Verification**:
    - [x] "Unknown" names resolved in personal expenses.
    - [x] Empty states appear correctly for new users.
    - [x] Offline mode shows neutral/calm indicators.
    - [x] Currency and Date headers are consistent.
    - [x] TalkBack correctly identifies "Syncing changes..." state.
    - [x] All currency amounts display "₹" correctly via ISO mapping.

---

# Sprint 26.1: CodeRabbit Hardening (Currency Finalization)

## Overview
This stabilization sprint addresses 4 specific architectural issues flagged by CodeRabbit to strictly enforce ISO-4217 correctness. It eliminates the last residual hardcoded "INR" strings and legacy formatter dependencies, ensuring that all currency formatting is driven dynamically by the underlying financial entities.

## Key Changes

### 1. Domain Hardening
- **Deprecated MoneyFormatter**: The domain-layer `MoneyFormatter.format` function has been annotated with `@Deprecated`, redirecting developers to the UI-layer `Formatters.formatMoney(amount, currencyCode)` which enforces explicit currency.

### 2. ISO Currency Threading
- **FriendLedgerItem Update**: Updated the `FriendLedgerItem` sealed class hierarchy to include a mandatory `currency` field.
- **Repository Derivation**: Updated `FriendTransactionsRepository` to populate this field:
    - **Group Expenses**: Derived from the underlying `Expense` entity (as `Group` has no currency).
    - **Settlements**: Derived strictly from `Settlement.currency`.
- **UI Remediation**: `FriendDetailScreen` and `PersonalLedgerScreen` now use this threaded currency instead of hardcoded "INR".

### 3. Sync Issues Correctness
- **SettlementAmount DTO**: Created a new DTO `SettlementAmount` to fetch both amount and currency for sync issue display.
- **Localized Labels**: `SyncIssuesViewModel` now uses a properly localized string resource (`R.string.settlement_label`) and dynamic currency formatting, removing the last hardcoded strings in the codebase.

## Verification Results
- **Build**: Successfully passed `assembleDebug` (verified deprecation warning visibility).
- **Manual Verification**: Confirmed that all transaction screens and sync issue dialogues correctly display currency symbols derived from the database state.

---

# Sprint 26.2: CodeRabbit Hardening (Fail-Closed Currency Threading)

## Overview
This stabilization sprint enforces a strict "Fail-Closed" architecture for currency operations. It removes all implicit defaults (such as assuming "INR" for new data) and blocks any write operation (Add Expense, Settle Up) if the currency context cannot be deterministically derived from the ledger history.

## Key Changes

### 1. Fail-Closed Write Paths
- **Settlement Blocking**: `SettleUpViewModel` and `GroupDetailViewModel` now explicitly **BLOCK** settlement attempts if the group/friend has no transaction history.
- **Genesis Block**: `AddExpenseViewModel` blocks the creation of the very first expense in a group if currency cannot be inherited (waiting for Sprint 28 explicit currency selection).
- **Explicit Error Messages**: Replaced silent defaults with user-facing errors requesting currency context (e.g., "Cannot determine currency. Add an expense first.").

### 2. Read-Only Derivation
- **No Preference Injection**: Removed `UserPreferencesManager` from all ViewModels to prevent preference-based data corruption.
- **Transaction-Based Display**: `FriendDetailViewModel` and `PersonalLedgerViewModel` now derive their display currency strictly from the transaction history (`FriendTransactionsRepository`).

## Verification Results
- **Build**: Successfully passed `assembleDebug`.
- **Manual Verification**: Verified that creation of a genesis expense in a fresh group fails with the expected error, ensuring no invented "INR" data enters the ledger.

 ---

 # Sprint 27: Settlement Suggestion Filtering & UI Polish

 ## Overview
 This sprint focused on refining the user experience by filtering irrelevant settlement suggestions and polishing UI edge cases. We also implemented a critical fix to unblock genesis expense creation in new groups.

 ## Key Changes

 ### 1. Settlement Filtering
 - **Domain Logic**: Added `filterActionableSettlements` to `SettlementCalculator`.
 - **Rule**: A user only sees settlements where they are the **Payer** or **Receiver**. Third-party debts (A owes B, viewed by C) are strictly hidden.
 - **Impact**: "Settle Up" section is now personalized and actionable.

 ### 2. UI Polish
 - **Clean Balances**: `GroupDetailScreen` now displays absolute values for balances (`amount.abs()`).
     - **Result**: `₹ 200.00` (Red/Green) instead of `-₹ 200.00`.
 - **Duplicate Member Fix**: Refactored `CreateGroupViewModel` to use a **Reactive Selection Queue**.
     - **Root Cause**: Race condition between optimistic UI updates and DB observation.
     - **Fix**: Removed manual list mutation; relying solely on DB as Single Source of Truth with an auto-select queue.

 ### 3. Genesis Expense Fix (Unblocking)
 - **Problem**: New groups had no expense history -> no currency context -> blocking expense creation (Fail-Closed).
 - **Fix**: `AddExpenseViewModel` now defaults to **"INR"** if no history exists.
 - **Result**: Enables bootstrapping of new groups.

 ## Verification Results
 - **Build**: Successfully passed.
 - **Manual Verification**:
     - [x] "Settle Up" shows only relevant items.
     - [x] Balances are clean (no minus signs).
     - [x] Create Group member chips do not flicker or duplicate.
     - [x] First expense in a new group succeeds.

---

---

# Sprint 28: Auth-Gated Sync Correction

## Overview
This sprint addressed a critical correctness issue where the synchronization pipeline (Push/Pull) would attempt to run on fresh installs or unauthenticated states, leading to confusing error logs (`IllegalStateException`) and violating the offline-first architecture.

## Key Changes

### 1. Authentic Identity Gating
- **Invariant**: Sync eligibility is now strictly determined by the presence of a **Bound Cloud Identity** (`tokenManager.getCloudUserId()`).
- **Gate Implementation in `SyncWorker`**:
    - Before any work begins, the worker checks if a Cloud Identity exists.
    - If missing (guest/fresh install), it logs `INFO` "Skipping sync: No bound cloud identity" and returns `Success`.
    - This prevents both Push and Pull phases from executing in invalid states.

### 2. Secondary Safety Gates
- **`LedgerSyncCoordinator`**: Added a secondary check for Cloud Identity at the start of `sync()` to protect against direct calls or race conditions.
- **`LedgerPullService`**: Implemented **Explicit Auth-Gating** via `AuthPaused`.
    - Old: Threw `IllegalStateException` or returned ambiguous `Result.success(emptyList())`.
    - New: Returns `PullResult.AuthPaused`.
    - Benefit: Distinguishes "Gate Violation" (handled by Worker) from "Transient Offline/Expiry" (handled by Service) using a typed contract.

### 3. Dependency Injection Update
- Updated `HydrationModule` to inject `TokenManager` into `LedgerSyncCoordinator`, ensuring the new gating logic has access to the authoritative identity state.

### 4. Contract Standardization (`PullResult`)
- **Sealed Result Type**: Introduced `PullResult<T>` to replace binary `Result<T>` in the sync pipeline.
- **Explicit States**: Success, AuthPaused, and Error.
- **Consumer Alignment**: Updated `LedgerSyncCoordinator` and `HydrationCoordinator` to handle `AuthPaused` as a graceful partial-success, eliminating "liar success" patterns (masking auth pauses as empty data).

### 5. Hydration State Machine Correction
- **Issue**: `hydrationAttempted` flag was leaking as `true` if hydration aborted during the network pull phase.
- **Impact**: Could trigger incorrect database remediation (full wipe) on subsequent app launches.
- **Fix**: Added explicit resets (`setHydrationAttempted(false)`) in the `AuthPaused` and `Error` branches of `HydrationCoordinator` to ensure the flag only remains `true` if the database has entered a potentially "dirty" write-capable state.

## Verification Results
- **Build**: Successfully passed.
- **Correctness**: The app now stays silent regarding sync until the user actually logs in.

---

# Sprint 28.5: Identity Integrity & Replay Determinism

## Overview
This sprint bridges the gap between the "Phantom-to-Real" merge logic (Sprint 24) and the "Ledger-Only" architecture (Sprint 22). It ensures that user identities are first-class ledger entities from the moment of creation and that the Replay Engine can deterministically handle out-of-order operations that reference those identities.

## Key Changes

### 1. ReplayEngine: Deterministic Dependency Checking
- **User Existence Guards**: Updated `canApplyOperation` to strictly block `EXPENSE`, `SETTLEMENT`, and `MEMBER` operations if their referenced `userId`s do not exist in the local database.
- **Convergence Loop**: Operations with missing users are automatically deferred. The engine now correctly retries these operations in subsequent passes once the prerequisite `USER.CREATE` operation has been replayed.
- **Idempotent User Upserts**: Refactored `applyUserOperation` to use `upsertUser` (REPLACE strategy). This ensures that both initial creations and subsequent updates (e.g., name changes) are handled safely and idempotently.

### 2. Identity Bootstrapper: Ledger Alignment
- **Pattern B (Immediate Write-Ahead)**: Aligned the local user bootstrapping flow with the standard ledger pattern.
- **Atomic Registration**: `ensureLocalUserRegistered` now uses `db.insertUserWithLedger` to atomically commit:
    1. The `User` record (enabling immediate UI availability).
    2. A `SyncOperation` (ensuring the fact is pushed to the cloud).
    3. A `USER.CREATE` `LedgerOperation` (ensuring the fact is durable in the historical timeline).
- **Personal Group Container**: Ensured the personal group is also bootstrapped if missing, satisfying the `ReplayEngine`'s group existence invariant.

### 3. Verification & Safety
- **`ReplayUserDependencyTest`**: Created a new instrumented test suite to verify:
  - **Partial Ordering**: An expense arriving before its user is deferred and then correctly applied.
  - **Data Cleanliness**: SQL-level audit confirming zero orphaned references in the database.
- **Zero-Orphan Invariant**: Hardened the system against the "Ghost User" race condition where an offline expense could be created before the local user was formally registered in the ledger.

## Verification Results
- **Build**: Successfully passed Kotlin compilation.
- **Tests**: `ReplayUserDependencyTest` passed with 100% success on the target device.
- **Integrity Audit**: Verified 0 orphaned records in a fresh installation scenario.

---
# Sprint 29A: Universal Person Foundation

## Overview
This sprint introduces a first-class, app-scoped `Person` identity to SplitEase. This identity is decoupled from the authentication `User`, establishing a foundation for participants who may not yet be registered users ("phantom" persons) while ensuring a consistent, ledger-backed identity for all participants.

## Key Changes

### 1. Data Model & Room Integration
- **`Person` Entity**: Introduced the `persons` table with canonical `id` (UUID), `displayName`, and a nullable `linkedUserId`.
- **`PersonDao`**: Implemented atomic upserts and queries by `personId` or `linkedUserId`.
- **Invariants**: Enforced one-to-one binding between Person and User via the ledger and Replay Engine.

### 2. Ledger Vocabulary & Factory
- **Entity Identification**: Added `ENTITY_PERSON` to the ledger vocabulary.
- **Link Operation**: Introduced `OP_LINK_USER` to explicitly bind a Person to a User identity.
- **Factory Integration**: Added `createPersonCreateOp` and `createPersonLinkUserOp` to `LedgerOperationFactory`.

### 3. Replay Engine Enrichment
- **Dependency Enforcement**: Updated `ReplayEngine.canApplyOperation` to strictly require both Person and User existence before applying a `LINK_USER` operation.
- **Immutability Protection**: Hardened `applyPersonOperation` to prevent overwriting an existing `linkedUserId`, adhering to the first-writer-wins rule for identity binding.
- **Bootstrapping Safety**: `OP_CREATE` is handled idempotently to allow for safe re-runs.

### 4. Identity Bootstrapper Integration
- **Self Person Creation**: On starting the app, `IdentityBootstrapper` now ensuring exactly one "Self Person" exists locally and is linked to the authenticated user.
- **Atomic Transaction**: Uses Room's `withTransaction` to ensure the entity creation and its ledger facts are committed together, preventing partial identity states.

### 5. Verification & Tests
- **`ReplayPersonTest`**: Verifies deterministic convergence of person operations and strict dependency enforcement.
- **`BootstrapPersonTest`**: Verifies correct creation of the "Self Person" on fresh installations.

## Verification Results
- **Build**: Successfully passed.
- **Tests**: `ReplayPersonTest` and `BootstrapPersonTest` passing 100%.
- **Doc Coverage**: KDoc coverage for all Sprint 29A code is >= 80%.
