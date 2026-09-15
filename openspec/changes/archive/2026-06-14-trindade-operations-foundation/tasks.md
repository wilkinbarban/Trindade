# Tasks: Trindade Operations Foundation

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~1100–1400 across ~30 files |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Foundation + DB) → PR 2 (Backend API + Docker) → PR 3 (Frontend App) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Monorepo scaffold, DB schema/seed, build verification | PR 1 | Base: main; ~300 lines; tests: schema creates 16 tables |
| 2 | Backend API: auth, dashboard, Docker Compose | PR 2 | Base: PR 1 branch; ~400 lines; tests: login, role guard, summary |
| 3 | Frontend: shell, login, dashboard, integration | PR 3 | Base: PR 2 branch; ~400 lines; manual E2E verification |

## Phase 1: Foundation & Database

- [x] 1.1 Create root `package.json` with npm workspaces pointing to `packages/*`
- [x] 1.2 Create `packages/backend/package.json` with deps: fastify, better-sqlite3, zod, jsonwebtoken, @fastify/cors
- [x] 1.3 Create `packages/backend/tsconfig.json` extending a shared strict TypeScript config
- [x] 1.4 Create `packages/frontend/package.json` with deps: react, react-dom, react-router-dom, tailwindcss, @shadcn/ui
- [x] 1.5 Create `packages/frontend/tsconfig.json`, `vite.config.ts`, `tailwind.config.ts`, `postcss.config.js`
- [x] 1.6 Create `Makefile` with targets: `dev`, `build`, `db-reset`, `docker-up`, `docker-down`
- [x] 1.7 Create `packages/backend/src/db/schema.sql` with all 16 PRD tables (roles, users, report_categories, report_tasks, report_products, report_templates, reports, report_items, report_temperatures, report_quantities, report_photos, drivers, vehicles, loading_schedules, audit_logs, settings)
- [x] 1.8 Create `packages/backend/src/db/seed.sql` inserting roles (Administrador, Trabalhador) and base admin user
- [x] 1.9 Verify: `npm install` at root resolves workspaces; `tsc --noEmit` passes in both packages

## Phase 2: Backend API & Docker

- [x] 2.1 Create `packages/backend/src/db/index.ts` — initialize better-sqlite3 with WAL mode, execute schema.sql + seed.sql on empty DB
- [x] 2.2 Create `packages/backend/src/server.ts` — Fastify instance with CORS, DB plugin, and route registration
- [x] 2.3 Create `packages/backend/src/modules/auth/auth.routes.ts` — `POST /api/auth/login` with Zod input validation, credential check, JWT generation returning `LoginResponse`
- [x] 2.4 Create `packages/backend/src/modules/auth/auth.middleware.ts` — JWT verification decorator + role guard (`requireRole('Administrador')`)
- [x] 2.5 Create `packages/backend/src/modules/dashboard/dashboard.routes.ts` — `GET /api/dashboard/summary` returning `DashboardSummary` counts
- [x] 2.6 Write backend tests using `node:test` + Fastify `.inject()`: successful login returns JWT, invalid login returns 401, role guard returns 403 for Trabalhador, summary endpoint returns counts
- [x] 2.7 Create `docker/Dockerfile.api` (node:24-bookworm-slim with build-essential) and `docker/Dockerfile.web` (nginx serving built React)
- [x] 2.8 Create `docker/nginx.conf` reverse proxy: `/api` → backend:3000, `/` → static frontend
- [x] 2.9 Create `docker-compose.yml` orchestrating api, web, and sqlite volume
- [x] 2.10 Verify: `docker compose up` starts stack; `curl POST /api/auth/login` returns JWT

## Phase 3: Frontend Application

- [x] 3.1 Create `packages/frontend/index.html`, `src/main.tsx`, and base Tailwind/CSS entry
- [x] 3.2 Create `packages/frontend/src/api/client.ts` — fetch wrapper that attaches JWT from localStorage and handles 401 responses
- [x] 3.3 Create `packages/frontend/src/contexts/AuthContext.tsx` — React context providing login/logout/token state with persistence across refreshes
- [x] 3.4 Create `packages/frontend/src/App.tsx` with React Router: public `/login`, protected `/dashboard` wrapped in AuthContext
- [x] 3.5 Create `packages/frontend/src/components/layout/AppShell.tsx` — responsive sidebar + header, mobile-first with collapsible menu
- [x] 3.6 Create `packages/frontend/src/pages/LoginPage.tsx` — credential form calling auth API, redirect on success, error display on failure
- [x] 3.7 Create `packages/frontend/src/pages/DashboardPage.tsx` — fetch and render summary cards (reports today, schedules tomorrow, active users)
- [x] 3.8 Verify: manual E2E — login with admin credentials → dashboard renders summary cards → refresh preserves session → deferred modules hidden/disabled

## Phase 4: Remediation (post-verify)

- [x] 4.1 Add shadcn/ui-compatible setup: `class-variance-authority`, `clsx`, `tailwind-merge`, `lucide-react`; create `src/lib/utils.ts` (cn), `Button`, `Card`, `Input` components
- [x] 4.2 Add i18next/react-i18next: `i18next`, `react-i18next`, `i18next-browser-languagedetector`; create `src/i18n/config.ts`, `pt-BR.json` locale, integrate into all UI components
- [x] 4.3 Replace `docker/Dockerfile.web` placeholder with multi-stage build: Stage 1 builds Vite dist, Stage 2 serves via Nginx
- [x] 4.4 Add direct backend DB initialization test (`src/db/db-init.test.ts`): schema tables, seed data, idempotency, journal mode pragma
- [x] 4.5 Verify: backend typecheck ✅, frontend typecheck ✅, all 17 backend tests pass ✅, frontend build ✅
