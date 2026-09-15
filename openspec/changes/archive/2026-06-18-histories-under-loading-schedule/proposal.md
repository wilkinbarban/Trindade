# Proposal: Histories Under Loading Schedule

## Intent
Add two sections under **Cronograma de Carga** for project-wide report/loading history with filters, 30-item pages, Portuguese content, and backend-enforced permissions.

## Scope

### In Scope
- **Historial de Reportes**: all reports, newest first, filtered by date, month, and user.
- **Historial de Cargamento**: loading schedules with the same criteria and permissions.
- Creator-only editing for one hour after creation; everyone else is read-only.
- Admin-only deactivate and transactional physical delete, audited where existing support allows.
- Portuguese display for registered domain content.

### Out of Scope
- Calendar-bucket pagination.
- Generic activity feed.
- WhatsApp export changes.

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `report-generation`: history filters/pages, Portuguese content, creator ownership, one-hour edit, admin deactivate/delete.
- `loading-schedule`: history, creator attribution, read-only states, one-hour edit, admin deactivate/delete.
- `auth`: admin-only delete/deactivation RBAC.

## Approach
Keep separate report/loading endpoints with a shared history response: `items`, `pagination`, `canEdit`, `canDeactivate`, `canDelete`, `isActive`, deletion state. Migrate state first (`is_active`, `deleted_at`, schedule `user_id`, `updated_at`), then enforce backend permissions before UI actions. Server decisions drive UI. Specs/design must decide legacy schedule backfill vs read-only.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/db/*` | Modified | Add migrations/state columns. |
| `packages/backend/src/modules/reports/*` | Modified | History query, permissions, admin actions. |
| `packages/backend/src/modules/loading/*` | Modified | Creator tracking, history endpoint, restricted mutations. |
| `packages/frontend/src/pages/*History*.tsx` | New/Modified | Loading-contained history views. |
| `packages/frontend/src/components/layout/AppShell.tsx` | Modified | Add sections under Cronograma de Carga. |
| `packages/**/__tests__`, `__e2e__` | Modified | Cover filters, permissions, pagination, admin actions. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Legacy schedules lack creator ownership | High | Decide backfill/read-only policy before implementation. |
| Permanent delete breaks related rows/files | Med | Use transactions, FK-aware cascades, and audit before deletion. |
| Edit-window change breaks existing behavior/tests | Med | Update specs/tests and gate server-side first. |

## Rollback Plan
Revert routes/navigation and disable new endpoints. Keep migrated columns harmless and restore old queries/edit rules. Physical deletes need backups to restore, so verify admin delete before release.

## Dependencies
- JWT roles and audit/logging support.
- Legacy loading schedule backfill decision.

## Success Criteria
- [ ] Both sections list newest-first under Cronograma de Carga with 30 items/page.
- [ ] Date, month, user filters work for all users.
- [ ] Only creators edit within one hour; others read-only.
- [ ] Admins deactivate and permanently delete transactionally with audit evidence.
- [ ] Registered content displays in Portuguese.
