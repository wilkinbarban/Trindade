## Exploration: Admin CRUD, Advanced Editing & Extra Exports

### Current State

The system has three completed slices (all verified PASS):
1. **Foundation** — Monorepo, SQLite (16 tables), JWT auth, Fastify + React, Docker
2. **Relatório Inteligente** — Reports module: 4 task types, WhatsApp export, history, edit window (today/yesterday only)
3. **Horário de Carregamento** — Loading schedules, driver management, max-3-fletero quota, WhatsApp export

**Admin CRUD gap**: There is currently **zero** admin catalog management. The `requireRole('Administrador')` guard exists in `auth.middleware.ts` but is never used. Catalog data (categories, tasks, products, fleteros, vehicles, time slots) is READ-ONLY via API:

| Entity | Existing Routes | Admin CRUD Needed |
|--------|----------------|-------------------|
| `report_categories` | `GET /api/reports/categories` | CREATE, UPDATE, DELETE, reorder |
| `report_tasks` | Embedded in categories response | CREATE, UPDATE, DELETE, reorder, change type |
| `report_products` | `GET /api/reports/products` | CREATE, UPDATE, DELETE, toggle active |
| `drivers` | `GET /loading/drivers`, `POST /loading/drivers` | Full CRUD (update + delete missing) |
| `vehicles` | **None exist** | Full CRUD |
| `time_slots` | `GET /loading/time-slots` (from settings) | Manage via settings table |

**Advanced Editing gap**: The PATCH `/api/reports/:id` endpoint exists but:
- No frontend edit UI — users can only view reports after creation
- The loading schedule has no edit endpoint — only delete + recreate
- No batch operations or loading schedule update

**Extra Exports gap**: Only WhatsApp copy-text export exists:
- Reports: `GET /reports/:id/export` returns plain text
- Loading: `GET /loading/export?date=` returns plain text
- **No TXT download, no PDF generation**

### Affected Areas

#### Backend

- `packages/backend/src/server.ts` — Register new admin routes module
- `packages/backend/src/modules/auth/auth.middleware.ts` — Already has `requireRole` — no change needed
- `packages/backend/src/modules/reports/reports.routes.ts` — **New admin sub-module** for catalog CRUD (separate from user-facing reports)
- `packages/backend/src/modules/loading/loading.routes.ts` — Add vehicles CRUD, driver update/delete, schedule edit
- `packages/backend/src/modules/loading/loading.service.ts` — Add vehicle CRUD functions, driver update/delete, schedule PATCH
- `packages/backend/src/modules/loading/loading.schema.ts` — Add vehicle schemas, schedule update schema
- `packages/backend/src/modules/reports/reports.service.ts` — Already has catalog reads; add catalog mutation functions
- `packages/backend/src/modules/reports/reports.schema.ts` — Add admin CRUD request schemas
- **New**: `packages/backend/src/modules/admin/` — Admin catalog routes module (categories, tasks, products CRUD)
- **New**: `packages/backend/src/modules/reports/export-txt.service.ts` — TXT export
- **New**: `packages/backend/src/modules/reports/export-pdf.service.ts` — PDF export
- **New**: `packages/backend/src/modules/loading/export-txt.service.ts` — Loading TXT export
- **New**: `packages/backend/src/modules/loading/export-pdf.service.ts` — Loading PDF export

#### Frontend

- `packages/frontend/src/api/client.ts` — **Requires `patch()` method** (currently only has `get`, `post`, `del`)
- `packages/frontend/src/App.tsx` — Add admin route(s)
- `packages/frontend/src/components/layout/AppShell.tsx` — Add admin navigation links
- **New**: `packages/frontend/src/pages/AdminCatalogPage.tsx` — Catalog management dashboard
- **New**: `packages/frontend/src/pages/AdminDriversPage.tsx` — Driver CRUD page
- **New**: `packages/frontend/src/pages/AdminVehiclesPage.tsx` — Vehicle CRUD page
- **New**: `packages/frontend/src/pages/AdminTimeSlotsPage.tsx` — Time slots management
- **New**: `packages/frontend/src/components/admin/` — Shared admin UI components
- `packages/frontend/src/pages/ReportViewPage.tsx` — Add edit button + edit mode toggle
- **New**: `packages/frontend/src/pages/ReportEditPage.tsx` (or inline edit mode in ReportViewPage)
- `packages/frontend/src/components/reports/ExportPreview.tsx` — Add TXT/PDF download buttons
- `packages/frontend/src/components/loading/ScheduleExportView.tsx` — Add TXT/PDF download buttons
- `packages/frontend/src/pages/LoadingSchedulePage.tsx` — Add schedule entry edit (inline or modal)
- `packages/frontend/src/contexts/AuthContext.tsx` — Role info already available (user.role)
- `packages/frontend/src/i18n/locales/pt-BR.json` — Add admin, edit, export translation keys

#### Tests

| Test File | Impact | Notes |
|-----------|--------|-------|
| `backend/src/modules/reports/reports.routes.test.ts` | Append admin catalog CRUD tests | New test group for admin routes |
| `backend/src/modules/loading/loading.routes.test.ts` | Append vehicles/schedule-edit tests | New test groups |
| `backend/src/db/db-init.test.ts` | Verify new admin-related seed data | Low impact |
| `frontend/__e2e__/report-view-export.spec.ts` | Add edit + TXT/PDF export E2E | New test cases |
| `frontend/__e2e__/loading-schedule.spec.ts` | Add schedule edit + TXT/PDF export E2E | New test cases |
| **New**: `frontend/__e2e__/admin-catalog.spec.ts` | Admin CRUD E2E scenarios | Full new spec |

