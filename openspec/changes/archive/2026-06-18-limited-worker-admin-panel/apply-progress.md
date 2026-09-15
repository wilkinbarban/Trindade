# Apply Progress: Limited Worker Admin Panel

## Status

- Change: `limited-worker-admin-panel`
- Apply mode: Standard (strict TDD disabled in OpenSpec config and Engram testing capabilities)
- Workload boundary: `size:exception` accepted by maintainer; all pending tasks implemented in one batch despite High review workload forecast.
- Result: 24/24 tasks completed and marked in `tasks.md`.

## Completed Tasks

- [x] 1.1 Add nullable `created_by_user_id` ownership columns to `report_tasks` and `drivers` in `packages/backend/src/db/schema.sql`, with foreign-key support for `users(id)`.
- [x] 1.2 Update `packages/backend/src/db/index.ts` so startup migrations add the ownership columns idempotently for existing SQLite databases.
- [x] 1.3 Add supporting indexes or query helpers for ownership lookups if the current query shapes need them for task/driver updates.
- [x] 1.4 Extend backend row schemas/types in `packages/backend/src/modules/admin/admin.schema.ts` so task/driver rows can carry creator metadata without breaking admin reads.
- [x] 2.1 Update task create/update handlers in `packages/backend/src/modules/admin/admin.routes.ts` and `admin.service.ts` so `Administrador` keeps full CRUD while `Trabajador` can create tasks under existing categories and edit only owned tasks.
- [x] 2.2 Enforce worker task restrictions server-side: no delete, no deactivate, no category creation/editing, and no cross-owner edits, even if the client sends forbidden fields.
- [x] 2.3 Update driver create/update handlers so workers can create and edit only owned `fletero` drivers, while `casa` remains admin-only and `is_active` changes are rejected for workers.
- [x] 2.4 Preserve legacy `NULL` owners as admin-only records so workers cannot claim or modify pre-existing shared tasks/drivers.
- [x] 2.5 Add current-user profile endpoints in `packages/backend/src/modules/auth/auth.routes.ts` for `GET /api/auth/profile` and `PATCH /api/auth/profile`, with name/display-name updates only for the authenticated user.
- [x] 2.6 Verify password change still works for the current user and that other-user profile access continues to return 403/404 according to the existing auth contract.
- [x] 3.1 Update `packages/frontend/src/components/layout/AppShell.tsx` so `Trabajador` users see the limited panel navigation, not the full admin menu.
- [x] 3.2 Update `packages/frontend/src/pages/admin/AdminDashboard.tsx` to render only Tasks, Drivers, and Profile modules for workers, while keeping full admin modules for `Administrador`.
- [x] 3.3 Hide destructive or disallowed actions in the worker UI: no delete, no deactivate, no category management, no cross-owner edit affordances, and no `casa` driver creation.
- [x] 3.4 Wire the profile screen to the new self-profile API so workers can update only their own name/display name and password from the limited panel.
- [x] 3.5 Add or adjust i18n strings for limited worker panel labels, read-only state, and profile save messages.
- [x] 4.1 Add backend permission tests for worker task/driver create/edit success paths, forbidden delete/deactivate paths, cross-owner denial, legacy null-owner denial, and admin regression coverage.
- [x] 4.2 Add backend auth tests for profile GET/PATCH self-access, password change, and denial of other-user access.
- [x] 4.3 Add E2E coverage for a worker session showing the limited panel, read-only universal records, editable owned records, and profile access.
- [x] 4.4 Add E2E assertions that universal worker-created tasks and fleteros remain visible through shared report-builder and loading-schedule APIs.
- [x] 5.1 Run backend verification: `npm run test --workspace=packages/backend`.
- [x] 5.2 Run workspace build verification: `npm run build --workspaces --if-present`.
- [x] 5.3 Run the relevant Playwright worker-panel E2E suite after the frontend build or against the local app server.
- [x] 5.4 Remove temporary test output; no debug logging or temporary guards remain.
- [x] 5.5 Confirm implementation matches proposal and modified specs for catalog permissions, worker management denial, auth profile, loading schedule, and report generation.

## Files Changed

| File | Action | What Was Done |
|------|--------|---------------|
| `packages/backend/src/db/schema.sql` | Modified | Added nullable ownership columns and indexes for report tasks and drivers. |
| `packages/backend/src/db/index.ts` | Modified | Added idempotent startup migrations and indexes for ownership columns. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modified | Extended task/driver row types with creator metadata. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modified | Added optional creator ownership persistence for task and driver creation. |
| `packages/backend/src/modules/admin/admin.routes.ts` | Modified | Added role-aware catalog policies for admin full CRUD and worker owned task/fletero create/edit. |
| `packages/backend/src/modules/auth/auth.routes.ts` | Modified | Added authenticated self-profile GET/PATCH endpoints. |
| `packages/backend/src/modules/admin/admin.routes.test.ts` | Modified | Added worker ownership/forbidden operation coverage and adjusted limited category-read expectation. |
| `packages/backend/src/modules/auth/auth.routes.test.ts` | Modified | Added self-profile tests and preserved password-change regression coverage. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modified | Shows limited admin-panel navigation to `Trabalhador` while keeping audit admin-only. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modified | Renders limited Tasks/Drivers/Profile tabs for workers, hides destructive actions, and wires profile API. |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modified | Added limited panel/profile/read-only strings. |
| `packages/frontend/src/i18n/locales/es.json` | Modified | Added limited panel/profile/read-only strings. |
| `packages/frontend/src/__e2e__/worker-admin-panel.spec.ts` | Created | Added worker limited-panel and shared catalog visibility E2E coverage. |
| `openspec/changes/limited-worker-admin-panel/tasks.md` | Modified | Marked all completed tasks `[x]`. |
| `openspec/changes/limited-worker-admin-panel/apply-progress.md` | Created | Persisted this apply progress report. |

## Verification

| Command | Outcome |
|---------|---------|
| `npm run test --workspace=packages/backend` | Passed — 150 tests passing. |
| `npm run build --workspaces --if-present` | Passed — backend TypeScript build and frontend TypeScript/Vite build completed. |
| `npm run test:e2e --workspace=packages/frontend -- worker-admin-panel.spec.ts` | Passed — 3 Playwright tests passing. |

## Deviations from Design

None requiring re-design. One interpretation was made: workers can read category lists because task creation needs existing categories, but category create/update/delete remains admin-only.

## Issues / Notes

- The repo `.git` is not used for validation or status, per instruction.
- A mistyped local TypeScript command (`npx tsc --noEmit --workspace=packages/frontend`) failed because `--workspace` is an npm option, not a TypeScript option; corrected verification used npm scripts.
- Build commands generated `dist/` artifacts as part of normal project scripts.

## Next Recommended Phase

`sdd-verify`
