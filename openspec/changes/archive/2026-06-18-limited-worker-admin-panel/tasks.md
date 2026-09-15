# Tasks: Limited Worker Admin Panel

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 650-950 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1: DB + backend ownership/auth + backend tests; PR 2: frontend panel wiring + i18n + E2E; optional PR 3: cleanup/docs |
| Delivery strategy | exception-ok |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: size-exception
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Persist ownership and enforce backend permissions | PR 1 | DB migration, admin/auth service and route checks, backend tests for permissions and legacy null-owner behavior |
| 2 | Expose the limited worker panel in the frontend | PR 2 | AppShell/AdminDashboard tabs, action visibility, profile UI, i18n strings, E2E coverage |
| 3 | Final regression pass and polish | PR 3 | Tighten wording, remove dead branches, verify docs and acceptance criteria |

## Phase 1: Database and Security Foundation

- [x] 1.1 Add nullable `created_by_user_id` ownership columns to `report_tasks` and `drivers` in `packages/backend/src/db/schema.sql`, with foreign-key support for `users(id)`.
- [x] 1.2 Update `packages/backend/src/db/index.ts` so startup migrations add the ownership columns idempotently for existing SQLite databases.
- [x] 1.3 Add supporting indexes or query helpers for ownership lookups if the current query shapes need them for task/driver updates.
- [x] 1.4 Extend backend row schemas/types in `packages/backend/src/modules/admin/admin.schema.ts` so task/driver rows can carry creator metadata without breaking admin reads.

## Phase 2: Backend Authorization and Ownership Rules

- [x] 2.1 Update task create/update handlers in `packages/backend/src/modules/admin/admin.routes.ts` and `admin.service.ts` so `Administrador` keeps full CRUD while `Trabajador` can create tasks under existing categories and edit only owned tasks.
- [x] 2.2 Enforce worker task restrictions server-side: no delete, no deactivate, no category creation/editing, and no cross-owner edits, even if the client sends forbidden fields.
- [x] 2.3 Update driver create/update handlers so workers can create and edit only owned `fletero` drivers, while `casa` remains admin-only and `is_active` changes are rejected for workers.
- [x] 2.4 Preserve legacy `NULL` owners as admin-only records so workers cannot claim or modify pre-existing shared tasks/drivers.
- [x] 2.5 Add current-user profile endpoints in `packages/backend/src/modules/auth/auth.routes.ts` for `GET /api/auth/profile` and `PATCH /api/auth/profile`, with name/display-name updates only for the authenticated user.
- [x] 2.6 Verify password change still works for the current user and that other-user profile access continues to return 403/404 according to the existing auth contract.

## Phase 3: Frontend Limited Worker Panel Wiring

- [x] 3.1 Update `packages/frontend/src/components/layout/AppShell.tsx` so `Trabajador` users see the limited panel navigation, not the full admin menu.
- [x] 3.2 Update `packages/frontend/src/pages/admin/AdminDashboard.tsx` to render only Tasks, Drivers, and Profile modules for workers, while keeping full admin modules for `Administrador`.
- [x] 3.3 Hide destructive or disallowed actions in the worker UI: no delete, no deactivate, no category management, no cross-owner edit affordances, and no `casa` driver creation.
- [x] 3.4 Wire the profile screen to the new self-profile API so workers can update only their own name/display name and password from the limited panel.
- [x] 3.5 Add or adjust i18n strings in `packages/frontend/src/locales/*` or equivalent translation files for the limited worker panel labels, empty states, and permission hints.

## Phase 4: Backend Regression, E2E, and Access Coverage

- [x] 4.1 Add backend permission tests for worker task/driver create/edit success paths, forbidden delete/deactivate paths, cross-owner denial, legacy null-owner denial, and admin regression coverage.
- [x] 4.2 Add backend auth tests for profile GET/PATCH self-access, password change, and denial of other-user access.
- [x] 4.3 Add E2E coverage for a worker session showing the limited panel, read-only universal records, editable owned records, and profile access.
- [x] 4.4 Add E2E assertions that universal worker-created tasks and fleteros remain visible to other users as shared catalog options.

## Phase 5: Verification and Cleanup

- [x] 5.1 Run backend verification: `npm run test --workspace=packages/backend`.
- [x] 5.2 Run workspace build verification: `npm run build --workspaces --if-present`.
- [x] 5.3 Run the relevant Playwright worker-panel E2E suite after the frontend build or against the local app server.
- [x] 5.4 Remove any temporary guards, dead branches, or debug logging introduced during implementation.
- [x] 5.5 Confirm the final state matches the proposal and all modified specs: admin catalog management, worker management, auth, loading schedule, and report generation.
