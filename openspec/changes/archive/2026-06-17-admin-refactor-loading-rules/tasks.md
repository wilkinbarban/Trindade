# Tasks: Admin Refactor and Loading Rules

## Review Workload Forecast

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

*Details:*
- **Decision needed before apply**: No (since delivery_strategy is auto-chain)
- **Chained PRs recommended**: Yes (since we are touching DB schema, backend, frontend, E2E tests, and removing products completely, the total diff is >400 lines)
- **Chain strategy**: stacked-to-main
- **400-line budget risk**: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Schema & Backend Refactor | PR 1 | Base branch: main. Drops tables, columns, adds driver_type, and updates seeds. |
| 2 | Backend Rules & Quota Engine | PR 2 | Base branch: PR 1. Adds validation, rolling quota engine, and refactors controllers/services. |
| 3 | Frontend Refactor & Cleanups | PR 3 | Base branch: PR 2. Updates dashboard, schedule grid, driver select, and tests. |

## Phase 1: Database & Seed Updates (PR 1)

- [x] 1.1 Update `packages/backend/src/db/schema.sql`: Drop tables `report_products` and `report_quantities`. Drop columns `task_type` and `sort_order` from `report_tasks`. Add `driver_type` TEXT (casa/fletero) to `drivers` table.
- [x] 1.2 Update `packages/backend/src/db/seed.sql`: Remove products/quantities data, clean up tasks seed, and add `driver_type` to drivers seeding.
- [x] 1.3 Update assertions in `packages/backend/src/db/db-init.test.ts` to expect 14 tables instead of 16.

## Phase 2: Backend Validation, Sorting & Quotas (PR 2)

- [x] 2.1 Update `packages/backend/src/modules/admin/admin.schema.ts`: Remove product schemas, and add `driver_type` enum to driver validation schemas.
- [x] 2.2 Update `packages/backend/src/modules/admin/admin.service.ts` & `admin.routes.ts`: Remove product CRUD routes and services. Order tasks dynamically by category sort order then task ID.
- [x] 2.3 Update `packages/backend/src/modules/loading/loading.schema.ts`: Validate `driver_id` is required, require `vehicle_id` conditionally if driver type is `casa`, and enforce `vehicle_id` is null if driver type is `fletero`.
- [x] 2.4 Update `packages/backend/src/modules/loading/loading.service.ts`: Enforce rolling quota check (max 3 active fleteros in any rolling 60-minute window on a date) and conditional vehicle constraint. Add active vehicles endpoint.
- [x] 2.5 Update WhatsApp export text in `packages/backend/src/modules/loading/loading.export.service.ts` to include vehicle plate only for `casa` drivers.
- [x] 2.6 Refactor `packages/backend/src/modules/reports/reports.schema.ts`, `reports.service.ts`, and `export.service.ts`: Remove quantities saving/loading logic, remove products/quantities from WhatsApp export, and support task type inheritance from categories (`check` and `temperature` types).
- [x] 2.7 Write unit and integration tests in `packages/backend/src/modules/loading/loading.routes.test.ts` to verify rolling quota limits and driver-vehicle constraint enforcement.

## Phase 3: Frontend Refactor & E2E Testing (PR 3)

- [x] 3.1 Modify `packages/frontend/src/pages/admin/AdminDashboard.tsx`: Remove product CRUD UI components. Update driver forms to select and persist `driver_type`.
- [x] 3.2 Update `packages/frontend/src/components/loading/DriverSelect.tsx`: Dynamically display/require vehicle selection dropdown only when selecting a `casa` driver.
- [x] 3.3 Update `packages/frontend/src/components/loading/ScheduleGrid.tsx`: Implement rolling 60-minute window quota status display and disable slot creation if quota limit is exceeded.
- [x] 3.4 Update `packages/frontend/src/components/reports/CategorySection.tsx`: Remove products rendering and quantity input elements.
- [x] 3.5 Update E2E Playwright tests in `packages/frontend/src/__e2e__/loading-schedule.spec.ts` and `admin-edit-export.spec.ts` to align with the new schema, validators, and export outputs.
