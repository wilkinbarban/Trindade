# Apply Progress: Localized Category and Task Templates

- **Change Name**: localized-category-task-templates
- **Project**: trindade
- **Mode**: Standard (strict TDD disabled)
- **Artifact Store**: hybrid
- **Delivery Strategy**: exception-ok
- **Chain Strategy**: size-exception
- **Status**: Complete — ready for SDD verify

## Completed Tasks

### Phase 1: Backend Contracts and Translation
- [x] 1.1 Added env-only Google Translate REST support in `packages/backend/src/utils/translator.ts` using `GOOGLE_TRANSLATE_API_KEY`, request timeout, no secret logging, and existing fallback chain.
- [x] 1.2 Updated `packages/backend/src/modules/admin/admin.service.ts` to preserve submitted locale values and only fill the missing counterpart when needed, with safe copy fallback.
- [x] 1.3 Updated `packages/backend/src/modules/admin/admin.schema.ts` to export full `CategoryType` support for `check`, `temperature`, `check_assai`, and `check_normal`.
- [x] 1.4 Updated `packages/backend/src/modules/reports/reports.schema.ts` so report task responses include all four task types.

### Phase 2: Frontend Report and Admin UI
- [x] 2.1 Created `packages/frontend/src/components/reports/productTemplates.ts` with readonly Assaí and Normal product arrays exactly as specified.
- [x] 2.2 Updated `packages/frontend/src/components/reports/CategorySection.tsx` to consume fixed product templates for `check_assai` and `check_normal` and persist selected products through callbacks.
- [x] 2.3 Updated `packages/frontend/src/pages/admin/AdminDashboard.tsx` category/task type unions to all four supported values while preserving active-locale-only payloads.

### Phase 3: Backend Tests
- [x] 3.1 Extended `packages/backend/src/modules/admin/admin.routes.test.ts` for PT-BR-only and ES-only category/task saves, translation failure fallback, preserving curated counterparts, and all category types.
- [x] 3.2 Extended `packages/backend/src/modules/reports/reports.routes.test.ts` for `check_assai` and `check_normal` selected product create/reopen/update round trips.
- [x] 3.3 Extended `packages/backend/src/modules/reports/export.service.test.ts` to verify selected products appear and WhatsApp export remains pt-BR.

### Phase 4: Verification
- [x] 4.1 Ran `npm run test --workspace=packages/backend` successfully. Note: this project script currently only executed `src/db/db-init.test.ts` in this shell because the unquoted glob expanded before Node saw it.
- [x] 4.1b Ran explicit full backend suite with `node --test --import tsx $(find packages/backend/src -name '*.test.ts' | sort)` successfully: 134 tests passed.
- [x] 4.2 Ran `npm run build --workspaces --if-present` successfully for backend and frontend.

## Verification Evidence

| Command | Result | Evidence |
|---|---|---|
| `npm run test --workspace=packages/backend` | Pass | 6 tests passed; script glob behavior limited execution to `src/db/db-init.test.ts` in current shell. |
| `node --test --import tsx $(find packages/backend/src -name '*.test.ts' | sort)` | Pass | 134 tests passed, 0 failed. |
| `npm run build --workspaces --if-present` | Pass | Backend `tsc` completed; frontend `tsc && vite build` completed. |
| Secret scan | Pass | Only `GOOGLE_TRANSLATE_API_KEY` variable name appears; no Google API key value was written. |

## Files Changed

| File | Action | What Was Done |
|---|---|---|
| `packages/backend/src/utils/translator.ts` | Modified | Added Google Translate REST provider, timeout helper, safe no-throw fallback chain, and final non-empty text fallback. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modified | Added safe `translateOrCopy`; create fills missing locale; update preserves existing counterpart unless it is empty. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modified | Added exported `CATEGORY_TYPES`/`CategoryType` and full four-type row typing. |
| `packages/backend/src/modules/reports/reports.schema.ts` | Modified | Added exported `TASK_TYPES`/`TaskType` and full four-type response typing. |
| `packages/frontend/src/components/reports/productTemplates.ts` | Created | Centralized readonly fixed Assaí and Normal product arrays. |
| `packages/frontend/src/components/reports/CategorySection.tsx` | Modified | Imports centralized templates and renders/persists selected products for fixed product checks. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modified | Tightened category/task type unions and category type setter. |
| `packages/backend/src/modules/admin/admin.routes.test.ts` | Modified | Added admin locale/type/fallback route coverage. |
| `packages/backend/src/modules/reports/reports.routes.test.ts` | Modified | Added selectedProducts create/get/update round-trip coverage. |
| `packages/backend/src/modules/reports/export.service.test.ts` | Modified | Added WhatsApp selected product/pt-BR stability coverage. |
| `openspec/changes/localized-category-task-templates/tasks.md` | Modified | Marked all tasks complete. |
| `openspec/changes/localized-category-task-templates/apply-progress.md` | Created | Persisted cumulative apply progress and evidence. |

## Deviations from Design

None — implementation matches the design. The Google environment variable was standardized as `GOOGLE_TRANSLATE_API_KEY`.

## Issues Found

- The backend npm test script uses an unquoted `src/**/*.test.ts` glob; in this shell it only ran the db init test file. I ran an explicit `find`-based full backend suite to avoid false confidence.
- Build output under `packages/*/dist` was regenerated by the workspace build command.

## Remaining Tasks

None.

## Workload / PR Boundary

- **Mode**: size:exception
- **Current work unit**: Full change implementation under accepted size exception.
- **Boundary**: Backend translation/contracts/tests, frontend fixed product templates/admin/report UI, and verification.
- **Estimated review budget impact**: Above the normal 400-line budget; accepted through `exception-ok` / `size-exception` because repository Git metadata is broken/empty.

## Next Recommended

`sdd-verify`
