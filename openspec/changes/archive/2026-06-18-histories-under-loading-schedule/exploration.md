## Exploration: histories-under-loading-schedule

### Current State
- The app is a React/Vite frontend plus Fastify/SQLite backend. SDD context confirms PT-BR/ES i18n and Portuguese-only WhatsApp exports.
- Reports already have a history page at `/reports/history`, backed by `GET /api/reports`. It lists all reports for all users, ordered by `report_date DESC, created_at DESC`, and supports period filters (`today`, `yesterday`, `7days`, `30days`, `custom`). It does not paginate, filter by user/month directly, expose a user list for filters to non-admin users, or live under the loading schedule UI.
- Report edit rules currently use report date only: current day and previous day are editable. They do not enforce “creator only” and do not enforce a one-hour creation window. The report view always shows an edit link; the edit page then sets read-only only by date. Backend `PATCH /api/reports/:id`, photo upload, and photo deletion also enforce only the old date window.
- Reports have `user_id`, `created_at`, and `updated_at`, but no `is_active`, `deleted_at`, or report delete/deactivate endpoint. Admin deletion/deactivation does not exist for reports.
- Loading schedules currently have date-specific CRUD under `/api/loading/schedules?date=YYYY-MM-DD`, plus `/api/loading/export?date=YYYY-MM-DD`. They support create, update, and physical delete for any authenticated user. There is no history route/page, pagination, user filter, creator ownership, one-hour edit window, admin-only delete/deactivate rule, or read-only mode.
- `loading_schedules` has `created_at` only. It lacks `user_id`/creator, `updated_at`, `is_active`, and `deleted_at`. Existing schedule rows cannot be attributed to a creator without a migration/backfill decision.
- Frontend navigation currently has top-level Operations links for `Relatórios` and `Cronograma de Carregamento`. There is no nested navigation or section placement under Cronograma de Carga.
- i18n convention: UI strings live in `pt-BR.json` and `es.json`; report detail can render category/task names in Spanish when UI locale is ES. Export services use Portuguese static strings and PT database fields. The new requirement says registered reports should always display in Portuguese, which is stricter than current report detail/history rendering.

### Affected Areas
- `packages/backend/src/db/schema.sql` — add/define durable columns for schedule creator/edit/delete state; add report active/deleted state if admin deactivate/delete is required.
- `packages/backend/src/db/index.ts` — add idempotent SQLite migrations/backfills for existing databases because schema uses `CREATE TABLE IF NOT EXISTS` plus manual ALTER-style migrations.
- `packages/backend/src/modules/reports/reports.schema.ts` — extend list query with page/pageSize, date/month/user filters, and include permission/status fields in response types.
- `packages/backend/src/modules/reports/reports.service.ts` — replace date-window logic with creator + one-hour rule, add pagination/counts, add active/deleted filtering, and keep Portuguese display fields for history/detail.
- `packages/backend/src/modules/reports/reports.routes.ts` — enforce edit ownership, add admin deactivate/delete endpoints, return pagination metadata, and audit admin actions.
- `packages/backend/src/modules/loading/loading.schema.ts` — add history query schema and permission/status response fields.
- `packages/backend/src/modules/loading/loading.service.ts` — add creator-aware create/update, history listing, read-only permission computation, active/deleted handling, and admin deletion/deactivation behavior.
- `packages/backend/src/modules/loading/loading.routes.ts` — pass authenticated user ID into create/update, add history endpoint, and gate delete/deactivate to Administrador.
- `packages/backend/src/modules/admin/admin.routes.ts` / `admin.service.ts` — expose users for report/schedule filters to all authenticated users via a non-admin endpoint or create a dedicated lightweight users lookup outside admin.
- `packages/frontend/src/App.tsx` — add routes for loading-contained histories, likely `/loading/reports-history` and `/loading/history` or nested equivalents.
- `packages/frontend/src/components/layout/AppShell.tsx` — place both new sections under Cronograma de Carga, either as child links or via the loading landing page.
- `packages/frontend/src/pages/ReportHistoryPage.tsx` — reuse/refactor for a loading-schedule placement, add pagination, month/date/user filters, Portuguese display, and permission-aware edit/admin actions.
- `packages/frontend/src/pages/ReportViewPage.tsx` / `ReportEditPage.tsx` — align read-only/edit buttons with server-provided permissions and Portuguese registered-report display.
- `packages/frontend/src/pages/LoadingSchedulePage.tsx` / `LoadingEditPage.tsx` — restrict delete/edit affordances by permission and admin role.
- New frontend page likely `packages/frontend/src/pages/LoadingHistoryPage.tsx` — schedule history list with the same criteria and permissions as reports.
- `packages/frontend/src/i18n/locales/pt-BR.json` and `es.json` — add nav/filter/pagination/status/action labels.
- `packages/backend/src/modules/reports/reports.routes.test.ts` — add pagination, user/month filters, creator-only one-hour edit, admin deactivate/delete, and read-only tests.
- `packages/backend/src/modules/loading/loading.routes.test.ts` — add creator storage, history listing, permission, admin delete/deactivate, and legacy row behavior tests.
- `packages/frontend/src/__e2e__/report-history.spec.ts` and `loading-schedule.spec.ts` — update E2E coverage for new placement, filters, pagination, and permission-visible UI.

