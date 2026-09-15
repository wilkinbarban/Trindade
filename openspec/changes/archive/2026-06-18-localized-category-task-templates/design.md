# Design: Localized Category and Task Templates

## Technical Approach

Use the existing bilingual columns (`name_pt`, `name_es`), category type column, and `report_items.selected_products` JSON storage. The frontend remains active-locale driven: Admin sends only the visible name field, while Reports renders localized labels and stores selected product arrays. The backend becomes the source of truth for filling missing locale names through `translateText()` with a Google REST provider before the existing MyMemory/local fallback chain.

## Architecture Decisions

| Decision | Choice | Alternatives considered | Rationale |
|---|---|---|---|
| Translation provider | Add env-only Google Translate REST support inside `packages/backend/src/utils/translator.ts`, then MyMemory, then local word fallback, then submitted text | Google SDK or checked-in service account file | REST avoids new credential files and keeps secrets in env only; fallback preserves saves during outages. |
| Locale persistence | Preserve submitted locale and fill only the missing counterpart in `admin.service.ts` | Re-translate both names on every edit | Prevents overwriting curated translations and matches current partial-locale payload behavior. |
| Product templates | Centralize Assaí/Normal fixed product arrays in a frontend constants module consumed by `CategorySection` and tests | Store products in DB or duplicate arrays in components | Requirement excludes product CRUD; one module prevents drift better than hardcoded component-local arrays. |
| Type unions | Define/shared local TS aliases for `check`, `temperature`, `check_assai`, `check_normal` in backend schemas and frontend report/admin types | Keep loose `string` types | DB already accepts all four; precise unions catch stale code without schema migration. |

## Data Flow

```text
AdminDashboard(active locale) -> POST/PATCH /admin/categories|tasks { name_pt OR name_es }
  -> admin.service preserves submitted value
  -> translateText(): dictionary -> Google REST if env configured -> MyMemory -> word/text fallback
  -> SQLite stores name_pt + name_es

ReportsPage/EditPage -> /reports/categories -> CategorySection
  -> check_assai/check_normal renders fixed product checkboxes
  -> POST/PATCH /reports items[].selectedProducts
  -> report_items.selected_products JSON
  -> ReportView/Edit/export round trip
```

## File Changes

| File | Action | Description |
|---|---|---|
| `packages/backend/src/utils/translator.ts` | Modify | Add Google Translate REST branch using env config only (`GOOGLE_TRANSLATE_API_KEY` or equivalent env var), short timeout, no logging of secrets, existing fallbacks preserved. |
| `packages/backend/src/modules/admin/admin.service.ts` | Modify | Ensure create/update fills only missing locale and falls back to submitted text if translator returns empty/fails. |
| `packages/backend/src/modules/admin/admin.schema.ts` | Modify | Replace stale `CategoryRow.category_type` union with all four category types; optionally export `CategoryType`. |
| `packages/backend/src/modules/reports/reports.schema.ts` | Modify | Update `TaskResponse.task_type` union to include `check_assai` and `check_normal`. |
| `packages/frontend/src/components/reports/productTemplates.ts` | Create | Export `ASSAI_PRODUCTS` and `NORMAL_PRODUCTS` as readonly arrays. |
| `packages/frontend/src/components/reports/CategorySection.tsx` | Modify | Import product constants; keep localized task/category display and selected product callbacks. |
| `packages/frontend/src/pages/admin/AdminDashboard.tsx` | Modify | Tighten `Category.category_type` / `Task.task_type` types to full union; keep active-locale single field payload. |
| `packages/backend/src/modules/admin/admin.routes.test.ts` | Modify | Add single-locale create/update and all-category-type acceptance coverage. |
| `packages/backend/src/modules/reports/reports.routes.test.ts` | Modify | Add selectedProducts persistence/round-trip coverage for `check_assai` and `check_normal`. |
| `packages/backend/src/modules/reports/export.service.test.ts` | Modify | Assert selected products appear while WhatsApp text remains pt-BR. |

## Interfaces / Contracts

```ts
type CategoryType = 'check' | 'temperature' | 'check_assai' | 'check_normal';
type ReportItemInput = { taskId: number; checked: boolean; selectedProducts?: string[] };
```

Translation contract: `translateText(text, fromLang)` must never throw to callers. If Google env is absent, invalid, timed out, or fails, continue to MyMemory/local fallback and finally return a safe non-empty submitted-text fallback.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Unit/backend | Translator fallback order and no-throw behavior | Mock `fetch`; verify Google success, Google failure -> MyMemory/local, missing env path. |
| Integration/backend | Admin single-locale saves and four category types | Extend `node:test` route tests with POST/PATCH payloads containing only `name_pt` or `name_es`. |
| Integration/backend | Selected product storage and detail retrieval | Route tests create report items with selectedProducts and reopen report. |
| E2E/frontend | Product checkbox UX and active-locale admin field | Extend Playwright report/admin flows if time permits; otherwise rely on backend + build. |

## Migration / Rollout

No destructive migration required. Existing schema already has localized names, full category type check constraint migration, and `selected_products`. Roll out by deploying backend first with safe translation fallback, then frontend constants/types. Configure Google translation via environment only; rollback removes env var and reverts code, with stored report JSON remaining compatible.

## Open Questions

- [ ] Exact Google env variable name should be standardized before implementation; design recommends env-only API key with no persisted credential file.
