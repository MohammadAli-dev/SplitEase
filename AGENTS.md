# SplitEase — Agent Development Guide

| Version | Last Updated | Applies To |
|---------|--------------|------------|
| 1.0 | 2026-01-13 | SplitEase Android App |

This guide provides build commands, code style guidelines, and architectural contracts for agentic coding agents working on this repository.

---

## 1. Build Commands

### Core Commands
```bash
# Build the app
./gradlew assembleDebug

# Clean build
./gradlew clean assembleDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.splitease.ui.expense.AddExpenseViewModelTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.splitease.ui.expense.AddExpenseViewModelTest.testAddExpense"
```

### Lint & Type Check
```bash
# Run lint checks
./gradlew lintDebug

# No separate typecheck command - Kotlin compilation handles type checking
```

### Database & Sync Debugging
```bash
# Force WorkManager execution (via Android Studio Background Task Inspector)
# Check sync_operations table via Database Inspector
```

---

## 2. Code Style Guidelines

### 2.1 Import Organization
```kotlin
// Standard library imports first
import java.math.BigDecimal
import java.util.Date

// Android/AndroidX imports
import androidx.lifecycle.ViewModel
import androidx.compose.material3.Text

// Project imports (grouped by feature)
import com.splitease.data.local.entities.Expense
import com.splitease.domain.SplitValidator
import com.splitease.ui.expense.AddExpenseUiState
```

