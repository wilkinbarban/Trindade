# Tasks: Localized Category and Task Templates

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 650-950 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | Size exception: single implementation because repo Git metadata is broken/empty |
| Delivery strategy | exception-ok |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: size-exception
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Backend localization/types/tests | Size exception | Translator, admin schemas/service, report schemas, backend tests. |
| 2 | Frontend templates/admin/report UI | Size exception | Product constants, report checkboxes, active-locale admin typing. |
| 3 | Verification | Size exception | Backend tests plus workspace build. |

## Phase 1: Backend Contracts and Translation

- [x] 1.1 Update `packages/backend/src/utils/translator.ts` to add env-only Google Translate REST support with timeout, no secret logging, and existing fallbacks.
- [x] 1.2 Update `packages/backend/src/modules/admin/admin.service.ts` so create/update preserves the submitted locale and fills only the missing locale with safe fallback text.
- [x] 1.3 Update `packages/backend/src/modules/admin/admin.schema.ts` to accept/export `check`, `temperature`, `check_assai`, and `check_normal` category types.
- [x] 1.4 Update `packages/backend/src/modules/reports/reports.schema.ts` so report task responses include `check_assai` and `check_normal`.

## Phase 2: Frontend Report and Admin UI

- [x] 2.1 Create `packages/frontend/src/components/reports/productTemplates.ts` with readonly Assaí and Normal product arrays from the spec.
- [x] 2.2 Update `packages/frontend/src/components/reports/CategorySection.tsx` to render fixed product checkboxes for `check_assai` and `check_normal` and persist `selectedProducts` callbacks.
- [x] 2.3 Update `packages/frontend/src/pages/admin/AdminDashboard.tsx` category/task type unions to all four supported values while keeping one active-locale name field payload.

## Phase 3: Backend Tests

- [x] 3.1 Extend `packages/backend/src/modules/admin/admin.routes.test.ts` for PT-BR-only and ES-only category/task saves, translation failure fallback, and all four category types.
- [x] 3.2 Extend `packages/backend/src/modules/reports/reports.routes.test.ts` for `check_assai` and `check_normal` selected product save/reopen round trips.
- [x] 3.3 Extend `packages/backend/src/modules/reports/export.service.test.ts` to verify selected products appear and WhatsApp export stays PT-BR.

## Phase 4: Verification

- [x] 4.1 Run `npm run test --workspace=packages/backend` and fix regressions.
- [x] 4.2 Run `npm run build --workspaces --if-present` and fix TypeScript/build regressions.
