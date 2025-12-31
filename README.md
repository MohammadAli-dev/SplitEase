# SplitEase - Android Expense Sharing Application

SplitEase is an offline-first, native Android application designed for shared expense management. It is built with modern Android development standards, including Jetpack Compose, Hilt, Room, and WorkManager.

## 🏗️ Architecture & Design

### High-Level Design (HLD)
The application follows the **MVVM (Model-View-ViewModel)** architectural pattern with a strict **Unidirectional Data Flow (UDF)**. It prioritizes the **Offline-First** principle, where the local database is the single source of truth for the UI.

- **UI Layer**: Jetpack Compose (Activities, Screens, Components). Reacts to state emitted by ViewModels.
- **Presentation Layer**: ViewModels. transform data from Repositories into UI State.
- **Domain Layer**: Pure Kotlin logic (e.g., `SplitValidator`, `SimplifyDebtUseCase`) for complex financial calculations.
- **Data Layer**: Repositories (`ExpenseRepository`, `AuthRepository`) that mediate between Local (Room) and Remote (Retrofit) data sources.
- **Background Layer**: WorkManager (`SyncWorker`) for resilient data synchronization.

### Low-Level Design (LLD)

#### 1. Database Schema (Room)
The local database (`AppDatabase`) consists of the following relational entities:
- **User**: `id` (PK), `name`, `email`, `profileUrl`.
- **Group** (Table `expense_groups`): `id` (PK), `name`, `type`, `createdBy`.
- **GroupMember**: Junction table for User-Group many-to-many relationship.
- **Expense**: `id` (PK), `amount`, `payerId`, `groupId`, `description`.
- **ExpenseSplit**: `expenseId` (FK), `userId` (FK), `amount`. Represents individual shares.
- **SyncOperation**: `id` (PK), `operationType` (CREATE/UPDATE/DELETE), `entityType`, `payload`, `timestamp`. Acts as a persistent writelog for sync.

#### 2. Key Components
- **SyncEngine**:
    - **Trigger**: Validates data changes (creates `SyncOperation`).
    - **Execution**: `SyncWorker` processes the queue in FIFO order.
    - **Idempotency**: API endpoints are designed to accept duplicate operation IDs safely.
- **Authentication**:
    - Uses `EncryptedSharedPreferences` for secure token storage.
    - `AuthRepository` manages login state and effectively "wipes" the DB on logout.
- **Dependency Injection**:
    - **Hilt** manages the object graph.
    - **Modules**: `DataModule` (Repos), `NetworkModule` (Retrofit/OkHttp), `DatabaseModule` (Room/DAOs), `SecurityModule`.

---

## 🛠️ How to Sync, Build, and Run

### Prerequisites
- **Android Studio**: Ladybug (2024.2) or newer.
- **Gradle**: 8.13.2 (Wrapper included).
- **JDK**: JDK 17 or higher (configured in Android Studio).

### Setup Steps
1. **Clone the repository**:
   Open the project folder (`c:\newprojects\SplitShare-2`) in Android Studio.
2. **Sync Project**:
   Android Studio should auto-sync. If not, go to **File > Sync Project with Gradle Files**.
3. **Build**:
   Run the following command in the terminal tab:
   ```powershell
   ./gradlew assembleDebug
   ```
4. **Run**:
   Select a connected device or emulator and press the green **Run** button (Shift+F10).

### Troubleshooting Build Errors
- **KSP Errors**: If you see `error.NonExistentClass`, ensure you are doing a clean build (`./gradlew clean assembleDebug`).
- **OOM Errors**: The `gradle.properties` file is already tuned with `-Xmx4g` to prevent this.

---

## 🧪 How to Add Data & Test

### 1. Authentication (First Run)
1. Launch the app.
2. **Signup**: Enter any Name, Email, and Password. (The app uses a Mock API, so it will succeed immediately).
3. **Login**: Alternatively, use the Login screen.
4. **Result**: You will land on the Dashboard.

### 2. Creating Data (Expense Flow)
1. **Navigate**: Tap "Groups" in the bottom bar.
2. **Select Group**: Tap on a group (if none exist, the app currently seeds/shows placeholders or you may need to implement Group Creation - *Note: Group Creation is the next feature sprint*).
3. **Add Expense**:
   - Tap "Add Expense".
   - Enter Amount (e.g., "100").
   - Select Payer (defaults to you).
   - Tap **Save**.
4. **Verification**:
   - The expense appears in the list locally immediately.
   - **Sync**: Check Logcat (`Tag: SyncRepository`) to see the background upload happening: `Sync success: EXPENSE/...`

### 3. Testing Offline Mode
1. **Enable Airplane Mode** on your device/emulator.
2. Add an Expense.
3. **Verify**: Expense saves and shows in UI.
4. **Check DB**: Using **App Inspection > Database Inspector**, view the `sync_operations` table. You will see a pending row.
5. **Disable Airplane Mode**.
6. The `SyncWorker` (or immediate trigger) will eventually run (approx 15 mins for periodic, or immediate if network constraint is met). You can force it via **App Inspection > Background Task Inspector**.
7. **Verify**: `sync_operations` table becomes empty after successful sync.

### 4. Running Automated Tests
- **Unit Tests** (Domain logic):
  ```powershell
  ./gradlew testDebugUnitTest
  ```
- **Instrumented Tests** (Room/DAO):
  ```powershell
  ./gradlew connectedDebugAndroidTest
  ```
