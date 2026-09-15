## Exploration: Relatório Inteligente — Smart Report Builder with WhatsApp Export

### Current State

**Foundation slice verified PASS** — the project has:

- **Monorepo**: npm workspaces with `packages/backend` (Fastify + better-sqlite3 + Zod + JWT) and `packages/frontend` (React 19 + Vite 6 + TailwindCSS 3 + shadcn/ui components + i18next)
- **Database**: All 16 PRD tables created upfront in `schema.sql`. The 9 tables relevant to reports already exist with full columns. No schema changes needed.
- **Auth**: JWT login with `Administrador` / `Trabalhador` roles, role guard middleware, AuthContext with localStorage persistence.
- **Frontend shell**: Responsive AppShell with sidebar/header, i18n (pt-BR only currently), shadcn components (Button, Card, Input).
- **Dashboard**: Summary endpoint + cards showing reports today, schedules tomorrow, active users.
- **Sidebar**: "Relatórios" nav item exists but is `disabled` — awaiting this slice.
- **Testing**: `node:test` with Fastify `.inject()`, in-memory SQLite, `buildTestApp()` helper.

No report-specific backend modules or frontend pages exist yet.

### PRD Requirements This Slice Must Satisfy

| Section | Requirement |
|---------|-------------|
| **§7** | Generate professional reports via visual selection — no long text writing |
| **§8** | Automatic turn detection: `tarde` (after 18:30), `noite` (06:00–18:30) via server time |
| **§9** | Generador Inteligente — user only selects, system builds formatted text |
| **§10** | Four element types: Check (☑), List (multi-select), Temperature (numeric °C), Quantity (product + amount) |
| **§11** | Dynamic categories — any authorized user may create new ones |
| **§12** | Master catalog for "Caixas" (admin-managed, Portuguese names) |
| **§13** | Recebimento catalog — dynamic products for list-type tasks |
| **§15** | Report history with filters: Today, Yesterday, Last 7 days, Last 30 days, Custom |
| **§16** | Edit rules: current day + previous day editable; older = read-only |
| **§17** | Export format: WhatsApp-styled text with Portuguese headers, emojis, sections |
| **§23** | Export types: Copiar WhatsApp (required), TXT (defer), PDF (defer) |
| **§6** | **All WhatsApp exports MUST be in Brazilian Portuguese regardless of system language** |

### Relevant Database Tables (Already in Schema)

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `report_categories` | Dynamic category groups | `name_pt`, `name_es`, `sort_order` |
| `report_tasks` | Items within categories | `category_id`, `name_pt`, `name_es`, `task_type` ('check','list','temperature','quantity'), `sort_order` |
| `report_products` | Master catalog products | `group_name`, `name_pt`, `is_active` |
| `report_templates` | Predefined structures | `name` |
| `reports` | Report headers | `user_id`, `turno` ('tarde','noite'), `report_date`, `notes`, `is_editable` |
| `report_items` | Check/list selections | `report_id`, `task_id`, `checked` |
| `report_temperatures` | Temperature readings | `report_id`, `location`, `value` |
| `report_quantities` | Product quantities | `report_id`, `product_id`, `quantity` |
| `report_photos` | Optional photos (deferred) | `report_id`, `file_path`, `file_size`, `mime_type` |

**No schema changes required** for this slice — all tables already exist. The only potential addition is an `emoji` column on `report_categories` for export formatting, but this can be handled via convention or a settings table lookup.

### Affected Areas

#### Backend — New Module: `packages/backend/src/modules/reports/`

| File | Why Affected |
|------|-------------|
| `src/modules/reports/reports.routes.ts` | NEW — Report CRUD, history, export endpoints |
| `src/modules/reports/reports.service.ts` | NEW — Business logic, turn detection, text generation |
| `src/modules/reports/reports.schema.ts` | NEW — Zod schemas for request/response validation |
| `src/modules/reports/reports.test.ts` | NEW — Integration tests for report endpoints |
| `src/server.ts` | MODIFY — Register `reportsRoutes` with JWT auth prefix |
| `src/test-helper.ts` | MODIFY — Register report routes for test app |

#### Backend — Existing

| File | Why Affected |
|------|-------------|
| `src/db/schema.sql` | No change needed — all tables ready |
| `src/modules/reports/export.service.ts` | NEW — WhatsApp text builder (Generador Inteligente) |

#### Frontend

| File | Why Affected |
|------|-------------|
| `src/pages/ReportsPage.tsx` | NEW — Report builder with dynamic form sections |
| `src/pages/ReportHistoryPage.tsx` | NEW — History list with date filters |
| `src/pages/ReportViewPage.tsx` | NEW — View single report with WhatsApp export |
| `src/App.tsx` | MODIFY — Add `/reports` and `/reports/history` routes |
| `src/components/layout/AppShell.tsx` | MODIFY — Enable the `reports` nav item, add history link |
| `src/components/reports/` | NEW — Sub-components: CategorySection, CheckItem, ListSelector, TemperatureInput, QuantitySelector, ExportPreview |
| `src/api/client.ts` | No change — generic enough |
| `src/i18n/locales/pt-BR.json` | MODIFY — Add report/export translation keys |
| `src/lib/utils.ts` | No change — cn() helper already exists |

### Approaches

#### Approach A: Full Relatório Slice (All Features)

Include report builder, history, photo uploads, WhatsApp export, TXT export, PDF export, admin CRUD for categories/products, and edit functionality — all in one slice.

