# Architecture & Build Guardrails — SplitEase

> **NOTE:** Automated review tools must follow [`.coderabbit/context.md`](.coderabbit/context.md).

This document defines **non-negotiable rules** for build configuration, dependency management, and architectural boundaries. These guardrails exist to prevent known failure modes encountered during early project setup.

Violating these rules is considered a **breaking change** and must not be merged.

---

## 1. Dependency & Build System Guardrails

### 1.1 Gradle & JDK
- Gradle Wrapper is the **only supported Gradle**.
- JDK 17+ must be used.
- No local Gradle installations.
- Do not change Gradle or AGP versions without explicit review.

### 1.2 Version Management
- **All dependency versions must live in `libs.versions.toml`**
- No hardcoded versions in `build.gradle.kts`
- Compose **must use BOM** — never individual version pinning.

❌ **Forbidden:**
```kotlin
implementation("androidx.compose.material3:material3:1.2.0")
```

✅ **Required:**
```kotlin
implementation(platform(libs.androidx.compose.bom))
implementation(libs.androidx.material3)
```

### 1.3 Kotlin Language Features
- **Kotlin 1.8 Compatibility**: Avoid using language features stabilized in 1.9+ (like `Enum.entries`) unless the project Kotlin version is explicitly upgraded.
- Use `values()` instead of `entries` for enums to ensure backward compatibility with the current toolchain.

---

## 2. UI & Material 3 Guardrails (Critical)

### 2.1 UI System
This project uses Material 3 exclusively. MaterialComponents (M2) themes are forbidden.

### 2.2 Material 3 Component Availability
- **Check BOM Version**: Before using advanced components (e.g., `SegmentedButton`, `DatePicker`), verify that the `androidx-compose-bom` in `libs.versions.toml` points to a version providing **Material 3 1.2.0+**.
- If the current BOM is older, you must fallback to stable components (e.g., `FilterChip` rows instead of `SegmentedButton`).

### 2.3 Layout & Experimental APIs
- **Avoid Experimental Layouts**: Avoid `FlowRow` or `FlowColumn` unless strictly necessary and annotated with `@OptIn(ExperimentalLayoutApi::class)`. 
- Prefer stable layouts (`Column` or `Row` with scrolling) to minimize build-time volatility and dependency on specific Compose Foundation versions.

### 2.4 Icon Library
- **Default Icons Only**: Assume only `androidx.compose.material:material-icons-core` is available.
- If an icon is missing (e.g., `Remove`, `DeleteOutline`), do not add the full `material-icons-extended` library without approval. Use `Text` symbols or simple vector assets as fallbacks.

---

## 3. Data & Domain Guardrails

### 3.1 BigDecimal Arithmetic
- **Strict Comparison**: Always use `.compareTo(BigDecimal.ZERO)` for comparisons. Avoid relying on operator overloading (`>`, `<=`) which can be brittle across Kotlin compiler versions or mixed Java/Kotlin modules.
❌ **Forbidden:**
```kotlin
if (amount > BigDecimal.ZERO) { ... }
```

✅ **Required:**
```kotlin
if (amount.compareTo(BigDecimal.ZERO) > 0) { ... }
```

### 3.2 Precision
- Always specify `Scale` and `RoundingMode` (preferably `HALF_UP`) during division or complex calculations to avoid `ArithmeticException`.

---

## 4. Annotation Processing (Hilt / Room)

### 4.1 KSP-Only Rule
KAPT is forbidden. This project uses KSP only.

### 4.2 Hilt Provider Rules
All `@Provides` functions must declare explicit return types and use block bodies.

### 4.3 No Type Inference in DI
Type inference inside Hilt modules is forbidden. All bindings must be explicit.

---

## 5. Architectural Boundaries

### 5.1 Repository Rules
Repositories **never** talk to `WorkManager`, `Retrofit`, or API interfaces. They only persist local data and call domain services.

### 5.2 Sync Rules
- Sync is write-ahead logged.
- Sync execution is isolated to background workers.
- UI never triggers sync directly.

---
+
+## 6. Conflict Resolution Guardrails
+
+### 6.1 Sealed Execution
+Conflict resolution **must** be deterministic and user-driven. Heuristics or "smart" auto-resolutions are forbidden.
+
+### 6.2 Suppression-Based Model
+Conflicts are resolved by **suppressing** operations during replay. The ledger history is never modified, deleted, or re-written to resolve a conflict. 
+
+### 6.3 Derived State Only
+The `conflict_resolutions` table is strictly **derived state**.
+- It must be populated ONLY during ledger replay.
+- Direct writes from the UI/Repository to this table are forbidden.
+- The first resolution encountered for a `conflictId` wins (`INSERT OR IGNORE`).
+
+### 6.4 Write Preconditions
+Resolving a conflict requires:
+- Local existence of the conflict diagnostic.
+- Membership of the chosen operation in the conflict set.
+- Explicit write authority (`PRIMARY` or `PROMOTED`).
+
+---
+
+## 7. Change Safety Checklist (MANDATORY)
+
Before merging any PR, the author must verify:
- [ ] `./gradlew assembleDebug` passes.
- [ ] **Kotlin Compatibility**: No Kotlin 1.9+ features (like `entries`) added.
- [ ] **M3 Component Check**: All UI components are available in the current BOM version.
- [ ] **BigDecimal Safety**: Used `.compareTo()` for all amount checks.
- [ ] **Icon Check**: No `material-icons-extended` dependencies added.
- [ ] No new `kapt` usage.
- [ ] No hardcoded dependency versions.
- [ ] No implicit return types in Hilt modules.

---

## 7. Why This Exists

