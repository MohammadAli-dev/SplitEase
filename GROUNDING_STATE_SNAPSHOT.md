# SplitEase System Grounding Report — Post-Sprint 23

## 1. System State Snapshot

| Component | Current State | Logic Source | Guarantees |
|:--- |:--- |:--- |:--- |
| **Ledger (Local)** | Room `ledger_operations` | `LedgerDao.kt` | Immutability, Idempotency, Local Monotonicity |
| **Replay Engine** | **Pure Execution** | `ReplayEngineImpl.kt` | Determinism (Unconditional apply, post-sort) |
| **Conflict Detection**| **Post-Replay** | `ConflictDetector.kt` | Canonical Identification (SHA-256 length-prefixed) |
| **Resolution Logic**| **Ledger History** | `ReplayEngine` (Ingest) | Choice is a ledger fact, not transient metadata |
| **Derivation Layer** | **Read-Path Filter** | `Repository` layer | Effective state projection (Zombies Hidden) |
| **Sync Layer** | **Bi-Directional** | `SyncWorker.kt` | Push (v17) + Incremental Pull (v23) integrated |
| **Supabase Status** | **Authoritative Mirror** | `SplitEaseApi.kt` | Durable exchange layer; PII (Profiles) + Ops |

---

## 2. Refined Principles (Grounding Adjustments)

### ⚖️ Resolution Invariant
**Observation**: The system successfully suppresses "losers" during replay. 
*   **Reality**: Resolution interpretation is **Derived from Ledger History**. 
*   Conflict resolutions are append-only facts. ReplayEngine scans for resolutions before execution to ensure that only the "winning" operation affects the entity tables, while the "losing" operation remains as a historical fact in the ledger.

### 🚀 Incremental Pull & Convergence
**Observation**: `SyncWorker` now pulls remote updates.
*   **Mechanism**: `LedgerSyncCoordinator` pulls all remote operations and persists them locally via `INSERT OR IGNORE`.
*   **Convergence**: After ingestion, a full `ReplayEngine` cycle occurs. This ensures that every device, regardless of whether it was the author or the observer, converges to the exact same state.
*   **Swipe-to-Refresh**: Provides the user with a direct, non-blocking way to trigger this reconciliation.

### 🧩 Identity Hydration Contract
**Observation**: Convergence requires a local user identity.
*   **Constraint**: The `ReplayEngine` requires a "Personal Group" to exist for financial consistency.
*   **Fix**: `IdentityBootstrapper` ensures that the current user's identity and personal group are present before the first replay cycle, preventing convergence deadlocks.

---

## 3. Validation Summary

| Question | Answer | Evidence |
|:--- |:--- |:--- |
| Can a new device hydrate its state? | **YES** | `LedgerSyncCoordinator` pulls everything on first sync. |
| Are remote conflicts detected? | **YES** | `ConflictDetector` runs after every `ReplayEngine` cycle. |
| Is user PII synced? | **YES** | User profiles are lazily hydrated during the ledger pull sync. |
| Is the system vulnerable to DB wipe? | **NO** | Logout clears data, but login re-hydrates everything from the ledger. |
| Is the server assumed to be "smart"? | **NO** | Server is a dumb append-only storage tier; logic remains in the client. |

---

## 4. Current Status: Production Ready Durability
The "Dumb Courier" architecture is now complete and verified. Every change on one device is mirrored to the cloud and eventually converges on all other devices, with explicit conflict detection providing diagnostic visibility for multi-device operations.

**Constraint Freeze**:
*   "Room is SSOT"
*   "Ledger is append-only"
*   "Replay is truth"
*   "Resolution is history"
*   "Supabase is durable exchange"

---
*Status: Verified Correct (Post-Sprint 23).*
