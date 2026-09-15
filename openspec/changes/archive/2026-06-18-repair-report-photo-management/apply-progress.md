# Apply Progress: repair-report-photo-management

**Change**: repair-report-photo-management  
**Mode**: Standard (`strict_tdd=false`)  
**Artifact store**: hybrid/OpenSpec  
**Delivery strategy**: exception-ok  
**Chain strategy**: size-exception  
**Status**: success

## Completed Tasks

- [x] 1.1 Added `report_photos.public_token`, public-token and created-at indexes, and startup backfill/migration support.
- [x] 1.2 Extended report photo service with max-5 accounting, token generation, authenticated/public URLs, and 30-day cleanup deletion.
- [x] 1.3 Wired `PUBLIC_APP_URL` into report routes and scheduled startup/daily retention cleanup.
- [x] 2.1 Added backend JPEG/PNG/WebP MIME + magic-byte validation, `<=5MB` handling, and 413/415/409 responses.
- [x] 2.2 Added unauthenticated public photo download route at `GET /api/reports/photos/public/:token`.
- [x] 2.3 Updated WhatsApp export text with Portuguese `📷 Fotos` public links.
- [x] 3.1 Updated report creation flow for optional multi-photo selection, thumbnails, pending removal, sequential upload after save, and retry-safe form preservation.
- [x] 3.2 Updated report edit flow for existing + pending photos, total max 5, existing delete, and state preservation on save/upload errors.
- [x] 3.3 Updated report view/export UI to use photo URLs and expose WhatsApp-only copy in export preview.
- [x] 3.4 Added PT-BR and ES i18n strings for count limits, help, removal, and retry actions.
- [x] 4.1 Extended backend coverage for validation, max count, public-token route, export photo links, and retention cleanup.
- [x] 4.2 Updated Playwright specs for create/edit pending previews, removal, and WhatsApp-only export behavior.
- [x] 4.3 Verified backend tests and workspace builds.

## Verification

| Command | Result |
|---|---|
| `npm run test --workspace=packages/backend` | PASS — 144 tests passed |
| `npm run build --workspaces --if-present` | PASS — backend `tsc`; frontend `tsc && vite build` |

## Deviations

- `report_photos.public_token` is nullable at schema/migration level for safe idempotent rollout on existing SQLite databases, then backfilled at startup and populated for all new uploads. This avoids unsafe table rebuilds while preserving the public URL contract for served/exported photos.
- The backend TXT export route was removed to satisfy the updated “WhatsApp-only” export capability; the UI also removes PDF/TXT controls.

## Issues / Follow-up

- `PUBLIC_APP_URL` must be set correctly in production for WhatsApp recipients to receive externally reachable public photo links. The server falls back to `APP_URL` or `http://localhost:<PORT>`.
- Frontend Playwright specs were updated but not executed in this apply run because the requested feasible verification focused on backend tests/build and no running browser stack was started.

## Workload Boundary

- Mode: size:exception
- Boundary: completed the full oversized change in one apply batch per maintainer-provided `exception-ok` / `size-exception` decision.

## Remediation — 2026-06-18

### Root Cause

- The failing edit-to-view photo E2E was a real application validation bug, not a photo persistence bug: `ReportEditPage` submitted `notes: null` for empty notes, while `UpdateReportBodySchema` only accepted `string | undefined`. The backend returned `400 Invalid input` on `PATCH /api/reports/:id`, so the sequential photo upload never ran and the report view correctly showed `Nenhuma foto anexada.`
- The original E2E also navigated back to the view immediately after clicking save, which could hide the failed PATCH/upload path. The test now waits for the PATCH result and the app navigation before asserting the thumbnail.

### Changes