These guardrails were introduced/updated after:
- **Sprint 4A Regressions**: `SplitType.entries` (Kotlin 1.9) and `SegmentedButton` (M3 1.2) caused multiple build failures.
- **Icon Library Bloat**: Attempts to use extended icons without the corresponding dependency.
- **BigDecimal Toolchain Issues**: Comparison operator overloading causing cryptic KSP errors.
- **Hilt `error.NonExistentClass` failures**.

📌 **These rules prevent 95% of the configuration regressions encountered during the early project phases.**

---

## 8. UI State Management Rules

> **The Core Rule:** A ViewModel’s `_uiState` must **NEVER** be an input to the flow that produces `_uiState`.

### 8.1 Separation of State
ViewModels must strictly separate **Derived State** (from repositories) and **Transient UI State** (local flags like `isRefreshing`).

- **Derived State**: Produced via `combine(repoA, repoB...)`. Must be PURE and derived ONLY from authoritative data sources.
- **Transient State**: Held in separate `MutableStateFlow`s or simple variables. Examples: `isRefreshing`, `isDialogOpen`.

### 8.2 The Merge Pattern
UI State must be produced by merging the Base (Repository) state with the Transient state atomically using `update {}`.

```kotlin
// ✅ Correct Pattern
baseState.collectLatest { repoState ->
    _uiState.update { current ->
        repoState.copy(
            isRefreshing = current.isRefreshing // Preserve transient state
        )
    }
}
```

### 8.3 Anti-Patterns to Avoid
- ❌ `combine(repoFlow, _uiState) { ... }`: Creates feedback loops and race conditions.
- ❌ Setting `_uiState.value = ...` directly inside flows (lost updates).
- ❌ Deriving transient flags from repository data.

---

## 9. Identity & Replay Guardrails (Sprint 28.5)

### 9.1 Ledger-First Identity
User creation must be ledger-driven.
- **Rule**: Never insert a `User` row without a corresponding `USER.CREATE` `LedgerOperation`.
- **Enforcement**: Always use `db.insertUserWithLedger()` for the current user and `ReplayEngine` for remote users.

### 9.2 Dependency Deferral
The `ReplayEngine` is the authoritative owner of entity lifecycle.
- **Rule**: Operations referencing an entity (Expense -> User, Member -> Group) must wait for the parent/dependency entity to exist.
- **Implementation**: Handled via `canApplyOperation()` deferral logic. No "dummy" or "placeholder" entities should be created to satisfy FK constraints.

### 9.3 v1.0 Pre-Release Invariants
This codebase is under active development and has not been released.
- **Rule**: Ledger backfilling for "orphan" users (users without a `USER.CREATE` operation) is strictly forbidden for the v1.0 launch.
- **Justification**: Since there are zero pre-ledger users in the wild, adding "backfill" logic (e.g., in `IdentityBootstrapper`) creates dead code and unnecessary complexity.
- **Enforcement**: All first-time installs must follow the Pattern B (Immediate Write-Ahead) initialization path, ensuring ledger-compliance from day zero.

---

## 10. Replay Performance & Scaling

### 10.1 Dependency Check Optimization
As the ledger grows beyond v1.0 (5,000+ operations), the cost of synchronous database hits during the convergence loop becomes prohibitive.

- **The Problem**: `canApplyOperation` currently performs multiple `SELECT` queries per operation to check for user/group existence.
- **The Scaling Strategy**:
    - **Session Caching**: The `ReplayEngine` should pre-fetch all existing entity IDs into an in-memory `HashSet` at the start of the `replay()` session.
    - **State Mirroring**: New identities created *during* the replay batch must be updated in the memory set immediately to allow dependent operations in the same batch to proceed without re-querying the DB.
    - **Constraint**: Do not use Room's `@Relation` or complex Joins for existence checks; keep the "Dumb Courier" logic simple and memory-first.

---

## 11. Universal Identity Guardrails (Sprint 29)

### 11.1 Authority Invariant
In the v1.0 architecture, the `Person` entity is the sole authoritative domain identity.
- **Rule**: All new domain entities (Expenses, Settlements, Group Members) MUST use `personId` for participant identification.
- **Exception**: Historical data (pre-Sprint 29) may use legacy `userId`, which must be resolved via the **Dual-Read Fallback** implemented in repositories.

### 11.2 Link Immutability
The binding between a human `Person` and a security `User` is established via the ledger (`OP_LINK_USER`).
- **Rule**: Once a person is linked to a user, the `linkedUserId` is immutable via standard ledger replays. This prevents "identity drift" where financial history could accidentally be re-parented during a merge.
- **Enforcement**: Handled via `applyPersonOperation` in the `ReplayEngine`.

### 11.3 Fail-Fast Policy
Identity correctness is prioritized over system availability.
- **Rule**: If a mutation is attempted without a valid `personId`, or if a consolidated identity merge fails the audit, the system MUST throw `IdentityInvariantViolationException` and abort the operation/session.
- **Rationale**: In financial systems, a hard crash is safer than a silent data orphan.

---

## 12. Person-Centric Replay Guardrails

### 12.1 Convergence Guard (Dependency Branching)
The `ReplayEngine` must strictly enforce existence dependencies to prevent orphaned links.
- **Rule**: A `LINK_USER` operation MUST NOT be applied if the referenced `Person` does not yet exist in the local database.
- **Mechanism**: Such operations must be held in the in-memory **Deferral Queue** and retried in subsequent replay passes.

### 12.2 Single Source of Identity
The Replay Engine is a "Dumb Courier" of ledger facts.
- **Rule**: The engine must NEVER "invent" or backfill a `personId` for an expense. If the ledger payload is missing the ID, the engine persists it as-is (relying on Dual-Read at the repository layer).