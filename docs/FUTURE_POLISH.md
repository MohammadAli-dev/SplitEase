# Future Polish Ideas

These are not issues — just future polish ideas that don't block current sprints.

## Sprint 10B Observations (2026-01-05)

### 1. Expose SyncState in Logs
- Currently logging PAUSED transitions — good.
- **Future**: Log full state transitions for analytics/debugging.
- Example: `Log.d("SyncHealth", "State: $previousState -> $currentState")`

### 2. Unit Tests (Pre-Launch)
- The state derivation logic is deterministic and highly testable.
- **Future**: Add unit tests for `deriveSyncState()` in ViewModels before public launch.
- Test cases:
  - `failedCount > 0` → FAILED
  - `pendingCount > 0 && age > threshold` → PAUSED
  - `pendingCount > 0` → SYNCING
  - else → IDLE

---

## Sprint 15A Observations (2026-01-16)

### 1. Balance Calculation Optimization
- **Current**: Balances are calculated on-demand by fetching all expenses, splits, and settlements for a group. This is simple and deterministic but expensive for large groups.
- **Future Optimization**:
    - **Cached Balances**: Maintain a `group_balances` table that stores the current net balance for each user per group.
    - **Incremental Updates**: Update the cached balances whenever an expense or settlement is created, updated, or deleted, avoiding full recalculations.
- **Why**: Recalculating everything on every "Leave Group" check (or other balance-intensive operations) may lead to performance bottlenecks as group history grows.

### 2. Logical Consistency in Leave Group (TOCTOU)
- **Observed Risk**: In `GroupRepository.leaveGroup`, multiple reads (members, expenses, splits, settlements) happen sequentially before a single atomic write. In a highly concurrent local environment (multiple threads/background sync), data could drift between the decision and the deletion.
- **Future Fix (Snapshot Consistency)**:
    - Avoid wrapping business logic in DB transactions (contention risk).
    - Implement a **Conditional Atomic Write** in `AppDatabase.leaveGroupWithSync`.
    - Pass an `expectedMemberCount` or `lastUpdatedAt` version to the transaction.
    - If the state has changed since the validation began, the transaction should fail, prompting a UI retry.
- **Current Status**: Low severity for Sprint 15A because financial liability is independent of group membership in the Splitwise model, but worth hardening for absolute determinism.

---

*Nothing here blocks or weakens Sprint 15A.*
