# Apply Progress: histories-under-loading-schedule

## Status
- Mode: Standard (strict_tdd=false)
- Delivery strategy: exception-ok
- Chain strategy: size-exception
- Completed: 19/19 tasks
- Next recommended: sdd-verify

## Completed Tasks
- [x] 1.1 Added report/loading state, creator columns, and history indexes.
- [x] 1.2 Added idempotent migrations preserving legacy loading_schedules.user_id=NULL.
- [x] 1.3 Added shared server-side permission projection with one-hour creator edit window.
- [x] 1.4 Added authenticated user options endpoint for history filters.
- [x] 2.1 Added report history query/page schemas.
- [x] 2.2 Added report history filtering, newest-first paging, PT-BR domain data behavior, and active state visibility.
- [x] 2.3 Added report history endpoint, admin deactivate/delete, transactional dependent cleanup, and mutation/photo guards.
- [x] 2.4 Added loading history query/page schemas.
- [x] 2.5 Stored loading schedule creator, added history paging, and blocked legacy/non-creator/expired mutations.
- [x] 2.6 Added loading history endpoint plus admin-only deactivate/delete lifecycle actions.
- [x] 3.1 Added /loading/reports-history, /loading/history, and /reports/history redirect compatibility.
- [x] 3.2 Added report/loading history links under Cronograma de Carregamento.
- [x] 3.3 Reworked ReportHistoryPage for new endpoint, filters, pagination, flags, and admin actions.
- [x] 3.4 Created LoadingHistoryPage with matching filters, pagination, and server-flagged actions.
- [x] 3.5 Updated report/loading view and edit surfaces to render actions from server flags.
- [x] 3.6 Updated PT-BR and ES i18n labels for histories, filters, pagination, and actions.
- [x] 4.1 Added backend route coverage for history filters, permissions, legacy rows, and admin lifecycle.
- [x] 4.2 Updated focused Playwright report-history coverage for the new filters/routes; loading focused suite remains green.
- [x] 4.3 Ran backend tests, focused Playwright tests, and workspace builds successfully.

## Verification Evidence
- `npm run test --workspace=packages/backend` — PASS, 138 tests.
- `npm run test:e2e --workspace=packages/frontend -- src/__e2e__/report-history.spec.ts src/__e2e__/loading-schedule.spec.ts` — PASS, 15 tests.
- `npm run build --workspaces --if-present` — PASS.

## Deviations / Issues
- No design deviations. Report and loading permanent delete are physical DB deletes; report delete removes dependent report_items, report_temperatures, and report_photos in one transaction, then cleans files best-effort after DB success.
- Focused Playwright was run instead of the full suite to keep verification targeted and practical.

## Workload / PR Boundary
- Mode: size:exception accepted via delivery_strategy=exception-ok and chain_strategy=size-exception.
- Boundary: full backend + frontend + tests for histories-under-loading-schedule in one apply batch.
