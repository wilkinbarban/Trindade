# Design: Stage-One Recovery and Canonical Truth

## Technical Approach

Stage one separates validation, classification, recovery, and canonical data before writes. Currently `db/index.ts` mutates during import and `server.ts` performs effects before checking JWT. Startup first validates configuration and classifies the target. Existing installations preserve database bytes and non-expired assets; the 30-day report-photo retention policy MAY delete expired photo files during startup. Ambiguity fails closed. Recovery covers Docker `sqlite_data` (database, WAL/SHM, photos). Later stages remain gated.

## Architecture Decisions

| Decision | Alternative / tradeoff | Choice and rationale |
|---|---|---|
| Startup secret authority | Module fallbacks (easy, divergent) | `loadRuntimeConfig()` precedes all effects. Production requires `JWT_SECRET` ≥32 UTF-8 bytes and rejects blank, public-development, and test values. One injected value signs/verifies; errors remediate without disclosure. |
| Lifecycle paths | Idempotent startup SQL (simple, unsafe) | Classify `fresh`, `existing`, or fail-closed `ambiguous` before writable open; only explicit fresh setup initializes. |
| Recovery set | Raw copy (WAL-unsafe) | Online backup, referenced files, manifest, integrity, isolated restore, and immutable evidence; missing/raced items invalidate the set. |
| Canonical truth | Production-derived seed/docs (drifts) | One versioned auth/reference contract feeds runtime, fixtures, bootstrap, and checks; conflicts are reported, never overwritten. |
| Bootstrap | Seed/environment password | One transaction verifies zero users/open bootstrap, creates one hashed `Administrador`, then closes bootstrap; races have one winner. |

## Data Flow

```text
entry -> validate config -> classify -> fresh -> references -> setup
                                  \-> existing -> read-only open/login
                                  \-> ambiguous -> stop
inventory -> online snapshot -> encrypted set -> verify -> isolated restore
          -> immutable proof -> freshness check -> named authorization
```

## File Changes

| File | Action | Purpose |
|---|---|---|
| `packages/backend/src/server.ts`, `packages/backend/src/db/index.ts` | Modify | Validate before effects; explicit read/init paths. |
| `packages/backend/src/runtime-config.ts`, `packages/backend/src/db/install-lifecycle.ts`, `packages/backend/src/db/reference-data.ts` | Create | Validated config, classification, and versioned references. |
| `packages/backend/src/modules/auth/auth.middleware.ts`, `packages/backend/src/modules/auth/auth.routes.ts` | Modify | Remove duplicated fallback secrets; inject canonical auth contract. |
| `packages/backend/src/modules/auth/bootstrap.routes.ts`, `packages/frontend/src/pages/SetupPage.tsx` | Create | Atomic administrator bootstrap and assistant. |
| `packages/backend/src/db/seed.sql`, `packages/backend/src/test-helper.ts` | Modify | Remove fixed/demo production users; use prerequisite-checked fixtures. |
| `packages/backend/src/modules/reports/export.service.ts` | Modify | Fail on missing required report references. |
| `packages/backend/src/modules/loading/loading.export.service.ts` | Modify | Fail on missing loading references; retain PT-BR text. |
| `packages/backend/src/recovery.ts` | Create | Recovery entry point matching existing root-level backend entry points. |
| `README.md`, `.env.example`, `docker-compose.yml` | Modify | Remove default-login/reset advice; document bootstrap, auth/secret contracts, injection, diagnostics, and drift checks. |

## Interfaces / Contracts

`RecoveryManifest` records set/source IDs; every DB/WAL/SHM/asset inventory item's identity, size, inventory timestamp, and SHA-256; exclusions; image/Compose/schema digests; UID/GID; mounts, paths, ports, environment names; and secret references, never values. `IntegrityEvidence` records set ID and every command, timestamp, and result. Storage evidence records destination, encryption/key reference, ACL, retention, and readability. Final evidence is content-addressed, signed, and append-only or offline read-only.

`RestoreProof` requires isolated-target identity; successful database opening; integrity results; schema identity; representative row counts; associated-file availability; application-level reads; production before/after fingerprints; commands, timestamps, and results. Missing or failed fields close the gate.

`RollbackEvidence` requires set/proof IDs, restore procedure, approver identity/role/time, triggers and decision owner, verification commands/expected results/time limit, pre-change fingerprint, `verifiedAt`, and `validUntil`. Expiry, changed DB/file/config/image fingerprints, or changed linked evidence makes it stale and denies authorization.

`SeedEvidence` requires installation class; dataset name/version/SHA-256; per-table stable keys for inserted, skipped, and conflicting rows; invariant query text/hash with expected/actual results; and before/after counts. It links to immutable recovery/authorization evidence and proves existing keys/counts remain preserved.

## Testing Strategy

| Layer | Planned evidence |
|---|---|
| Unit | Secret rejection precedes mocked effects; classification, auth/doc drift, fixture prerequisites, seed accounting, manifest/staleness. |
| Integration | Signing/verifying share injected config; fresh repeatability, existing byte preservation, concurrent bootstrap, explicit export reference failures, PT-BR output. |
| Recovery/E2E | WAL DB/photos: encrypted capture, corruption/secret-leak rejection, isolated restore reads, immutable proof, stale refusal, unchanged database/non-expired-asset fingerprints, and expired-photo disposal. |

## Threat Matrix

| Boundary | Applicability | Safe/failure behavior and planned RED test |
|---|---|---|
| Recovery paths/process | Applicable | Use canonical absolute allowlisted roots and `execFile`-style argv (no shell); reject traversal, metacharacters, symlink escape, overlap with production, non-zero exit, timeout, and partial output. RED test each case. |
| Documentation-like paths | N/A | No executable-file classification. |
| Git repository selection | N/A | No VCS operation. |
| Commit state | N/A | No commit operation. |
| Push state | N/A | No push operation. |
| PR commands | N/A | Publication is outside stage one. |

## Migration / Rollout

Plan and verify only against temporary databases and an isolated production restore. Stage one does not deploy or mutate production. A later named upgrade needs fresh evidence and explicit approval; delivery stays in root-cluster auto-chain slices below 400 review lines.

## Open Blockers

- Production identities, backup destination, encryption/key provider, retention, restore host, reference dataset/version, and password policy still require operator inventory/approval.