### Approaches

#### Approach A: Single monolithic slice — ALL in one PR
Run Admin CRUD + Editing + Exports as one massive change.

- **Pros**: Single planning/execution cycle, no context switching
- **Cons**: Estimated **1100–1500+ changed lines** — far exceeds the 400-line review budget. Massive PR risk. High cognitive load. No partial delivery.
- **Effort**: Very High

#### Approach B: Logical slices — 3 chained PRs (recommended)

**PR 1: Admin Catalog CRUD backend + frontend (~450 lines)**
- Backend: New `admin` module with catalog endpoints (categories, tasks, products CRUD) protected by `requireRole('Administrador')`
- Backend: Complete vehicle CRUD + driver update/delete
- Backend: Time slots management endpoint
- Frontend: `patch()` method in API client, Admin pages (categories, tasks, products, drivers, vehicles, time slots)
- Frontend: Admin navigation in AppShell (role-gated)
- Tests: Backend integration + E2E admin-catalog spec

**PR 2: Advanced Editing (~350 lines)**
- Frontend: Report edit mode in ReportViewPage (reuse builder form components, populate from existing report data)
- Backend: No new routes needed (PATCH exists) — verify edit-window enforcement works
- Frontend: Loading schedule entry edit (inline in ScheduleGrid — modal for driver/vehicle change)
- Backend: PATCH endpoint for loading schedule entries (new route)
- Tests: E2E report edit + loading schedule edit scenarios

**PR 3: Extra Exports (TXT + PDF) (~400 lines)**
- Backend: TXT export service for reports (plain text download with UTF-8 BOM)
- Backend: TXT export service for loading schedules
- Backend: PDF export for both (using `pdf-lib` or `jspdf` alternative on backend — or delegate to frontend)
- Frontend: TXT download button in ExportPreview and ScheduleExportView
- Frontend: PDF download / print-to-PDF option
- Tests: Backend TXT/PDF generation tests + E2E download flow

| | Pros | Cons | Complexity |
|---|------|------|------------|
| **B (recommended)** | Each PR under or near 400 lines; independent deploy; clear boundaries | 3 PRs to review; chained dependency | Medium |
| **C: Split exports further** | PR 3a (TXT) + PR 3b (PDF) = max 250 lines each | More PR overhead | Medium |

#### Approach C: Split exports — PR 3a (TXT) + PR 3b (PDF)
- Separates TXT and PDF into distinct PRs if PDF generation is complex
- **Effort**: Medium-High (more PRs, less risk per PR)

### Recommendation

**Approach B — 3 chained PRs with clear, bounded scope per PR.**

Rationale:
- The 400-line review budget is a hard constraint: Approach A would exceed it 3-4x
- PR 1 (Admin CRUD) is the highest priority and provides immediate value: operators can manage their catalog without SQL
- PR 2 (Editing) builds naturally on existing PATCH infrastructure and is mostly frontend work
- PR 3 (Exports) is the most independent — TXT is trivial, PDF requires a library decision
- Each PR can be verified and deployed independently

**Order**: PR 1 → PR 2 → PR 3 (each targets `main` after previous merges, using stacked-to-main strategy)

**PDF library decision** (needs discussion):
- Option 1: Backend generates PDF with `pdf-lib` (pure JS, no headless Chrome) — lighter but more manual layout
- Option 2: Frontend generates PDF with `html2canvas` + `jspdf` — heavier on client, but renders actual UI
- Option 3: Backend uses `puppeteer` — heavy dependency, not recommended for this stack

### Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Admin CRUD exposes mutating endpoints without proper authorization | Low | Critical | Use `requireRole('Administrador')` consistently on all admin routes |
| PDF library choice adds unexpected complexity | Medium | Medium | Start with TXT (trivial), defer PDF to end of PR 3; prototype both options before committing |
| Report edit UI is complex (all 4 task types) | Medium | Medium | Reuse CategorySection component in edit mode; pre-populate from fetched report data |
| Loading schedule edit needs null/empty state handling | Low | Low | Model after existing create pattern; use same schema validation |
| E2E tests become fragile with CRUD operations changing seed data | Medium | Low | Use unique temp names in E2E tests; clean up created records in test teardown |

### Ready for Proposal

**Yes.** The analysis is complete:

1. **Define scope per PR**:
   - PR 1: Admin CRUD (categories, tasks, products, drivers full CRUD, vehicles CRUD, time slots)
   - PR 2: Report edit UI + loading schedule edit
   - PR 3: TXT + PDF exports for reports and loading schedules

2. **Key technical decisions needed in proposal**:
   - Whether admin routes go in a standalone `modules/admin/` or extend existing `modules/reports/` (recommend: standalone `admin/` for clean boundary, `requireRole` protection)
   - PDF library choice (`pdf-lib` vs frontend-based)
   - Report edit UI approach: inline in ReportViewPage (toggle mode) vs separate ReportEditPage
   - Whether loading schedule edit modifies a single entry or replaces it (recommend: PATCH `loading/schedules/:id` with same validation as create)

3. **Direct next phase**: `sdd-propose` for PR 1 (Admin CRUD)

### Effort Estimate

| Slice | Backend | Frontend | Tests | Total (est. lines) |
|-------|---------|----------|-------|-------------------|
| PR 1: Admin CRUD | ~180 | ~200 | ~70 | ~450 |
| PR 2: Advanced Editing | ~50 | ~200 | ~100 | ~350 |
| PR 3: Extra Exports | ~150 | ~100 | ~150 | ~400 |
| **Total** | ~380 | ~500 | ~320 | **~1200** |
