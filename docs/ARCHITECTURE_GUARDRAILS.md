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

---
*Created: 2026-01-17 during Sprint 17 Consolidation.*
