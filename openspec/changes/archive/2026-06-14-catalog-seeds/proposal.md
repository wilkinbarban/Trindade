# Proposal: Catalog Seeds Refresh

Update the system's seed data to provide a PRD-complete operational demo environment, adding categories, tasks, products, drivers, vehicles, and a worker user without introducing new admin UI or routes.

## Intent

The current system has minimal seed data (3 categories, 8 tasks, 4 products) which doesn't reflect the full PRD. This change expands `seed.sql` to provide a realistic demo dataset for the foundation, reports, and loading schedules modules, bridging the gap between the initial implementation and the PRD requirements.

## Scope

### In Scope
- Add remaining categories per PRD (Recebimento de Mercadorias, Abastecimento, Produção, Lotes).
- Add full list of tasks for the new categories.
- Add full product lists (Caixas Assaí Grandes, Caixas Pequenas, Recebimento). Product names MUST be in Portuguese.
- Add a sample `Trabalhador` user, sample drivers (fleteros), and sample company vehicles.
- Update `db-init.test.ts` to assert the larger seed counts.
- Adjust E2E tests (e.g., `report-builder.spec.ts`) if sort order or available data changes.

### Out of Scope
- Building Admin CRUD UIs for any of these entities.
- New API routes or services for managing catalog entities.
- Database schema changes (tables, columns).

## Capabilities

### New Capabilities
- `catalog-seed-data`: Defines the comprehensive set of seed data required for demoing the full operational scope.

### Modified Capabilities
- None

## Approach

We will replace the current minimal seed data in `packages/backend/src/db/seed.sql` with a full PRD-compliant set.
1. **Preserve existing IDs**: Existing roles (1-2), tasks (1-8), and products (1-4) will retain their IDs to minimize breakage in E2E tests that rely on them.
2. **Append new entities**: New categories, tasks, products, users, drivers, and vehicles will use new IDs (starting from current max + 1).
3. **Idempotency**: Use `INSERT OR IGNORE` to safely handle repeated executions if needed.
4. **Test alignment**: Ensure `db-init.test.ts` expects the new row counts, and patch E2E tests if UI layout changes due to the new data volume.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/db/seed.sql` | Modified | Expand with PRD-complete seed data. |
| `packages/backend/src/db/db-init.test.ts` | Modified | Update row count assertions. |
| `packages/frontend/src/__e2e__/report-builder.spec.ts` | Modified | Fix broken selectors if data expansion changes UI ordering. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| E2E test failures from ID/order changes | Medium | Explicitly preserve existing IDs (1-8 for tasks, 1-4 for products) in the seed data. |
| Seed doesn't run on existing DBs | Medium | Document `make db-reset` to recreate the database for demo environments, as the current guard skips if data exists. |

## Rollback Plan

Revert the PR containing the `seed.sql` changes and test updates. Developer environments will need to re-run `make db-reset` to clear the new data from their local SQLite databases.

## Dependencies

- None.

## Success Criteria

- [ ] `seed.sql` includes the complete list of products (Caixas Assaí Grandes, Caixas Pequenas, Recebimento) in Portuguese.
- [ ] Sample worker user, drivers, and vehicles exist in the database upon initialization.
- [ ] All E2E tests pass with the new data.
- [ ] `db-init.test.ts` correctly verifies the new expected row counts.