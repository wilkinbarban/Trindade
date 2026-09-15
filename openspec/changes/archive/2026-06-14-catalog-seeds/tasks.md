# Tasks: Catalog Seeds Refresh

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 120–170 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: stacked-to-main
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Idempotent seed.sql + new catalog data + test alignment + README | PR 1 | All changes are coupled; splitting adds review overhead with no cognitive-load benefit |

## Phase 1: Seed Data — Idempotency & Expansion

- [x] 1.1 Convert existing `INSERT INTO` statements in `packages/backend/src/db/seed.sql` (roles, users) to `INSERT OR IGNORE INTO` so re-execution is safe.
- [x] 1.2 Append new `report_categories` (IDs 4–7): "Recebimento de Mercadorias", "Abastecimento", "Produção", "Lotes" — with Portuguese and Spanish names, using `INSERT OR IGNORE`.
- [x] 1.3 Append new `report_tasks` (IDs 9+) for the new categories, preserving existing IDs 1–8. Use explicit IDs and `INSERT OR IGNORE`.
- [x] 1.4 Replace `report_products` seed with full PRD catalog: keep IDs 1–4, add products for "Caixas Assaí Grandes" (Nhoque 1kg, Quadrada, Rolo 500g/1kg/2kg, Disco 400g/500g), "Caixas Pequenas" (same variants minus 500g Rolo), and "Recebimento" group (Farinha, Sal, Gordura, Pizza, Canudo, Salgadinho). All names in Portuguese. Use `INSERT OR IGNORE` with explicit IDs.
- [x] 1.5 Add sample `Trabalhador` user (ID 2, role_id 2) with bcrypt password hash, using `INSERT OR IGNORE`.
- [x] 1.6 Add sample `drivers` (3 fleteros with IDs, license plates) and one `vehicles` entry (casa), using `INSERT OR IGNORE`.

## Phase 2: Test Alignment

- [x] 2.1 In `packages/backend/src/db/db-init.test.ts`, update the "seed.sql inserts roles and admin user" test to also assert the `Trabalhador` user exists (ID 2, role_id 2, is_active 1).
- [x] 2.2 Replace the `assert.throws(/UNIQUE constraint failed/)` assertion (line ~124) with `assert.doesNotThrow(() => db.exec(seed))` to validate idempotent re-execution.
- [x] 2.3 Add row-count assertions for new seed data: categories ≥ 7, tasks ≥ 9, products per PRD catalog, drivers = 3, vehicles ≥ 1.

## Phase 3: Documentation

- [x] 3.1 Create `README.md` at project root with: project description, prerequisites, `make install`, `make dev`, and `make db-reset` instructions (explain that `db-reset` deletes the SQLite file and the backend recreates it with fresh seed data on next start).

## Phase 4: Verification

- [x] 4.1 Run `npx tsx --test packages/backend/src/db/db-init.test.ts` — all tests pass including idempotency.
- [x] 4.2 Run `make db-reset` then start backend — verify no startup errors and seed data is present.
- [x] 4.3 Run E2E tests (`report-builder.spec.ts`, `report-view-export.spec.ts`, `loading-schedule.spec.ts`) — verify existing hardcoded IDs (tasks 1–8, products 1–4) still resolve correctly.
