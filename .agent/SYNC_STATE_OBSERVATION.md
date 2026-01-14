# Sync State Observation Enhancement

## Problem
The `DashboardViewModel.triggerSync()` method used a hardcoded 2-second delay to reset the `isSyncing` flag, which could:
- Reset too early (sync still running) → UI shows "done" while work continues
- Reset too late (sync already finished) → UI shows spinner longer than needed
- Break on different network conditions or device speeds

## Solution
Replaced the arbitrary delay with Flow-based observation of WorkManager's actual completion state.

## Changes Made

### 1. SyncRepository Interface (`SyncRepository.kt`)
**Added:**
- `observeManualSyncWork(): Flow<Boolean>` - Returns a Flow that emits `true` when sync work is finished

### 2. SyncRepositoryImpl (`SyncRepository.kt`)
**Added:**
- `MANUAL_SYNC_WORK_TAG` constant for tagging manual sync work
- Updated `triggerManualSync()` to:
  - Create work request with the tag
  - Use `ExistingWorkPolicy.REPLACE` for spam-safety
- Implemented `observeManualSyncWork()`:
  - Uses `WorkManager.getWorkInfosByTagFlow()`
  - Maps to boolean: `workInfo.state.isFinished`
  - Returns `true` when no work exists (initial state)

**Import added:**
- `kotlinx.coroutines.flow.map`

### 3. DashboardViewModel (`DashboardViewModel.kt`)
**Updated `triggerSync()`:**
- Removed `try/finally` block with `delay(2000)`
- Added Flow collection that observes `observeManualSyncWork()`
- Sets `isSyncing = false` only when work actually finishes
- Early return from collection prevents unnecessary updates

## Benefits

✅ **Deterministic** - UI state now reflects actual sync lifecycle  
✅ **Responsive** - Spinner disappears immediately when sync completes  
✅ **Reliable** - No race conditions or premature state resets  
✅ **Observable** - Can easily add error handling or progress tracking  
✅ **Maintainable** - No magic numbers; centralized sync state logic

## Technical Details

**Flow Behavior:**
- `getWorkInfosByTagFlow()` emits whenever WorkInfo changes
- `isFinished` is true for: SUCCEEDED, FAILED, CANCELLED states
- When work is REPLACED, the old work becomes CANCELLED (triggers finish)
- Initial emission returns `true` (no work = finished state)

**Thread Safety:**
- All Flow operations run on main thread (WorkManager LiveData conversion)
- ViewModel collection happens in `viewModelScope`
- State updates are sequential (no concurrent modification)

## Future Enhancements
- Add error state handling (check `WorkInfo.state == FAILED`)
- Extract failure reason from `WorkInfo.outputData`
- Add sync duration metrics for analytics
- Surface sync errors in UI
