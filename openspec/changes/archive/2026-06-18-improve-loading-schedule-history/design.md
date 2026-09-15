# Design: Improve Loading Schedule and History

## Technical Approach

Keep the existing REST/module shape. Treat `loading_schedules.schedule_date` as the persisted creation/batch date. The schedule page always uses today's São Paulo date; export formats `schedule_date + 1 day`; history groups rows by `schedule_date` into batch summaries.

## Architecture Decisions

| Decision | Choice | Alternatives considered | Rationale |
|----------|--------|-------------------------|-----------|
| Batch identity | Use `schedule_date` as creation-date batch key | Add a new `loading_batches` table | Existing rows already carry the date; no migration needed. |
| Duplicate driver rule | Enforce `driver_id` uniqueness per active `schedule_date` in service create/update | Frontend-only hiding | Backend must protect concurrent clients and edit page flows. |
| History shape | Return grouped batch items from `/loading/schedules/history` | Keep row API and group only in UI | Pagination/filter totals must count batches, not rows. |
| Export date | Compute display date in backend export service | Compute in UI | WhatsApp/PDF/TXT exports stay consistent across clients. |

## Data Flow

    LoadingSchedulePage(today) ──→ GET /loading/schedules?date=today
             │                    └─ rows stored under creation date
             ├─ DriverSelect filters assigned driver_ids across all rows
             └─ Export modal ──→ GET /loading/export?date=today
                                      └─ text renders today + 1 day

    LoadingHistoryPage filters ──→ GET /loading/schedules/history
                                      └─ grouped by schedule_date batch

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/modules/loading/loading.schema.ts` | Modify | Add batch history response typing if needed; keep query filters. |
| `packages/backend/src/modules/loading/loading.service.ts` | Modify | Enforce driver-per-date uniqueness; group history by `schedule_date`; add batch deactivate/delete helpers. |
| `packages/backend/src/modules/loading/loading.routes.ts` | Modify | Wire grouped history and batch lifecycle routes/parameters while preserving auth. |
| `packages/backend/src/modules/loading/loading.export.service.ts` | Modify | Render exact PT-BR WhatsApp layout with next-day date and total count. |
| `packages/backend/src/modules/loading/loading.routes.test.ts` | Modify | Replace row-history/export assertions and add duplicate-per-day coverage. |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | Modify | Remove date URL navigation controls; fetch today's batch; pass global assigned IDs. |
| `packages/frontend/src/components/loading/ScheduleGrid.tsx` | Modify | Compute assigned IDs from all schedules, not only current slot. |
| `packages/frontend/src/components/loading/DriverSelect.tsx` | Modify | Continue hiding provided assigned IDs; validate empty state copy. |
| `packages/frontend/src/pages/LoadingHistoryPage.tsx` | Modify | Render report-history-like batch cards with date, count, creator/status/actions. |
| `packages/frontend/src/components/loading/ScheduleExportView.tsx` | Modify | Keep actions; consume current batch date. |
| `packages/frontend/src/__e2e__/loading-schedule.spec.ts` | Modify | Remove nav expectations; cover global driver hiding and export text. |
| `packages/frontend/src/__e2e__/loading-history.spec.ts` | Modify | Cover one card per batch and filters. |

## Interfaces / Contracts

```ts
interface LoadingBatchHistoryItem {
  batch_date: string;          // persisted creation date, YYYY-MM-DD
  loading_date: string;        // display/export date, batch_date + 1 day
  total_loadings: number;
  created_at: string | null;
  updated_at: string | null;
  isActive: boolean;
  canEdit: boolean;
  canDeactivate: boolean;
  canDelete: boolean;
  creator: { id: number; display_name: string } | null;
}
```

Batch lifecycle endpoints may either accept `date` query params on existing admin routes or add date-scoped routes; implementation should choose the smallest change that keeps tests clear.

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Backend | Duplicate driver across slots, grouped history pagination/filters, batch delete/deactivate, exact export text | `loading.routes.test.ts` with seeded rows. |
| Frontend E2E | No date arrows, assigned driver hidden in other slots, export modal text, grouped history filters | Existing Playwright specs. |
| Build | Type compatibility for new history item contract | `npm run build --workspaces --if-present`. |

## Migration / Rollout

No schema migration required. Existing rows naturally group by `schedule_date`; legacy rows with `user_id = NULL` remain read-only for workers and admin-manageable at batch level.

## Open Questions

- [ ] If a day has rows from multiple creators, implementation should choose deterministic creator display (first creator, mixed label, or null); plan assumes first non-null creator unless product says otherwise.
