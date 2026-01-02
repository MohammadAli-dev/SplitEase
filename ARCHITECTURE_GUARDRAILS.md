# Architecture & Build Guardrails — SplitEase

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
- **Strict Comparison**: Always use `.compareTo(BigDecimal.ZERO)` for comparisons. Avoid relyng on operator overloading (`>`, `<=`) which can be brittle across Kotlin compiler versions or mixed Java/Kotlin modules.

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

## 6. Change Safety Checklist (MANDATORY)

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