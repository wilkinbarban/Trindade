# Proposal: admin-refactor-loading-rules

## Intent

Simplify catalog management by removing unused product lists, dynamicizing task typing/sorting, and improving loading schedule accuracy with a rolling 60-minute fletero quota and explicit Casa driver-to-vehicle association.

## Scope

### In Scope
- Remove products and report product quantities from database schemas, backend services, exports, and UI components.
- Inherit task types from categories and sort tasks dynamically using category sort order and task ID.
- Restructure driver and schedule tables/schemas: link Casa drivers to vehicles.
- Implement rolling 60-minute quota validation (maximum of 3 active fleteros in any 60-minute window on a date).
- Update frontend schedule grid and forms to support driver/vehicle select logic and quota displays.

### Out of Scope
- Adding new report export formats (PDF/Excel) or third-party integrations.
- Modifying security roles, authentication, or worker-specific flows.

## Capabilities

### New Capabilities
- None

### Modified Capabilities
- `admin-catalog-management`: Remove product entities/CRUD. Inherit task type from category, and order tasks dynamically. Allow configuring drivers with type constraints.
- `loading-schedule`: Require scheduling a `driver_id` for all types. Link `vehicle_id` only if driver type is `casa`. Enforce rolling 60-minute fletero limit.
- `report-generation`: Remove product list selector and quantities.
- `loading-export`: Update WhatsApp export text for scheduled drivers/vehicles.
- `report-export`: Remove product quantities from exported report text.

## Approach

1. **Schema Refactoring:** Update `schema.sql` and `seed.sql`. Drop `report_products`/`quantities` tables. Modify `drivers`, `tasks`, and `schedules` to support the new constraints and relationships.
2. **Quota Engine:** In `loading.service.ts`, convert schedule times to minutes-from-midnight. Query all active fleteros on the target date, and validate that no two schedules are within 60 minutes of each other such that the total fleteros count exceeds 3.
3. **Casa Scheduling Link:** Modify frontend components (`DriverForm`, `DriverSelect`, `ScheduleGrid`) and backend validations to conditionally require a vehicle selection only when a Casa driver is assigned.
4. **Clean up codebases:** Remove product-related files, routers, schemas, and references in backend exports and frontend dashboards/forms.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/db/schema.sql` | Modified | Drop tables/columns, adjust constraints. |
| `packages/backend/src/modules/admin/` | Modified | Update CRUD logic, schemas, and delete products routes. |
| `packages/backend/src/modules/loading/` | Modified | Implement rolling quota, dynamic driver type validations. |
| `packages/backend/src/modules/reports/` | Modified | Remove product/quantity handling and formatting. |
| `packages/frontend/src/` | Modified | Update Admin Dashboard, Report Pages, and Schedule grid UI. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Test suites fail due to schema/route deletions | High | Update `db-init.test.ts` and integration/E2E tests. |
| Rolling quota performance issues | Low | Query by date only, sorting and validation computed in memory. |

## Rollback Plan

Revert git commits, restore SQLite DB using existing backup, and redeploy previous container builds.

## Success Criteria

- [ ] Database schema successfully reduced from 16 to 14 tables.
- [ ] No fleteros exceed the 3-limit threshold within any rolling 60-minute window.
- [ ] Casa drivers scheduled successfully with vehicle validation in frontend and backend.
- [ ] Product entities and quantities removed from all report forms and exports.
- [ ] Existing and new test suites pass successfully.
