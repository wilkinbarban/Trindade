## Exploration: Photos in Reports + Visual Audit

### Current State

**Database**: `report_photos` and `audit_logs` tables already exist in schema.sql with full column definitions matching the PRD. No implementation code references them yet.

**Backend** (Fastify + better-sqlite3):
- Reports module at `src/modules/reports/` — routes, service, schema, export service, tests.
- Admin module at `src/modules/admin/` — CRUD for categories, tasks, products, drivers, vehicles, time slots.
- Auth at `src/modules/auth/` — JWT middleware + role guard (`requireRole`).
- No multipart/file upload support yet (`@fastify/multipart` missing from dependencies).
- No static file serving configured for uploaded content.
- No audit logging service — `audit_logs` table is unused.
- Test pattern: Node.js `node:test` + `app.inject()`, in-memory SQLite via `buildTestApp()`.

**Frontend** (React + Vite + shadcn/ui):
- New report at `ReportsPage.tsx`, edit at `ReportEditPage.tsx`, view at `ReportViewPage.tsx`.
- Admin dashboard at `pages/admin/AdminDashboard.tsx` — tabbed interface with 6 tabs (categories, tasks, products, drivers, vehicles, timeSlots).
- API client at `api/client.ts` — `get`, `post`, `patch`, `put`, `del` methods with JSON body, token from localStorage.
- i18n: pt-BR JSON locale at `i18n/locales/pt-BR.json`.
- Vite dev server proxies `/api` to `localhost:3000`. Nginx proxies `/api/` to `api:3000` in Docker.

**Infrastructure**:
- Docker Compose: two services (`api` + `web`). API serves on :3000, Nginx on :80.
- Nginx config: `/api/` → proxy_pass to Fastify; `/` → static files from `/usr/share/nginx/html`.
- SQLite data volume at `/app/packages/backend/data`.

### Affected Areas

- `packages/backend/src/modules/reports/reports.service.ts` — add photo queries, audit logging for report CRUD.
- `packages/backend/src/modules/reports/reports.routes.ts` — add photo upload/serve/list/delete endpoints.
- `packages/backend/src/modules/reports/reports.schema.ts` — add Zod schemas for photos.
- `packages/backend/src/modules/reports/reports.routes.test.ts` — add photo tests.
- `packages/backend/src/modules/loading/loading.service.ts` — add audit logging for loading CRUD.
- `packages/backend/src/modules/admin/admin.service.ts` — add audit logging for admin CRUD.
- `packages/backend/src/modules/auth/auth.routes.ts` — add audit logging for login.
- `packages/backend/src/modules/audit/audit.service.ts` — **NEW**: centralized audit log function.
- `packages/backend/src/modules/audit/audit.routes.ts` — **NEW**: admin GET endpoint for audit logs.
- `packages/backend/src/modules/audit/audit.schema.ts` — **NEW**: Zod schemas + types for audit.
- `packages/backend/src/modules/audit/audit.routes.test.ts` — **NEW**: audit routes tests.
- `packages/backend/package.json` — add `@fastify/multipart` dependency.
- `packages/backend/src/server.ts` — register `@fastify/multipart`, audit routes, possibly `@fastify/static`.
- `docker-compose.yml` — add `uploads_data` volume for photos.
- `docker/Dockerfile.api` — create photos data directory.
- `packages/frontend/src/api/client.ts` — add multipart upload method if needed (or use raw fetch).
- `packages/frontend/src/pages/ReportsPage.tsx` — add photo upload section.
- `packages/frontend/src/pages/ReportEditPage.tsx` — add photo upload/delete section.
- `packages/frontend/src/pages/ReportViewPage.tsx` — add photo gallery section.
- `packages/frontend/src/pages/admin/AdminDashboard.tsx` — add "Auditoria" tab.
- `packages/frontend/src/i18n/locales/pt-BR.json` — add translations for photos and audit.

### Approaches

#### A1: Photo Storage and Serving

1. **Fastify file serving with auth** — Store photos in `data/photos/`, serve via a Fastify GET route that checks auth before streaming the file.
   - Pros: Auth-gated, no nginx changes, simple implementation, works with existing infrastructure.
   - Cons: Fastify not optimized for large-file serving (fine for <5MB images).
   - Effort: Low

2. **Nginx X-Accel-Redirect with Fastify auth check** — Fastify validates auth, sets `X-Accel-Redirect` header, Nginx serves the file.
   - Pros: Nginx serves files directly (performance), auth decoupled from serving.
   - Cons: Requires nginx config changes + Docker rebuild, more complex.
   - Effort: Medium

3. **S3/Cloud storage** — Overkill for SQLite-local single-server app.
   - Effort: High (over-engineering)

**Recommendation**: **Option 1** — Fastify auth-gated file serving. The app serves <5MB images in a single-server deployment. Adding Nginx complexity provides no real benefit at this scale.

#### A2: Photo Upload Implementation

1. **@fastify/multipart** — Standard Fastify multipart plugin, handles file stream, validation, storage.
   - Pros: Official plugin, tested, handles multipart boundaries correctly.
   - Cons: One more dependency.
   - Effort: Low

2. **Raw Buffer** — Read raw body as Buffer, parse manually.
   - Pros: No dependency.
   - Cons: Reinventing the wheel, fragile with large files, no progress tracking.
   - Effort: Medium (not worth it)

**Recommendation**: **Option 1** — `@fastify/multipart`. It's the standard approach and handles edge cases.

