# SplitEase System Grounding Report — Post-Sprint 28.5

## 1. System State Snapshot

| Component | Current State | Logic Source | Guarantees |
|:--- |:--- |:--- |:--- |
| **Ledger (Local)** | Room `ledger_operations` | `LedgerDao.kt` | Immutability, Idempotency, Local Monotonicity |
| **Replay Engine** | **Dependency-Aware** | `ReplayEngineImpl.kt` | Determinism (Deferred apply until users/groups exist) |
| **Identity Management**| **Ledger-First** | `IdentityBootstrapper.kt`| Zero Orphans (User.Create emitted at bootstrap) |
| **Conflict Detection**| **Post-Replay** | `ConflictDetector.kt` | Canonical Identification (SHA-256 length-prefixed) |
| **Resolution Logic**| **Ledger History** | `ReplayEngine` (Ingest) | Choice is a ledger fact, not transient metadata |
| **Derivation Layer** | **Read-Path Filter** | `Repository` layer | Effective state projection (Zombies Hidden) |
| **Sync Layer** | **Auth-Gated** | `SyncWorker.kt` | No sync until cloud identity is bound (Sprint 28) |
| **Replay Performance** | **O(N*M) Debt** | `ReplayEngine.kt` | Known bottleneck for large ledgers; Cache planned for Sprint 30 |
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

### 🧩 Identity Grounding (Sprint 28.5)
**Observation**: Identity references must be durable and ordered.
*   **Identity Integrity**: User identities are now emitted into the ledger via `USER.CREATE` at the moment of registration. 
*   **Replay Determinism**: `ReplayEngine` blocks any operation referencing a user (Payer, Member, etc.) until that user's creation operation has been applied locally.
*   **Zero Orphans**: By treating the user as a ledger dependency, we guarantee that no expense or settlement can exist in the database without a valid owner identity.

---

## 3. Validation Summary

| Question | Answer | Evidence |
|:--- |:--- |:--- |
| Can a new device hydrate its state? | **YES** | `LedgerSyncCoordinator` pulls everything on first sync. |
| Are orphaned user IDs allowed? | **NO** | `ReplayEngine` defers until prerequisite user ops arrive. |
| Is local user creation ledgered? | **YES** | `IdentityBootstrapper` emits `USER.CREATE` via `insertUserWithLedger`. |
| Is the system vulnerable to DB wipe? | **NO** | Logout clears data, but login re-hydrates everything from the ledger. |
| Is the server assumed to be "smart"? | **NO** | Server is a dumb append-only storage tier; logic remains in the client. |

---

## 4. Current Status: Universal Person Pool Ready
The "Dumb Courier" architecture is hardened. The system is now resilient to out-of-order operation ingestion and guarantees identity integrity. This completes the prerequisites for the **Universal Person Pool** (Sprint 29), where non-contact identities will be managed via the same ledger-driven dependency model.

**Constraint Freeze**:
*   "Room is SSOT"
*   "Ledger is append-only"
*   "Replay is truth"
*   "Resolution is history"
*   "Identity is a dependency"
*   "Supabase is durable exchange"

---
*Status: Verified Correct (Post-Sprint 28.5).*
