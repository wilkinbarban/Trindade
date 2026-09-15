# Proposal: Limited Worker Admin Panel

## Intent

Give `Trabajador` users a restricted panel for self-owned catalog contributions: tasks, fletero drivers, and their own profile. This is not full admin access; it is scoped self-owned CRUD with shared catalog output.

## Scope

### In Scope
- Limited panel with Tasks, Drivers, and User Profile only.
- Workers create tasks under existing categories and edit only their own tasks.
- Workers create only `fletero` drivers and edit only their own fleteros.
- Workers view/update only their own name/display name and password.
- Worker-created tasks and fleteros are universal and usable by everyone.

### Out of Scope
- Category creation/editing for workers.
- User creation, user listing, role/status management, or full admin access.
- Worker delete/deactivate rights for tasks or drivers.
- Worker creation/editing of `casa` drivers.

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `admin-catalog-management`: Add worker-owned task create/edit and fletero-only driver create/edit while preserving admin catalog controls.
- `admin-worker-management`: Workers cannot access user administration, create users, list users, or modify other users.
- `auth`: Add self-profile access rules: own name/display-name update and own password change.
- `loading-schedule`: Worker-created fleteros become active universal scheduling options.
- `report-generation`: Worker-created tasks become universal report-builder options.

## Approach

Extend RBAC from admin-only catalog CRUD to explicit permission checks. `Administrador` keeps full access. `Trabajador` receives module-level routes/UI for owned tasks, owned fleteros, and self-profile only. Persist creator metadata for new tasks/drivers if absent, validate ownership server-side, and hide unauthorized edit/delete/deactivate actions in the frontend.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/modules/admin/*` | Modified | Catalog endpoints, schemas, services, ownership checks. |
| `packages/backend/src/modules/auth/*` | Modified | Self-profile/password authorization. |
| `packages/frontend/src/pages/admin/*` | Modified | Limited worker panel modules and action visibility. |
| `packages/frontend/src/api/client.ts` | Modified | Worker-scoped catalog/profile calls. |
| `packages/backend/src/db/*` | Modified | Creator metadata for tasks/drivers if needed. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Privilege escalation through hidden endpoints | Med | Enforce ownership/type checks on backend. |
| Shared catalogs polluted by worker mistakes | Med | Limit workers to existing categories and fletero-only type. |
| Mobile admin UX becomes crowded | Low | Keep only three modules and mobile-first navigation. |

## Rollback Plan

Disable worker panel routes/navigation and revert permission changes. Preserve created tasks/fleteros as catalog records unless targeted cleanup is explicitly requested.

## Dependencies

- Existing `Administrador`/`Trabajador` JWT role model.
- Existing task categories and driver type support.

## Success Criteria

- [ ] Workers can create usable project-wide tasks under existing categories.
- [ ] Workers can edit only their own tasks and fleteros.
- [ ] Workers can create only `fletero` drivers, never `casa` drivers.
- [ ] Workers can update only their own profile name/display name and password.
- [ ] Workers cannot access full admin modules, other users, or delete/deactivate shared records.