### Approaches
1. **Unified history contracts per module** — keep reports and loading as separate domain endpoints but standardize query/response shape (`items`, `pagination`, `canEdit`, `canDelete`, `isActive`, `deletedAt`).
   - Pros: Fits existing module boundaries; lower coupling; easiest to test incrementally; avoids an over-generic history abstraction before the domain rules settle.
   - Cons: Some duplicate pagination/filter code across reports and loading.
   - Effort: Medium

2. **Generic activity/history service** — create a shared history layer that queries reports and loading schedules through one abstraction.
   - Pros: Centralizes pagination/filter mechanics and permission projection.
   - Cons: Higher upfront complexity; risks hiding important domain differences such as report item rendering versus schedule rows; more refactor pressure.
   - Effort: High

3. **Frontend-only placement with minimal backend changes** — move the existing report history under loading UI and add a schedule history page using current endpoints.
   - Pros: Fastest visible change.
   - Cons: Does not satisfy core permissions, pagination, creator attribution, admin deactivate/delete, or one-hour edit rules; unsafe because current schedule delete/edit is available to all authenticated users.
   - Effort: Low but incomplete

### Recommendation
Use Approach 1. Define module-specific history endpoints with a shared response convention and small shared helpers only where useful. First migrate data/state (`reports.is_active`, `reports.deleted_at`, `loading_schedules.user_id`, `loading_schedules.updated_at`, `loading_schedules.is_active`, `loading_schedules.deleted_at`), then enforce backend permissions before updating frontend affordances. Treat the server as the source of truth for `canEdit`, `canDeactivate`, `canDelete`, and `readOnly`; the frontend should display those decisions, not recalculate them.

Implementation should preserve Portuguese WhatsApp exports and also make registered report display Portuguese by default in the new report history/detail context: use `name_pt`/Portuguese labels for persisted report content even when UI chrome remains translated.

### Risks
- Existing schedules have no creator. A migration must either backfill `user_id` to the admin/default user or mark legacy rows as admin-owned/read-only. This is a product decision because it affects who can edit legacy schedules.
- Changing the report edit window from current/previous day to one hour after creation is a breaking business-rule change and will invalidate current tests/specs.
- Admin physical delete can conflict with foreign keys (`report_items`, `report_temperatures`, `report_photos`) unless implemented as soft delete or cascaded inside a transaction with file cleanup for photos.
- Report detail currently supports Spanish display fields; “registered reports should always display in Portuguese” needs clear scope so UI chrome can remain localized while persisted report content stays PT-BR.
- Existing schedule delete is physical and available to all authenticated users; backend must be fixed before relying on UI hiding.
- Pagination “roughly one month / 30 reports per page” should be specified as `pageSize=30` ordered newest-first, not calendar-month pages, unless product explicitly wants month buckets.

### Ready for Proposal
Yes. The proposal should clarify legacy schedule ownership/backfill, whether admin delete is soft delete or physical delete, route names under Cronograma de Carga, and the exact Portuguese-display scope for report/schedule detail versus surrounding UI chrome.
