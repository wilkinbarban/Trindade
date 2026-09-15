# Proposal: Localized Category and Task Templates

## Intent

Make bilingual Admin catalog editing match the active app language while still storing both `name_pt` and `name_es`. Add fixed Assaí/Normal check templates so daily reports select mounted products instead of creating one task per product.

## Scope

### In Scope
- Category/task create-edit shows one localized name field based on i18n language.
- Backend auto-translates the missing locale with Google Cloud Translation API using env-only credentials.
- Schemas accept `check`, `temperature`, `check_assai`, and `check_normal` consistently.
- Assaí/Normal report items select mounted products from fixed lists.

### Out of Scope
- Free-form product catalog CRUD.
- Printing, committing, or persisting translation secrets.
- Changing WhatsApp export language rules.

## Capabilities

### New Capabilities
None

### Modified Capabilities
- `admin-catalog-management`: single-locale category/task editing plus fixed check template types.
- `report-generation`: mounted-product selection for Assaí/Normal check categories.

## Approach

Keep the frontend sending only the active locale name. Backend preserves the submitted locale and fills only the missing counterpart through Google translation when env config is present, with safe fallback behavior on failure. Centralize fixed products:
- Assaí: Nhoque Kg, Quadrada, Rolo 500, Rolo kg, Rolo 2kg, Lashana, Disco 200g, Disco 400g, Disgo 500g.
- Normal: Nhoque 400g, Nhoque Kg, Quadrada.

Use existing `name_pt`, `name_es`, `category_type`, and `selected_products` fields; update stale TS/Zod unions to match DB types.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/frontend` | Modified | Admin forms and report product selection UI. |
| `packages/backend` | Modified | Translation utility, schemas, admin validation. |
| `packages/backend/src/db` | Modified | Existing localized/type/selected-products fields. |
| `openspec/specs` | Modified | Admin catalog and report generation deltas. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Translation outage blocks saves | Medium | Preserve fallback and require only one submitted locale. |
| Fixed lists drift | Medium | Centralize constants and cover both types in tests. |
| Wrong locale overwrite | Low | Only auto-fill missing counterpart. |

## Rollback Plan

Revert frontend form/report changes and backend translator/schema changes. Remove Google translation env vars if added. No data migration rollback is required because existing DB columns remain compatible.

## Dependencies

- Google Cloud Translation API credential from environment only.
- Existing localized name, category type, and selected product storage.

## Success Criteria

- [ ] PT-BR admins edit PT-BR names only; ES admins edit ES names only.
- [ ] Saves store both locales without exposing secrets.
- [ ] Assaí/Normal reports allow selecting one or more fixed products.
- [ ] TypeScript and backend validation accept all DB-supported category types.
