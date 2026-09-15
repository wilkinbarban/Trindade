# Tasks: Admin Management & Language Switch

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 800 - 1100 lines |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 (Seed & User CRUD BE) → PR 2 (BE Delete Constraints & Password Change) → PR 3 (FE Language Switcher) → PR 4 (FE UI Modals & Dashboard) → PR 5 (Tests) |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Database seed update and Backend User CRUD routes/schemas | PR 1 | Base branch; integration tests updated |
| 2 | Backend physical delete constraint catches and self-password change API | PR 2 | Extends PR 1; includes constraint tests |
| 3 | Frontend PT/ES translations and header language toggle switcher | PR 3 | Extends PR 2 |
| 4 | Frontend change-password footer modal and Admin Workers CRUD/delete UI | PR 4 | Extends PR 3 |
| 5 | Backend and E2E tests update with [REDACTED LEGACY CREDENTIAL] and CRUD verification | PR 5 | Target main branch |

## Phase 1: Database & Backend Foundation

- [x] 1.1 Rename default administrator [REDACTED LEGACY CREDENTIAL] in `packages/backend/src/db/seed.sql`
- [x] 1.2 Define `CreateUserSchema` and `UpdateUserSchema` in `packages/backend/src/modules/admin/admin.schema.ts`
- [x] 1.3 Add backend service functions for user CRUD (list, create, update, delete) in `packages/backend/src/modules/admin/admin.service.ts`
- [x] 1.4 Add `/users` CRUD routes requiring role 'Administrador' in `packages/backend/src/modules/admin/admin.routes.ts`

## Phase 2: Backend Delete Constraints & Password Change

- [x] 2.1 Update catalog services to support physical deletion catching `SQLITE_CONSTRAINT` in `packages/backend/src/modules/admin/admin.service.ts`
- [x] 2.2 Add physical delete endpoints for categories, tasks, products, drivers, and vehicles in `packages/backend/src/modules/admin/admin.routes.ts`
- [x] 2.3 Implement password change route at `POST /api/auth/change-password` verifying current password and enforcing `min(4)` in `packages/backend/src/modules/auth/auth.routes.ts`

## Phase 3: Frontend Layout & Translations (i18n)

- [x] 3.1 Add translation keys for CRUD actions, deletes, warnings, and switcher in `packages/frontend/src/i18n/locales/pt-BR.json` and `packages/frontend/src/i18n/locales/es.json`
- [x] 3.2 Implement header language switcher buttons (PT / ES) utilizing `i18next` context in `packages/frontend/src/components/layout/AppShell.tsx`

## Phase 4: Frontend UI Features

- [x] 4.1 Implement change password footer trigger & modal with non-blocking suggestion warnings (8+ chars, uppercase, digit) in `packages/frontend/src/components/layout/AppShell.tsx`
- [x] 4.2 Add "Workers" management tab with listing, CRUD actions, and deactivating in `packages/frontend/src/pages/admin/AdminDashboard.tsx`
- [x] 4.3 Add physical delete buttons to catalog pages in `packages/frontend/src/pages/admin/AdminDashboard.tsx` displaying database constraint errors as friendly messages

## Phase 5: Testing & Verification

- [x] 5.1 Update admin username and assert user count to 4 in `packages/backend/src/db/db-init.test.ts`
- [x] 5.2 Update admin login credentials in `packages/backend/src/modules/*/*.test.ts` and `packages/frontend/src/__e2e__/auth-helpers.ts`
- [x] 5.3 Write integration tests for `/api/admin/users` CRUD, `/api/auth/change-password`, and physical deletes with constraint violations in `packages/backend/src/modules/admin/admin.routes.test.ts` and `packages/backend/src/modules/auth/auth.routes.test.ts`
- [x] 5.4 Run E2E test verification command to ensure backend and Playwright E2E suites pass
