## Exploration: admin-mgmt-lang-switch

### Current State
- **Backend Seeding & Auth:** The historical seed included [REDACTED LEGACY CREDENTIAL] with the administrator role. Auth routes are defined in `packages/backend/src/modules/auth/auth.routes.ts` (`/login` for signing JWTs, `/me` for retrieving session data, `/logout` for auditing). Token validation is implemented in `packages/backend/src/modules/auth/auth.middleware.ts` via Fastify's `authenticate` decorator, which extracts the bearer token, validates it against `JWT_SECRET`, checks if the user is active in SQLite, and puts the payload into `request.user`. Role guards use the `requireRole` helper.
- **Frontend Dashboard Structure:** The Admin Panel is located at `packages/frontend/src/pages/admin/AdminDashboard.tsx`. Access is restricted to users with the `'Administrador'` role. The page is structured around a tab-based state manager (`TabKey = 'categories' | 'tasks' | 'products' | 'drivers' | 'vehicles' | 'timeSlots'`) that fetches datasets dynamically from `/api/admin` endpoints via the customized API client. Routing is configured in `packages/frontend/src/App.tsx`, and sidebar layout and visibility filters based on roles are located in `packages/frontend/src/components/layout/AppShell.tsx`.
- **Soft Delete / Deactivate:** In-place lists (categories, tasks, products, drivers, vehicles) currently implement deactivation (soft delete) by toggling `is_active` to `0` or `1` through `PATCH` requests. There are no physical `DELETE` endpoints or controls, and database schemas do not specify `ON DELETE CASCADE`.
- **i18n & Locales:** Located in `packages/frontend/src/i18n/config.ts`, loading Portuguese and Spanish locale files (`pt-BR.json`, `es.json`) via `i18next-browser-languagedetector`. It is set to read from and cache to `localStorage` under `i18nextLng`, with browser-detection fallback.

### Affected Areas
- `packages/backend/src/modules/auth/auth.routes.ts` — Requires a new `/change-password` endpoint.
- `packages/backend/src/modules/admin/admin.routes.ts` — Needs `GET`, `POST`, `PATCH`, and `DELETE` endpoints for users/workers, plus physical `DELETE` endpoints for categories, tasks, products, drivers, and vehicles.
- `packages/backend/src/modules/admin/admin.service.ts` — Requires SQLite CRUD helpers for users and physical delete commands.
- `packages/backend/src/modules/admin/admin.schema.ts` — Needs new Zod schemas for user creation and updates.
- `packages/frontend/src/pages/admin/AdminDashboard.tsx` — Renders the new workers tab, handles user CRUD forms, lists users in a data table, and adds physical delete buttons to all entity lists with error handling.
- `packages/frontend/src/components/layout/AppShell.tsx` — Renders the sidebar and header. Needs language switcher toggle buttons (PT / ES) in the header and a password update trigger in the footer.
- `packages/frontend/src/i18n/locales/pt-BR.json` & `es.json` — Translation bundles needing new keys for worker fields, change password forms, validation recommendations, and delete/error labels.

### Approaches
1. **Consolidated Admin Dashboard Extension**
   - **Description:** Implement worker management as an additional tab inside `AdminDashboard.tsx`, place physical delete buttons directly in existing tables, and add an own-password change modal triggered globally from the `AppShell` User footer.
   - **Pros:** High code reuse, fast implementation, centralized admin settings.
   - **Cons:** Increases the length of `AdminDashboard.tsx` (currently ~925 lines) to over ~1300 lines, complicating readability.
   - **Effort:** Medium
2. **Modularized Admin Views & Profile Settings**
   - **Description:** Extract tab views into individual components (e.g. `UsersManager.tsx`) and add a dedicated settings view or profile page for changing passwords.
   - **Pros:** Promotes clean React structure, limits code bloat in a single file, conforms to solid separation of concerns.
   - **Cons:** Setup overhead is higher since existing code has to be refactored into modular subcomponents.
   - **Effort:** Medium-High

### Recommendation
We recommend **Approach 1** due to the existing structural patterns in the frontend. We suggest:
- Adding a `users` tab to `AdminDashboard.tsx` for worker CRUD.
- Implementing the user password change feature via a secure modal triggered from the User footer in `AppShell.tsx` (making it accessible to any logged-in user).
- Implementing relaxed password verification on the backend (Zod `min(4)`) to maintain backwards compatibility, while implementing dynamic client-side regex check recommendations on the frontend (at least 8 chars, uppercase, numbers) without preventing form submissions.
- Catching foreign key errors on deletion endpoints and sending clean 400 messages to the frontend to handle deletes safely.

### Risks
- **Foreign Key Violation/Database integrity:** Because tables do not cascade delete, physical deletes will fail if an item is referenced elsewhere (e.g. a category with tasks or a driver with schedules). The backend must catch `SQLITE_CONSTRAINT` and return a clean, friendly error advising deactivation instead of crashing.
- **Backwards Compatibility:** Changes to default password verification could break E2E test helpers which log in with weak passwords. Using warnings rather than blockers on the frontend mitigates this.

### Ready for Proposal
Yes. The orchestrator should proceed to the proposal/design phase.
