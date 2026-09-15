# Proposal: Admin Management and Language Switcher

## Intent
Allow administrators to manage worker users, physically delete catalog entities with safe backend constraint handling, switch frontend languages (PT/ES), and change user passwords with dynamic helper suggestions.

## Scope

### In Scope
- Rename the historical seeded administrator [REDACTED LEGACY CREDENTIAL].
- Add user/worker management UI (list, create, update, deactivate/delete) to `AdminDashboard.tsx` and REST endpoints.
- Add change password modal to `AppShell.tsx` footer (accessible by logged-in users).
- Client-side weak password visual recommendation (8+ chars, uppercase, digit) without blocking submission; relaxed Zod validation (`min(4)`) on backend.
- Header language selection buttons (PT / ES) using `i18next`.
- Add physical delete buttons for categories, tasks, products, drivers, and vehicles in `AdminDashboard.tsx`.
- Backend SQLITE_CONSTRAINT catch for physical delete endpoints to return friendly 400 errors instead of crashing.

### Out of Scope
- Direct DB cascade deletion or automatic reference cleanup.
- Block weak passwords on form submission.
- Multi-admin role editing or permissions matrix UI.

## Capabilities

### New Capabilities
- `admin-worker-management`: UI and APIs for listing, creating, and modifying workers/users.

### Modified Capabilities
- `auth`: Modified requirements for user password change flows and default seed credentials.
- `dashboard`: Modified layout requirements to include PT/ES header switchers and footer password change.
- `admin-catalog-management`: Modified requirements to allow physical deletions and handle ref constraints.

## Approach
Extend `AdminDashboard.tsx` with a new "Workers" management tab. Add physical delete actions to lists. Backend catches database constraint errors during deletion, returning HTTP 400. Insert PT/ES language toggle buttons in `AppShell` header and password modal trigger in footer.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/db/seed.sql` | Modified | Rename default seed user [REDACTED LEGACY CREDENTIAL] |
| `packages/backend/src/modules/admin/` | Modified/New | User CRUD routes, schemas, services, and physical delete logic |
| `packages/backend/src/modules/auth/` | Modified | Add password change API route and validation |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modified | Add worker tab and physical delete buttons |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modified | Add language switcher (header) and password modal (footer) |
| `packages/frontend/src/i18n/locales/` | Modified | Add translation keys for new UI texts and error messages |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Physical delete constraints fail | High | Backend catches constraint errors, returns clean HTTP 400 |
| Strict password enforcement breaks tests | Medium | Implement recommendation UI warning, keep submission open |

## Rollback Plan
Revert database schema changes (or re-run `seed.sql` with default values) and revert Git commits for backend and frontend packages.

## Dependencies
- None

## Success Criteria
- [ ] Seed script successfully boots with the historical administrator [REDACTED LEGACY CREDENTIAL].
- [ ] Users can toggle system locale between PT and ES in header.
- [ ] Admins can create/edit/delete workers and physically delete catalog items.
- [ ] Friendly warning is displayed if password is under 8 chars/no capital/no digit, but doesn't block submission.
