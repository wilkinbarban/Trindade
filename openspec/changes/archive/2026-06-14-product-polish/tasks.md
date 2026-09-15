# Tasks: Product Polish

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 280–350 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | auto-chain (not triggered — under budget) |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: stacked-to-main
400-line budget risk: Low

## Phase 1: Foundation

- [x] 1.1 Create `packages/frontend/src/components/ui/loading-spinner.tsx` exporting `LoadingSpinner({ className?: string })` per design interface.
- [x] 1.2 Create `packages/frontend/src/i18n/locales/es.json` as empty skeleton `{}`.
- [x] 1.3 Update `packages/frontend/src/i18n/config.ts` — import `es.json` and add `'es': { translation: es }` to `resources`.

## Phase 2: Core Implementation

- [x] 2.1 In `packages/frontend/src/App.tsx` — remove inline `LoadingSpinner` function (lines 15–21), import shared component from `components/ui/loading-spinner`.
- [x] 2.2 Replace inline spinner in `packages/frontend/src/components/loading/ScheduleExportView.tsx` with shared `LoadingSpinner`.
- [x] 2.3 Replace inline spinner in `packages/frontend/src/components/reports/ExportPreview.tsx` with shared `LoadingSpinner`.
- [x] 2.4 Scan remaining pages (`DashboardPage`, `LoginPage`, `LoadingSchedulePage`, `LoadingEditPage`, `ReportHistoryPage`, `AuditPage`) — replace any inline spinner markup with shared `LoadingSpinner`.
- [x] 2.5 Add `role="alert"` and `aria-live="assertive"` to error/success banner divs in `LoginPage`, `DashboardPage`, and `AdminDashboard`.
- [x] 2.6 In `packages/frontend/src/pages/admin/AdminDashboard.tsx` — replace all hardcoded PT-BR string literals with `t()` calls.
- [x] 2.7 Add corresponding keys to `packages/frontend/src/i18n/locales/pt-BR.json` for all AdminDashboard strings extracted in 2.6.
- [x] 2.8 Fix `reloadData()` re-render flash in `AdminDashboard` (stabilize callback reference or loading state).
- [x] 2.9 Fix stale `deferredNotice` copy in `AdminDashboard`.
- [x] 2.10 In `ReportsPage` and `ReportViewPage` — separate emojis from `t()` calls; render emojis in JSX, keep translation values semantic.
- [x] 2.11 Remove emoji characters from `turno` and related keys in `pt-BR.json`.

## Phase 3: Verification

- [x] 3.1 Run full Playwright E2E suite — update text assertions if hardcoded PT-BR strings changed.
- [x] 3.2 Manual: verify `LoadingSpinner` renders consistently on all 9 pages.
- [x] 3.3 Manual: inspect error/success banners in dev tools to confirm `role="alert"` and `aria-live="assertive"` presence.
- [x] 3.4 Manual: switch locale to `es` — confirm fallback to `pt-BR` without crash.
