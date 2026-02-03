# Architecture Guardrails

## Network Layer & DTOs

### 1. DTO Placement
**Policy**: Maintain `SplitEaseApi.kt` as the canonical network contract.
- **Rule**: All Remote DTOs (Request/Response objects used by Retrofit) should be defined within `SplitEaseApi.kt` to maintain high visibility and low indirection.
- **Exception**: If a DTO is shared across multiple distinct services or if `SplitEaseApi.kt` exceeds 1000 lines, a migration to a dedicated `remote/dto` package should be performed holistically.
- **Rationale**: Currently, SplitEase uses a layer-per-package structure. Fragmentation of DTOs into individual files creates unnecessary cognitive load for protocol reviews.

### 2. DTO Naming
- **Outbound**: Use the `UploadDto` suffix for write-only models (Client -> Cloud).
- **Inbound**: Use the `Remote` prefix for read-authoritative models (Cloud -> Client).

## Sync & Error Handling

### 3. Failure Taxonomy (The Three-Bucket Rule)
**Policy**: All sync workers must use the centralized `NetworkResultMapper` to translate transport-layer errors (HTTP codes, IOExceptions) into domain-layer work results.
- **Transient (Retry)**: 5xx, 429, 408, and IOExceptions. These trigger `Result.retry()` with exponential backoff.
- **Actionable (Pause)**: 401, 403. These return `Result.success()` to park the worker until the next app start or login triggers a fresh sync.
- **Terminal (Fail)**: 400, 404, etc. These return `Result.failure()` to prevent battery drain from poisonous payloads.

**Rule**: Workers must NOT reason about raw HTTP status codes. Use `response.toWorkResult()` or `exception.toWorkResult()`.

### 4. Drain Loop Invariant (Append-Only Workers)
**Policy**: Workers handling append-only data (like the Ledger Mirror) must use a "Drain Loop" rather than relying on WorkManager rescheduling for liveness.
- **Rule**: The worker must loop (`while(true)`) and only exit with `Result.success()` when a fetch from the local database returns an empty result.
- **Rationale**: This prevents race conditions where new data is written during worker execution while using `ExistingWorkPolicy.KEEP`, ensuring that no operations are "stranded" in the local database.
- **Performance**: Internalizing the loop avoids the overhead of WorkManager rescheduling and ensures near-instant data durability.

---
*Created: 2026-01-17 during Sprint 17 Consolidation.*

## Identity Layer

### 5. Person vs User Separation
**Policy**: Enforce strict separation between Human Identity (Person) and Security Credentials (User).
- **Rule**: `Person.id` is the only primary key permitted in domain entity relationship columns (e.g., `payerPersonId`).
- **Rule**: Use the `personId` for all UI display names. Use `userId` exclusively for authentication checks and sync gating.
- **Rationale**: This decouples participation from authentication, allowing for stable historical records and "Phantom" participants.