| Pros | Cons | Effort |
|------|------|--------|
| Single delivery, one complete feature | Very large scope (~1500+ lines), risky, exceeds 400-line budget many times over | **Very High** |

#### Approach B: Core Relatório + Generador Inteligente + WhatsApp Export (Recommended)

The report builder with all 4 element types, Generador Inteligente (text generation), WhatsApp export, history with filters, and category/task/product read endpoints. **Defer**: photo uploads, PDF export, TXT export, admin CRUD UIs for categories/tasks/products, edit functionality.

| Pros | Cons | Effort |
|------|------|--------|
| Delivers the PRIMARY value — users can create reports with the smart builder and share via WhatsApp immediately. Tightly bounded scope. No photo/PDF complexity. All 4 element types included — no crippled features. | Photos deferred (optional per PRD). PDF/TXT deferred. Edit remains for follow-up. At ~600-800 lines, likely needs 2 chained PRs. | **Medium-High** |
| WhatsApp export IS the Generador Inteligente output — they are the same feature. | | |

#### Approach C: Minimal — Checks Only

Only implement the "check" element type, skip list/temperature/quantity. Defer most features.

| Pros | Cons | Effort |
|------|------|--------|
| Smallest scope | Incomplete — 3 of 4 element types missing. Users cannot report temperatures or quantities. Not a "Generador Inteligente" by definition. | **Low-Medium** |

#### WhatsApp Export Decision

**Belongs in this slice. Here's why:**

1. The PRD **Section 9** defines "Generador Inteligente" as the system building text automatically — this IS the WhatsApp export engine.
2. **Section 17** shows the full WhatsApp export format — it's the output of the smart builder.
3. **Section 23** lists "Copiar WhatsApp" as a required export for reports.
4. Without WhatsApp export, there is no delivery mechanism for the relatório — the user can see it in the browser but can't share it.
5. **Section 6** mandates all WhatsApp exports in Brazilian Portuguese — this logic lives in the export service.
6. Implementation complexity is **low**: it's pure text templating (no external API, no dependencies beyond string formatting).

### Recommendation

**Approach B** — Core Relatório Inteligente with WhatsApp export, deferring photos, PDF, and admin CRUD UIs.

This slice comprises:

```
BACKEND:
├── GET    /api/reports/categories          — List categories with their tasks (READ)
├── GET    /api/reports/products            — List master catalog products (READ for quantity type)
├── POST   /api/reports                     — Create report with items/temperatures/quantities
├── GET    /api/reports                     — List reports with ?from=&to=&page= filters (history)
├── GET    /api/reports/:id                 — Get single report with all elements
├── PATCH  /api/reports/:id                 — Update editable report (within edit window)
├── GET    /api/reports/:id/export/whatsapp — Generate WhatsApp-formatted Portuguese text
└── Service: turno auto-detection from server time

FRONTEND:
├── /reports/new         — Report builder with dynamic sections per category
│   ├── Check items       → checkbox per task
│   ├── List items        → multi-select checkboxes
│   ├── Temperature items → numeric input with °C
│   └── Quantity items    → product selector + number input
├── /reports              — History list with date filter buttons
├── /reports/:id          — Single report view + WhatsApp copy button
└── i18n additions         — pt-BR keys for all report UI + static export headers
```

**Component Sizing Forecast**:
- Backend: ~400 lines (routes + service + schemas + tests)
- Frontend: ~450 lines (pages + components + i18n)
- **Total: ~850 lines** → exceeds 400-line budget → **requires 2 chained PRs**
- Suggested split: PR 1 (Backend API + tests) → PR 2 (Frontend pages + integration)

### Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Scope creep into photo uploads or PDF | Medium | Strictly enforce the bounded slice scope; these are separate follow-up slices |
| WhatsApp emoji rendering differs across mobile clients | Low | Use common emoji (✅📦🌡️📥🎯) that render on all modern WhatsApp clients |
| Turno auto-detection based on server time may not match user expectation | Low | Display the detected turno prominently and allow manual override in the form |
| The Generador Inteligente text builder needs i18n-neutral category headers for WhatsApp export (always Portuguese) | Low | Export service always generates Portuguese text; use `name_pt` column regardless of user's UI language |
| Report edit window (current + previous day) edge cases | Medium | Implement simple `is_editable` check as boolean on the server; PATCH route verifies date |
| Missing category emoji data for export formatting | Low | Add `emoji` column to `report_categories` or derive from task_type via convention (map: check→✅, list→📥, temperature→🌡️, quantity→📦) |

### Ready for Proposal

**Yes.** The exploration is complete. The bounded slice covers:

1. Backend reports module with all 4 element types + Generador Inteligente text engine
2. WhatsApp export (IS the "inteligente" output — not optional, not deferrable)
3. Report history with filters
4. Frontend report builder with dynamic category-driven forms
5. i18n for all report UI and static export text (always Portuguese for WhatsApp)

**Explicitly deferred from this slice**:
- Photo uploads (report_photos table exists, unused until next slice)
- PDF and TXT export formats
- Admin CRUD UIs for categories/tasks/products (API endpoints for READ included)
- Standalone audit log UI

The orchestrator should tell the user:

> "Exploration complete for Relatório Inteligente. The recommended bounded slice includes: report builder with all 4 element types (check, list, temperature, quantity), the Generador Inteligente text builder, WhatsApp export in Portuguese, and history with date filters. Photos, PDF, and admin CRUD UIs are deferred. Estimated ~850 lines → needs 2 chained PRs."
