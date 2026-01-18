# SplitEase – Development Status Report

*as of 2026‑01‑18 22:30 +05:30*

---

## 1. Overview

SplitEase is a Kotlin‑based Android application that enables users to split expenses and manage group finances with an **offline-first, multi-device sync engine**. The architecture has evolved from a simple mock-synced local DB to a sophisticated **Supabase-backed ledger system**.

The current development effort (Sprint 19) focused on **Multi-Device Write Promotion**, **Read-only Hydration**, and **Crash-Safe Integrity**. This includes deterministic ledger replaying, per-device write serialization (Mutex-gating), and authoritative device role management (REPLICA vs PROMOTED).

---

## 2. Implemented Features

### 2.1 Multi-Device Ledger Sync (Sprints 17-19)

| Component | What was implemented | Key details |
|-----------|----------------------|--------------|
| **Supabase Ledger Mirror** | Append-only `ledger_operations` table. | Provides a durable, idempotent cloud mirror for all device-local mutations. |
| **Deterministic Hydration** | Convergence-based Replay Engine. | Allows NEW devices to pull and replay the global ledger to reconstruct local state with 100% fidelity. |
| **Write Serialization** | `LedgerWriteGate` (Mutex-based). | Guarantees strict per-device ordering for logical clocks, preventing clock collisions during local writes. |
| **Promotion State Machine** | `REPLICA` → `PROMOTED` transition. | Explicit, crash-safe workflow for a read-only device to become an authoritative writer. |
| **Fail-Closed Authorization** | Robust `DeviceRoleManager`. | Corrupted or unknown roles default to `REPLICA`, preventing unauthorized privilege escalation. |

### 2.2 Integrity & Hardening (Sprint 19 Audit)

| Feature | Status | Key details |
|---------|--------|-------------|
| **Crash-Safe Promotion** | ✅ Implemented | `recoverPromotionIfNeeded` repairs partial commits (COMPLETED state with REPLICA role). |
| **Error Differentiation** | ✅ Implemented | `LedgerSetComparator` distinguishes transient network errors from genuine ledger mismatches. |
| **Mutex Lifecycle Safety** | ✅ Implemented | Fixed illegal state crashes by tracking explicit lock acquisition state in `HydrationCoordinator`. |
| **Identity Restoration** | ✅ Implemented | `AuthManager` restores cached profiles on bootstrap, ensuring immediate identity availability. |

### 2.3 Core Features (Stable)

| Area | Features | Status |
|------|----------|--------|
| **Authentication** | Real Supabase Auth (JWT), Token Refresh, Interceptors | ✅ Stable |
| **Expense Management** | CRUD, Splits (Equal/Exact/%), Settlement recording | ✅ Stable |
| **Group Operations** | Creation, Member Management, Permission Guards | ✅ Stable |
| **UI/UX** | M3 Scaffold, Dashboard, Groups, Activity, Sync Status | ✅ Stable |

---

## 3. Issues Encountered & Resolutions

| Issue | Description | Resolution |
|-------|-------------|------------|
| **Dual-Write Crash Window** | Device marked as PROMOTED in state but still REPLICA in role after crash. | Implemented recovery logic that re-drives the promotion to completion on startup. |
| **Transient Promotion Failure** | Network drops during ledger check caused terminal `FAILED_PERMANENTLY`. | Introduced `LedgerSetComparison.Error` to handle transient failures as retryable. |
| **Auth Bootstrapping Gap** | UI showed "Unknown" during cold start while waiting for network auth. | Implemented local profile caching and restoration during early bootstrap. |
| **Safe Role Mapping** | DataStore corruption could potentially default a device to PRIMARY. | Refactored mapping to fail-closed (`REPLICA`) and log corruption loudly. |

---

## 4. Current Stage: Production Hardening

The system has successfully moved from a single-device POC to a robust multi-device distributed system. The focus is now on finalizing the "Promotion UI" and comprehensive edge-case testing for concurrent multi-device edits.

---

**All work described in Section 2 has been implemented, compiled, and verified via automated test suites. Sprint 19 is considered integrated and hardened.**

