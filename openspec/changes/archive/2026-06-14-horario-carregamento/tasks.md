# Tasks: Horário de Carregamento

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~730 (backend ~300, frontend ~350, E2E ~80) |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Backend API + tests) → PR 2 (Frontend UI + E2E) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Backend loading module: schemas, services, routes, quota enforcement, export, integration tests | PR 1 | Base: main; self-contained API; tests included |
| 2 | Frontend schedule page, grid, driver components, i18n, E2E tests | PR 2 | Base: main (after PR 1 merges); depends on API from PR 1 |

## Phase 1: Backend Foundation

- [x] 1.1 Create `packages/backend/src/modules/loading/loading.schema.ts` with Zod schemas for schedule CRUD, driver creation, and query params (date, time_slot, driver_type enum).
- [x] 1.2 Create `packages/backend/src/modules/loading/loading.service.ts` with `listByDate()`, `create()` (with fletero COUNT quota check ≤ 3), `delete()`, `listDrivers()`, `createDriver()`.
- [x] 1.3 Create `packages/backend/src/modules/loading/loading.export.service.ts` with `generateWhatsAppText(date)` — ascending time slot order, pt-BR headers (Horário/Nome/Placa).
- [x] 1.4 Create `packages/backend/src/modules/loading/loading.routes.ts` with endpoints: `GET /schedules`, `POST /schedules`, `DELETE /schedules/:id`, `GET /export`, `GET /drivers`, `POST /drivers`.
- [x] 1.5 Register loading routes in `packages/backend/src/server.ts` with prefix `/api/loading` and in `test-helper.ts`.

## Phase 2: Backend Tests

- [x] 2.1 Create `packages/backend/src/modules/loading/loading.routes.test.ts` — test list schedules by date returns sorted entries.
- [x] 2.2 Test: POST schedule accepts 3rd fletero (within quota) and rejects 4th fletero with 409.
- [x] 2.3 Test: POST schedule rejects duplicate driver in same slot.
- [x] 2.4 Test: GET export returns pt-BR formatted text in ascending time order; empty schedule returns no-loadings message.
- [x] 2.5 Test: POST/GET drivers — list returns only active; create makes driver available immediately.

## Phase 3: Frontend UI

- [x] 3.1 Create `packages/frontend/src/pages/LoadingSchedulePage.tsx` — date navigation (prev/next/today), fetches schedules by date, renders ScheduleGrid.
- [x] 3.2 Create `packages/frontend/src/components/loading/ScheduleGrid.tsx` — time slot rows (04:00–07:00), driver names, fletero count indicator (N/3), delete button per entry.
- [x] 3.3 Create `packages/frontend/src/components/loading/DriverSelect.tsx` — dropdown of active fleteros to assign to a slot.
- [x] 3.4 Create `packages/frontend/src/components/loading/DriverForm.tsx` — quick-add form (name + optional plate) calling `POST /api/loading/drivers`.
- [x] 3.5 Create `packages/frontend/src/components/loading/ScheduleExportView.tsx` — dialog with WhatsApp text from `GET /api/loading/export`, copy-to-clipboard button.
- [x] 3.6 Modify `packages/frontend/src/App.tsx` — add `/loading` route pointing to `LoadingSchedulePage`.
- [x] 3.7 Modify `packages/frontend/src/components/layout/AppShell.tsx` — enable "Cronograma de Carregamento" sidebar link with `href="/loading"`.
- [x] 3.8 Update `packages/frontend/src/i18n/locales/pt-BR.json` — add loading module keys (schedule UI labels, validation messages, export headers).

## Phase 4: E2E Tests

- [x] 4.1 Create Playwright test: navigate to `/loading`, verify grid loads with time slots for selected date.
- [x] 4.2 Playwright test: assign 3 fleteros to a slot, verify 4th is blocked with error message.
- [x] 4.3 Playwright test: open export modal, verify Portuguese text with ascending time order and copy button present.
