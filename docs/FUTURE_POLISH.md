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

*Nothing here blocks or weakens Sprint 10B.*
