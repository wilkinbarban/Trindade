# Proposal: Trindade Operations — Foundation Slice

## Intent

Establish the core technical foundation for the Trindade Massas Operations web system. This slice delivers a monorepo structure, Docker Compose orchestration, a complete SQLite database schema, and JWT authentication, culminating in a responsive layout and a base dashboard with summary cards.

## Scope

### In Scope
- Monorepo setup (npm workspaces) with `packages/backend` and `packages/frontend`.
- TypeScript strict configuration for both packages.
- SQLite database initialization, full schema definitions, and seed data.
- JWT Authentication (login endpoint, guard middleware, admin/trabajador roles).
- Base dashboard summary endpoint (report count, tomorrow's schedule count, active users).
- Responsive mobile-first layout shell (sidebar, header).
- Frontend auth flow (AuthContext, API client with interceptors, login page).
- Dashboard page rendering summary cards.
- Docker Compose setup (API, DB, reverse proxy) and Makefile.

### Out of Scope
- Full reports UI module (task lists, checks, temperatures, quantities).
- Loading schedules UI and rules.
- Master catalog (products, tasks, categories) UI management.
- Photo uploads and processing.
- Report history and PDF generation.
- WhatsApp exports (to be built later, keeping in mind they will require Portuguese output).
- Audit logs UI.

## Capabilities

### New Capabilities
- `foundation`: Monorepo scaffold, Docker Compose, SQLite schema, Makefile.
- `auth`: JWT login, role guards, AuthContext, login UI.
- `dashboard`: Summary endpoint, base dashboard UI shell.

### Modified Capabilities
- None

## Approach

Use an npm workspaces single-bucket monorepo. The backend uses Fastify with better-sqlite3, creating all tables defined in the PRD upfront to prevent schema migrations in future slices. The frontend uses Vite, React, Tailwind, and shadcn/ui. Docker Compose will handle multi-service orchestration (Node API + SQLite volume, Nginx reverse proxy) for both local verification and production readiness.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `/` | New | Monorepo config, Docker Compose, Makefile |
| `packages/backend/src/db/` | New | SQLite schema and seed |
| `packages/backend/src/modules/auth/` | New | JWT auth endpoints and guards |
| `packages/backend/src/modules/dashboard/` | New | Summary query endpoints |
| `packages/frontend/src/` | New | React App shell, router, i18n, API client |
| `packages/frontend/src/pages/` | New | LoginPage and DashboardPage |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| SQLite write concurrency in Docker | Low | Rely on better-sqlite3 WAL mode and single-server context. |
| Scope creep into report features | Medium | Strictly enforce that this slice ends at auth + dashboard summary cards. |
| better-sqlite3 native build fails | Medium | Use `node:24-bookworm-slim` with `build-essential` and `python3` in the Dockerfile build stage. |

## Rollback Plan

Delete the foundation commit/branch and drop the local SQLite database file, as no prior production state exists to restore.

## Success Criteria

- [ ] Monorepo successfully builds both frontend and backend.
- [ ] Backend exposes `/api/auth/login` and returns a valid JWT.
- [ ] Frontend can log in and view the dashboard summary cards.
- [ ] Docker Compose brings up the entire stack seamlessly.
- [ ] Database contains all PRD tables, even those unused in this slice.
