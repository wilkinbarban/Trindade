# Design: Limited Worker Admin Panel

## Technical Approach

Extend the existing Fastify `/api/admin` module from admin-only guards to route-level policy checks that preserve full `Administrador` behavior and add limited `Trabalhador` access (the code/seed use Portuguese role spelling). Persist ownership on newly created tasks and drivers, then enforce ownership in backend services before any update. The frontend keeps the current `AdminDashboard` shape but renders a restricted panel for workers: tasks, fletero drivers, and profile.

## Architecture Decisions

| Decision | Choice | Alternatives considered | Rationale |
|---|---|---|---|
| Role policy | Replace blanket `adminGuard` only on task/driver read/create/update with `authenticate` plus explicit role/ownership checks; keep category, vehicle, slot, and user admin routes admin-only. | Duplicate a worker-only API module. | Reuses current route/service contracts and avoids drift; backend still owns authorization. |
| Ownership metadata | Add nullable `created_by_user_id INTEGER REFERENCES users(id)` to `report_tasks` and `drivers`; service create methods accept creator id. | Separate ownership table. | Simple SQLite migration, direct query predicates, and legacy rows remain valid. |
| Legacy rows | Existing rows keep `created_by_user_id = NULL`; only admins can edit/delete/deactivate them. | Backfill all legacy rows to an admin. | Null clearly means system/legacy-owned; prevents workers from claiming old shared catalog data. |
| Worker updates | Worker task/driver update schemas reject `is_active`; driver policy also rejects `driver_type !== 'fletero'` and existing non-fletero rows. | Hide fields only in UI. | Specs require backend enforcement; UI hiding is not security. |
| Profile | Add `/api/auth/profile` GET/PATCH for current user name/display name; keep `/change-password` current-user only. | Let workers call `/admin/users/:id`. | Avoids exposing user administration and keeps self-service under auth. |

## Data Flow

```text
Worker UI ──POST/PATCH /api/admin/tasks or drivers──> route policy
   │                                                   │
   │                                                   ├─ role = Administrador: existing full path
   │                                                   └─ role = Trabalhador: validate owned/fletero/no deactivate
   └──GET/PATCH /api/auth/profile────────────────────> current user row only
```

Worker-created tasks are normal active `report_tasks`, so `reports.service` continues listing them by active category/task. Worker-created fleteros are normal active `drivers`, so `loading` driver listing continues returning them when active.

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/db/schema.sql` | Modify | Add nullable `created_by_user_id` to `report_tasks` and `drivers`. |
| `packages/backend/src/db/index.ts` | Modify | Add idempotent `ensureColumn` migrations and indexes for ownership columns. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modify | Add ownership fields to row types; add worker-safe schemas or route-level payload stripping. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modify | Accept creator id on create; add helpers to fetch owned task/driver and enforce fletero ownership before update. |
| `packages/backend/src/modules/admin/admin.routes.ts` | Modify | Use explicit role checks for tasks/drivers; keep admin-only guards for destructive/admin routes. |
| `packages/backend/src/modules/auth/auth.routes.ts` | Modify | Add current-user profile GET/PATCH schema and audit logging. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modify | Permit worker access; restrict tabs/actions/fields and show only own editable records where appropriate. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modify | Show admin panel nav for `Administrador` and limited panel nav for `Trabalhador`. |
| `packages/frontend/src/api/client.ts` | Modify | No transport change expected; add typed helpers only if implementation extracts admin API calls. |
| Backend + E2E tests | Modify | Add authorization/ownership/profile coverage. |

## Interfaces / Contracts

- `GET /api/admin/tasks`: admins receive all; workers receive active tasks with `created_by_user_id`, marking own rows editable client-side.
- `POST /api/admin/tasks`: admin unchanged; worker creates under existing category with `created_by_user_id = request.user.sub`.
- `PATCH /api/admin/tasks/:id`: admin unchanged; worker may update only own `name_pt`, `name_es`, `category_id`; `is_active` denied.
- `GET/POST/PATCH /api/admin/drivers`: same split; worker create/update is fletero-only, owned-only, no `is_active`.
- `GET/PATCH /api/auth/profile`: returns/updates only authenticated user's `username` and `display_name`; password remains `/auth/change-password`.

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Backend integration | Worker allowed owned task/fletero create/edit; denied delete, deactivate, casa, cross-owner, users/categories/vehicles/slots. | Extend `admin.routes.test.ts` with worker token fixtures. |
| Backend auth | Profile self-read/update succeeds; other-user profile remains unreachable; password min length unchanged. | Extend `auth.routes.test.ts`. |
| Regression | Admin full CRUD still works; loading/reports include worker-created active records. | Existing admin/loading/report tests plus targeted fixtures. |
| E2E | Worker sees only limited admin tabs and no destructive actions. | Add Playwright worker-panel spec. |

## Migration / Rollout

Run idempotent SQLite column migrations at startup. No data backfill: legacy tasks/drivers stay universal but unowned, editable only by admins. Roll out backend checks first, then frontend navigation; rollback hides worker nav/routes while preserving created shared records.

## Open Questions

- [ ] Should worker task/driver lists show all universal records read-only, or only owned records plus create forms? Current design supports all with own rows editable.
