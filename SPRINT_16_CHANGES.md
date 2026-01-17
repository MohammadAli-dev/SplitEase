# Sprint 16: Local Ledger Foundations – Changes & Rationale

This sprint introduced a deterministic, local, immutable ledger system to SplitEase. This serves as the "Ground Truth" for all financial mutations, enabling future conflict resolution, audit trails, and robust offline-to-cloud synchronization.

## 1. Technical Changes

### Core Data Layer (Room)
- **`LedgerOperation` Entity**: Created a new Room table to store every mutative action (Create, Update, Delete) as an immutable record.
  - Fields: `operationId`, `entityType`, `entityId`, `operationType`, `payload` (JSON), `deviceId`, `logicalClock`, and `authorLocalUserId`.
  - Indices: Unique index on `(deviceId, logicalClock)` and search index on `(entityType, entityId)`.
- **`LedgerDao`**: Implemented the storage interface with a CRITICAL atomic query: `COALESCE(MAX(logicalClock), 0) + 1`. This ensures monotonic ordering within a single device's timeline without gaps.
- **Migration 10 -> 11**: Implemented Schema migration to add the `ledger_operations` table and its indices.

### Canonical Snapshots (DTOs)
- Created **Snapshot DTOs** in `com.splitease.data.ledger.model`:
  - `ExpenseSnapshot`, `GroupSnapshot`, `SettlementSnapshot`, `MemberSnapshot`.
- **Rationale**: By storing the *entire state* of an entity at the time of the operation (rather than just the change), we ensure that replaying the ledger can perfectly reconstruct the database at any point in time, even if the application logic changes.

### Deterministic Factory & Device ID
- **`InstallationIdProvider`**: Introduced to generate and persist a stable UUID for each app installation in `SharedPreferences`. This identifies which device "authored" an operation.
- **`LedgerOperationFactory`**: A pure logic component that transforms Room entities into `LedgerOperation` records. It ensures that JSON serialization is consistent and payloads are canonical.
  - **Refinement**: Implemented **String Canonicalization** for all `BigDecimal` fields (Amount) using `setScale(2, RoundingMode.HALF_UP).toPlainString()`. This eliminates serialization non-determinism at the domain level.

### Ledger-Inclusive Atomic Commit (`AppDatabase`)
- Updated `AppDatabase.kt` with a **Ledger-Inclusive Atomic Commit** pattern.
- **`commitLedgerOp()`**: A new internal helper that handles the atomic clock allocation and insertion with retry logic to handle concurrent write contention.
- **Guaranteed Transactions**: Introduced methods like `updateExpenseWithLedger` and `removeMemberWithLedger` that atomize the writing of the Business Entity, the Legacy `SyncOperation`, and the New `LedgerOperation`.

### Repository Instrumentation
- **`GroupRepository`**: Updated `createGroup`, `leaveGroup`, and `removeMember` to generate and persist ledger operations.
- **`ExpenseRepository`**: Updated `addExpense`, `updateExpense`, and `deleteExpense`.
- **`SettlementRepository`**: Updated `executeSettlement`.

---

## 2. The "Why": Rationale & Benefits

### Why a Ledger?
Existing `SyncOperation` objects were "per-sync-job" and often lacked the full state required to resolve complex conflicts. The Ledger provides an **immutable timeline** that is separate from active synchronization logic.

### Why Full Snapshots?
By storing a full snapshot in every `UPDATE` operation, we eliminate "Snapshot Dependency" issues. To reconstruct an entity, you only need the *latest* operation, rather than having to apply every delta since the beginning of time.

### Why Device-Specific Logical Clocks?
In an offline-first app, local timestamps are unreliable (users can change their clocks). By using a `logicalClock` (1, 2, 3...) scoped to a specific `deviceId`, we get a **perfectly ordered sequence** of operations that the server can trust to detect "happened-before" relationships.

### Why No Behavioral Changes?
This sprint was implemented as a **Dual-Write system** using **Ledger-Inclusive Atomic Commits**. The app continues to function exactly as before, but it now "silently" records its own history. This allows us to verify the ledger logic in production before relying on it for critical sync features.

---

## 3. Key Code Snippets

