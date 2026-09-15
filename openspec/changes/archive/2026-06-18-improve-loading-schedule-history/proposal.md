# Proposal: Improve Loading Schedule and History

## Intent

Align loading operations with the real flow: today users create the batch for tomorrow's loading, drivers cannot be duplicated in the day, exports use the polished PT-BR WhatsApp format, and history shows one batch per day.

## Scope

### In Scope
- Hide drivers already assigned anywhere in today's batch.
- Remove date arrows/today navigation from Cronograma de Carga.
- Store loading rows under today's creation date; export displays tomorrow's weekday/date.
- Add requested export style and total count line.
- Show Historial de Cargamento as one report-history-like row/card per day batch with filters.
- Enforce one active loading batch per day.

### Out of Scope
- Deployment or `/trindade` subpath changes.
- Time-slot configuration changes.
- Schema migration; existing rows group by `schedule_date`.

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `loading-schedule`: Daily scheduling, driver availability, batch history, and one-batch-per-day rules.
- `loading-export`: Next-day WhatsApp export date and exact text style.

## Approach

Use `schedule_date` as the creation/batch date. The schedule page loads today only. Backend rejects duplicate active drivers per date and returns grouped history batches. Export receives the batch date but renders `batch date + 1 day` in PT-BR.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/modules/loading/*` | Modified | Validation, grouped history, batch actions, export. |
| `packages/frontend/src/pages/LoadingSchedulePage.tsx` | Modified | Today-only schedule UI. |
| `packages/frontend/src/pages/LoadingHistoryPage.tsx` | Modified | Batch cards matching report history. |
| `packages/frontend/src/components/loading/*` | Modified | Global assigned-driver filtering. |
| Loading tests/E2E | Modified | New contracts and regressions. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Per-row history tests break | High | Replace with grouped batch expectations. |
| Date semantics confusion | Medium | Name code as batch date vs loading date. |
| Batch actions affect many rows | Medium | Use transactions. |

## Rollback Plan

Revert implementation commits; current per-row history and export format remain baseline. No schema rollback needed.

## Dependencies

- Existing `loading_schedules.schedule_date`, `created_at`, `user_id`, `is_active`.

## Success Criteria

- [ ] Assigned drivers are hidden from every other slot.
- [ ] Cronograma has no date navigation.
- [ ] Export displays tomorrow's PT-BR date and exact requested style.
- [ ] History shows one filtered row/card per day batch.
- [ ] Backend rejects duplicate active driver assignment in the same day.