### 2.2 Naming Conventions
- **Classes**: PascalCase (e.g., `AddExpenseViewModel`, `SplitValidator`)
- **Functions**: camelCase (e.g., `calculateBalance`, `validateSplit`)
- **Variables**: camelCase (e.g., `uiState`, `expenseAmount`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_EXPENSE_AMOUNT`)
- **Sealed classes/enums**: PascalCase (e.g., `SplitType`, `AuthState`)

### 2.3 Type Safety
- **Nullable types**: Explicitly declare nullable types with `?`
- **Non-null assertions**: Avoid `!!` - use safe calls `?.` or `let`
- **Smart casting**: Let Kotlin handle smart casting when possible

### 2.4 BigDecimal Rules (CRITICAL)
```kotlin
// ✅ CORRECT - Use compareTo for comparisons
if (amount.compareTo(BigDecimal.ZERO) > 0) { ... }

// ❌ FORBIDDEN - No operator overloading for comparisons
if (amount > BigDecimal.ZERO) { ... }

// ✅ CORRECT - Specify scale and rounding mode
amount.divide(divisor, 2, RoundingMode.HALF_UP)
```

### 2.5 StateFlow Patterns
```kotlin
// ViewModel state management
private val _uiState = MutableStateFlow(AddExpenseUiState())
val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

// Atomic updates (concurrent access)
_uiState.update { it.copy(isLoading = true) }

// Snapshot updates (single coroutine)
_uiState.value = _uiState.value.copy(isLoading = false)
```

### 2.6 Error Handling
```kotlin
// Use kotlin.Result<T> for failable operations
suspend fun createExpense(expense: Expense): Result<Unit> {
    return try {
        expenseRepository.insert(expense)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// Handle Result in ViewModels using idiomatic extension functions
viewModelScope.launch {
    createExpense(expense)
        .onSuccess {
            _uiState.update { it.copy(isLoading = false) }
        }
        .onFailure { error ->
            _uiState.update { it.copy(error = error.message) }
        }
}

// Alternative: Use fold for explicit branching
viewModelScope.launch {
    createExpense(expense).fold(
        onSuccess = { _uiState.update { it.copy(isLoading = false) } },
        onFailure = { error -> _uiState.update { it.copy(error = error.message) } }
    )
}

// Check result status without consuming
viewModelScope.launch {
    val result = createExpense(expense)
    if (result.isSuccess) {
        // Handle success
    } else {
        // Handle failure: result.exceptionOrNull()
    }
}
```

---

## 3. Architectural Rules

### 3.1 Layer Boundaries
- **UI Layer**: Only observes StateFlow from ViewModels
- **ViewModel Layer**: Orchestrates state, calls repositories
- **Domain Layer**: Pure business logic, no Android dependencies
- **Data Layer**: Room database and network operations

### 3.2 Dependency Injection
```kotlin
// ✅ CORRECT - Explicit return types in Hilt modules
@Provides
fun provideExpenseDao(db: AppDatabase): ExpenseDao {
    return db.expenseDao()
}

// ❌ FORBIDDEN - Type inference in DI modules
@Provides
fun provideExpenseDao(db: AppDatabase) = db.expenseDao()
```

### 3.3 Room Database
- **Single Source of Truth**: All UI data comes from Room
- **Offline-First**: Write to Room first, sync later
- **Migrations**: Must be explicit in DatabaseModule

### 3.4 Sync Operations
- **Write-Ahead Logging**: Create SyncOperation before API call
- **Idempotency**: All operations must be safe to retry
- **Background Only**: Sync runs in WorkManager, never UI thread

---

## 4. Technology Stack

### 4.1 Core Dependencies
- **Kotlin**: 1.9.0 (avoid 1.9+ features like `Enum.entries`)
- **Compose**: Material 3 with BOM (no hardcoded versions)
- **Hilt**: 2.48 with KSP (no KAPT)
- **Room**: 2.6.0 with KSP
- **WorkManager**: 2.9.0 for background sync

### 4.2 Version Management
- **All versions in `libs.versions.toml`**
- **Use BOM for Compose dependencies**
- **No hardcoded versions in build.gradle.kts**

---

## 5. Testing Guidelines

### 5.1 Unit Tests
```kotlin
// Test ViewModel state transitions
@Test
fun `when expense added, should update UI state`() {
    // Given
    val initial_state = viewModel.uiState.value
    
    // When
    viewModel.addExpense(testExpense)
    
    // Then
    assertEquals(expected_state, viewModel.uiState.value)
}
```

### 5.2 Test Structure
- **Unit tests**: `app/src/test/` - ViewModels, domain logic
- **Instrumented tests**: `app/src/androidTest/` - Room DAOs, sync
- **Test naming**: Descriptive with backticks for Kotlin

---

## 6. Navigation Patterns

### 6.1 Command-Driven Navigation
```kotlin
// In Composable
onClick = {
    onNavigateToGroupDetail(groupId)
}

// Navigation callback type
typealias OnNavigate = (String) -> Unit
```

### 6.2 Route Definitions
```kotlin
sealed class Screen(val route: String) {
    object Groups : Screen("groups")
    data class GroupDetail(val groupId: String) : Screen("group_detail/{groupId}")
}
```

---

## 7. Forbidden Patterns

### 7.1 ❌ Never Do
- Access DAOs directly from Composables
- Use `Double` for money calculations
- Observe network responses in UI
- Use `LiveData` (prefer `StateFlow`)
- Add hardcoded dependency versions
- Use KAPT (KSP only)

### 7.2 ✅ Always Do
- Write to Room first, sync later
- Use `BigDecimal` for all money
- Expose `StateFlow` from ViewModels
- Use explicit return types in DI modules
- Handle errors with `Result<T>`

---

## 8. Build Configuration

### 8.1 JDK Requirements
- **JDK 17 or 21** (NOT 25)
- Set in `gradle.properties` if needed
- Gradle Wrapper is mandatory

### 8.2 Gradle Properties
```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.java.home=C:\\Program Files\\Java\\jdk-21
ksp.incremental=false
```

---

## 9. Debugging Tips

### 9.1 Common Issues
- **Build fails**: Check for KSP errors, ensure JDK 17/21
- **Sync not working**: Check `sync_operations` table, WorkManager status
- **Room schema errors**: Verify migrations in DatabaseModule

### 9.2 Useful Logcat Tags
- `SyncRepository` - Sync operations
- `SyncWorker` - Background sync
- `AuthRepository` - Authentication
- `ExpenseDao` - Database operations

---

## 10. Code Review Checklist

Before submitting code, verify:
- [ ] `./gradlew assembleDebug` passes
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] BigDecimal comparisons use `.compareTo()`
- [ ] No hardcoded dependency versions
- [ ] Explicit return types in Hilt modules
- [ ] StateFlow mutations are atomic or snapshot-safe
- [ ] Error handling uses `Result<T>`
- [ ] No forbidden patterns (DAO access, etc.)

---

## 11. Architectural Contracts

This repository follows strict architectural contracts documented in:
- `ARCHITECTURE_GUARDRAILS.md` - Build and dependency rules
- `.coderabbit/context.md` - Detailed architectural contracts

**These contracts override automated tool suggestions.** When in doubt, follow the documented contracts over tool recommendations.

---

## 12. Emergency Commands

```bash
# Full clean rebuild
./gradlew clean
./gradlew assembleDebug

# Reset sync state (via Database Inspector)
# DELETE FROM sync_operations;

# Force sync (via Background Task Inspector)
# Find and run sync_now work
```

---

**Remember**: This is an offline-first app. The local database is always the source of truth. Network is an afterthought.