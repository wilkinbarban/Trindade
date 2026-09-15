## Exploration: admin-refactor-loading-rules

### Current State
The system has categories, tasks, products, drivers, and vehicles. Checklists map to tasks which have individual types and sort orders. Schedules are created for either a driver (as fletero) or a vehicle (as casa), without linking casa drivers to the vehicles they drive. Quotas are checked strictly per slot (max 3 fleteros).

### Affected Areas
- `packages/backend/src/db/schema.sql` — Modify tables (drop report_products/quantities; update drivers/tasks/schedules).
- `packages/backend/src/db/seed.sql` — Remove product inserts; update driver seed data.
- `packages/backend/src/db/db-init.test.ts` — Expect 14 tables instead of 16.
- `packages/backend/src/modules/admin/admin.schema.ts` — Remove product schemas; update category, task, and driver schemas.
- `packages/backend/src/modules/admin/admin.service.ts` — Update CRUD actions, implement reference checks and cascade deletes.
- `packages/backend/src/modules/admin/admin.routes.ts` — Delete product routes.
- `packages/backend/src/modules/reports/reports.schema.ts` & `reports.service.ts` — Remove quantities.
- `packages/backend/src/modules/reports/export.service.ts` — Remove quantities formatting.
- `packages/backend/src/modules/loading/loading.schema.ts` & `loading.service.ts` — Implement the rolling 60-minute limit and dynamic driver type resolution.
- `packages/backend/src/modules/loading/loading.export.service.ts` — Update WhatsApp schedule text formatting.
- `packages/frontend/src/pages/admin/AdminDashboard.tsx` — Remove products, update category, task, and driver creation.
- `packages/frontend/src/components/reports/CategorySection.tsx` — Remove products list task component.
- `packages/frontend/src/pages/ReportEditPage.tsx`, `ReportsPage.tsx`, `ReportViewPage.tsx` — Remove products/quantities.
- `packages/frontend/src/components/loading/DriverForm.tsx` & `DriverSelect.tsx` & `ScheduleGrid.tsx` — Allow scheduling both driver types and selecting vehicles for Casa drivers.

### Approaches
1. **Dynamic Task Type & Sorting:** Inherit `task_type` from `category_type` and use category sort order + task ID for sorting.
   - Pros: Simplifies task management, ensures database integrity.
   - Cons: Requires database table redefinition.
   - Effort: Medium

2. **Rolling 60-Minute Quota:** Implement minutes-from-midnight conversion for all scheduled fleteros on a date and verify no 60-minute window has $>3$ entries.
   - Pros: Exact, generic, future-proof.
   - Cons: Slightly more queries.
   - Effort: Medium

3. **Dynamic Vehicle/Plate Selection:** Keep `vehicles` catalog, require scheduling a `driver_id` for both types, and conditionally allow selecting a `vehicle_id` only if the driver is type `casa`.
   - Pros: Clear separation of driver (person) and vehicle (license plate).
   - Cons: The UI grid needs to render a vehicle selector conditionally.
   - Effort: Medium

### Recommendation
Implement the unified refactoring across all affected files. It simplifies the database schema by removing unnecessary product lists, enhances scheduler flexibility, and prevents SQLite constraint errors with proactive validations.

### Risks
- **Test breaks:** Removing `report_products` and editing driver attributes will break existing route and E2E tests.
- **Rollbacks:** The rolling window logic must be strictly transactional so failed validations rollback properly.

### Ready for Proposal
Yes
