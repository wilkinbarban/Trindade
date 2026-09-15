## Exploration: Horário de Carregamento — Loading Schedule with Max 3 Fleteros per Time Slot

### Current State

**Foundation slice verified PASS. Relatório Inteligente slice verified PASS with 42/42 backend tests and 20/20 Playwright E2E tests.**

The project has:
- **Backend**: Fastify 5 + better-sqlite3 + Zod + JWT with module pattern (routes/service/schema) and node:test with in-memory SQLite via `buildTestApp()`
- **Frontend**: React 19 + Vite 6 + TailwindCSS 3 + shadcn/ui components + i18next with pt-BR locale
- **E2E**: Playwright with webServer config (e2e-server + Vite), `loginViaApi` + `goToProtected` auth helpers
- **Database**: All 16 PRD tables already exist in schema.sql. Relevant tables: `drivers`, `vehicles`, `loading_schedules` with unique index on (schedule_date, time_slot, driver_id). Settings table already seeded with `loading_time_slots`.
- **Dashboard**: Already queries `loading_schedules` for tomorrow's count (schedulesTomorrow card)
- **Sidebar**: "Cronograma de Carregamento" nav item exists but is `disabled`
- **i18n**: `nav.loadingSchedule` key exists in pt-BR.json, plus `dashboard.schedulesTomorrow`

### PRD Requirements This Slice Must Satisfy

| Section | Requirement |
|---------|-------------|
| **§18** | Module to organize next day's loading |
| **§19** | Two driver types: Fleteros (external, name required, plate optional) and Casa (company vehicles, plate required) |
| **§20** | Max 3 fleteros per time slot. Error message: "No es posible agregar más fleteros en este horario. El límite máximo es 3." |
| **§21** | Allowed time slots: 04:00, 04:30, 05:00, 05:30, 06:00, 06:30, 07:00 (configurable in future) |
| **§22** | WhatsApp export: ascending by time slot, table format with Horário/Nome/Placa columns, always in Portuguese |
| **§23** | Export types: Copiar WhatsApp, TXT, PDF (same as reports) |
| **§5 (Roles)** | Both Administrador and Trabalhador can create horários |
| **§24** | Dashboard card: "Cargamentos de mañana" (already implemented) |

### Relevant Database Tables (Already in Schema)

| Table | Purpose | Key Columns | Notes |
|-------|---------|-------------|-------|
| `drivers` | Fleteros (external freelancers) | `id`, `name`, `license_plate` (optional), `is_active` | Already exists |
| `vehicles` | Company-owned vehicles | `id`, `description`, `license_plate` (mandatory), `is_active` | Already exists |
| `loading_schedules` | Schedule entries | `id`, `schedule_date`, `time_slot`, `driver_id` (nullable FK), `vehicle_id` (nullable FK), `driver_type` ('fletero'|'casa') | Already exists |
| `settings` | App configuration | `key`, `value` | `loading_time_slots` already seeded |

**Schema notes:**
- The unique index `idx_schedule_slot` on (schedule_date, time_slot, driver_id) prevents duplicate driver entries per slot
- **No DB constraint** enforces max 3 fleteros per slot — must be validated in business logic
- driver_id is nullable (correct: casa entries use vehicle_id), vehicle_id is nullable (correct: fletero entries use driver_id)
- No CHECK constraint ensuring at least one of driver_id/vehicle_id is populated based on driver_type

### Affected Areas

#### Backend — New Module: `packages/backend/src/modules/loading/`

| File | Action | Why |
|------|--------|-----|
| `src/modules/loading/loading.schema.ts` | CREATE | Zod schemas for schedule CRUD, driver management, fletero quota validation |
| `src/modules/loading/loading.service.ts` | CREATE | Business logic: create/read/list/delete/update schedules, fletero count validation per slot, WhatsApp export text |
| `src/modules/loading/loading.routes.ts` | CREATE | Fastify routes: schedule CRUD + export + driver/vehicle listing |
| `src/modules/loading/loading.routes.test.ts` | CREATE | Integration tests for schedule endpoints, quota enforcement, auth |
| `src/modules/loading/loading.export.service.ts` | CREATE | WhatsApp text builder for loading schedules (ascending by time, table format, always pt-BR) |

#### Backend — Existing Files Modified

| File | Why Affected |
|------|-------------|
| `src/server.ts` | Register `loadingRoutes` with prefix `/api/loading` |
| `src/test-helper.ts` | Register loading routes for test app |
| `src/modules/dashboard/dashboard.routes.ts` | No change — already queries loading_schedules for tomorrow count |

