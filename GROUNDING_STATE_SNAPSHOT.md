# SplitEase System Grounding Report — Post-Sprint 21

## 1. System State Snapshot

| Component | Current State | Logic Source | Guarantees |
|:--- |:--- |:--- |:--- |
| **Ledger (Local)** | Room `ledger_operations` | `LedgerDao.kt` | Immutability, Idempotency, Local Monotonicity |
| **Replay Engine** | **Pure Execution** | `ReplayEngineImpl.kt` | Determinism (Unconditional apply, post-sort) |
| **Conflict Detection**| **Post-Replay** | `ConflictDetector.kt` | Canonical Identification (SHA-256 length-prefixed) |
| **Resolution Logic**| **Ledger History** | `ReplayEngine` (Ingest) | Choice is a ledger fact, not transient metadata |
| **Derivation Layer** | **Read-Path Filter** | `Repository` layer | Effective state projection (Zombies Hidden) |
| **Sync Layer** | **Push-Only Dormant** | `LedgerPushWorker.kt` | Atomic cursor tracking, Batching supports 50 ops |
| **Supabase Status** | **Disconnected** | `SplitEaseApi.kt` | Endpoints defined but no backend tables exist |

---

## 2. Refined Principles (Grounding Adjustments)

### ⚖️ Resolution Invariant
**Correction**: The system does **not** rely on "database arbitration" (First-wins at DB). 
*   **Reality**: Resolution interpretation is **Derived from Ledger Order**. 
*   While `ConflictResolutionDao` uses `INSERT OR IGNORE` to persist the first *historical* resolution encountered, the actual visibility logic in the Repositories looks up these choice facts and reconciles them against the entity snapshots in the ledger.

### 🚀 PushWorker "Ticking Side Effect"
**Observation**: `LedgerPushWorker` is scheduled on every write.
*   **Current Failure Mode**: If Supabase returns a 404 (terminal), the worker returns `Result.failure()`. 
*   **Risk**: If Supabase tables are created late, existing local operations will only sync upon the *next* write (which triggers a new schedule) or a manual sync trigger.
*   **Startup**: Does not block app startup; runs best-effort in background.

### 🧩 Schema Parity Contract
**Constraint**: The cloud schema MUST be a byte-for-byte reflection of the client.
*   No auto-generated timestamps for ordering (Logical Clock is authoritative).
*   No server-side resolution logic.
*   Deterministic JSON fields (BigDecimal as String).

---

## 3. Validation Summary

| Question | Answer | Evidence |
|:--- |:--- |:--- |
| Can I delete Supabase without breaking correctness? | **YES** | Local state derived entirely from local ledger. |
| Can I replay from zero on a fresh install? | **YES** | `ReplayEngine` is restart-safe. |
| Can two devices converge deterministically? | **YES** | Provided they share the same ledger prefix. |
| Can resolution be audited end-to-end locally? | **YES** | Resolutions are immutable ledger operations. |
| Is the server assumed to be "smart"? | **NO** | Server is a dumb append-only storage tier. |

---

## 4. Next Step: Supabase Ledger Mirror
The system is now grounded. We are ready to implement the cloud durability layer with the explicit knowledge that it serves **exchange and durability only**, never authority.

**Constraint Freeze**:
*   "Room is SSOT"
*   "Ledger is append-only"
*   "Replay is truth"
*   "Resolution is history"
*   "Supabase is non-authoritative"

---
*Status: Verified Correct.*
