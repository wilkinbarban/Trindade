## Exploration: Trindade Operations — Foundation Slice

### Current State

**Greenfield project** — only `PRD_Trindade.md` exists. No code, no packages, no configuration. The project directory contains:

```
Trindade/
├── .atl/skill-registry.md
├── .gitignore
├── openspec/
│   ├── config.yaml
│   ├── specs/         (empty)
│   └── changes/
│       └── archive/
├── PRD_Trindade.md
```

No `package.json`, no `tsconfig.json`, no Docker files, no source code at all. The SDD init phase has been completed — project context and testing capabilities artifacts exist in Engram.

### Foundation Slice Scope (from orchestrator)

The requested foundation slice comprises:

1. **Monorepo** — single repo with `packages/backend` and `packages/frontend`
2. **TypeScript** — strict configuration for both packages
3. **Docker Compose** — multi-service orchestration (API + DB + reverse proxy)
4. **SQLite** — database initialization, schema, and migrations
5. **JWT Auth** — login, admin/trabajador roles, middleware guard
6. **Responsive Layout** — mobile-first base shell (sidebar/nav, header)
7. **Base Dashboard** — operational summary cards (reports today, loading tomorrow, users, summary)

Subsequent slices will add: full reports module, loading schedules, master catalogs, photos, history, WhatsApp exports, PDF generation, audit logs.

### Affected Areas

Since this is greenfield, every path will be created. The key ones:

- `docker-compose.yml` — container orchestration
- `packages/backend/src/index.ts` — Fastify entry point
- `packages/backend/src/db/schema.ts` — SQLite table definitions
- `packages/backend/src/modules/auth/` — JWT login, role guards
- `packages/backend/src/modules/users/` — user CRUD
- `packages/backend/src/modules/dashboard/` — summary queries
- `packages/frontend/src/App.tsx` — router + layout shell
- `packages/frontend/src/pages/LoginPage.tsx` — auth UI
- `packages/frontend/src/pages/DashboardPage.tsx` — dashboard UI
- `packages/frontend/src/contexts/AuthContext.tsx` — auth state, token mgmt
- `packages/frontend/src/api/client.ts` — HTTP client with token injection

---

## Technical Plan

### Folder Structure

```
trindade/
├── .gitignore
├── .env.example
├── docker-compose.yml
├── Dockerfile                    # multi-stage: build + prod
├── nginx.conf                    # reverse proxy for production
├── Makefile                      # convenience commands
│
├── packages/
│   ├── backend/
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── src/
│   │   │   ├── index.ts                  # Fastify bootstrap
│   │   │   ├── config/
│   │   │   │   └── env.ts                # environment variables (Zod)
│   │   │   ├── db/
│   │   │   │   ├── client.ts             # better-sqlite3 init
│   │   │   │   ├── schema.ts             # full table creation DDL
│   │   │   │   └── seed.ts               # default admin user + roles
│   │   │   ├── modules/
│   │   │   │   ├── auth/
│   │   │   │   │   ├── auth.routes.ts
│   │   │   │   │   ├── auth.service.ts
│   │   │   │   │   ├── auth.schema.ts    # Zod request/response
│   │   │   │   │   └── auth.guard.ts     # JWT verification + role check
│   │   │   │   ├── users/
│   │   │   │   │   ├── users.routes.ts
│   │   │   │   │   ├── users.service.ts
│   │   │   │   │   └── users.schema.ts
│   │   │   │   └── dashboard/
│   │   │   │       ├── dashboard.routes.ts
│   │   │   │       ├── dashboard.service.ts
│   │   │   │       └── dashboard.schema.ts
│   │   │   └── shared/
│   │   │       ├── errors.ts             # custom error classes
│   │   │       └── helpers.ts
│   │   └── data/                         # SQLite volume mount at runtime
│   │
│   └── frontend/
│       ├── package.json
│       ├── tsconfig.json
│       ├── tsconfig.node.json
│       ├── vite.config.ts
│       ├── tailwind.config.ts
│       ├── postcss.config.js
│       ├── components.json               # shadcn/ui config
│       ├── index.html
│       ├── public/
│       │   └── logo.svg
│       └── src/
│           ├── main.tsx
│           ├── App.tsx
│           ├── index.css                  # Tailwind directives
│           ├── routes/
│           │   └── index.tsx              # react-router config
│           ├── pages/
│           │   ├── LoginPage.tsx
│           │   └── DashboardPage.tsx
│           ├── components/
│           │   ├── ui/                    # shadcn/ui primitives
│           │   ├── layout/
│           │   │   ├── AppShell.tsx       # sidebar + header + main
│           │   │   ├── Sidebar.tsx
│           │   │   └── Header.tsx
│           │   └── dashboard/
│           │       └── SummaryCard.tsx
│           ├── hooks/
│           │   └── useAuth.ts
│           ├── i18n/
│           │   ├── index.ts
│           │   ├── locales/
│           │   │   ├── pt-BR.json
│           │   │   └── es.json
│           ├── api/
│           │   └── client.ts              # fetch/axios wrapper with JWT
│           ├── contexts/
│           │   └── AuthContext.tsx
│           └── lib/
│               ├── utils.ts               # cn() helper
│               └── constants.ts
```

