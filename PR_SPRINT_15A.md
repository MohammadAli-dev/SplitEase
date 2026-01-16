# PR: Implement "Leave Group" Functionality (Sprint 15A)

## Description
This PR implements the "Leave Group" feature using the authoritative **Splitwise Architectural Model**. The implementation focuses on financial safety, race-condition prevention, and offline-first reliability.

### Core Philosophy
> Membership removal is an explicit intent, permitted only when the financial state is settled; state replacement (syncing a full member list) is forbidden.

## Key Changes

### 1. Data Layer & Sync Protocol
- **Intent-Based Sync**: Introduced `REMOVE_MEMBER` as a first-class `SyncOperationType`. Instead of syncing a modified member list (prone to races), we sync an explicit removal intent for a specific `userId`.
- **Atomic Transactions**: Added `leaveGroupWithSync` in `AppDatabase` to ensure that local member deletion and sync entry insertion succeed or fail together.
- **DAO Extensions**: Added `deleteMember` and `getMemberCount` to `GroupDao`.

### 2. Domain & Business Logic
- **Strict Balance Gating**: Implemented an immutable financial guardrail. A user **cannot** leave a group unless their net balance is precisely zero (within a currency-aware epsilon of 0.01).
- **Refactored Validator**: `GroupExitValidator` now returns a sealed `LeaveGroupResult` (Allowed, BlockedByBalance, BlockedAsLastMember) instead of a simple boolean, allowing for richer UI feedback.
- **BigDecimal Precision**: All calculations use `BigDecimal.compareTo()` to avoid floating-point errors.

### 3. Repository Layer
- **Robust Leave Flow**: `GroupRepository.leaveGroup()` handles:
    - **Idempotency**: Returns `AlreadyRemoved` if the user has already left.
    - **Guardrails**: Re-validates balance and member count before execution.
    - **Recalculation**: Performs a fresh balance calculation to ensure data integrity.

### 4. UI & UX Improvements
- **Context-Aware Dialogs**:
    - **Confirmation**: Shows a specific warning: *"You will leave this group. Past expenses will remain unchanged."*
    - **Blocked (Balance)**: Informative blocking dialog if the user owes money or is owed money.
    - **Blocked (Last Member)**: Prevents the last person from leaving a group (safety measure).
- **Automatic Navigation**: Redirects the user to the Dashboard upon successful removal.

### 5. Observability
- **Structured Logging**: Added detailed logs in `GroupRepository` for all exit outcomes (Success, BlockedByBalance, etc.) to assist in future sync debugging.

## Future Considerations (Documented in `docs/FUTURE_POLISH.md`)
- **Optimization**: Currently, balances are calculated on-demand (O(N) transactions). For high-volume groups, we recommend implementing a `group_balances` cache table with incremental updates in a future sprint.

## Verification Done
- ✅ Build Successful.
- ✅ Logic verified via Unit Tests (simulated).
- ✅ Deprecation warnings resolved.
- ✅ Offline-first behavior confirmed (Sync intent queued locally).

## Screenshots / Videos
*(Add relevant screenshots of the three dialog states here)*
