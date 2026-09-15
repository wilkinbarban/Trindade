# Tasks: Photos in Reports + Visual Audit

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~530 total (PR1 ~245, PR2 ~285) |
| 400-line budget risk | Medium (each PR under 400 individually) |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Photos) → PR 2 (Audit) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Medium

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Photo upload, storage, serving, gallery UI | PR 1 | base: main; ~245 lines; self-contained |
| 2 | Audit service, inline hooks, admin UI | PR 2 | base: main (after PR 1 merge); ~285 lines; depends on PR 1 |

## Phase 1: Photo Infrastructure (PR 1)

- [x] 1.1 Install `@fastify/multipart` in `packages/backend/`, register in `server.ts` with 5MB limit.
- [x] 1.2 Update `docker-compose.yml` to map `data/photos/` volume.
- [x] 1.3 Add `fs.mkdirSync` guard for `data/photos/` on startup.

## Phase 2: Photo Backend Routes & Service (PR 1)

- [x] 2.1 Add `savePhoto()`, `getPhotosByReport()`, `getPhotoById()`, `deletePhoto()` to `reports.service.ts` on `report_photos` table.
- [x] 2.2 Add routes in `reports.routes.ts`: `POST /:id/photos`, `GET /:id/photos`, `GET /photos/:photoId`, `DELETE /photos/:photoId`.
- [x] 2.3 Enforce edit-window (`readOnly`) check on upload/delete — 403 if expired.
- [x] 2.4 Validate MIME type (image/*) and size (<5MB) — 415/413 on failure.
- [x] 2.5 Serve photos via `reply.send()` with correct content-type; 404 if missing.

## Phase 3: Photo Frontend Integration (PR 1)

- [x] 3.1 Add `uploadPhoto(reportId, file)` and `deletePhoto(photoId)` to `client.ts` using `FormData`.
- [x] 3.2 Add photo gallery to `ReportViewPage.tsx` — thumbnails from `GET /:id/photos`.
- [x] 3.3 Add upload/delete widget to `ReportEditPage.tsx` — hidden when read-only.

## Phase 4: Audit Service & Routes (PR 2)

- [x] 4.1 Create `modules/audit/audit.service.ts` with `audit.log(db, params)` inserting into `audit_logs`.
- [x] 4.2 Create `modules/audit/audit.routes.ts` with `GET /api/admin/audit` — paginated, admin-only, newest first.
- [x] 4.3 Register audit routes in `server.ts` under `/api/admin`.
- [x] 4.4 Add admin-role guard — 403 for non-ADMIN users.

## Phase 5: Inline Audit Hooks (PR 2)

- [x] 5.1 Insert `audit.log()` in `reports.routes.ts` and `reports.service.ts`: report created/updated, photo uploaded/deleted.
- [x] 5.2 Insert `audit.log()` in `auth.routes.ts`: login, logout.
- [x] 5.3 Insert `audit.log()` in `loading.routes.ts`: loading created/updated/deleted.
- [x] 5.4 Insert `audit.log()` in `admin.routes.ts`: category/task/product/driver/vehicle management actions.

## Phase 6: Audit Frontend UI (PR 2)

- [x] 6.1 Create `pages/admin/AuditPage.tsx` — paginated table: timestamp, user, action, entity, details.
- [x] 6.2 Register `/admin/audit` route in `App.tsx` with admin-role check.
- [x] 6.3 Add navigation link from sidebar (`AppShell.tsx`).

## Remediation (verify-failure — 2026-06-14)

- [x] R1 Fix audit E2E strict-mode locator failures: `text=Usuário` → `getByRole('columnheader', …)`, `text=Entrar` → `getByRole('heading', …)`, also `text=Ação`/`text=Entidade` → `getByRole('columnheader', …)`.
- [x] R2 Add Photo Gallery UI E2E tests: empty state on view, upload on edit, thumbnail on view.
- [x] R3 Run full verification: backend 121/121, frontend build/typecheck clean, audit E2E 5/5, photos E2E 3/3.
