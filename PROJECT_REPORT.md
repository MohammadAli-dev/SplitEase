# SplitEase Project Report

## Project Overview
**Application**: SplitEase - Splitwise-style expense sharing app  
**Package**: `com.splitease`  
**MinSdk**: 24 | **TargetSdk**: 34  
**Architecture**: Offline-first, MVVM with Repository pattern

---

## Build Configuration

### Prerequisites
- **Android Studio**: Ladybug (2024.2+) recommended
- **JDK**: 17+
- **Gradle**: 8.13.2 (via wrapper)
- **Kotlin**: 1.9.0

### Key Dependencies
| Component | Version |
|-----------|---------|
| Compose BOM | 2023.08.00 |
| Hilt | 2.48 |
| Room | 2.6.0 |
| WorkManager | 2.9.0 |
| Retrofit | 2.9.0 |
| Material3 | 1.2.0 |

---

## How to Sync & Build

### 1. Clone/Open Project
```
Open c:\newprojects\SplitShare-2 in Android Studio
```

### 2. Sync Gradle
```
File > Sync Project with Gradle Files
```
Or via command line:
```powershell
cd c:\newprojects\SplitShare-2
.\gradlew.bat build
```

### 3. Build Debug APK
```powershell
.\gradlew.bat assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### 4. Install on Device/Emulator
```powershell
.\gradlew.bat installDebug
```

---

## Project Structure

```
com.splitease/
├── MainActivity.kt          # Entry point, state-driven navigation
├── SplitEaseApp.kt          # Hilt Application + WorkManager config
├── data/
│   ├── local/
│   │   ├── dao/             # Room DAOs (UserDao, GroupDao, ExpenseDao, SyncDao)
│   │   ├── entities/        # Room Entities
│   │   ├── converters/      # TypeConverters (BigDecimal, Date)
│   │   └── AppDatabase.kt   # Room Database
│   ├── remote/
│   │   ├── SplitEaseApi.kt  # Retrofit interface
│   │   └── MockAuthInterceptor.kt
│   └── repository/
│       ├── AuthRepository.kt
│       ├── ExpenseRepository.kt
│       └── SyncRepository.kt
├── di/                       # Hilt Modules
├── domain/                   # Pure Kotlin logic
│   ├── SplitValidator.kt    # Split calculations
│   └── SimplifyDebtUseCase.kt
├── ui/
│   ├── auth/                # Login, Signup screens
│   ├── dashboard/           # Dashboard with balance
│   ├── groups/              # Group list
│   ├── expense/             # Add Expense
│   ├── account/             # Account & Logout
│   └── navigation/          # NavGraph, MainScaffold
└── worker/
    └── SyncWorker.kt        # Background sync
```

---

## How to Test

### Unit Tests (Domain Logic)
```powershell
.\gradlew.bat testDebugUnitTest
```
Tests: `app/src/test/java/com/splitease/domain/DebtLogicTest.kt`

### Instrumented Tests (Database)
```powershell
.\gradlew.bat connectedDebugAndroidTest
```
Tests: `app/src/androidTest/java/com/splitease/DbContextTest.kt`

### Manual Testing Checklist
| Feature | Steps | Expected |
|---------|-------|----------|
| **Signup** | Enter name/email, click Signup | Navigate to Dashboard |
| **Login** | Enter email, click Login | Navigate to Dashboard |
| **Persistence** | Kill app, restart | Auto-navigate to Dashboard |
| **Logout** | Account tab > Logout | Navigate to Login, DB cleared |
| **Add Expense** | Groups > Add Expense > Save | DB has expense + splits |
| **Sync** | Create expense offline, go online | Logcat shows "Sync success" |

### App Inspector Testing
1. **View > Tool Windows > App Inspection**
2. **Database Inspector**: View Room tables
3. **Background Task Inspector**: Trigger SyncWorker

---

## Known Issues / Notes

### AGP Version
The project uses AGP 8.13.2. If build fails:
1. Update Android Studio to latest
2. Or downgrade to `agp = "8.1.0"` in `libs.versions.toml`

### Schema Changes
If you modify Room entities, either:
- Uninstall app and reinstall
- Or implement Room migrations

### Mock API
All network calls are mocked via `MockAuthInterceptor`. No real backend required.

---

## Sprint Summary

| Sprint | Feature | Status |
|--------|---------|--------|
| 0 | Foundation (Build/Config) | ✅ Complete |
| 1 | Room Database | ✅ Complete |
| 2 | Authentication | ✅ Complete |
| 3 | Domain Logic (Splits) | ✅ Complete |
| 4 | Dashboard & Navigation | ✅ Complete |
| 5 | Add Expense | ✅ Complete |
| 6 | Account & Logout | ✅ Complete |
| 7 | Background Sync | ✅ Complete |
