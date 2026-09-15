# Proposal: Admin Edit & Exports

## Intent

Add comprehensive admin catalog management, advanced editing for reports and schedules, and extended TXT/PDF export capabilities, addressing current gaps in data mutability and reporting.

## Scope

### In Scope
- **Admin CRUD**: Report categories, tasks, products, drivers, vehicles, and time slots.
- **Advanced Editing**: Report edit UI (reusing existing PATCH), loading schedule edit endpoint and UI with quota revalidation.
- **Extra Exports**: TXT and PDF downloads for reports and loading schedules.
- **Localization**: Portuguese (pt-BR) content for user-facing UI and exports.

### Out of Scope
- Unrelated modules or features.
- Audit log UI.
- Photos or media handling.
- Heavy backend PDF dependencies (e.g., Puppeteer/Playwright).

## Capabilities

### New Capabilities
- `admin-catalog-management`: Admin-only endpoints and UI to manage system entities.
- `advanced-editing`: UI and endpoints allowing modification of existing reports and schedules.
- `extra-exports`: TXT and PDF generation utilities for records.

### Modified Capabilities
- None

## Approach

To stay under the 400-line review budget, we will deliver this in 3 chained PRs:

1. **Admin CRUD**: Add `modules/admin` backend routes protected by `requireRole('Administrador')` and build frontend Admin dashboards.
2. **Advanced Editing**: Add report edit UI using existing builder forms, plus a new PATCH endpoint for loading schedules with transaction-safe quota checks.
3. **Extra Exports**: Add TXT exports via simple string builders (UTF-8 BOM). Add PDF exports using a lightweight frontend library (`jspdf` + `html2canvas`) to avoid heavy backend dependencies.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `backend/src/modules/admin/` | New | Admin catalog routes. |
| `backend/src/modules/loading/` | Modified | Vehicle CRUD, schedule PATCH, export APIs. |
| `frontend/src/pages/Admin*` | New | Role-gated management dashboards. |
| `frontend/src/components/` | Modified | Add edit mode to ReportView/ScheduleView; add export buttons. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Quota desync on schedule edit | Low | Wrap schedule update and quota recalculation in a single SQLite transaction. |
| PDF styling mismatches | Medium | Use frontend-generated PDFs (`jspdf`) capturing the exact UI rendered. |
| Unauthorized mutation | Low | Consistently apply `requireRole('Administrador')` middleware. |

## Rollback Plan

- **Code**: Revert PRs starting from the most recent.
- **Data**: Changes to catalogs (e.g., new drivers) will remain in SQLite but will not break older views. Ensure any schema additions are purely additive.

## Dependencies

- Frontend PDF libraries: `jspdf` and `html2canvas` (minimal dependency).

## Success Criteria

- [ ] Admins can create, update, and delete catalog items via UI.
- [ ] Users can edit reports and loading schedules post-creation.
- [ ] Editing a loading schedule correctly re-validates the max-3-fletero quota.
- [ ] TXT and PDF exports download correctly with pt-BR terminology.