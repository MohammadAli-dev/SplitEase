# Antigravity Context Dump

This document serves as a comprehensive context dump for the SplitEase project. It outlines the architecture, core philosophy, guardrails, and the evolution of the project through its sprints.

## 1. Project Overview
**SplitEase** is a production-grade, offline-first Android expense sharing application designed to demonstrate modern Android architecture patterns. It allows users to manage shared expenses within groups, splitting costs effectively.

-   **Primary Stack**: Android (Kotlin), Jetpack Compose, Hilt, Room, Coroutines/Flow, WorkManager.
-   **Backend**: Transitioning from Mock Backend to Supabase.

## 2. Core Philosophy & Principles

### 2.1 Offline-First
-   **Room is SSOT**: The local Room database is the **Single Source of Truth** for the UI. The UI *never* waits for a network response to update.
-   **Immediate Feedback**: User actions (Add Expense, Create Group) are persisted locally immediately.
-   **Background Sync**: Network synchronization happens in the background via `WorkManager`. If the device is offline, operations are queued and processed when connectivity returns.

### 2.2 Zero Data Loss
-   We prioritize data integrity. Local writes happen first.
-   Sync failures do not revert local state; they purely retry independently.

### 2.3 Strict Material 3
-   We use **Material 3** exclusively.
-   No mixing with Material 2 (M2) components.
-   Design system tokens (Typography, Color Scheme) are strictly followed.

### 2.4 Architecture Clarity
-   **Presentation**: ViewModel puts data into `StateFlow<UiState>`.
-   **Domain**: Pure Kotlin logic (e.g., `SplitValidator`, split algorithms).
-   **Data**: Repositories abstract the data sources. They expose `Flow` for reads and suspend functions for writes.
-   **Boundaries**: Repositories do not access `WorkManager` directly for scheduling; they use a `SyncScheduler` abstraction. Repositories do not expose `Retrofit` types to the domain.

## 3. Architecture & Design

### 3.1 Layered Architecture
```mermaid
graph TD
    UI[Compose UI] --> VM[ViewModel]
    VM --> Domain[Domain/UseCases]
    Domain --> Repo[Repository]
    Repo --> Local[Room Database (Local)]
    Repo --> Sync[SyncQueue]
    Sync --> WM[WorkManager]
    WM --> Remote[Supabase API]
```

### 3.2 Key Components
-   **Identity Management**: `IdentityBootstrapper` ensures a valid local user exists on startup. `LocalUserManager` manages the currently active user's ID.
-   **Authentication**: `AuthManager` handles Login/Logout/Refresh. `TokenManager` securely stores tokens. `AuthInterceptor` manages HTTP 401s and token injection.
-   **Data Persistence**: Complex relations (Expenses, Splits, Groups, Users) are modeled in SQL (Room) with Foreign Keys.
-   **Money Handling**: All monetary values use `BigDecimal` to prevent floating-point errors.

## 4. Guardrails (Non-Negotiable)
*See `ARCHITECTURE_GUARDRAILS.md` for the authoritative source.*

### 4.1 Build & Dependencies
-   **Gradle Wrapper Only**: Never use local Gradle distributions.
-   **Version Catalog**: All versions must be in `libs.versions.toml`.
-   **Compose BOM**: Always use the Bill of Materials for Compose.
-   **No KAPT**: Use KSP (Kotlin Symbol Processing) exclusively.

### 4.2 Coding Standards
-   **BigDecimal**: always use `.compareTo(BigDecimal.ZERO)`. Never use operators like `>` or `<` directly on BigDecimal if it risks stability issues across compiler versions.
-   **Hilt**: All `@Provides` methods must have explicit return types.
-   **Experimental APIs**: Must be explicitly opted-in. Avoid unstable Compose layouts if stable alternatives exist.

## 5. Sprint History & Evolution

### Sprint 0: Foundation
-   Initial project scaffolding.
-   Setup of Hilt, Gradle Version Catalog, and multi-module structure (conceptually).
-   Established basic "Hello World" compilable state.

### Sprint 1: Data Layer & Offline Core
-   Designed and implemented the Room schema.
-   Built DAOs and Repositories.
-   Established the offline-first pattern where Repositories return `Flow` from DB.

### Sprint 4: Stability & Regressions
-   Addressed build failures caused by Kotlin 1.9 and Compose BOM updates.
-   Formalized `ARCHITECTURE_GUARDRAILS.md` to prevent recurrence of these issues.

### Sprint 17: Supabase Ledger Mirror (Push Phase)
- **Concept**: Transitioned from entity-based sync to a more robust **ledger-operation** based sync.
- **Accomplished**:
    - Created `ledger_operations` table in Supabase.
    - Implemented `LedgerPushWorker` to mirror local mutations to cloud in a durable, append-only fashion.

### Sprint 18: Deterministic Hydration (Pull Phase)
- **Objective**: Enable secondary devices to join a group and reconstruct state via the ledger.
- **Accomplished**:
    - `LedgerPullService` for fetching global operations.
    - `ReplayEngine` with convergence-based retry logic for out-of-order dependency resolution.

### Sprint 19: Write Authority & Promotion (Multi-Device Phase)
- **Objective**: Allow read-only devices to become writers safely.
- **Accomplished**:
    - `LedgerWriteGate`: Exclusive mutex for serialized clock allocation and local writes.
    - `PromotionCoordinator`: Crash-safe workflow to elevate `REPLICA` devices to `PROMOTED` writers.
    - **Hardening Audit**: Integrated semantic error handling (`LedgerSetComparison.Error`), fail-closed role management, and mutex lifecycle fixes.

## 6. Current Status & Known Context
- **Stage**: The core "Sync Engine 2.0" (Ledger-based) is fully integrated and hardened.
- **Capability**: The app now supports secure, multi-device, offline-first synchronization with deterministic state reconstruction.
- **Identity**: Session recovery and identity restoration are stable, eliminating UI flickering on startup.
- **Resources**: 
    - `CURRENT_SPRINT_CHANGES.md` (detailed log of current work)
    - `SpliteaseStatusReport.md` (high-level executive summary)
    - `ARCHITECTURE_GUARDRAILS.md` (authoritative coding standards)
