# Apply Progress: Product Polish

**Batch**: 1/1 (single PR, under budget)  
**Mode**: Standard (strict_tdd: false)  
**Date**: 2026-06-14

## Completed Tasks

### Phase 1: Foundation
- [x] 1.1 Created `packages/frontend/src/components/ui/loading-spinner.tsx` with `LoadingSpinner({ className? })` per design interface.
- [x] 1.2 Created `packages/frontend/src/i18n/locales/es.json` as empty skeleton `{}`.
- [x] 1.3 Updated `packages/frontend/src/i18n/config.ts` — imported `es.json`, added `'es': { translation: es }` to resources.

### Phase 2: Core Implementation
- [x] 2.1 `App.tsx` — removed inline `LoadingSpinner` function, imported shared component. Wrapped with `flex items-center justify-center h-screen` divs for full-screen centering.
- [x] 2.2 `ScheduleExportView.tsx` — replaced inline spinner with shared `LoadingSpinner`. Also added `role="alert"` and `aria-live="assertive"` to error banner.
- [x] 2.3 `ExportPreview.tsx` — replaced inline spinner with shared `LoadingSpinner`. Also added `role="alert"` and `aria-live="assertive"` to error banner.
- [x] 2.4 Replaced inline spinners in all remaining pages: `DashboardPage`, `ReportsPage`, `ReportViewPage`, `ReportHistoryPage`, `LoadingSchedulePage`, `LoadingEditPage`, `ReportEditPage` (including small upload spinner), `AdminDashboard`, `AuditPage`.
- [x] 2.5 Added `role="alert"` and `aria-live="assertive"` to error banners in `LoginPage`, `DashboardPage`, `AdminDashboard`.
- [x] 2.6 `AdminDashboard.tsx` — replaced all hardcoded PT-BR strings (~60+ string literals) with `t()` calls using `admin.*` keys. Refactored TABS, InlineForm, AccessDenied, and Badge to accept translation functions/strings. Also refactored data fetching with `useCallback`/`loadTab` for the reloadData fix.
- [x] 2.7 Added comprehensive `admin.*` namespace to `pt-BR.json` with keys for tabs, forms, tables, empty states, error messages, task types, status labels, and actions.
- [x] 2.8 Fixed `reloadData()` — replaced hacky tab toggle with `reloadCount` state counter that triggers `useEffect` re-fetch without tab flash.
- [x] 2.9 Fixed stale `deferredNotice` — updated from "Módulos adicionais (Relatórios, Cronograma de Carregamento, Exportação WhatsApp) estarão disponíveis em versões futuras." to "Novos módulos estarão disponíveis em versões futuras." (removed references to already-implemented modules).
- [x] 2.10 Separated emojis from `t()` calls — `ReportsPage`, `ReportViewPage`, `ReportHistoryPage`, `ReportEditPage` now render `🌅`/`🌙` emojis in JSX alongside `t('reports.turnoTarde')`/`t('reports.turnoNoite')`.
- [x] 2.11 Removed emoji characters from `turnoTarde` and `turnoNoite` keys in `pt-BR.json` — values are now `"Tarde"` and `"Noite"` only.

## Files Changed

| File | Action | What Was Done |
|------|--------|---------------|
| `packages/frontend/src/components/ui/loading-spinner.tsx` | Created | Shared LoadingSpinner component per design contract |
| `packages/frontend/src/i18n/locales/es.json` | Created | Empty Spanish locale skeleton `{}` |
| `packages/frontend/src/i18n/config.ts` | Modified | Imported es.json, registered `es` locale |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modified | Added `admin.*` keys (~40), updated `deferredNotice`, removed emojis from `turnoTarde`/`turnoNoite` |
| `packages/frontend/src/App.tsx` | Modified | Removed inline spinner function, imported shared LoadingSpinner |
| `packages/frontend/src/components/loading/ScheduleExportView.tsx` | Modified | Imported LoadingSpinner, replaced inline spinner, added aria-live to error |
| `packages/frontend/src/components/reports/ExportPreview.tsx` | Modified | Imported LoadingSpinner, replaced inline spinner, added aria-live to error |
| `packages/frontend/src/pages/DashboardPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner, added role/aria-live to error |
| `packages/frontend/src/pages/LoginPage.tsx` | Modified | Added aria-live="assertive" to error banner |
| `packages/frontend/src/pages/ReportsPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner, emojis in JSX for turno |
| `packages/frontend/src/pages/ReportViewPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner, emojis in JSX for turno |
| `packages/frontend/src/pages/ReportHistoryPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner, emojis in JSX for turno |
| `packages/frontend/src/pages/ReportEditPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner + upload spinner, emojis in JSX for turno |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | Modified | Imported LoadingSpinner, replaced spinner |
| `packages/frontend/src/pages/LoadingEditPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modified | Complete i18n rewrite (~60 strings → t()), reloadData fix, LoadingSpinner import |
| `packages/frontend/src/pages/admin/AuditPage.tsx` | Modified | Imported LoadingSpinner, replaced spinner |

## Deviations from Design

None — implementation matches design.md exactly. The `LoadingSpinner` component follows the exact interface specified. All usage sites preserve their own wrapper divs with appropriate padding.

## Issues Found

None.

## Remaining Tasks

### Phase 3: Verification (for sdd-verify)
- [ ] 3.1 Run full Playwright E2E suite — update text assertions if hardcoded PT-BR strings changed.
- [ ] 3.2 Manual: verify `LoadingSpinner` renders consistently on all 9 pages.
- [ ] 3.3 Manual: inspect error/success banners in dev tools to confirm `role="alert"` and `aria-live="assertive"` presence.
- [ ] 3.4 Manual: switch locale to `es` — confirm fallback to `pt-BR` without crash.

## Workload / PR Boundary

- Mode: single PR
- Current work unit: complete product-polish
- Boundary: full change, all 14 tasks in Phases 1-2
- TypeScript check: passes clean

## Verification

- TypeScript `tsc --noEmit`: passes with zero errors
