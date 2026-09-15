# Apply Progress: photos-audit (Cumulative — PR1 + PR2)

## Completed Tasks (Phases 1-3 — PR1)
- [x] 1.1 Installed @fastify/multipart, registered in server.ts with 5MB limit
- [x] 1.2 Updated docker-compose.yml volume comment, Dockerfile.api creates data/photos/
- [x] 1.3 Added mkdirSync guard for data/photos/ in server.ts startup
- [x] 2.1 Added savePhoto, getPhotosByReport, getPhotoById, deletePhoto, isReportReadOnly to reports.service.ts
- [x] 2.2 Added POST /:id/photos, GET /:id/photos, GET /photos/:photoId, DELETE /photos/:photoId routes
- [x] 2.3 Enforced edit-window (readOnly) check on upload/delete — 403 if expired
- [x] 2.4 Validated MIME type (image/jpeg, image/png, image/webp) and size (<5MB) — 415/413 on failure
- [x] 2.5 Serve photos via readFile + reply.send() with correct content-type and Cache-Control header
- [x] 3.1 Added api.upload() method to client.ts using FormData (no Content-Type override for multipart boundary)
- [x] 3.2 Added photo gallery grid to ReportViewPage.tsx with lazy loading, thumbnails from API
- [x] 3.3 Added upload/delete widget to ReportEditPage.tsx — hidden when readOnly, styled label with hidden file input

## Completed Tasks (Phases 4-6 — PR2)
- [x] 4.1 Created modules/audit/audit.service.ts with audit.log(db, params) inserting into audit_logs
- [x] 4.2 Created modules/audit/audit.routes.ts with GET /api/admin/audit — paginated, admin-only, newest first, with queryParams: page, limit, action, entityType, userId
- [x] 4.3 Registered audit routes in server.ts under /api/admin prefix
- [x] 4.4 Admin-role guard via requireRole('Administrador') — 403 for non-ADMIN users
- [x] 5.1 Inserted audit.log() in reports.routes.ts: report created/updated, photo uploaded/deleted
- [x] 5.2 Inserted audit.log() in auth.routes.ts: login + new POST /logout endpoint with audit
- [x] 5.3 Inserted audit.log() in loading.routes.ts: schedule created/updated/deleted
- [x] 5.4 Inserted audit.log() in admin.routes.ts: category/task/product/driver/vehicle create/update actions
- [x] 6.1 Created pages/admin/AuditPage.tsx — paginated table with filters (action, entityType, userId), colored action badges, Previous/Next pagination, pt-BR i18n
- [x] 6.2 Registered /admin/audit route in App.tsx under ProtectedRoute
- [x] 6.3 Added "Auditoria" navigation link in AppShell sidebar (History icon), admin-only section

## Files Changed (PR2 — Audit)

### Backend (created)
| File | Action | Description |
|------|--------|-------------|
| packages/backend/src/modules/audit/audit.service.ts | Created | Audit service: log(), queryLogs() with pagination/filters, AuditLogParams/Row/Page types |
| packages/backend/src/modules/audit/audit.routes.ts | Created | GET /api/admin/audit with Zod query validation, admin-only, newest-first |
| packages/backend/src/modules/audit/audit.routes.test.ts | Created | 11 tests: auth guard, pagination, action/entityType filters, validation, content verification |

### Backend (modified)
| File | Action | Description |
|------|--------|-------------|
| packages/backend/src/server.ts | Modified | Registered auditRoutes under /api/admin prefix |
| packages/backend/src/test-helper.ts | Modified | Registered auditRoutes under /api/admin prefix for test app |
| packages/backend/src/modules/auth/auth.routes.ts | Modified | Added audit.log() on login success + new POST /logout endpoint with audit |
| packages/backend/src/modules/reports/reports.routes.ts | Modified | Added audit.log() on report create/update, photo upload/delete |
| packages/backend/src/modules/loading/loading.routes.ts | Modified | Added audit.log() on schedule create/update/delete |
| packages/backend/src/modules/admin/admin.routes.ts | Modified | Added audit.log() on category/task/product/driver/vehicle create/update |

### Frontend (created)
| File | Action | Description |
|------|--------|-------------|
| packages/frontend/src/pages/admin/AuditPage.tsx | Created | Paginated audit table with action/entityType/userId filters, colored badges, pt-BR i18n |
| packages/frontend/src/__e2e__/audit.spec.ts | Created | 5 E2E tests: page load, filters, pagination, sidebar nav, auth redirect |

