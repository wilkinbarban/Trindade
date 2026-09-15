# Tasks: Stage-One Recovery and Canonical Truth

## Review Workload Forecast

| Field | Value |
|---|---|
| Estimated changed lines | ~650 lines |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Recovery & Lifecycle) -> PR 2 (Config & References) -> PR 3 (Bootstrap & Auth) |
| Delivery strategy | auto-chain |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|---|---|---|---|---|---|
| 1 | Recovery harness & target classification | PR 1 | `npm --prefix packages/backend test recovery.test.ts` | `node packages/backend/dist/recovery.js --verify-isolated` | Remove `recovery.ts` and `install-lifecycle.ts` |
| 2 | Fail-fast runtime config & canonical fixtures | PR 2 | `npm --prefix packages/backend test runtime-config.test.ts` | N/A: internal module & test-helper refactor | Revert `runtime-config.ts`, `seed.sql`, `test-helper.ts` |
| 3 | Atomic admin bootstrap & startup wiring | PR 3 | `npm --prefix packages/backend test auth.test.ts` | `npm --prefix packages/backend run dev` | Revert `bootstrap.routes.ts`, `SetupPage.tsx`, `server.ts` |

## Phase 1: Recovery Harness & Installation Classification

- [x] 1.1 RED Test: Write tests rejecting path traversal, metacharacters, symlink escape, production path overlap, non-zero exits, timeouts, and partial outputs in recovery CLI execution.
- [x] 1.2 Implement SQLite snapshot capture, WAL/asset inventory, checksum verification, and isolated restore proof in `packages/backend/src/recovery.ts`.
- [x] 1.3 Create target classifier in `packages/backend/src/db/install-lifecycle.ts` to distinguish fresh install, existing upgrade, or ambiguous state (failing closed).

## Phase 2: Runtime Configuration & Canonical References

- [x] 2.1 Implement runtime config validator in `packages/backend/src/runtime-config.ts` enforcing `JWT_SECRET` length (>=32 UTF-8 bytes) and rejecting default/test values prior to DB effects.
- [x] 2.2 Refactor `packages/backend/src/db/reference-data.ts` and `packages/backend/src/db/seed.sql` to manage versioned catalog references and remove [REDACTED LEGACY CREDENTIAL].
- [x] 2.3 Refactor `packages/backend/src/test-helper.ts` to verify reference prerequisites and fail fast on contract drift.

## Phase 3: Auth Bootstrap & Startup Safety Wiring

- [x] 3.1 Create atomic administrator setup route in `packages/backend/src/modules/auth/bootstrap.routes.ts` and update `packages/backend/src/modules/auth/auth.middleware.ts` & `auth.routes.ts`.
- [x] 3.2 Create frontend first-run setup UI page in `packages/frontend/src/pages/SetupPage.tsx`.
- [x] 3.3 Modify export validation in `packages/backend/src/modules/reports/export.service.ts` and `packages/backend/src/modules/loading/loading.export.service.ts` to fail explicitly on missing references while preserving PT-BR output.
- [x] 3.4 Reorder startup sequence in `packages/backend/src/server.ts` and `packages/backend/src/db/index.ts` to enforce config validation and classification before any database mutations.

## Phase 4: Stage-One Verification & Documentation

- [x] 4.1 Update `README.md`, `.env.example`, and `docker-compose.yml` to remove default credentials and document bootstrap/recovery procedures.
- [x] 4.2 Verify stage-one read-only invariants against existing production fingerprint and isolated restore proof, allowing only policy-expired report-photo disposal.
