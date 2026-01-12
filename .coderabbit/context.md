# SplitEase — Architectural Contracts for Review Bots

| Version | Last Updated | Applies To |
|---------|--------------|------------|
| 1.0 | 2026-01-12 | SplitEase Android App |

This repository follows **strict architectural contracts**. Automated review feedback **MUST respect** the following rules. These contracts are authoritative and override heuristic-based suggestions.

---

## 1. Authentication & Logout Contract

### AuthManager.logout() is a Blocking Boundary

- `AuthManager.logout()` is a **suspending, completion-guaranteed boundary**.
- When `logout()` returns, **ALL logout side effects are complete**:
  - Tokens cleared from secure storage
  - Identity-link state reset
  - userProfile cleared (`null`)
  - authState = `AuthState.Unauthenticated`
- UI code is **allowed to navigate immediately** after invoking logout.
- **Do NOT** require UI to observe `AuthState` to infer logout completion.
- **Do NOT** suggest adding completion callbacks or result flows for logout.

### AuthManager.updateProfile() / updateEmail()

- These are **network-bound suspend functions** that return `Result<Unit>`.
- Success/failure is communicated via the `Result` return type.
- `userProfile` StateFlow is updated internally on success (for name).
- Email changes require verification — **email in userProfile does NOT change until verified**.

### AuthState Observable

- `AuthState` is a **read-only observable**, not a completion signal.
- UI may observe it for display purposes.
- UI **MUST NOT** use AuthState transitions to infer operation completion.
- Navigation is **command-driven**, not inferred from auth state.

### ViewModel Responsibility (Logout)

- ViewModels MUST invoke `AuthManager.logout()` from a coroutine (`viewModelScope`).
- ViewModels MUST NOT expose fire-and-forget logout APIs to the UI.
- UI code may call `viewModel.logout()` synchronously.
- Completion semantics are enforced at the **ViewModel → AuthManager boundary**.
- **Do NOT** suggest UI-level await/observe patterns for logout.

---

## 2. StateFlow Mutation Contract

### Single-Writer Semantics

- StateFlows are mutated **only by ViewModels**.
- Each StateFlow has a **single logical owner** (ViewModel).
- External consumers observe only; they do not mutate.

### Atomic vs Snapshot Updates

| Use Case | Allowed Pattern |
|----------|----------------|
| Concurrent updates possible | `_uiState.update { it.copy(...) }` |
| Sequential, single-threaded | `_uiState.value = currentState.copy(...)` |
| Reading for guards/conditions | `val state = _uiState.value` |

- **Atomic `update {}`** is used when concurrency exists (e.g., inside coroutines).
- **Snapshot `.copy()`** is allowed ONLY when:
  - Mutation happens on a single coroutine
  - No other concurrent writers exist
  - The mutation occurs before any suspension point

---

## 3. Room Migration Contract

### All Schema Bumps Require Explicit Handling

- Every database version increment **MUST** include:
  - An explicit `Migration(oldVersion, newVersion)` **OR**
  - An explicit destructive migration policy (`.fallbackToDestructiveMigration()`)

### Cross-File Registration is Valid

- Migrations **may be declared outside** `AppDatabase.kt`.
- Migrations are **registered in** `DatabaseModule.kt` via `.addMigrations()`.
- **Reviewers MUST check** `DatabaseModule.kt` for migration registration.
- **Do NOT** flag missing migrations based solely on `AppDatabase.kt` inspection.

### Current Migration Chain

```text
Version 2 → 3: Add expenseDate, trip dates, sync status
Version 3 → 4: No-op (enum type converters)
Version 4 → 5: Add failureType to sync_operations
Version 5 → 6: Add createdByUserId/lastModifiedByUserId columns
Version 6 → 7: Add connection_states table
```

---

## 4. Navigation Contract

### Command-Driven Navigation

- Navigation is **command-driven**, not inferred from state.
- NavController operations are called explicitly from Composables.
- **Do NOT** suggest observing StateFlows to trigger navigation.

### Navigation Callbacks

- `onLogout: () -> Unit` — Called after logout completes.
- `onNavigateToLogin: () -> Unit` — Called when login is required.
- `onNavigateToHome: () -> Unit` — Called after successful auth.

These are **one-shot commands**, not reactive observers.

---

## 5. Data Layer Contracts

### Data Ownership Rules

