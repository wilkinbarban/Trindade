# Design: Catalog Seeds Refresh

## Technical Approach

We will append the new PRD-required seed data directly to `seed.sql`, ensuring existing IDs for tasks (1-8) and products (1-4) are preserved to prevent breaking current E2E tests. We will use `INSERT OR IGNORE` for all data and update the database initialization test to assert that re-running the seed file is safely ignored rather than throwing errors. We will also include sample data for users, drivers, and vehicles to populate all operational domains.

## Architecture Decisions

### Decision: Idempotent Seed Execution

**Choice**: Refactor `seed.sql` to use `INSERT OR IGNORE` for all table inserts, including roles and users.
**Alternatives considered**: Keep `INSERT INTO` for base records and rely entirely on `db/index.ts` to guard execution.
**Rationale**: By making `seed.sql` fully idempotent, we allow developers to selectively re-run `seed.sql` locally when new seed data is added without having to wipe their entire database or write a migration script for demo data.

### Decision: Hardcoded IDs for Existing Data

**Choice**: Explicitly assign IDs for existing tasks (1-8) and products (1-4).
**Alternatives considered**: Let SQLite auto-increment IDs for all data.
**Rationale**: Current E2E tests (e.g., `report-builder.spec.ts`) rely on specific `data-testid` attributes bound to task and product IDs. Changing these IDs would cause test failures. Hardcoding guarantees stability.

### Decision: Sample Users and Dependencies

**Choice**: Create a `Trabalhador` user (ID 2), one company vehicle (casa), and three freelance drivers (fleteros).
**Alternatives considered**: Create multiple users and only one type of driver.
**Rationale**: This configuration matches the real-world usage and is necessary to test the 3-fletero quota limit in the loading schedules UI.

## Data Flow

    Developer/Deployment ──→ make db-reset / npm start
                                │
                                └──→ db/index.ts ──→ execute schema.sql
                                        │
                                        └──→ execute seed.sql (Idempotent Inserts) ──→ SQLite

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `packages/backend/src/db/seed.sql` | Modify | Add `INSERT OR IGNORE` to all statements. Append new categories (IDs 4-7), tasks (IDs 9+), products (IDs 5+), one `Trabalhador` user, one `casa` vehicle, and three `fletero` drivers. |
| `packages/backend/src/db/db-init.test.ts` | Modify | Update the "roles and admin user" test to also assert the `Trabalhador` user. Change the "idempotency is guarded" test to assert that `db.exec(seed)` `doesNotThrow` instead of throwing a `UNIQUE constraint failed` error, since we are moving to `INSERT OR IGNORE`. Update `roleCount` logic if necessary. |
| `README.md` | Modify | Document the `make db-reset` command for recreating the DB locally if developers need a fresh state. |

## Interfaces / Contracts

No new API contracts. Seed additions are strictly DB-level.

```sql
-- Example idempotent inserts
INSERT OR IGNORE INTO report_categories (id, name_pt, name_es, sort_order) VALUES
  (4, 'Recebimento de Mercadorias', 'Recepción de Mercancías', 4);

INSERT OR IGNORE INTO drivers (id, name, license_plate, is_active) VALUES
  (1, 'João Silva (Fletero)', 'ABC-1234', 1);
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | Database initialization | Update `db-init.test.ts` to expect no errors when `seed.sql` is run multiple times. |
| E2E | Loading Schedule Grid | `loading-schedule.spec.ts` handles its own data setup, but we verify it still passes with new seed data. |
| E2E | Report Builder Form | `report-builder.spec.ts` relies on IDs 1-8. We will verify they still resolve correctly in the DOM. |

## Migration / Rollout

No migration required for production as it relies on an empty DB or manual seeding. Developer environments will need to execute `make db-reset` to rebuild their SQLite database with the new seed data. We will add a note in the README.

## Open Questions

- None
