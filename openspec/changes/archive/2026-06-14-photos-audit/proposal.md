# Proposal: Photos in Reports + Visual Audit

## Intent

Enable users to attach, view, and remove photos on reports. Introduce a centralized audit logging system to track critical operations, along with a dedicated UI for administrative oversight.

## Scope

### In Scope
- **PR1 Photos**: Upload, storage, serving, and deletion of photos linked to reports.
- **PR2 Audit**: Centralized inline audit logging across backend services.
- **PR2 Audit**: Standalone admin page to view and paginate audit logs.

### Out of Scope
- External object storage (e.g., S3) – using local Docker volumes.
- Complex analytics or exporting of audit logs.
- Category-level photo attachment.

## Capabilities

### New Capabilities
- `report-photo-management`: Handles file uploads via `@fastify/multipart`, stores files locally, and serves them securely through auth-gated routes.
- `visual-audit`: Provides a centralized `audit.log()` utility and exposes a paginated admin UI to review system actions.

### Modified Capabilities
- None

## Approach

Use a 2-PR chained approach (stacked-to-main work units) to stay under the 400-line budget per PR:
1. **PR1 (Photos)**: Register `@fastify/multipart`. Save files to `data/photos/`. Serve via Fastify GET endpoint with auth checks. Add upload/gallery UI components to report pages.
2. **PR2 (Audit)**: Create an `audit` module. Add explicit `audit.log()` calls inside existing service modules. Build a separate `/admin/audit` page in the frontend to display logs.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `src/modules/reports/` | Modified | Add photo routes, schemas, and service logic. |
| `src/modules/audit/` | New | Add standalone audit service and routes. |
| `packages/frontend/src/pages/` | Modified | Update report views; create new `AuditPage.tsx`. |
| `src/modules/*/` | Modified | Insert inline audit hooks into existing services. |
| `docker-compose.yml` | Modified | Map new `uploads_data` volume. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Disk exhaustion | Medium | Enforce strict file size limits during upload. |
| Audit log bloat | Low | Add limits and pagination to the admin audit endpoint. |
| Bypassing edit-window | Medium | Ensure photo upload/delete logic enforces the existing report `readOnly` state. |

## Rollback Plan

1. Revert backend API route additions.
2. Revert frontend UI additions to hide the photo gallery and audit tab.
3. Retain database tables (`report_photos`, `audit_logs`) and stored files to prevent data loss.

## Dependencies

- `@fastify/multipart` (new backend dependency).
- Existing DB schema tables (`report_photos`, `audit_logs`).

## Success Criteria

- [ ] Users can successfully upload and delete report photos.
- [ ] Uploaded photos are viewable on the report details page.
- [ ] Photos cannot be modified if a report's edit-window has expired.
- [ ] System actions (e.g., login, create, update) are visibly recorded in the database.
- [ ] Admins can browse and filter audit logs via the `/admin/audit` UI.