| Data | Owner | Storage |
|------|-------|---------|
| Email, Name | Supabase Auth | AuthManager (in-memory) |
| Currency, Timezone | App | Preferences DataStore |
| Expenses, Groups, Settlements | App (offline-first) | Room |
| Tokens | App (secure) | EncryptedSharedPreferences |

### Do NOT:
- Store name/email in Room.
- Persist userProfile to disk.
- Poll `/auth/v1/user` endpoint.
- Create new Room tables for preferences.

### Email Change Behavior (LOCKED)

- After `updateEmail()` success, UI **MUST continue to display OLD email**.
- New email is reflected **only after verification + token refresh**.
- **Do NOT** suggest optimistic updates for email changes.

---

## 6. Invite & Connection Contracts

### PendingInviteStore

- `get()` — Reads token without clearing (allows retry on failure).
- `clear()` — Removes token permanently.
- Token is cleared **only on successful claim**, not on read.

### Claim Flow

```text
Deep Link → Store Token → Load VM (read only) → Claim API → Clear Token (on success only)
```

- **Do NOT** suggest consuming token on ViewModel init.
- **Do NOT** suggest clearing token before claim completion.

---

## 7. Sync Invariants

### Idempotency Rule

> For any SyncOperation payload with ID = X, applying it N times must result in the same final database state as applying it once.

- All DAOs use `OnConflictStrategy.REPLACE` for idempotency.
- At-least-once delivery semantics are assumed.
- Duplicate operations are safe.

---

## 8. Code Style & Patterns

### Kotlin Conventions

- Use `Result<T>` for failable operations (not exceptions).
- Use `sealed class` for UI state.
- Use `StateFlow` for observable state, `SharedFlow` for events.
- Use `suspend fun` for async operations.

### ViewModel Patterns

```kotlin
// UI State
data class MyUiState(
    val isLoading: Boolean = false,
    val data: List<Item> = emptyList(),
    val error: String? = null
)

// Events (one-shot)
sealed class MyEvent {
    object NavigateToDetails : MyEvent()
    data class ShowSnackbar(val message: String) : MyEvent()
}
```

### Avoid:

- LiveData (use StateFlow)
- RxJava (use Kotlin Coroutines + Flow)
- Callbacks for async operations (use suspend/Result)
- God ViewModels (split by feature)

---

## 9. Dependency Injection

### Hilt Modules

| Module | Responsibility |
|--------|----------------|
| `AppModule` | Application-scoped singletons (DataStore, Preferences) |
| `DatabaseModule` | Room database and DAOs |
| `NetworkModule` | Retrofit, OkHttp, API services |
| `AuthModule` | Auth-related services and managers |
| `ConnectionModule` | Connection/invite API services |

### Provider Rules

- DAOs are provided via `DatabaseModule`.
- Managers/Repositories are `@Singleton` scoped.
- ViewModels are `@HiltViewModel` annotated.

---

## 10. Testing Guidelines

### Unit Test Scope

- ViewModels: Test state transitions and Result handling.
- Repositories: Test data transformation, not Room queries.
- UseCases: Test business logic in isolation.

### Do NOT Test:

- Room-generated code.
- Hilt injection wiring.
- Compose UI rendering (use manual/snapshot tests).

---

## 11. Review Tool Policy

### Automated Reviews Are Advisory Only

Architectural contracts documented in this repository **override** tool suggestions.

### CodeRabbit May Review:

- ✅ Null-safety issues
- ✅ Threading mistakes
- ✅ Missing migrations (cross-file check required)
- ✅ Incorrect Flow usage
- ✅ Obvious lifecycle leaks
- ✅ Security concerns

### CodeRabbit MUST NOT:

- ❌ Redesign navigation flow
- ❌ Change auth semantics
- ❌ Impose reactive patterns where contracts exist
- ❌ Refactor architecture boundaries
- ❌ Suggest pattern changes that contradict this document

---

## 12. False Positive Dismissal Template

When dismissing suggestions, use this format:

```text
Dismissed: False positive.

Reason:
- [Contract reference, e.g., "logout() is a suspending, completion-guaranteed boundary"]
- [Implementation detail, e.g., "No async work is launched during logout"]
- [Architecture reference, e.g., "Navigation after logout is explicitly supported"]

No action required.
```

---

## CTO Principle

> **Tools must adapt to architecture — not the other way around.**