---

### Database Model (Foundation-Ready Schema)

For the foundation slice, I propose creating **all tables** defined in the PRD upfront with their full columns, so that subsequent slices only add queries and endpoints — no schema migrations. SQLite's schema-on-write makes this risk-free.

```sql
-- ============================================================
-- Roles
-- ============================================================
CREATE TABLE roles (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL UNIQUE,  -- 'admin' | 'trabajador'
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Users
-- ============================================================
CREATE TABLE users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  username       TEXT    NOT NULL UNIQUE,
  password_hash  TEXT    NOT NULL,
  display_name   TEXT    NOT NULL,
  role_id        INTEGER NOT NULL REFERENCES roles(id),
  is_active      INTEGER NOT NULL DEFAULT 1,
  created_at     TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Categories (dynamic, user-created)
-- ============================================================
CREATE TABLE report_categories (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name_pt     TEXT    NOT NULL,            -- name in Portuguese
  name_es     TEXT    NOT NULL,            -- name in Spanish
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Tasks (items inside a category, e.g. "Pátio organizado")
-- ============================================================
CREATE TABLE report_tasks (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  category_id   INTEGER NOT NULL REFERENCES report_categories(id),
  name_pt       TEXT    NOT NULL,
  name_es       TEXT    NOT NULL,
  task_type     TEXT    NOT NULL CHECK(task_type IN ('check','list','temperature','quantity')),
  sort_order    INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Master Catalog Products (admin-managed, "Caixas Assaí Grandes" etc.)
-- ============================================================
CREATE TABLE report_products (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  group_name    TEXT    NOT NULL,           -- e.g. "Caixas Assaí Grandes"
  name_pt       TEXT    NOT NULL,           -- always in Portuguese per PRD
  is_active     INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Templates (pre-defined report structures)
-- ============================================================
CREATE TABLE report_templates (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Reports
-- ============================================================
CREATE TABLE reports (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id         INTEGER NOT NULL REFERENCES users(id),
  turno           TEXT    NOT NULL CHECK(turno IN ('tarde','noite')),
  report_date     TEXT    NOT NULL,          -- YYYY-MM-DD
  notes           TEXT,
  is_editable     INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Items (checklist / list selections)
-- ============================================================
CREATE TABLE report_items (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id INTEGER NOT NULL REFERENCES reports(id),
  task_id   INTEGER NOT NULL REFERENCES report_tasks(id),
  checked   INTEGER NOT NULL DEFAULT 0
);

-- ============================================================
-- Report Temperatures
-- ============================================================
CREATE TABLE report_temperatures (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES reports(id),
  location    TEXT    NOT NULL,              -- e.g. "Câmara Principal"
  value       REAL    NOT NULL,              -- e.g. -18.0
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Quantities
-- ============================================================
CREATE TABLE report_quantities (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES reports(id),
  product_id  INTEGER NOT NULL REFERENCES report_products(id),
  quantity    INTEGER NOT NULL,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Report Photos
-- ============================================================
CREATE TABLE report_photos (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES reports(id),
  file_path   TEXT    NOT NULL,
  file_size   INTEGER NOT NULL,             -- bytes, max 5MB
  mime_type   TEXT    NOT NULL CHECK(mime_type IN ('image/jpeg','image/png','image/webp')),
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Drivers (Fleteros — external freelancers)
-- ============================================================
CREATE TABLE drivers (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  license_plate TEXT,                        -- optional for fleteros
  is_active   INTEGER NOT NULL DEFAULT 1,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Vehicles (Casa — company-owned)
-- ============================================================
CREATE TABLE vehicles (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  description     TEXT    NOT NULL,
  license_plate   TEXT    NOT NULL,          -- mandatory for company vehicles
  is_active       INTEGER NOT NULL DEFAULT 1,
  created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Loading Schedules
-- ============================================================
CREATE TABLE loading_schedules (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  schedule_date TEXT   NOT NULL,             -- YYYY-MM-DD
  time_slot   TEXT    NOT NULL,              -- '04:00', '04:30', etc.
  driver_id   INTEGER REFERENCES drivers(id),
  vehicle_id  INTEGER REFERENCES vehicles(id),
  driver_type TEXT    NOT NULL CHECK(driver_type IN ('fletero','casa')),
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Audit Log
-- ============================================================
CREATE TABLE audit_logs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER REFERENCES users(id),
  action      TEXT    NOT NULL,              -- 'login','create','edit','delete'
  entity_type TEXT    NOT NULL,              -- 'report','schedule','user',etc.
  entity_id   INTEGER,
  ip_address  TEXT,
  details     TEXT,                          -- JSON payload for context
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- App Settings
-- ============================================================
CREATE TABLE settings (
  key         TEXT    PRIMARY KEY,
  value       TEXT    NOT NULL,
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- ============================================================
-- Indexes
-- ============================================================
CREATE INDEX idx_reports_date     ON reports(report_date);
CREATE INDEX idx_reports_user     ON reports(user_id);
CREATE INDEX idx_reports_turno    ON reports(turno);
CREATE INDEX idx_audit_user       ON audit_logs(user_id);
CREATE INDEX idx_audit_created    ON audit_logs(created_at);
CREATE INDEX idx_schedules_date   ON loading_schedules(schedule_date);
CREATE UNIQUE INDEX idx_schedule_slot ON loading_schedules(schedule_date, time_slot, driver_id);
```

