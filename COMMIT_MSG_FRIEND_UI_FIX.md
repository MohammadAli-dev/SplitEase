# Commit Details: Fix Dashboard Friends Empty-State Logic

**Branch**: `sprint15A-fixFriendUI`  
**Focus**: Entity-Driven vs Ledger-Driven UI Logic  

---

## Commit Message Summary
**Fix Dashboard Friends Empty-State Logic (Entity-Driven vs Ledger-Driven)**

### 1. [Problem]
The Friends section in the Dashboard showed a "No friends" empty state even after a friend (phantom or joined) was added. This occurred because the UI gated the list visibility based on `friendBalances`, which is derived from transaction history. Consequently, friends with zero balance were invisible, preventing the UI from reflecting new additions immediately.

### 2. [Solution]
Refactored the dashboard state to decouple friend existence from financial activity.

*   **Model Changes**:
    *   Added `knownUserCount` to `DashboardUiState` to track total friends (excluding self) derived directly from the users table.
    *   Renamed `friendBalances` to `ledgerBalances` to explicitly signal its derivation from the expense/settlement ledger.
*   **ViewModel Improvements**:
    *   Injected `UserContext` to enable accurate self-exclusion when calculating `knownUserCount`.
    *   Updated `loadDashboardData` to reactively observe `UserRepository` and publish the current `knownUserCount`.
*   **UI/UX Enhancements**:
    *   Implemented a 3-state logic for the Friends list:
        *   **State 1 (Zero Users)**: Show primary "Add friends" CTA (Alpha 0.5f).
        *   **State 2 (Friends exist, No activity)**: Show "No expenses yet" helper (using 0.3 alpha for lower visual prominence).
        *   **State 3 (Active ledger)**: Show the standard balance list items.
    *   Ensured friends appear immediately after being added, even before an expense is logged.
*   **Documentation**:
    *   Updated `docs/FUTURE_POLISH.md` to note semantic refinements for `knownUserCount` (soft-deletes, blocks) for future Connection lifecycle sprints.

---

## 🏛 Architectural Rationale (CTO Review Compliance)
*   **Single Source of Truth (SSOT)**: Friend count is now based on active entities in the `users` table, not derived "side effects" like a non-empty ledger.
*   **Naming Clarity**: Switched from `friend` to `knownUser` to maintain semantic precision, as the system currently tracks any interacted user, not necessarily a verified/confirmed "friend."
*   **UX Determinism**: Solves the "Zero-to-One" magic moment bug, ensuring the app feels alive as soon as the first user action (adding a person) is taken.
*   **Performance**: Uses `combine` to share existing DB flows, adding negligible overhead while significantly improving response time of the UI to local entity changes.

---
*Co-authored-by: Antigravity AI*
