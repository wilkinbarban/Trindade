# Tasks: Admin Edit & Exports

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 600–750 total across 3 PRs |
| 400-line budget risk | High (total), Low per PR |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Admin CRUD) → PR 2 (Advanced Editing) → PR 3 (Extra Exports) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Admin CRUD: role guard, backend routes, admin dashboard | PR 1 | ~200–250 lines; base = main |
| 2 | Advanced Editing: report/schedule edit with quota revalidation | PR 2 | ~200–250 lines; base = main after PR 1 merges |
| 3 | Extra Exports: TXT + PDF generation for reports/schedules | PR 3 | ~150–200 lines; base = main after PR 2 merges |

## Phase 1: Foundation — Admin CRUD (PR 1)

- [x] 1.1 Add `requireRole(role)` middleware to `packages/backend/src/modules/auth/auth.middleware.ts` returning 403 if `request.user?.role !== role`.
- [x] 1.2 Create `packages/backend/src/modules/admin/admin.service.ts` with CRUD methods for categories, tasks, products, drivers, vehicles, time slots (soft-delete toggle).
- [x] 1.3 Create `packages/backend/src/modules/admin/admin.routes.ts` with `GET/POST/PATCH/DELETE /api/admin/:entity` routes, each guarded by `requireRole('Administrador')`.
- [x] 1.4 Add `patch()` and `put()` methods to `packages/frontend/src/api/client.ts`.
- [x] 1.5 Create `packages/frontend/src/pages/admin/AdminDashboard.tsx` with navigation links and CRUD tables for each entity, hidden for non-admin roles.
- [x] 1.6 Write unit tests for `admin.service.ts` CRUD operations and integration test for 403 on non-admin role.

## Phase 2: Core — Advanced Editing (PR 2)

- [x] 2.1 Enhance `packages/backend/src/modules/reports/reports.routes.ts` `PATCH /:id` to wrap deep updates in a single DB transaction.
- [x] 2.2 Add `PATCH /:id` to `packages/backend/src/modules/loading/loading.routes.ts` with transactional quota revalidation (max-3 fleteros per slot).
- [x] 2.3 Create `packages/frontend/src/pages/ReportEditPage.tsx` reusing `CategorySection` component, pre-populated with existing report data, enforcing edit window.
- [x] 2.4 Create `packages/frontend/src/pages/LoadingEditPage.tsx` reusing `ScheduleGrid` component with time slot quota display.
- [x] 2.5 Write integration test: PATCH loading schedule rolls back on quota violation. Write test: PATCH report outside edit window returns 403.

## Phase 3: Extra Exports (PR 3)

- [x] 3.1 Add `jspdf` and `html2canvas` to `packages/frontend/package.json`.
- [x] 3.2 Create `packages/frontend/src/lib/exportPdf.ts` utility using `html2canvas` + `jspdf` to capture a target DOM node and download as PDF.
- [x] 3.3 Create `packages/frontend/src/lib/exportTxt.ts` utility generating UTF-8 BOM encoded plain text in pt-BR.
- [x] 3.4 Modify `packages/frontend/src/components/reports/ExportPreview.tsx` (or create if missing) adding "Exportar PDF" and "Exportar TXT" buttons wired to the utilities.
- [x] 3.5 Add TXT endpoint `GET /api/reports/:id/export/txt` in `packages/backend/src/modules/reports/reports.routes.ts` (server-side fallback).
- [x] 3.6 Write unit tests for `exportTxt.ts` BOM encoding and PDF generation mock test verifying download trigger.

## Phase 4: Verification & Cleanup

- [x] 4.1 E2E test (Playwright): admin creates a category, edits a report, exports PDF — verify each step.
- [x] 4.2 Verify role guard: non-admin user cannot access `/api/admin/*` or see admin UI links.
- [x] 4.3 Verify quota enforcement: attempt to overload a time slot via schedule edit, confirm rollback and error message.
- [x] 4.4 Remove any temporary debug code; ensure all UI strings are in pt-BR.