#### Frontend — New Files

| File | Action | Why |
|------|--------|-----|
| `src/pages/LoadingSchedulePage.tsx` | CREATE | Main schedule page: calendar/day selector, time slot grid, driver assignment per slot |
| `src/components/loading/ScheduleGrid.tsx` | CREATE | Receives time slots x drivers for a given date, displays entries per time slot |
| `src/components/loading/DriverSelect.tsx` | CREATE | Dropdown to select fletero/casa driver for a slot |
| `src/components/loading/DriverForm.tsx` | CREATE | Quick-add form for new fletero (name + optional plate) |
| `src/components/loading/ScheduleExportView.tsx` | CREATE | Dialog showing WhatsApp-formatted table with copy button |

#### Frontend — Existing Files Modified

| File | Why Affected |
|------|-------------|
| `src/App.tsx` | Add route `/loading` pointing to LoadingSchedulePage |
| `src/components/layout/AppShell.tsx` | Enable "Cronograma de Carregamento" nav item, set href="/loading" |
| `src/i18n/locales/pt-BR.json` | Add loading schedule UI strings, export formats, validation messages |

### Approaches

#### Approach A: Full Schedule Module in One Slice

Include: driver CRUD, schedule CRUD, fletero quota enforcement, WhatsApp export, date navigation, fleet vehicle management, all E2E tests.

| Pros | Cons | Effort |
|------|------|--------|
| Complete feature in one delivery | ~800-1000 lines — exceeds 400-line budget significantly | **High** |

#### Approach B: Core Schedule CRUD + Quota + WhatsApp Export (Recommended)

**Recommended bounded slice**:
- Backend: `GET /api/loading/schedules?date=YYYY-MM-DD` (list schedule for a date), `POST /api/loading/schedules` (add entry with fletero quota check), `DELETE /api/loading/schedules/:id` (remove entry), `GET /api/loading/export?date=YYYY-MM-DD` (WhatsApp format), `GET /api/loading/drivers` (list fleteros), `POST /api/loading/drivers` (create fletero)
- Frontend: Date-picker/selector, time slot grid showing current assignments, add/delete per slot, WhatsApp export modal, driver quick-add form
- Fletero quota: max 3 per time_slot per schedule_date, validated in service layer

| Pros | Cons | Effort |
|------|------|--------|
| Delivers PRIMARY value — users can schedule fleteros with conflict prevention. Tightly bounded. All key rules enforced. | Fleet vehicle CRUD deferred (add via seed initially). Edit/deferred (delete+recreate works). TXT/PDF export deferred. | **Medium** (~600-700 lines) |

#### Approach C: Minimal — View-Only Schedule

Read-only grid showing today's schedule, no CRUD, no WhatsApp export.

| Pros | Cons | Effort |
|------|------|--------|
| Tiny scope | Incomplete — users cannot CREATE or MANAGE schedules. Zero operational value. | **Low** |

### Recommendation

**Approach B** — Core Schedule CRUD with fletero quota enforcement and WhatsApp export.

This slice comprises:

```
BACKEND:
├── GET    /api/loading/schedules?date=YYYY-MM-DD    — List all schedule entries for a date (sorted by time_slot)
├── POST   /api/loading/schedules                     — Add a schedule entry, validate max 3 fleteros per slot
├── DELETE /api/loading/schedules/:id                 — Remove an entry (undo/override)
├── GET    /api/loading/export?date=YYYY-MM-DD        — WhatsApp-formatted table (ascending by time, pt-BR)
├── GET    /api/loading/drivers                       — List active fleteros
├── POST   /api/loading/drivers                       — Create new fletero
└── Service: fletero count validation per slot, deduplication check

FRONTEND:
├── /loading                                           — Date selector + time slot grid
│   ├── Date navigation (prev/next day, today button)
│   ├── Time slot rows (04:00–07:00, 30-min intervals)
│   │   ├── Slot label → current driver names + plates
│   │   ├── "Add" button → driver selector (dropdown of fleteros + casa vehicles)
│   │   └── Fletero count indicator (3/3 = full, 2/3 = OK, etc.)
│   ├── Driver creation: quick-add form (name + optional plate)
│   ├── Delete button per entry (with confirmation)
│   └── WhatsApp export button → modal with formatted table + copy
├── i18n additions: schedule UI, validation messages, export headers
└── Nav: enable "Cronograma de Carregamento" in sidebar
```

