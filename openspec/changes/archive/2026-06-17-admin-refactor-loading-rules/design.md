# Design: Admin Refactor and Loading Rules

## Technical Approach

We will refactor the product catalog, task dynamicization, driver/vehicle scheduling relationships, and transition to a rolling 60-minute quota limit.

1. **Database Schema Optimization**: Drop the `report_products` and `report_quantities` tables. Update `report_tasks` by removing the `task_type` and `sort_order` columns.
2. **Dynamic Task Typing and Sorting**: Tasks will dynamically inherit their type from their parent category (`category_type`). They will be ordered dynamically in queries by category sort order then task ID.
3. **Driver-to-Vehicle Association**: Conditionally link drivers and vehicles in `loading_schedules`. All schedules require a driver. A company-owned vehicle is required only if the driver type is `casa`. 
4. **Rolling Quota Engine**: Enforce a rolling 60-minute window limit allowing at most 3 active fleteros on any given date.

## Architecture Decisions

| Option | Tradeoff | Decision |
|---|---|---|
| **A) Keep task type column in tasks table**<br>**B) Dynamic inheritance from category** | A) Risk of data mismatch between parent category and child tasks.<br>B) Normalizes the schema but requires joins in queries. | **Option B**: Remove `task_type` from `report_tasks` and inherit dynamically via join with `report_categories`. |
| **A) Store type in drivers table**<br>**B) Infer driver type by license plate nullability** | A) Explicit column configuration in the DB.<br>B) License plate configuration may change and is not type-safe. | **Option A**: Add a `driver_type` column to the `drivers` table to serve as a reliable source of truth. |
| **A) Rolling quota validation in SQLite**<br>**B) Rolling quota validation in backend memory** | A) Complex SQL CTE code that is harder to test and debug.<br>B) In-memory array filtering on date-filtered records (trivial size). | **Option B**: Fetch date schedules and run in-memory rolling interval check in backend service. |

## Data Flow

```text
               [ Frontend Schedule Grid ]
                           │
                           ▼  (POST/PATCH /schedules)
               [ Backend Schedule Service ]
                           │
                           ▼
                   [ Quota Engine ]
       (Checks rolling 60m window count on date)
           /                               \
  (If count > 3)                       (If count <= 3)
       /                                     \
      ▼                                       ▼
[ Reject / 409 Conflict ]            [ Write to SQLite DB ]
```

## File Changes

| File | Action | Description |
|---|---|---|
| `packages/backend/src/db/schema.sql` | Modify | Drop tables `report_products`/`report_quantities`. Drop `task_type`/`sort_order` columns from `report_tasks`. Add `driver_type` to `drivers`. |
| `packages/backend/src/db/seed.sql` | Modify | Remove products seeding. Update tasks seeding. Add `driver_type` to drivers seeding. |
| `packages/backend/src/db/db-init.test.ts` | Modify | Update assertions to expect 14 tables instead of 16. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modify | Remove products schemas. Add `driver_type` enum to driver schemas. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modify | Remove product CRUD. Persist `driver_type`. Update task sorting. |
| `packages/backend/src/modules/admin/admin.routes.ts` | Modify | Remove product CRUD endpoints. |
| `packages/backend/src/modules/loading/loading.schema.ts` | Modify | Require `driver_id` and validate `vehicle_id` conditionally. |
| `packages/backend/src/modules/loading/loading.service.ts` | Modify | Enforce rolling quota check and conditional vehicle constraint. Add active vehicles endpoint. |
| `packages/backend/src/modules/loading/loading.export.service.ts` | Modify | Update WhatsApp text to show vehicle plates only for `casa` drivers. |
| `packages/backend/src/modules/reports/reports.schema.ts` | Modify | Remove quantities schema and references. |
| `packages/backend/src/modules/reports/reports.service.ts` | Modify | Remove quantities saving/loading logic. Update task type inheritance. |
| `packages/backend/src/modules/reports/export.service.ts` | Modify | Remove quantities from WhatsApp export. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modify | Remove products tab, CRUD tables/forms. Add `driver_type` to driver form. |
| `packages/frontend/src/components/loading/ScheduleGrid.tsx` | Modify | Update quota indicator and add-button blocking to use rolling checks. |
| `packages/frontend/src/components/loading/DriverSelect.tsx` | Modify | Add vehicle selection dropdown when assigning a `casa` driver. |
| `packages/frontend/src/components/reports/CategorySection.tsx` | Modify | Remove products rendering list. |

## Interfaces / Contracts

### DB Changes
```sql
ALTER TABLE drivers ADD COLUMN driver_type TEXT NOT NULL DEFAULT 'fletero' CHECK(driver_type IN ('casa', 'fletero'));
```

### Schedule Creation API
`POST /api/loading/schedules`
- **Request Body**:
```typescript
interface CreateScheduleBody {
  schedule_date: string; // YYYY-MM-DD
  time_slot: string;     // HH:MM
  driver_id: number;
  vehicle_id?: number | null;
}
```
- **Validation**: If driver type is `casa`, `vehicle_id` is required. If driver type is `fletero`, `vehicle_id` must be null.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Unit | Rolling Quota check | Test `checkRollingQuota` helper with various time arrays (e.g. `['04:00', '04:30', '05:00', '05:30']`). |
| Integration | Driver validation and Quota rules | Send requests to `/api/loading/schedules` to test rolling quota limits and driver-vehicle constraint enforcement. |
| E2E | Schedule grid interactions | Validate grid display, select lists, and conditional fields. |

## Migration / Rollout

Run `npm run db:reset` in the development environment to apply the schema updates cleanly.

## Open Questions

None.
