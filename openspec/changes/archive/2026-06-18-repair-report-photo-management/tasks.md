# Tasks: Repair Report Photo Management

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 500-850 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 foundation+backend, PR 2 frontend+export, PR 3 tests+polish |
| Delivery strategy | exception-ok |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: size-exception
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | DB, validation, public links, cleanup | PR 1 | Base backend changes plus migration and retention wiring |
| 2 | Create/edit/view/export UI | PR 2 | Frontend photo flows, thumbnails, remove, retry-safe upload |
| 3 | Tests and verification | PR 3 | Backend tests, Playwright, build/test commands |

## Phase 1: Foundation

- [x] 1.1 Update `packages/backend/src/db/schema.sql` and `packages/backend/src/db/index.ts` to add `report_photos.public_token`, `created_at` index, and startup backfill/migration.
- [x] 1.2 Extend `packages/backend/src/modules/reports/reports.service.ts` with max-5 accounting, token generation, photo listing URLs, and `cleanupExpiredPhotos`.
- [x] 1.3 Wire `packages/backend/src/server.ts` retention scheduling and `PUBLIC_APP_URL` handling for export/public links.

## Phase 2: Core Implementation

- [x] 2.1 Add backend MIME + magic-byte validation in `packages/backend/src/modules/reports/reports.routes.ts` for JPEG/PNG/WebP, `<=5MB`, and clear `413/415/409` errors.
- [x] 2.2 Add `GET /api/reports/photos/public/:token` in `packages/backend/src/modules/reports/reports.routes.ts` for downloadable WhatsApp links.
- [x] 2.3 Update `packages/backend/src/modules/reports/export.service.ts` to append a Portuguese `📷 Fotos` section with public photo URLs.

## Phase 3: Integration

- [x] 3.1 Update `packages/frontend/src/pages/ReportsPage.tsx` for optional multi-photo input, thumbnails, remove pending, and sequential upload after report save without clearing the form on error.
- [x] 3.2 Update `packages/frontend/src/pages/ReportEditPage.tsx` to manage existing + new photos, enforce total max 5, allow delete of existing photos, and preserve state on save/upload failures.
- [x] 3.3 Update `packages/frontend/src/pages/ReportViewPage.tsx` and `packages/frontend/src/components/reports/ExportPreview.tsx` so report/export UI shows thumbnails/links and WhatsApp-only export copy.
- [x] 3.4 Add/update i18n strings in `packages/frontend/src/i18n/locales/*.json` for validation, count limits, retries, and photo actions.

## Phase 4: Testing / Verification

- [x] 4.1 Extend backend tests in `packages/backend/test/reports.routes.test.ts`, `export.service.test.ts`, and cleanup coverage for validation, public token routes, and retention deletion.
- [x] 4.2 Extend Playwright specs such as `packages/frontend/tests/photos.spec.ts` and `report-view-export.spec.ts` for create/edit previews, removal, upload retry, and WhatsApp-only export.
- [x] 4.3 Verify with `npm run test --workspace=packages/backend` and `npm run build --workspaces --if-present`.
