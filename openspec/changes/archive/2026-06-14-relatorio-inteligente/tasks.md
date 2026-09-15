# Tasks: Relatório Inteligente

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~900–1100 (4 backend new + 5 frontend new + 4 modifications + tests) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (backend core + export + tests) → PR 2 (frontend builder + history + export UI) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Backend: schema, service, export, routes, integration tests | PR 1 | base: main; self-contained API, no frontend dependency |
| 2 | Frontend: builder, history, export preview, nav, i18n | PR 2 | base: main after PR 1 merges; depends on PR 1 API |

## Phase 1: Backend Foundation (PR 1)

- [x] 1.1 Create `packages/backend/src/modules/reports/reports.schema.ts` with Zod schemas for `CreateReportBody`, `UpdateReportBody`, `ReportQuery`, and response types. (Used Zod per project convention — design referenced TypeBox but project uses Zod.)
- [x] 1.2 Create `packages/backend/src/modules/reports/reports.service.ts` with `createReport()`, `getReport()`, `listReports()`, `updateReport()`, and edit-window validation (`report_date >= date('now','localtime','-1 day')`).
- [x] 1.3 Create `packages/backend/src/modules/reports/export.service.ts` with `generateWhatsAppText(reportId)` — emoji section headers, bulleted task lists, strict pt-BR using `name_pt` fields.
- [x] 1.4 Create `packages/backend/src/modules/reports/reports.routes.ts` with `POST /`, `GET /`, `GET /:id`, `PATCH /:id`, `GET /:id/export`, plus `GET /categories`, `GET /products`, `GET /turno` — all behind `fastify.authenticate`.
- [x] 1.5 Register `reportsRoutes` in `packages/backend/src/server.ts` with prefix `/api/reports`.
- [x] 1.6 Register `reportsRoutes` in `packages/backend/src/test-helper.ts` for test injection.

## Phase 2: Backend Tests (PR 1)

- [x] 2.1 Create `packages/backend/src/modules/reports/reports.routes.test.ts` — test: create report returns 201, list returns reports, edit-window blocks old reports (403), unauthenticated returns 401, plus categories/products/turno/patch/export endpoints (15 tests).
- [x] 2.2 Create `packages/backend/src/modules/reports/export.service.test.ts` — test: output contains emoji headers, all text is pt-BR, task sections match categories, temperatures and quantities formatted correctly, notes section, noite/tarde emoji, PT-only language enforcement (10 tests).

## Phase 3: Frontend Builder & Components (PR 2)

- [x] 3.1 Create `packages/frontend/src/components/reports/CategorySection.tsx` — renders dynamic inputs per `task_type`: check (toggle), list (dropdown of products), temperature (numeric), quantity (numeric).
- [x] 3.2 Create `packages/frontend/src/pages/ReportsPage.tsx` — builder form with turno auto-detection (server-time via API), category sections, notes, submit via `POST /api/reports`.
- [x] 3.3 Create `packages/frontend/src/components/reports/ExportPreview.tsx` — dialog fetching `GET /:id/export`, displays WhatsApp text, copy-to-clipboard button.
- [x] 3.4 Create `packages/frontend/src/pages/ReportViewPage.tsx` — single report detail with export button triggering `ExportPreview`.

## Phase 4: Frontend History & Wiring (PR 2)

- [x] 4.1 Create `packages/frontend/src/pages/ReportHistoryPage.tsx` — list with date filters (Today, Yesterday, Last 7, Last 30, Custom), links to `ReportViewPage`.
- [x] 4.2 Add `/reports`, `/reports/history`, `/reports/:id` routes in `packages/frontend/src/App.tsx` as `ProtectedRoute` children.
- [x] 4.3 Enable "Relatórios" `NavItem` in `packages/frontend/src/components/layout/AppShell.tsx` — set `href="/reports"`, remove `disabled`.
- [x] 4.4 Add report UI strings to `packages/frontend/src/i18n/locales/pt-BR.json` under `reports.*` key (builder, history, export labels).

## Phase 5: Verification

- [x] 5.1 Run `npm test` in backend — all report route and export service tests pass.
- [x] 5.2 Run frontend dev server — builder form renders categories, submit creates report, history lists it, export copies pt-BR text.

## Phase 6: Frontend E2E Test Coverage (Remediation)

- [x] 6.1 Create `packages/backend/src/e2e-server.ts` — standalone Fastify server with in-memory SQLite + seed data for E2E test backend.
- [x] 6.2 Install `@playwright/test` + Chromium in frontend; create `playwright.config.ts` with dual webServer config (backend e2e-server + Vite dev server).
- [x] 6.3 Add `data-testid` attributes to `CategorySection.tsx` (check-task-*, list-task-*, temperature-task-*, quantity-task-*), `ReportsPage.tsx` (form, turno, notes, submit), `ReportHistoryPage.tsx` (period-filter-*, custom dates, filter btn, report-card-*, history-new-report-btn), `ReportViewPage.tsx` (report-detail, export-whatsapp-btn), `ExportPreview.tsx` (export-modal, export-text-content, copy-export-btn, close-export-btn), `LoginPage.tsx` (already had id attrs).
- [x] 6.4 Update `vite.config.ts` to support `VITE_API_TARGET` env var for proxy override during E2E.
- [x] 6.5 Create `src/__e2e__/auth-helpers.ts` — programmatic login via API + addInitScript token injection.
- [x] 6.6 Create `src/__e2e__/report-builder.spec.ts` — 7 tests: check tasks render and toggle, list tasks show product checkboxes, temperature numeric inputs, quantity numeric inputs, turno selector visible, notes textarea, submit button present.
- [x] 6.7 Create `src/__e2e__/report-history.spec.ts` — 8 tests: period filter tabs, Today active by default, switch to Yesterday/7-day/30-day, custom date inputs, custom submit, new-report button navigates to builder.
- [x] 6.8 Create `src/__e2e__/report-view-export.spec.ts` — 5 tests: create report → detail renders, export button opens modal with pt-BR text, close modal, copy button shows Copied! feedback, clipboard contains export text.
- [x] 6.9 Add `test:e2e` and `test:e2e:ui` scripts to `packages/frontend/package.json`.
- [x] 6.10 Run full E2E suite — 20/20 tests pass; backend tests 42/42 pass; frontend typecheck + build pass.