**Foundation seed data**:
- Role `admin` (id=1), role `trabajador` (id=2)
- Default admin user (`admin` / hashed password — configured via env var)
- Allowed time slots in settings: `loading_time_slots = ["04:00","04:30","05:00","05:30","06:00","06:30","07:00"]`

---

### Main Modules (Foundation)

| Module | Endpoints | Frontend Pages | Auth |
|--------|-----------|----------------|------|
| **Auth** | `POST /api/auth/login` → JWT | `LoginPage.tsx` | Public |
| **Auth** | `GET /api/auth/me` → current user | — | JWT |
| **Users** | `GET /api/users` (list), `POST /api/users` (create), `PATCH /api/users/:id`, `DELETE /api/users/:id` | — *(admin UI in later slice)* | Admin only |
| **Dashboard** | `GET /api/dashboard/summary` → report count, tomorrow's schedule count, active users | `DashboardPage.tsx` | JWT |
| **Health** | `GET /api/health` → DB connectivity check | — | Public |

**JWT Strategy**:
- Access token: short-lived (15 min), signed with HS256, payload includes `{ sub: userId, role: roleName }`
- No refresh token in v1 foundation (session on SPA: re-login or extend expiry)
- Guard middleware: `verifyJwt` → check signature + expiry → attach user to request → optional `requireRole('admin')` for admin-only routes

