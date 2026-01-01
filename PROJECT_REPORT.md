# SplitEase - Final Project Report

## Executive Summary

SplitEase is a production-grade, offline-first Android expense sharing application demonstrating modern Android architecture patterns. The app currently uses a mock backend to simulate network operations while maintaining a fully functional client-side implementation.

---

## Who This Project Is For

- **Android engineers** learning offline-first architecture
- **Developers** exploring WorkManager-based sync
- **Interview reviewers** evaluating system design skills
- **Founders** prototyping expense-sharing apps
- **Students** studying MVVM + Clean Architecture

---

## How SplitEase Works (In Simple Terms)

1. A user logs in (currently mocked — always succeeds).
2. The app stores **all data locally** on the phone using Room database.
3. When you add a group or expense:
   - It is saved **instantly** on the device.
   - A background job records the change in a **sync queue**.
4. If the phone is **offline**:
   - The app continues to work normally.
   - You can add expenses, view groups, everything.
5. When the internet becomes **available**:
   - Pending changes are synced to the server **automatically**.
   - The sync queue is processed in order (FIFO).

**Key Guarantee:** Your data is never lost, even if the app crashes or the phone restarts.

---

## 1. What We Have Built

### Core Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Authentication** | ✅ Complete (Mocked) | Login/Signup with token persistence |
| **Groups** | ✅ Complete | View expense groups |
| **Expenses** | ✅ Core Complete | Add expense + equal split + local persistence |
| **Equal Splits** | ✅ Complete | Automatic equal split calculation with BigDecimal |
| **Offline Mode** | ✅ Complete | Full functionality without internet |
| **Background Sync** | ✅ Complete | WorkManager-based reliable sync |
| **Navigation** | ✅ Complete | Back arrows on all detail screens |

---

## End-to-End Data Flow Example: Add Expense

```
User taps FAB on GroupDetailScreen
        │
        ▼
AddExpenseScreen collects title, amount, payer
        │
        ▼
ViewModel validates input (SplitValidator)
        │
        ▼
ViewModel calls ExpenseRepository.addExpense()
        │
        ├──▶ Insert Expense into Room
        ├──▶ Insert ExpenseSplits into Room
        └──▶ Create SyncOperation entry
                │
                ▼
        Room Flow emits → UI updates instantly
                │
                ▼
        WorkManager picks up SyncOperation
                │
                ▼
        SyncWorker calls POST /sync
                │
                ▼
        On success: SyncOperation deleted from queue
```

---

## Known Limitations (Current Stage)

| Feature | Status |
|---------|--------|
| Group creation UI | ❌ Not implemented |
| Member management (add/remove) | ❌ Pending |
| Expense editing | ❌ Not implemented |
| Expense deletion | ❌ Not implemented |
| Settlements UI | ❌ Modeled but not surfaced |
| Real authentication | ❌ Mocked |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                       │
│  Compose UI → ViewModel → StateFlow<UiState>                │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                      DOMAIN LAYER                           │
│  SplitValidator, SimplifyDebtUseCase (Pure Kotlin)          │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                       DATA LAYER                            │
│  Repositories → Room (SSOT) + Retrofit (Mocked)             │
│           → SyncOperation Queue → WorkManager               │
└─────────────────────────────────────────────────────────────┘
```

### Database Schema

| Table | Purpose |
|-------|---------|
| `users` | User profiles |
| `expense_groups` | Groups for expense sharing |
| `group_members` | User-Group relationships |
| `expenses` | Expense records |
| `expense_splits` | Individual shares per expense |
| `settlements` | Payment records |
| `sync_operations` | Pending sync queue |

---

## 2. Current Mock Backend

### How It Works

The app uses an **OkHttp Interceptor** to simulate backend responses without hitting a real server.

**Location:** `data/remote/MockAuthInterceptor.kt`

### Intercepted Endpoints

| Endpoint | Behavior |
|----------|----------|
| `POST /auth/login` | Returns fake UUID, token, user data |
| `POST /auth/signup` | Same as login |
| `POST /sync` | Always returns `{ "success": true }` |

### Mock Response Examples

```json
// Auth Response (Login/Signup)
{
  "userId": "random-uuid",
  "token": "mock_token_random-uuid",
  "name": "Test User",
  "email": "test@example.com"
}

// Sync Response
{ "success": true, "message": "Sync successful" }
```

### Simulated Latency
- Auth: 500ms delay
- Sync: 200ms delay

### Base URL (Fake)
```kotlin
.baseUrl("https://api.splitease.com/")
```

---

## 3. How to Add a Real Backend

### Step 1: Define Backend Requirements

| Endpoint | Method | Request | Response |
|----------|--------|---------|----------|
| `/auth/login` | POST | `{ email, password }` | `{ userId, token, name, email }` |
| `/auth/signup` | POST | `{ name, email, password }` | `{ userId, token, name, email }` |
| `/sync` | POST | `{ operationId, entityType, operationType, payload }` | `{ success, message }` |
| `/groups` | GET | - | `[{ id, name, type, members }]` |
| `/expenses/{groupId}` | GET | - | `[{ id, title, amount, ... }]` |

### Step 2: Update NetworkModule

```kotlin
// Before (Mock)
.baseUrl("https://api.splitease.com/")

// After (Real)
.baseUrl(BuildConfig.API_BASE_URL)
```

### Step 3: Remove Mock Interceptor

Replace `MockAuthInterceptor` with a real auth header interceptor:

```kotlin
.addInterceptor { chain ->
    val token = tokenProvider.getToken()
    chain.proceed(
        chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()
    )
}
```

### Step 4: Expand API Interface

```kotlin
// Add to SplitEaseApi.kt
@GET("groups")
suspend fun getGroups(): List<GroupResponse>

@GET("expenses/{groupId}")
suspend fun getExpenses(@Path("groupId") groupId: String): List<ExpenseResponse>
```

### Step 5: Recommended Backend Options

| Option | Pros | Cons |
|--------|------|------|
| **Firebase** | Zero infrastructure, auth built-in | Vendor lock-in |
| **Supabase** | Open source Firebase alternative | Newer ecosystem |
| **Node.js + Express** | Full control | Must manage hosting |
| **Kotlin + Ktor** | Same language as app | Less common |

---

## 4. Files to Modify for Real Backend

| File | Change |
|------|--------|
| `NetworkModule.kt` | Replace base URL, remove mock interceptor |
| `MockAuthInterceptor.kt` | Delete or disable |
| `SplitEaseApi.kt` | Add new endpoints |
| `AuthRepositoryImpl.kt` | Handle real auth errors |
| `build.gradle.kts` | Add `BuildConfig` for API URL |

---

## 5. Summary

### What's Done ✅
- Complete offline-first architecture
- Room as single source of truth
- Background sync with WorkManager
- Material Design 3 UI with Compose
- Hilt dependency injection
- Full navigation with back arrows

### What's Mocked ⚠️
- Authentication (always succeeds)
- Sync endpoint (always succeeds)

### Next Steps for Production
1. Deploy a real backend
2. Remove `MockAuthInterceptor`
3. Add real authentication
4. Implement remote data fetching
5. Add group creation UI
