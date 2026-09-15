# Proposal: Relatório Inteligente

Deliver the core "Smart Report Builder" (Generador Inteligente) allowing users to compile categorized shift reports and export them formatted for WhatsApp in Brazilian Portuguese, eliminating manual typing.

## Intent

To provide a visual, structured report builder that dynamically handles checks, lists, temperatures, and quantities, generating ready-to-share WhatsApp text. This solves the operational pain of unstructured, manually typed shift reports.

## Scope

### In Scope
- Core report builder UI with 4 dynamic element types (Check, List, Temperature, Quantity).
- Auto-detection of shift turns (tarde/noite) based on server time.
- Generador Inteligente text engine for WhatsApp export (strictly Portuguese).
- Report history with date filters (Today, Yesterday, Last 7/30 days, Custom).
- Edit window limited to the current and previous day.

### Out of Scope
- Photo uploads and attachments.
- PDF and TXT export formats.
- Standalone admin CRUD UIs for categories, tasks, or products.
- Standalone audit log UI.

## Capabilities

> This section is the CONTRACT between proposal and specs phases.

### New Capabilities
- `report-generation`: Core builder UI, dynamic category-driven forms, history filtering, and edit window validation.
- `report-export`: Generador Inteligente text engine formatting reports for WhatsApp in Brazilian Portuguese.

### Modified Capabilities
- None

## Approach

1. **Backend**: Implement Fastify routes for category/product read endpoints, report CRUD, and a dedicated text-generation service for the WhatsApp format. Auto-detect shifts in the service layer.
2. **Frontend**: Build dynamic React form sections for the 4 element types using `shadcn/ui`. Add history and view pages.
3. **Execution**: Split into 2 chained PRs (Backend API first, Frontend UI second) to stay under the 400-line review budget.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `packages/backend/src/modules/reports/` | New | Routes, schema, and core business logic for reports. |
| `packages/backend/src/modules/reports/export.service.ts` | New | WhatsApp text engine (Generador Inteligente). |
| `packages/frontend/src/pages/Reports*` | New | Builder, history, and single report view pages. |
| `packages/frontend/src/components/reports/` | New | Dynamic form components (CheckItem, ListSelector, etc.). |
| `packages/frontend/src/i18n/locales/pt-BR.json` | Modified | Addition of UI strings and static export headers. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Scope creep (Photos/PDF) | High | Strictly enforce the bounded slice; explicitly defer in PR notes. |
| Edit window edge cases | Medium | Server-side validation block on PATCH `/api/reports/:id` based on strict date diffs. |

## Rollback Plan

Revert the frontend PR, then the backend PR. No database schema rollback is required since all tables are already present in the existing `schema.sql`.

## Dependencies

- Existing database schema (`reports`, `report_categories`, etc.) must be untouched.

## Success Criteria

- [ ] Users can successfully create a report using all 4 element types.
- [ ] The system accurately generates formatted WhatsApp text in Portuguese.
- [ ] Users can view and filter historical reports.
- [ ] Reports older than the previous day are blocked from being edited.