- Updated `packages/backend/src/modules/reports/reports.schema.ts` so report updates accept nullable notes, matching the edit page's existing clear-notes payload.
- Added runtime backend coverage that `cleanupExpiredPhotos` keeps fresh photo records and files.
- Added Playwright coverage for upload failure retry preserving create form state and pending photos.
- Added Playwright coverage for non-creator read-only photo controls on the edit page.
- Added a stable `data-testid="delete-existing-photo-btn"` to existing-photo delete controls for read-only assertions.
- Strengthened the edit photo thumbnail E2E to prove the update request succeeds before checking the report view gallery.

### Verification

| Command | Result |
|---|---|
| `npm run test --workspace=packages/backend` | PASS — 145 tests passed, 0 failed |
| `npm run build --workspaces --if-present` | PASS — backend `tsc`; frontend `tsc && vite build` |
| `npm run test:e2e --workspace=packages/frontend -- photos.spec.ts report-view-export.spec.ts` | PASS — 12 tests passed, 0 failed |

### Remaining Issues

- E2E still emits the existing Node warning: `The 'NO_COLOR' env is ignored due to the 'FORCE_COLOR' env being set.` It is non-failing verification noise and unchanged by this remediation.

### Next Recommended

- `sdd-verify`

## Warning Remediation — 2026-06-18

### Changes

- Updated `packages/frontend/package.json` so Playwright commands run with `env -u NO_COLOR`, eliminating the `NO_COLOR` / `FORCE_COLOR` warning noise during E2E verification.
- Added `PUBLIC_APP_URL` to `docker-compose.yml` API environment so production/container exports receive an explicit public base URL.
- Added `PUBLIC_APP_URL` to `.env.example` and local `.env`; local Docker testing uses `http://localhost:3005`, while production must replace it with the real public API/domain URL.

### Verification

| Command | Result |
|---|---|
| `npm run test:e2e --workspace=packages/frontend -- photos.spec.ts report-view-export.spec.ts` | PASS — 12 tests passed, NO_COLOR/FORCE_COLOR warning no longer appeared |
| `npm run test --workspace=packages/backend` | PASS — 145 tests passed, 0 failed |
| `npm run build --workspaces --if-present` | PASS — backend `tsc`; frontend `tsc && vite build` |

### Next Recommended

- Rerun `sdd-verify` so the verify report reflects the warning remediation.

## Domain Deployment Prep — 2026-06-18

### Changes

- Configured the project to run under `https://wilkinbarban.duckdns.org/trindade` without changing the portfolio root `/`.
- Added `VITE_APP_BASE=/trindade/` support for Docker/Vite production builds.
- Updated the React router to use `import.meta.env.BASE_URL` as `BrowserRouter` basename.
- Updated the frontend API client so API and authenticated photo URLs resolve under the configured base path.
- Configured system Nginx `wilkinbarban.duckdns.org` with isolated `/trindade/` proxying to local Trindade web port `8085`, leaving portfolio `location /` unchanged.
- Set `PUBLIC_APP_URL=https://wilkinbarban.duckdns.org/trindade` for WhatsApp photo links.
- Fixed SQLite production startup by keeping `report_photos.public_token` and `created_at` indexes in `db/index.ts` after `ensureColumn`, not in `schema.sql` before idempotent migrations.

### Verification

| Check | Result |
|---|---|
| `npm run test --workspace=packages/backend` | PASS — 145 tests passed |
| `npm run build --workspaces --if-present` | PASS |
| `npm run test:e2e --workspace=packages/frontend -- photos.spec.ts report-view-export.spec.ts` | PASS — 12 tests passed |
| `docker compose up -d --build` | PASS — api and web containers running |
| `https://wilkinbarban.duckdns.org/trindade/` | PASS — HTTP 200 |
| `https://wilkinbarban.duckdns.org/trindade/login` | PASS — HTTP 200 SPA fallback |
| `https://wilkinbarban.duckdns.org/trindade/api/health` | PASS — HTTP 200 |
| `https://wilkinbarban.duckdns.org/` | PASS — HTTP 200 portfolio root preserved |

### Next Recommended

- Rerun `sdd-verify`, then archive if PASS.
