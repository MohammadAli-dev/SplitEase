# PROJECT_REPORT2.md

## 1️⃣ Sync Architecture (Current)
- **Push Flow**: Local writes (CREATE/UPDATE/DELETE) in Repositories (e.g., `ExpenseRepository`) generate a `SyncOperation` entity.
- **Persistence**: `AppDatabase` uses `@Transaction` blocks to atomically persist entity data (e.g., `Expense` + `ExpenseSplit`) alongside the `SyncOperation` record.
- **Execution**: FIFO processing via `PushSyncService` (managed by WorkManager).
- **Pull Flow**: Manual trigger via `PullSyncService.performPullSync()`. Fetches entities in specific order: `Groups` → `Expenses` (with `Splits`) → `Settlements`.
- **Conflict Detection (Sprint 20)**: Introduced explicit, deterministic conflict detection.
    - **Detection Boundary**: Runs exclusively after full remote ledger exhaustion and replay convergence.
    - **Identity**: Conflicts are keyed by a deterministic `conflictId` (SHA-256 fingerprint of the entity type, ID, and sorted operations).
    - **Invariants**: Detection is O(N) linear, monotonic (append-only), and strictly device-local (never synced).
    - **Visibility**: Surfaced via `ConflictRepository` without resolve/resolution logic.
- **Transactions**: Atomic transactions exist per-entity (e.g., `insertExpenseWithSync` includes splits and the sync op).


## 2️⃣ Identity Model (Current)
- **User Table**: `users(id String, name, email?, profileUrl?)`.
- **Phantom Representation**: Represented as a standard `users` row. Business logic differentiates phantoms via ID prefixing (`phantom_...`).
- **Auth Identity**: The logged-in user's identity is provided by `UserContext`.
- **FK Reassignment**: Existing implementation in `AppDatabase.mergePhantomToReal` handles merging a phantom user into a real cloud user by updating foreign keys across expenses, splits, settlements, and group members.

## 3️⃣ Balance Engine (Current)
- **Location**: `BalanceSummaryRepository` aggregates data into `DashboardSummary`.
- **Inputs**: `ExpenseDao`, `SettlementDao`, `UserContext`.
- **Logic**: Iteratively processes all splits and settlements where the current user is a party, computing a net balance per `userId`.
- **Identity Resolution**: Calculating balances is purely ID-based; name resolution for UI occurs in the ViewModel using a lookup map pre-fetched from `UserDao`.

## 4️⃣ Sync Failure Handling (Current)
- **Failure States**: `SyncStatus` (PENDING, SYNCED, FAILED).
- **Persistence**: `SyncOperation` stores `failureReason` (string) and `failureType` (enum).
- **Retries**: Standard WorkManager exponential backoff; no custom classification of permanent vs. transient failures yet.

## 5️⃣ What Is Already Implemented from 13G / 13C
- **Pull Fetch**: `PullSyncService` successfully fetches paged updates from Supabase.
- **Reconciliation**: Logic for merging remote updates with local data (with conflict rules) is implemented.
- **Invite Acceptance**: `ClaimManager` handles the backend call to claim an invite and upsert the inviter.
- **Phantom Merge**: `AppDatabase.mergePhantomToReal` provides the atomic transaction for identity linking.

## 6️⃣ Explicit Non-Goals
- Do not modify Database schema unless explicitly instructed.
- Do not change existing UI navigation flows.
- Do not add new screens.
