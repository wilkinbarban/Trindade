# Design: Histories Under Loading Schedule

## Technical Approach
Keep the existing REST modules (`reports`, `loading`) and add parallel history contracts instead of a generic feed. The backend remains the source of truth for permissions, pagination, active state, and read-only behavior; the frontend renders server flags under **Cronograma de Carregamento**.

## Architecture Decisions

| Decision | Choice | Alternatives considered | Rationale |
|---|---|---|---|
| History shape | Separate `/api/reports/history` and `/api/loading/schedules/history` with the same envelope | Reuse current `/api/reports`; generic activity endpoint | Preserves module boundaries and avoids mixing report/schedule domain rules. |
| Deletion | Admin-only physical delete in DB transactions | Soft delete only | Product decision requires permanent deletion; transactions protect dependent rows. |
| Deactivation | Add `is_active` for visible inactive records | Use `deleted_at` as inactive marker | Specs require inactive records remain visible; deleted records must disappear. |
| Legacy schedules | `user_id NULL` rows are read-only for regular users, admin-manageable | Backfill to admin/default user | Avoids inventing ownership. Legacy data remains safe and explicit. |
| Edit rule | `creator && created_at + 1 hour` for reports and schedules | Current report-date window | Matches new business rule and removes client-side date guessing. |

## Data Flow

```text
History page -> API history endpoint -> service builds SQL filters/page
                                  -> permission projector(current user, row)
                                  -> { items, pagination, flags }
Admin action -> route role guard -> service transaction -> audit log -> refreshed list
```

## File Changes

| File | Action | Description |
|---|---|---|
| `packages/backend/src/db/schema.sql` | Modify | Add `reports.is_active`; add `loading_schedules.user_id`, `updated_at`, `is_active`; add indexes for history filters. No `deleted_at`: admin delete is physical. |
| `packages/backend/src/db/index.ts` | Modify | Idempotent `ALTER TABLE` migrations; leave existing `loading_schedules.user_id` as `NULL` for legacy rows. |
| `packages/backend/src/modules/reports/*` | Modify | History query, permission projection, creator-only edit, admin deactivate/delete, photo mutation guard. |
| `packages/backend/src/modules/loading/*` | Modify | Store `request.user.sub` on create, history query, permission-aware update/delete/deactivate. |
| `packages/frontend/src/App.tsx` | Modify | Add `/loading/reports-history` and `/loading/history`. Keep old `/reports/history` redirect/compat if practical. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modify | Add child links under Cronograma de Carregamento. |
| `packages/frontend/src/pages/ReportHistoryPage.tsx` | Modify | New filters, pagination, flags/actions, Portuguese content rendering; load user options from an authenticated lightweight users endpoint. |
| `packages/frontend/src/pages/LoadingHistoryPage.tsx` | Create | Loading schedule history list with same filters/actions. |
| `packages/frontend/src/pages/ReportViewPage.tsx`, `ReportEditPage.tsx`, `LoadingSchedulePage.tsx`, `LoadingEditPage.tsx` | Modify | Hide/disable actions from server flags; no local edit-window authority. |
| `packages/frontend/src/i18n/locales/*.json` | Modify | Add PT-BR/ES UI labels for history, filters, pagination, inactive/read-only/admin actions. |
| `packages/backend/src/modules/*/*.test.ts`, `packages/frontend/src/__e2e__/*.spec.ts` | Modify | Add backend and Playwright coverage. |

## Interfaces / Contracts

`GET /api/reports/history?date=YYYY-MM-DD&month=YYYY-MM&userId=1&page=1&pageSize=30`
`GET /api/loading/schedules/history?...same filters...`

```ts
type HistoryPage<T> = {
  items: Array<T & {
    creator: { id: number; display_name: string } | null;
    isActive: boolean;
    readOnly: boolean;
    canEdit: boolean;
    canDeactivate: boolean;
    canDelete: boolean;
  }>;
  pagination: { page: number; pageSize: number; total: number; totalPages: number };
};
```

Filter users: add authenticated `GET /api/users/options` (or equivalent non-admin lookup) returning active users. Admin actions: `PATCH /api/reports/:id/deactivate`, `DELETE /api/reports/:id`, `PATCH /api/loading/schedules/:id/deactivate`, `DELETE /api/loading/schedules/:id`. Deletes run a transaction; report delete removes `report_photos`, `report_items`, `report_temperatures`, then `reports`, with best-effort file cleanup after DB success. All admin actions audit `deactivate`/`delete`.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Backend | migrations, history filters/page/order, permission flags, creator one-hour rule, legacy schedule read-only, admin deactivate/delete transactions | `node:test` route/service tests with seeded rows and multiple users. |
| Frontend E2E | navigation under Cronograma, filters, pagination, read-only UI, admin buttons, Portuguese domain display in ES locale | Playwright specs extending report/loading history coverage. |
| Regression | existing report exports and loading schedule quota | Existing backend + Playwright suites must stay green. |

## Migration / Rollout
1. Add nullable/state columns and indexes idempotently.
2. Deploy backend guards before frontend action buttons.
3. Existing reports get `is_active=1`; existing schedules keep `user_id=NULL`, `is_active=1`, `updated_at=created_at`.

## Open Questions

- [ ] None.
