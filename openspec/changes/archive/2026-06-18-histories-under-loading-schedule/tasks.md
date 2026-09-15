# Tasks: Histories Under Loading Schedule

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 900-1,300 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 backend → PR 2 frontend → PR 3 tests |
| Delivery strategy | exception-ok |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: size-exception
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | DB and permission primitives | PR 1 | Backend-only. |
| 2 | History APIs and admin lifecycle | PR 2 | Depends on Unit 1. |
| 3 | Frontend navigation and pages | PR 3 | Depends on Unit 2. |

## Phase 1: Database and Permission Foundation

- [x] 1.1 Modify `packages/backend/src/db/schema.sql`: add state/creator columns and history indexes.
- [x] 1.2 Modify `packages/backend/src/db/index.ts`: add idempotent migrations; keep legacy schedules `user_id=NULL`.
- [x] 1.3 Add shared permission projection in report/loading services: server flags and one-hour edit window.
- [x] 1.4 Add authenticated non-admin user options endpoint, for date/month/user filters.

## Phase 2: Backend History and Lifecycle APIs

- [x] 2.1 Modify `packages/backend/src/modules/reports/reports.schema.ts`: add history query/page schemas.
- [x] 2.2 Modify `packages/backend/src/modules/reports/reports.service.ts`: add filters, newest-first page, PT-BR content, active visibility.
- [x] 2.3 Modify `packages/backend/src/modules/reports/reports.routes.ts`: add history, admin deactivate, transactional physical delete, and one-hour mutation guards including photos.
- [x] 2.4 Modify `packages/backend/src/modules/loading/loading.schema.ts`: add history query/page schemas.
- [x] 2.5 Modify `packages/backend/src/modules/loading/loading.service.ts`: store creator, add history page, deny legacy/non-creator/expired mutations.
- [x] 2.6 Modify `packages/backend/src/modules/loading/loading.routes.ts`: add history, admin deactivate/delete, and admin-only guards for lifecycle actions.

## Phase 3: Frontend Navigation and Pages

- [x] 3.1 Modify `packages/frontend/src/App.tsx`: add `/loading/reports-history`, `/loading/history`, and old route compatibility if practical.
- [x] 3.2 Modify `packages/frontend/src/components/layout/AppShell.tsx`: add Historial de Reportes and Historial de Cargamento under Cronograma de Carregamento.
- [x] 3.3 Modify `packages/frontend/src/pages/ReportHistoryPage.tsx`: use new endpoint, pagination, filters, flags, PT-BR domain content.
- [x] 3.4 Create `packages/frontend/src/pages/LoadingHistoryPage.tsx`: mirror report filters/page/actions.
- [x] 3.5 Modify `ReportViewPage.tsx`, `ReportEditPage.tsx`, `LoadingSchedulePage.tsx`, `LoadingEditPage.tsx`: render actions from server flags only.
- [x] 3.6 Update `packages/frontend/src/i18n/locales/pt-BR.json` and `es.json` with history/filter/action labels.

## Phase 4: Tests and Verification

- [x] 4.1 Add backend route tests for migrations, filters, permissions, legacy rows, admin lifecycle.
- [x] 4.2 Update Playwright specs for navigation, filters, pagination, permissions, PT-BR content.
- [x] 4.3 Run `npm run test --workspace=packages/backend` and `npm run build --workspaces --if-present`; fix regressions before handoff.
