# Apply Progress: Improve Loading Schedule and History

## Status

- Change: `improve-loading-schedule-history`
- Apply mode: Standard (strict TDD disabled)
- Workload boundary: `size:exception` accepted by maintainer; implemented all pending work in one apply batch.
- Result: 20/20 tasks completed.

## Completed Tasks

### Phase 1: Backend Contract and Tests

- [x] 1.1 Added backend coverage rejecting duplicate drivers across different slots on the same `schedule_date`.
- [x] 1.2 Added backend export coverage for next-day PT-BR header, visual separators, casa vehicle plate format, and total count.
- [x] 1.3 Added backend history coverage for one grouped item per batch date, total count, filters, and pagination.
- [x] 1.4 Added backend batch lifecycle coverage for transactional date-scoped deactivate/delete.

### Phase 2: Backend Implementation

- [x] 2.1 Updated create/update duplicate checks to reject the same active driver anywhere in the same batch date.
- [x] 2.2 Updated `listScheduleHistory` to group by `schedule_date`, count rows, preserve pagination, filters, and permissions.
- [x] 2.3 Added date-scoped batch deactivate/delete service helpers and routes.
- [x] 2.4 Rewrote loading export format to the requested PT-BR WhatsApp style using batch date + 1 day.
- [x] 2.5 Added `LoadingBatchHistoryItem` response typing.

### Phase 3: Frontend Schedule UX

- [x] 3.1 Removed previous/next/today controls and navigable date state from `LoadingSchedulePage`.
- [x] 3.2 Kept edit/export links tied to today's São Paulo batch date.
- [x] 3.3 Updated `ScheduleGrid` to hide drivers assigned anywhere in the current day.
- [x] 3.4 Verified `DriverSelect` empty-state behavior remains driven by filtered availability.

### Phase 4: Frontend History UX

- [x] 4.1 Updated `LoadingHistoryPage` to consume one batch item per date.
- [x] 4.2 Shows batch date, total loading count, creator/status, edit link, and admin batch actions.
- [x] 4.3 Kept date/month/user filters and pagination aligned with `ReportHistoryPage` style.

### Phase 5: Verification

- [x] 5.1 Updated `loading-schedule.spec.ts` for no date arrows, global driver hiding, and export text.
- [x] 5.2 Updated `loading-history.spec.ts` for grouped batch cards and filters.
- [x] 5.3 Ran `npm run test --workspace=packages/backend` successfully.
- [x] 5.4 Ran `npm run build --workspaces --if-present` successfully.

## Verification Evidence

| Command | Result |
|---------|--------|
| `npm run test --workspace=packages/backend` | Passed: 154 tests, 0 failures |
| `npm run build --workspaces --if-present` | Passed: backend `tsc`, frontend `tsc` + Vite build |
| `npm run test:e2e --workspace=packages/frontend -- src/__e2e__/loading-schedule.spec.ts src/__e2e__/loading-history.spec.ts` | Passed: 15 Playwright tests, 0 failures |

## Files Changed

| File | Action | Summary |
|------|--------|---------|
| `packages/backend/src/modules/loading/loading.service.ts` | Modified | Batch-wide duplicate driver validation, grouped batch history, date-scoped batch deactivate/delete. |
| `packages/backend/src/modules/loading/loading.routes.ts` | Modified | Added batch deactivate/delete routes. |
| `packages/backend/src/modules/loading/loading.export.service.ts` | Modified | Implemented exact PT-BR WhatsApp export with next-day date and total count. |
| `packages/backend/src/modules/loading/loading.schema.ts` | Modified | Added batch history response type. |
| `packages/backend/src/modules/loading/loading.routes.test.ts` | Modified | Added/updated backend coverage for duplicate rule, export, grouped history, and batch lifecycle. |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | Modified | Removed date navigation and fixed schedule/export to today's São Paulo date. |
| `packages/frontend/src/components/loading/ScheduleGrid.tsx` | Modified | Filters assigned driver IDs globally across the day. |
| `packages/frontend/src/pages/LoadingHistoryPage.tsx` | Modified | Renders report-history-like batch cards with filters and batch actions. |
| `packages/frontend/src/__e2e__/loading-schedule.spec.ts` | Modified | Updated E2E expectations for today-only schedule, hidden assigned drivers, and export copy. |
| `packages/frontend/src/__e2e__/loading-history.spec.ts` | Modified | Updated E2E coverage for loading batch history. |
| `openspec/changes/improve-loading-schedule-history/tasks.md` | Modified | Marked all tasks complete. |
| `openspec/changes/improve-loading-schedule-history/apply-progress.md` | Created | Captured cumulative apply progress and verification evidence. |

## Deviations

None — implementation follows the design. Existing row-level delete/deactivate routes remain for backward compatibility while new batch routes power grouped history actions.

## Issues / Follow-up

None found during this apply batch.

## Next Recommended Phase

Run `sdd-verify` for final spec/design/task validation.