### Ledger Operation Entity
```kotlin
@Entity(
    tableName = "ledger_operations",
    indices = [
        Index(value = ["deviceId", "logicalClock"], unique = true),
        Index(value = ["entityType", "entityId"])
    ]
)
data class LedgerOperation(
    @PrimaryKey val operationId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val payload: String, // Canonical JSON Snapshot
    val authorLocalUserId: String,
    val deviceId: String,
    val logicalClock: Long,
    /** CRITICAL: Diagnostic only. Never used for ordering. */
    val createdAt: Long
)
```

### Atomic Clock Allocation (DAO)
```kotlin
@Dao
interface LedgerDao {
    @Query("SELECT COALESCE(MAX(logicalClock), 0) + 1 FROM ledger_operations WHERE deviceId = :deviceId")
    suspend fun getNextLogicalClock(deviceId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: LedgerOperation)
}
```

### Transactional Helper (AppDatabase)
```kotlin
@androidx.room.Transaction
open suspend fun commitLedgerOp(ledgerOp: LedgerOperation) {
    // Retry-on-conflict loop to handle race conditions in MAX(clock) allocation.
    var attempts = 0
    while (attempts < 3) {
        try {
            val nextClock = ledgerDao().getNextLogicalClock(ledgerOp.deviceId)
            val finalOp = ledgerOp.copy(logicalClock = nextClock)
            ledgerDao().insert(finalOp)
            return
        } catch (e: SQLiteConstraintException) {
            attempts++
            if (attempts >= 3) throw e
        }
    }
}
```
@androidx.room.Transaction
open suspend fun updateExpenseWithLedger(
    expense: Expense,
    splits: List<ExpenseSplit>,
    syncOp: SyncOperation,
    ledgerOp: LedgerOperation
) {
    expenseDao().updateExpense(expense)
    expenseDao().deleteSplitsForExpense(expense.id)
    expenseDao().insertSplits(splits)
    syncDao().insertSyncOp(syncOp)
    commitLedgerOp(ledgerOp) // Atomic ledger commit
}
```

### Repository Instrumentation (Example)
```kotlin
override suspend fun updateExpense(expense: Expense, splits: List<ExpenseSplit>) =
    withContext(Dispatchers.IO) {
        val syncOp = syncWriteService.createUpdateExpenseSyncOp(expense, splits)
        // Factory provides a state snapshot WITHOUT assigning the clock
        val ledgerOp = ledgerOperationFactory.createExpenseUpdateOp(
            expense, splits, expense.lastModifiedByUserId
        )
        // Transaction handles atomic database write and clock assignment
        appDatabase.updateExpenseWithLedger(expense, splits, syncOp, ledgerOp)
    }
```

---

## 4. PR Message

**Title**: feat: Implement Local Ledger Foundations (Sprint 16)

### Summary
This PR introduces the "Local Ledger" foundation—a deterministic, immutable, and append-only record system that tracks all financial mutations (Create, Update, Delete) in the application. This layer provides a "Ground Truth" history to enable robust offline synchronization and conflict resolution in future sprints.

### Key Changes
- **Immutable Ledger Schema**: Added `LedgerOperation` Room entity with device-scoped `logicalClock` for authoritative ordering.
- **Canonical Snapshots**: Implemented versioned DTOs (`ExpenseSnapshot`, etc.) stored as JSON payloads in the ledger.
  - **Deterministic Money**: Refactored DTOs to store amounts as `String` to ensure bit-for-bit identical payloads regardless of JSON serializer configuration (BigDecimal scientific notation fix).
- **Ledger-Inclusive Atomic Commit**: Refactored `AppDatabase` transaction helpers to ensure Business Data, Sync Intents, and Ledger Operations are committed atomically.
- **Concurrency Handling**: Implemented a retry-on-conflict loop for logical clock allocation to resolve potential SQLite write contention inside Room transactions.
- **Repository Instrumentation**: Updated `GroupRepository`, `ExpenseRepository`, and `SettlementRepository` to implement dual-writes (Entities + Ledger).
- **Architecture**: Decoupled operation generation (`LedgerOperationFactory`) from persistence logic and clock assignment.

### Rationale
By recording a full state snapshot in every operation, we eliminate complex "snapshot dependency" issues during sync. The use of per-device logical clocks provides a reliable ordering mechanism that remains immune to local system time manipulation.

### Verification
- ✅ **Build**: SUCCESSFUL (Migration 10 -> 11 applied)
- ✅ **Test**: All 33 existing unit tests pass, ensuring zero regression in existing behavior.
- ✅ **Invariants**: `logicalClock` monotonicity and snapshot integrity verified via code review.
