# Nullable Time Parsing Enhancement

## Problem
The time parsing helpers (`parseIso8601ToEpochMillis` and `parseIso8601ToDate`) were using dangerous sentinel values:
- `0L` (epoch 1970) for malformed timestamps
- `Date()` (current time) for malformed dates

This caused:
- **Silent data corruption** - Historical expenses appearing as "today"
- **Broken conflict resolution** - Records with 0L timestamp always lose in LWW comparisons
- **Cursor stalling** - Incorrect max(updated_at) calculations causing missed updates
- **User confusion** - "Why is my 2023 expense showing as today?"

## Solution
Changed parsing helpers to return nullable types and updated all callers to handle nulls explicitly by skipping malformed records.

## Changes Made

### 1. Time Parsing Helpers (`PullSyncService.kt`)

**Before:**
```kotlin
private fun parseIso8601ToEpochMillis(iso8601: String): Long {
    return try {
        Instant.parse(iso8601).toEpochMilli()
    } catch (e: Exception) {
        Log.e(TAG, "...", e)
        0L  // ❌ Dangerous sentinel
    }
}
```

**After:**
```kotlin
private fun parseIso8601ToEpochMillis(iso8601: String): Long? {
    return try {
        Instant.parse(iso8601).toEpochMilli()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse ISO-8601 timestamp: '$iso8601'", e)
        null  // ✅ Explicit failure
    }
}
```

Same pattern applied to `parseIso8601ToDate`.

### 2. Mapper Functions

**Updated to nullable return types:**
- `mapRemoteToLocalExpense()` → `Expense?`
- `mapRemoteToLocalGroup()` → `Group?`
- `mapRemoteToLocalSettlement()` → `Settlement?`

**Validation logic:**
```kotlin
private fun mapRemoteToLocalExpense(remote: RemoteExpense, updatedAtMillis: Long): Expense? {
    val amount = parseAmount(remote.amount, "Expense:${remote.id}") ?: return null
    val date = parseIso8601ToDate(remote.date)
    if (date == null) {
        Log.e(TAG, "Skipping expense ${remote.id}: invalid date '${remote.date}'")
        return null
    }
    return Expense(...)
}
```

### 3. Reconciliation Functions

**Added null checks for `updatedAt`:**
```kotlin
private suspend fun reconcileExpense(...): ReconcileAction {
    val remoteUpdatedAt = parseIso8601ToEpochMillis(remote.updated_at)
    if (remoteUpdatedAt == null) {
        Log.e(TAG, "Reconcile[EXPENSE:${remote.id}]: SKIP (invalid updated_at timestamp)")
        return ReconcileAction.SKIP
    }
    // ... rest of logic
}
```

**Added null checks for mapper results:**
```kotlin
val group = mapRemoteToLocalGroup(remote, remoteUpdatedAt) ?: run {
    Log.e(TAG, "Reconcile[GROUP:${remote.id}]: SKIP (mapper returned null)")
    return ReconcileAction.SKIP
}
```

Applied to:
- `reconcileExpense()`
- `reconcileGroup()`
- `reconcileSettlement()`

### 4. Settlement Mapper Enhancement
Also added defensive parsing for `Settlement.amount` (was using raw `BigDecimal()` constructor):
```kotlin
val amount = parseAmount(remote.amount, "Settlement:${remote.id}") ?: return null
```

## Benefits

✅ **No silent corruption** - Malformed data is rejected, not transformed  
✅ **Explicit logging** - Every failure is logged with entity ID and malformed value  
✅ **Fail-soft** - Individual bad records don't crash the entire sync  
✅ **Consistent pattern** - Matches the existing `parseAmount` defensive strategy  
✅ **Correct conflict resolution** - No more 0L timestamps breaking LWW logic  
✅ **Accurate cursor tracking** - Sync cursor advances correctly

## Error Handling Flow

1. **Parse failure** → Helper returns `null` + logs error
2. **Mapper receives null** → Returns `null` + logs skip reason
3. **Reconciliation receives null** → Skips record + logs entity ID
4. **Sync continues** → Other records still process normally

## Observability

All failures are logged at `Log.e` level with:
- Entity type and ID
- Malformed value (for debugging)
- Exception details
- Clear skip reason

Example log:
```
E/PullSyncService: Failed to parse ISO-8601 date: '2023-13-45T99:99:99Z'
E/PullSyncService: Skipping expense abc123: invalid date '2023-13-45T99:99:99Z'
E/PullSyncService: Reconcile[EXPENSE:abc123]: SKIP (mapper returned null - malformed data)
```

## Testing Recommendations

1. **Unit test** malformed timestamp handling in mappers
2. **Integration test** sync with poisoned data from server
3. **Monitor** production logs for parse failures (indicates server data quality issues)
