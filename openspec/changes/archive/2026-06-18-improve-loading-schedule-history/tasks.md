# Tasks: Improve Loading Schedule and History

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 550-800 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 backend rules/export/history → PR 2 frontend schedule/history → PR 3 E2E polish |
| Delivery strategy | exception-ok |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: size-exception
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Backend contract and tests | PR 1 | Duplicate rule, grouped history, export format. |
| 2 | Frontend schedule/history UX | PR 2 | Today-only schedule and batch cards. |
| 3 | E2E/build verification | PR 3 | Update Playwright expectations and fix polish. |

## Phase 1: Backend Contract and Tests

- [x] 1.1 Add backend tests for duplicate driver across different slots on the same `schedule_date`.
- [x] 1.2 Add backend tests for export next-day PT-BR header, exact visual separators, casa plate format, and total count.
- [x] 1.3 Add backend tests for `/loading/schedules/history` returning one item per batch date with total count and filters.
- [x] 1.4 Add backend tests for admin batch deactivate/delete applying transactionally to all rows in a date batch.

## Phase 2: Backend Implementation

- [x] 2.1 Update `loading.service.ts` create/update duplicate checks from same slot to same active batch date.
- [x] 2.2 Update `listScheduleHistory` to group by `schedule_date`, count rows, preserve pagination, filters, and permissions.
- [x] 2.3 Add/adjust service helpers and routes for date-scoped batch deactivate/delete.
- [x] 2.4 Rewrite `loading.export.service.ts` to render the exact requested WhatsApp style using `date + 1 day`.
- [x] 2.5 Update `loading.schema.ts` response types/interfaces for batch history items.

## Phase 3: Frontend Schedule UX

- [x] 3.1 Update `LoadingSchedulePage.tsx` to remove previous/next/today controls and ignore navigable date state.
- [x] 3.2 Update edit/export links to use today's São Paulo batch date consistently.
- [x] 3.3 Update `ScheduleGrid.tsx` to pass all assigned driver IDs for the day into `DriverSelect`.
- [x] 3.4 Verify `DriverSelect.tsx` empty-state copy still works when all drivers are already assigned.

## Phase 4: Frontend History UX

- [x] 4.1 Update `LoadingHistoryPage.tsx` item type and rendering to one report-history-like card per batch.
- [x] 4.2 Show batch date, total loading count, creator/status, edit link, and admin batch actions.
- [x] 4.3 Keep date/month/user filters and pagination aligned with `ReportHistoryPage.tsx`.

## Phase 5: Verification

- [x] 5.1 Update `loading-schedule.spec.ts` for no date arrows, global driver hiding, and export text.
- [x] 5.2 Update `loading-history.spec.ts` for one card per batch and filters.
- [x] 5.3 Run `npm run test --workspace=packages/backend`.
- [x] 5.4 Run `npm run build --workspaces --if-present`.
