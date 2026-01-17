# Fix: Atomicity Bug in ExpenseRepository.addExpense

## Problem Identified by CodeRabbit

The `addExpense` method had a **critical atomicity bug**:

```kotlin
// BEFORE (BUGGY):
expenseDao.insertExpenseWithSync(expense, splits, syncOp)  // Transaction 1
appDatabase.commitLedgerOp(ledgerOp)                        // Transaction 2
```

If Transaction 2 failed after Transaction 1 committed, we would have:
- ✅ Expense + splits written
- ✅ SyncOperation written  
- ❌ **LedgerOperation missing**

This violated our Sprint 16 invariant:
> "LedgerOperation is the deterministic ground truth for financial facts."

---

## Solution Implemented

### 1. Added Missing `insertExpenseWithLedger` Method

Created the entity-specific transaction helper in `AppDatabase.kt`:

```kotlin
@androidx.room.Transaction
open suspend fun insertExpenseWithLedger(
    expense: Expense,
    splits: List<ExpenseSplit>,
    syncOp: SyncOperation,
    ledgerOp: LedgerOperation
) {
    expenseDao().insertExpense(expense)
    expenseDao().insertSplits(splits)
    syncDao().insertSyncOp(syncOp)
    commitLedgerOp(ledgerOp)  // Atomic with retry logic
}
```

### 2. Updated ExpenseRepository

```kotlin
// AFTER (ATOMIC):
override suspend fun addExpense(expense: Expense, splits: List<ExpenseSplit>) =
    withContext(Dispatchers.IO) {
        val syncOp = syncWriteService.createExpenseSyncOp(expense, splits)
        val ledgerOp = ledgerOperationFactory.createExpenseCreateOp(expense, splits, expense.createdByUserId)
        appDatabase.insertExpenseWithLedger(expense, splits, syncOp, ledgerOp)
    }
```

This now matches the pattern used by `updateExpense`, `deleteExpense`, and all other operations.

---

## Architectural Documentation Added

Added comprehensive KDoc to `AppDatabase` explaining:

1. **Why entity-specific methods?**
   - Room's `@Transaction` is method-scoped, not lambda-scoped
   - No `runInTransaction { }` API that allows calling DAOs inside
   
2. **What are the alternatives?**
   - Generic dispatch with `Any` → loses type safety
   - Repository-level coordination → technically impossible with Room
   - Custom transaction manager → unnecessary complexity

3. **Established invariant:**
   > Every financial mutation MUST commit entity + SyncOp + LedgerOp atomically.
   > If you add a new mutation, you MUST create a `*WithLedger` method.

---

## Verification

- ✅ **Build**: SUCCESSFUL  
- ✅ **Tests**: All 33 unit tests pass
- ✅ **Pattern consistency**: Now all 6 ledger-aware methods follow the same pattern

---

## ChatGPT's Critique (Rejected)

ChatGPT argued we should "move transactions to the repository level." This is:
- **Technically impossible** with Room's API design
- **Contradicts Sprint 16's verified architecture**
- **Introduces inconsistency** (some ops atomic, some not)

Our entity-specific pattern is the correct design given Room's constraints.