**Frontend Auth Flow**:
1. `LoginPage` submits credentials → receives JWT
2. `AuthContext` stores token in `localStorage`
3. `api/client.ts` attaches `Authorization: Bearer <token>` on every request
4. `useAuth()` hook exposes `{ user, login, logout, isAuthenticated, isAdmin }`
5. Route guards redirect unauthenticated users to `/login`
6. Token expiry → 401 interceptor → redirect to login

---

### Implementation Order (Foundation Slice)

The foundation slice should be built in 8 ordered steps:

| Step | What | Why This Order |
|------|------|----------------|
| **1** | Root monorepo + `packages/backend` scaffold | Package manager, root `tsconfig`, backend skeleton must exist first |
| **2** | Backend config + database schema + seed | Everything depends on DB being ready — tables, indexes, seed data |
| **3** | Auth module (server) | Login and JWT issuance — no frontend can work without it |
| **4** | Auth guard middleware + `/api/auth/me` | Enables all subsequent authenticated endpoints |
| **5** | Users CRUD (server, admin-only) | Needed for admin to manage who can log in |
| **6** | Dashboard summary endpoint | Low complexity, gives frontend something real to display |
| **7** | Frontend scaffold + layout shell | Vite + React + Tailwind + shadcn/ui + routing + i18n + responsive AppShell |
| **8** | Login page + AuthContext + API client | Connect frontend auth flow to backend — full login cycle operational |
| **9** | Dashboard page | Consume `/api/dashboard/summary`, render SummaryCards |
| **10** | Docker Compose + production Dockerfile + nginx.conf | Containerize everything; test full stack in Compose |
| **11** | Makefile + `.env.example` + final wiring | Developer experience: `make install`, `make dev`, `make build` |

---

### Approaches

1. **Single-bucket monorepo (npm workspaces)** — recommended
   - npm workspaces at root, `packages/backend` + `packages/frontend`
   - Shared TypeScript config through root `tsconfig.base.json`
   - No additional build tooling (Turborepo/Nx) in foundation — add later if needed
   - Pros: Zero extra dependencies, simple, standard
   - Cons: No caching/skip-worktree for large builds (irrelevant for foundation)
   - Effort: Low

2. **pnpm workspaces** — alternative
   - Same structure, pnpm for stricter dependency isolation
   - Pros: Disk-efficient, strict
   - Cons: Adds a tool the PRD doesn't specify; introduces pnpm-specific lockfile
   - Effort: Low

3. **Separate repos** — not recommended
   - Decoupled repos for frontend and backend with Docker Compose linking them
   - Pros: Cleaner separation
   - Cons: PRD implies a single project; higher overhead for the MVP team of one
   - Effort: Medium

### Recommendation

**Approach 1 (npm workspaces single monorepo)**. It is the simplest path that satisfies all foundation requirements. The PRD doesn't specify pnpm or Turborepo, so we stay with what Node.js provides natively. The Docker Compose setup serves two purposes: production deployment AND ensures the two packages communicate correctly before deployment.

### Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| SQLite write concurrency under Docker | Low | SQLite handles this well for single-server workloads; the VPS runs one app instance |
| JWT secret management in Docker | Low | Use environment variable via `.env` + Docker secrets; document in `.env.example` |
| better-sqlite3 native build in Docker | Medium | Use `node:24-bookworm-slim` image; install `build-essential` + `python3` in build stage |
| Foundation scope creep | Medium | Treat this as PURE foundation — no reports UI, no loading schedules, no photos. Cut anything beyond auth + dashboard |
| shadcn/ui init requires interactive CLI | Low | Use `npx shadcn@latest init --defaults` with `components.json` pre-configured |
| i18next setup complexity | Low | Use `i18next` + `react-i18next` + `i18next-browser-languagedetector`; ship with only PT-BR and ES files for foundation |

### Ready for Proposal

**Yes.** The foundation scope is well-defined by the PRD and the orchestrator's instructions. The orchestrator should tell the user:

> "The exploration is complete. The foundation slice covers: monorepo scaffold, Docker Compose, SQLite with full schema, JWT auth (admin/trabajador), responsive layout, and base dashboard. 11 implementation steps ordered by dependency. Ready to move to the proposal phase."
