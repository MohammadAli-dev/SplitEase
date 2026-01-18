# PROJECT_REPORT2.md

## 1️⃣ Ledger-Based Sync Architecture (Current)
- **Push Flow**: Local writes in Repositories (e.g., `ExpenseRepository`) are serialized via `LedgerWriteGate`. They generate a `LedgerOperation`.
- **Persistence**: `AppDatabase` atomically persists entity data (e.g., `Expense`) alongside the `LedgerOperation` record.
- **Clock Allocation**: Monotonic logical clocks are allocated using an atomic SQL subquery during insertion.
- **Mirroring**: `LedgerPushWorker` (managed by WorkManager) mirrors pending local operations to the Supabase `ledger_operations` table.
- **Pull Flow**: `LedgerPullService` fetches operations from Supabase for all devices.
- **Hydration**: `ReplayEngine` performs convergence-based deterministic replay of global operations to reconstruct the local Room database on secondary devices.

## 2️⃣ Device Role & Permission Model
- **Device Roles**: `PRIMARY` (initial writer), `PROMOTED` (writer), `REPLICA` (read-only).
- **Promotion Flow**: Explicit recovery-aware state machine (`PromotionCoordinator`) transitions a device from `REPLICA` to `PROMOTED` after validating local/remote ledger equality.
- **Write Serialization**: Exclusive lock acquisition via `LedgerWriteGate` ensures no two operations on the same device receive the same clock.
- **Guards**: Repositories throw `WritePermissionDeniedException` if a non-writer device attempts a mutation.

## 3️⃣ Identity Model
- **User Table**: `users(id String, name, email?, profileUrl?)`.
- **Bootstrap Identity**: `AuthManager` caches and restores the user's profile on startup to eliminate "Unknown" user flickering during cold boot.
- **JWT Authorization**: All Supabase calls are secured with the user's real JWT access token.

## 4️⃣ Sync Failure & Hardening
- **Semantic Errors**: `LedgerSetComparison.Error` distinguishes between transient network failures and genuine ledger set mismatches.
- **Fail-Closed Mapping**: `DeviceRoleManager` defaults invalid persisted roles to `REPLICA` for safety.
- **Mutex Integrity**: `HydrationCoordinator` uses explicit lock-state tracking for safe mutex release.

## 5️⃣ Sprint Progress (17 - 19)
- **Sprint 17**: Implemented Supabase Ledger Mirroring (Push only).
- **Sprint 18**: Implemented Deterministic Hydration (Pull/Replay) and Read-Only enforcement.
- **Sprint 19**: Implemented Write Promotion, Serialized Mutations, and Audit Hardening.

## 6️⃣ Explicit Non-Goals
- Do not modify Database schema unless explicitly instructed.
- Do not add new UI screens unless requested (Focus on architecture).
