## Exploration: Catalog Seeds — Operational Demo Data

### Current State

The system has three verified PASS slices:
1. **trindade-operations-foundation** — Monorepo, SQLite (16 tables), JWT auth, dashboard, Docker
2. **relatorio-inteligente** — Reports module, 4 task types, WhatsApp export, history, edit window
3. **horario-carregamento** — Loading schedules, driver management, quota enforcement, WhatsApp export

**Current seed data (`packages/backend/src/db/seed.sql`)** is minimal for foundation:
- 2 roles, 1 admin user, loading time slots setting
- 3 categories, 8 tasks, 4 products
- No sample fleteros, vehicles, or trabalhador users

The E2E tests reference seed data by hardcoded IDs (e.g., `task ID 1` = "Pátio organizado"), which means any seed change must either preserve IDs or update tests.

### Gaps Against PRD

| PRD Section | Current Seed | PRD States | Gap |
|-------------|-------------|------------|-----|
| §11 — Categorias | 3 categories | Organização, Recebimento, Temperaturas, Lotes, Abastecimento, Produção | Missing ~4 categories |
| §10 — Task Types | 8 tasks (4 check, 2 temp, 1 list, 1 qty) | Many more tasks implied per category | Thin coverage for all 4 types |
| §12 — Caixas Assaí Grandes | 2 products | 7 products (Nhoque 1kg, Quadrada, Rolo 500g/1kg/2kg, Disco 400g/500g) | Missing 5 products |
| §12 — Caixas Pequenas | 0 products | 8 products (same variants, smaller format) | Entire group missing |
| §13 — Recebimento | 0 products | Farinha, Sal, Gordura, Pizza, Canudo, Salgadinho | Entire group missing |
| §5 — Trabajador Role | 1 admin user only | At least 1 trabalhador for role testing | Missing worker user |
| §19 — Drivers | 0 fleteros | Sample drivers for demo | Missing |
| §19 — Vehicles (Casa) | 0 vehicles | Company vehicles with plates | Missing |

### Affected Areas

- `packages/backend/src/db/seed.sql` — **Primary**: add categories, tasks, products, drivers, vehicles, user, settings
- `packages/backend/src/db/db-init.test.ts` — Must verify new seed rows exist
- `packages/backend/src/modules/reports/reports.routes.test.ts` — May reference task/product IDs
- `packages/backend/src/modules/loading/loading.routes.test.ts` — Seeds drivers programmatically (low risk)
- `packages/frontend/src/__e2e__/report-builder.spec.ts` — **High risk**: references hardcoded task IDs (1-8) and product IDs (1-4)
- `packages/frontend/src/__e2e__/loading-schedule.spec.ts` — Creates drivers dynamically (low risk)
- `packages/frontend/src/__e2e__/report-view-export.spec.ts` — May reference seed product names
- `packages/frontend/src/__e2e__/report-history.spec.ts` — Could benefit from richer data

### Approaches

1. **PRD-complete seed replacement** — recommended
   - Replace seed.sql with full PRD-matching data using explicit IDs
   - Maintain backward-compatible IDs for existing rows (1-2 roles, 1-8 tasks, 1-4 products remain)
   - Add new rows with IDs starting at current max+1
   - Update `db-init.test.ts` to verify new seed rows
   - Update `report-builder.spec.ts` to use new test IDs (adjust for new sort order)
   - Update `loading.routes.test.ts` if it depends on seed driver data (it doesn't — it seeds dynamically)
   - Effort: Medium
   - Lines: ~200 seed.sql + ~100 test updates

2. **Minimal append-only** — safer but less complete
   - Only append a few new categories/tasks/products, don't restructure
   - Keep all existing IDs exactly as-is
   - Add 1 trabalhador user, 2-3 drivers, 1 vehicle
   - Fewer test changes needed
   - Effort: Low
   - But: still leaves demo visually thin — PRD says "Nhoque 1kg" but seed has "Caixa Assaí Grande (20kg)"

3. **Full seed + dedicated seed test suite**
   - Same as Approach 1 plus create a standalone `seed-data.test.ts` that validates every seeded row
   - Adds another test file
   - Effort: Medium-High
   - Over-engineered for the current state (db-init.test.ts already checks role count)

### Recommendation

**Approach 1 — PRD-complete seed replacement with explicit ID preservation.**

Rationale:
- The PRD explicitly lists product names in Portuguese — the seed should match them verbatim for a realistic demo
- Current seed uses made-up names like "Caixa Assaí Grande (20kg)" instead of PRD's "Nhoque 1kg"
- Keeping existing IDs for rows 1-8 (tasks), 1-4 (products), 1-2 (roles) means existing tests pass without major rewrites
- The E2E tests reference task IDs 1-4 (check), 5-6 (temperature), 7 (list), 8 (quantity) — as long as these base mappings stay, new rows just extend the set
- Explicit `INSERT OR IGNORE` with hardcoded IDs keeps seeds idempotent and predictable

**Seed plan:**
- Roles 1-2: unchanged (Administrador, Trabalhador)
- User 1: admin (unchanged), add user 2: worker/trabalhador
- Categories 1-3: unchanged (Higiene e Organização, Temperaturas, Estoque)
- Categories 4-7: Recebimento de Mercadorias, Abastecimento, Produção, Lotes
- Tasks 1-8: unchanged (preserve E2E test references)
- Tasks 9-16+: new tasks under new categories (Recebimento items, Produção items, etc.)
- Products 1-4: unchanged (keep for compatibility)
- Products 5-10+: PRD Caixas Assaí Grandes line items (Nhoque 1kg, Quadrada, Rolo 500g/1kg/2kg, Disco 400g/500g)
- Products 11-18+: PRD Caixas Pequenas line items
- Products 19-24+: Recebimento items (Farinha, Sal, Gordura, Pizza, Canudo, Salgadinho)
- Drivers 1-3: Sample fleteros (Pedro, José, Marcos — matching PRD example)
- Vehicles 1-2: Company vehicles with plates
- Settings: keep existing, optionally add default shift hours

**Order of insertion matters for `report_products`** — the `group_name` column groups them.

### Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| E2E tests break because new tasks change sort order | Medium | High | Verify `report-builder.spec.ts` references task IDs not positional order; fix test assertions if needed |
| Existing production DB has the sparse seed; new seed `INSERT OR IGNORE` won't add new rows | Medium | Medium | The guard in `db/index.ts` only runs seed when `roles` table is empty. For existing DBs we need a separate approach — either add a migration mechanism or document `make db-reset` |
| Products with group_name in pt-BR duplicate across groups | Low | Low | Use distinct `group_name` values that match PRD exactly: "Caixas Assaí Grandes", "Caixas Pequenas" |

### Ready for Proposal

**Yes.** The scope is well-bounded:
1. Update `seed.sql` — PRD-complete categories, tasks, products, sample drivers/vehicles, worker user
2. Update `db-init.test.ts` — verify new rows
3. Update `report-builder.spec.ts` — adjust for any sort order changes
4. Document that existing production DBs need `make db-reset` to pick up new seed data

The orchestrator should tell the user:
> "Exploration complete. The recommended slice is a PRD-complete seed data refresh that preserves existing IDs for backward compatibility, adds all missing categories/tasks/products per PRD sections 11-13, samples drivers/vehicles, and a worker user. Updates the db-init test and adjusts E2E tests for the richer dataset. No new routes, services, UI components, or admin CRUD — purely seed data and test updates."
