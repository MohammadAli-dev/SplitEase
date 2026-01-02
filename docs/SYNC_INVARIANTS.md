# Sync Invariants

This document defines the correctness guarantees for SplitEase's offline-first sync system.

---

## 🔒 Core Idempotency Invariant

> **For any SyncOperation payload with ID = X, applying it N times must result in the same final database state as applying it once.**

This is the north star for all sync work.

---

## At-Least-Once Delivery

> **All sync writes must be safe under at-least-once delivery semantics.**

This means:
- Duplicates are expected
- Retries are normal
- Idempotency is mandatory

---

## Implementation Guarantees

### Database Layer
All entity DAOs use `OnConflictStrategy.REPLACE` on primary key:

| Entity | Strategy | Idempotent |
|--------|----------|------------|
| Settlement | REPLACE | ✅ |
| Expense | REPLACE | ✅ |
| ExpenseSplit | REPLACE | ✅ |
| Group | REPLACE | ✅ |
| GroupMember | REPLACE | ✅ |
| GroupMember (batch) | IGNORE | ✅ (creates only) |

### Payload Identity
Every sync payload includes a unique entity ID:
- `SettlementCreatePayload.id`
- `ExpenseCreatePayload.expense.id`
- `GroupCreatePayload.group.id`

This enables backend deduplication and client-side replay safety.

---

## Domain Invariant

The **zero-sum balance invariant** must hold after any sync replay:

```kotlin
assertEquals(
    BigDecimal.ZERO,
    balances.values.fold(BigDecimal.ZERO, BigDecimal::add)
)
```

If this assertion fails after replay, the sync system has corrupted financial state.

> **No sync operation may mutate balances directly; all balance changes must be derivable from persisted entities.**

---

## Testing Requirements

### Instrumented Tests
1. **Settlement replay**: Insert same settlement twice → single row
2. **Expense replay**: Insert same expense twice → single row
3. **Replay order inversion**: Entity creation order does not affect final state (assuming all entities exist after replay)

### Manual Crash Simulation

**Procedure:**
1. Enable airplane mode
2. Create 2+ settlements
3. Force-stop app (Settings → Apps → Force Stop)
4. Restart app
5. Disable airplane mode → trigger sync
6. Force sync again (simulate retry)

**Verify:**
- No duplicate rows in any table
- Balances sum to zero
- `sync_operations` table has expected entries