**Component Sizing Forecast**:
- Backend: ~300 lines (routes + service + schemas + tests)
- Frontend: ~350 lines (pages + components + i18n)
- E2E tests: ~80 lines
- **Total: ~730 lines** → exceeds 400-line budget → **likely needs 2 chained PRs**
- Suggested split: PR 1 (Backend API + schema + tests) → PR 2 (Frontend UI + E2E)

### Data Flow

```
[Loading Schedule Page] ──(GET /api/loading/schedules?date=...)──→ [Fastify Route]
        │                                                              │
        │                                                              ▼
        │                                                    [Service: list by date]
        │                                                              │
        │                                                              ▼
        │                                              [SQLite: WHERE schedule_date = ?]
        │
        │──(POST /api/loading/schedules)──→ [Route → Service]
        │                                      │
        │                                      ├→ Validate: driver exists
        │                                      ├→ Validate: time_slot is valid
        │                                      ├→ Validate: max 3 fleteros (COUNT WHERE driver_type='fletero')
        │                                      └→ INSERT into loading_schedules
        │
        │──(DELETE /api/loading/schedules/:id)──→ [Route → Service → DELETE]
        │
        └──(GET /api/loading/export?date=...)──→ [Export Service → WhatsApp formatted text]
```

### Validation Rules

| Rule | Where | Behavior |
|------|-------|----------|
| Max 3 fleteros per time slot | Service::createSchedule | COUNT fleteros for (date, slot) BEFORE insert; reject with 409 if >= 3 |
| Duplicate driver in same slot | Unique index + service check | IF EXISTS (schedule_date, time_slot, driver_id) → reject duplicate |
| Valid time slot | Service | Compare against `loading_time_slots` from settings (04:00-07:00, 30min) |
| Valid driver_type | Schema | Zod enum: 'fletero' | 'casa' |
| Driver exists | Service | Foreign key reference must exist; check is_active |
| Schedule date | Schema | Required, format YYYY-MM-DD, must be today or future (schedule for next day) |

### Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Scope creep into fleet vehicle management | Medium | Defer to separate slice; seed a few casa vehicles for demo |
| Empty drivers table at launch | Medium | Seed at least 5-10 sample fleteros for testing |
| Time slot overlap confusion | Low | Clear slot labels, show assigned count per slot, color-code when full |
| Date navigation complexity | Low | Simple prev/next day + today button pattern |
| WhatsApp table formatting on mobile devices | Low | Use monospace-friendly styling or pipe-delimited table that renders well in WhatsApp |
| No edit endpoint — delete+recreate UX | Low | Acceptable for MVP; users can delete and re-add easily |

### Ready for Proposal

**Yes.** The exploration is complete. The bounded slice covers:

1. Backend loading module: schedule CRUD, driver listing/creation, fletero quota enforcement (max 3 per time slot per date), WhatsApp export in pt-BR
2. Frontend schedule page: date navigation, time slot grid, driver assignment, quota indicators, WhatsApp export modal
3. i18n for all schedule UI and export text
4. Backend + E2E tests

**Explicitly deferred from this slice**:
- Fleet vehicle CRUD (vehicles table exists, seed a few for initial use)
- Schedule edit endpoint (delete + re-create pattern is acceptable for MVP)
- TXT/PDF export formats
- Admin-only management UI for drivers

The orchestrator should tell the user:
> "Exploration complete for Horário de Carregamento. The recommended bounded slice includes: schedule CRUD for a selected date, driver listing and creation, max 3 fleteros per time slot validation, WhatsApp export in Portuguese with ascending time-sort order, time slot grid with quota indicators, and corresponding tests. Estimated ~730 lines → likely needs 2 chained PRs (backend first, then frontend + E2E). Fleet vehicle CRUD, edit endpoint, and TXT/PDF export are deferred."

### Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Fletero quota validation | Service layer COUNT before INSERT | No DB constraint exists; business logic is simpler and more expressive |
| WhatsApp export location | Backend export service | Same pattern as reports module; always generates pt-BR text from backend |
| Driver CRUD in this slice | Yes — minimal: list + create only | Users must be able to add fleteros; no edit/delete for now |
| Date navigation | Frontend-managed state, passed as query param | Same as existing ReportHistoryPage pattern |
| Delete instead of edit | Yes for MVP | Lower complexity; user can delete and re-add if correction needed |