#### A3: Audit Logging Strategy

1. **Inline calls** — Call `audit.log(db, ...)` at each service function after successful operations.
   - Pros: Simple, explicit, easy to test, no magic.
   - Cons: Manual — easy to miss a new operation.
   - Effort: Low

2. **Fastify onResponse hook** — Global hook inspects request/response and logs based on URL patterns.
   - Pros: Automatic coverage, less code.
   - Cons: Hard to extract semantic action types, fragile URL parsing, harder to test.
   - Effort: Medium

3. **Proxy/wrapper around db** — Wrap `db.prepare/run` with interceptors.
   - Pros: Fine-grained.
   - Cons: Over-engineered for SQLite, doesn't capture the "why".
   - Effort: High

**Recommendation**: **Option 1** — Inline. The project has a small number of operations. Explicit calls in each service are readable, testable, and maintainable. A Fastify hook would add magic without real benefit at this scale.

#### A4: Audit Visual UI

1. **Tab in AdminDashboard** — Add "Auditoria" as a 7th tab, reusing the existing pattern.
   - Pros: Zero routing changes, consistent UX, no new page file.
   - Cons: AdminDashboard is already 796 lines; adding a complex tab makes it harder to maintain.
   - Effort: Low

2. **Separate admin audit page** — New route `/admin/audit` with its own page component, add nav link from admin dashboard header.
   - Pros: Clean separation of concerns, isolated component, easier to maintain.
   - Cons: More files, more routing.
   - Effort: Medium

**Recommendation**: **Option 2** — Separate page. The audit tab would require filtered tables, pagination, date pickers — significant complexity. Keeping it separate avoids bloating AdminDashboard further and follows single-responsibility better. Add a link from the admin dashboard's tab bar.

#### A5: Photo UI Placement

1. **Dedicated "Fotos" card in report form** — A separate card/section at the bottom of report create/edit/view pages, after notes.
   - Pros: Clear section, independent of category system, easy to add/remove.
   - Cons: Not part of the report builder flow.
   - Effort: Low

2. **Per-category photos** — Each category section can have photos attached.
   - Pros: More granular.
   - Cons: Schema doesn't support it (photos are report-level, not category-level), complex UI.
   - Effort: High

**Recommendation**: **Option 1** — Dedicated card at the bottom. Matches the PRD's "optional photos" positioning and works with the existing schema.

### Recommendation

**Architecture decisions**:
1. Photo storage: Fastify auth-gated file serving (`data/photos/`).
2. Photo upload: `@fastify/multipart` for multipart handling.
3. Audit logging: Inline `audit.log()` calls in each service module.
4. Audit UI: Separate admin page at `/admin/audit` (not a tab in AdminDashboard).
5. Audit query: Admin-only paginated GET endpoint with filters.
6. Photo UI: Dedicated card section in report create/edit/view pages.

### PR Split (400-line budget)

**PR #1 — Photos in Reports** (forecast: ~380 lines)
- **Backend**: `@fastify/multipart` registration, photo upload/list/serve/delete routes + service (ReportsPage + ReportEditPage integration)
- **Frontend**: Photo upload widget (reusable), photo gallery component in ReportViewPage, API client upload method
- **i18n**: Photo-related translation keys
- **Infrastructure**: Uploads volume in docker-compose, photos dir in Dockerfile.api
- Audit: Photo create/delete events logged

**PR #2 — Audit Service + Visual UI** (forecast: ~350 lines)
- **Backend**: `audit.service.ts` (log function), `audit.routes.ts` (admin GET with filters + pagination), `audit.schema.ts`. Add inline audit.log() calls to all existing services (reports CRUD, loading CRUD, admin CRUD, auth login)
- **Frontend**: `AuditPage.tsx` (standalone admin page), route `/admin/audit` in App.tsx, nav link
- **i18n**: Audit-related translation keys
- **Tests**: audit routes test, audit log function unit test

### Risks

- **Dependency**: Adding `@fastify/multipart` to a project that so far has minimal deps — validate it works with Fastify v5.
- **File size enforcement**: Must validate file size BEFORE writing to disk to avoid disk-waste attacks. `@fastify/multipart` supports `limits.fileSize`.
- **MIME type spoofing**: Client can lie about Content-Type. Should verify magic bytes server-side (or at minimum validate via `file-type` or extension whitelist + re-check on read).
- **Photo storage location**: `data/photos/` must be inside the Docker volume-mount path. Currently `data/` is volume-mounted. Adding `data/photos/` subdirectory works as long as the app creates it at startup.
- **Edit-window interaction**: Photos attached to edit-expired reports should be view-only (no upload or delete). The readOnly flag from the edit window must gate photo actions too.
- **Audit table growth**: SQLite with audit logs could grow. Add pagination (LIMIT/OFFSET) to the audit query and consider a future archive strategy for old logs. Not blocking for MVP.

### Ready for Proposal

**Yes**. The exploration is complete. The orchestrator should proceed to `sdd-propose` with the two-PR split (Photos first, Audit second). Both are well-understood, the schema is ready, the patterns are established, and the line budgets fit.

Key message to the user: "I've explored the full codebase. Photos need `@fastify/multipart` and a serving strategy (recommend Fastify auth-gated). Audit needs a service module and inline hooks in existing services. I recommend splitting into 2 chained PRs — Photos first (~380 lines), Audit second (~350 lines) — both within the 400-line budget. Ready to propose."