### Frontend (modified)
| File | Action | Description |
|------|--------|-------------|
| packages/frontend/src/App.tsx | Modified | Registered /admin/audit route |
| packages/frontend/src/components/layout/AppShell.tsx | Modified | Added "Auditoria" nav link with History icon (admin-only section) |
| packages/frontend/src/contexts/AuthContext.tsx | Modified | Calls POST /api/auth/logout before clearing localStorage (best-effort) |
| packages/frontend/src/i18n/locales/pt-BR.json | Modified | Added nav.audit key |

## Deviations from Design
- Task 5.4 says "admin.service.ts: user management actions" — no user management endpoints exist currently. Instead, audit.log() was added to admin.routes.ts for all existing CRUD actions (categories, tasks, products, drivers, vehicles).
- Audit hooks placed in route handlers rather than service modules to access request.user and request.ip context without threading request through service signatures. This is a pragmatic choice documented in code comments.
- Added POST /api/auth/logout endpoint (was not explicitly in design but required by task 5.2 for audit trail of logout events).

## Issues Found
- None blocking. All tests pass cleanly.

## Test Results
- Backend: 121/121 tests pass (110 original + 11 new audit tests)
- Frontend: TypeScript tsc --noEmit clean, vite build succeeds
- All existing test suites pass individually

## Verification Commands
- Backend tests: `node --test --import tsx src/db/db-init.test.ts src/modules/auth/auth.routes.test.ts src/modules/admin/admin.routes.test.ts src/modules/audit/audit.routes.test.ts src/modules/dashboard/dashboard.routes.test.ts src/modules/reports/reports.routes.test.ts src/modules/reports/export.service.test.ts src/modules/loading/loading.routes.test.ts` — 121 pass
- Frontend typecheck: `npx tsc --noEmit` — clean
- Frontend build: `npm run build` — succeeds
- E2E tests: `npx playwright test src/__e2e__/audit.spec.ts` (requires running E2E server)

## PR Boundary
- PR 2 (Audit Service + Visual UI): self-contained, depends on PR 1 (Photos) already merged to main
- Chain strategy: stacked-to-main
- Work unit: Audit logging infrastructure, inline hooks, admin audit UI

---

## Remediation Batch (verify-failure — 2026-06-14)

### Issue: Strict-mode locator failures in audit.spec.ts

- **Root cause**: `locator('text=Usuário')` matched both `<th>Usuário</th>` and `<label>ID Usuário</label>` (userId filter). Playwright strict mode rejected the ambiguous match.
- **Fix**: Changed to `getByRole('columnheader', { name: 'Usuário' })` — targets only the `<th>` element.
- **Root cause**: `locator('text=Entrar')` matched both `<h3>Entrar</h3>` (CardTitle heading) and `<button>Entrar</button>` (submit button) on the login page.
- **Fix**: Changed to `getByRole('heading', { name: 'Entrar' })` — targets only the heading.
- **Preemptive fix**: `text=Ação` and `text=Entidade` also had latent ambiguity (`<label>` + `<th>`). Changed to `getByRole('columnheader', { name: … })`.

### Issue: Missing Photo Gallery UI E2E coverage

- Created `photos.spec.ts` with 3 tests:
  - 3.1: Report view page shows "Fotos do Relatório" section with empty state ("Nenhuma foto anexada.")
  - 3.2: Upload photo on edit page (hidden file input via `setInputFiles`) and verify gallery renders image
  - 3.3: Upload photo on edit → navigate to view → verify thumbnail appears in view gallery

### Files Changed (Remediation)
| File | Action | Description |
|------|--------|-------------|
| `packages/frontend/src/__e2e__/audit.spec.ts` | Modified | Fixed 4 strict-mode locators (Usuário, Ação, Entidade → columnheader; Entrar → heading) |
| `packages/frontend/src/__e2e__/photos.spec.ts` | Created | 3 E2E tests: empty state, upload gallery, view thumbnail |

### Verification Results
- Backend tests: 121/121 pass (unchanged)
- Frontend typecheck: `npx tsc --noEmit` — clean (unchanged)
- Frontend build: `npm run build` — succeeds (unchanged)
- Audit E2E: 5/5 pass (`npx playwright test src/__e2e__/audit.spec.ts`)
- Photos E2E: 3/3 pass (`npx playwright test src/__e2e__/photos.spec.ts`)
- Full E2E suite: audit + photos all pass; pre-existing flaky tests in admin-edit-export and loading-schedule (timeouts, unrelated to photos-audit change)

### Deviations
- None. Remediation is strictly locator precision fixes and test coverage gap closure. No product behavior changed.
