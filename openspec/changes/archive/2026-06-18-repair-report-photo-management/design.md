# Design: Repair Report Photo Management

## Technical Approach

Extend the existing Fastify photo routes and React report pages instead of replacing the report flow. Reports remain JSON-first: create/update report data, then upload optional photos with multipart requests. The UI keeps selected files in local component state until the report has an ID, previews them with object URLs, and retries photo upload failures without clearing report fields.

## Architecture Decisions

| Decision | Choice | Alternatives considered | Rationale |
|---|---|---|---|
| Multi-photo contract | Keep `POST /api/reports/:id/photos` as one file per request; upload selected files sequentially after report save | Add multipart batch endpoint or embed files in report JSON | Fits current `api.upload`, audit logging, and simple per-file error handling. |
| Public export links | Add `public_token` to `report_photos` and serve `GET /api/reports/photos/public/:token` without auth | Reuse authenticated `/api/reports/photos/:id` links | WhatsApp recipients need downloadable links. Token URLs are stable and unguessable, but anyone with the link can view until retention deletes the file. |
| Image validation | Validate MIME allow-list plus magic bytes for JPEG/PNG/WebP in backend; mirror size/type checks in UI | Trust browser MIME only or add a new dependency | Avoids fake-image uploads without adding package risk. |
| Retention | Add service cleanup and run on startup plus daily interval | Manual admin cleanup | Meets automatic hard-delete with low operational complexity. |

## Data Flow

Create with photos:

```text
ReportsPage selected Files -> preview object URLs
submit -> POST /api/reports -> report id
      -> sequential POST /api/reports/:id/photos
      -> navigate to /reports/:id or keep form + photoError on failure
```

Edit with photos:

```text
ReportEditPage -> GET report + GET photos
existing thumbnails + pending previews
delete existing -> DELETE /api/reports/photos/:photoId
save fields -> PATCH /api/reports/:id
new files -> POST /api/reports/:id/photos
```

Export:

```text
GET /api/reports/:id/export -> generateWhatsAppText(db, id, publicBaseUrl)
                         -> public photo URLs from report_photos.public_token
```

## File Changes

| File | Action | Description |
|---|---|---|
| `packages/backend/src/db/schema.sql` | Modify | Add `public_token TEXT UNIQUE` and `idx_report_photos_created_at`. |
| `packages/backend/src/db/index.ts` | Modify | Add migration/backfill for `report_photos.public_token`. |
| `packages/backend/src/modules/reports/reports.service.ts` | Modify | Enforce max 5 photos/report, generate tokens, list photos with `url/publicUrl`, add `cleanupExpiredPhotos`. |
| `packages/backend/src/modules/reports/reports.routes.ts` | Modify | Add magic-byte validation, count handling, public photo route, cleanup helper usage. |
| `packages/backend/src/modules/reports/export.service.ts` | Modify | Accept `publicBaseUrl`, append Portuguese photo-link section. |
| `packages/backend/src/server.ts` | Modify | Derive `PUBLIC_APP_URL`, schedule startup/daily retention cleanup. |
| `packages/frontend/src/pages/ReportsPage.tsx` | Modify | Add optional multi-file selector, previews, removal, sequential post-create upload, retry-safe error. |
| `packages/frontend/src/pages/ReportEditPage.tsx` | Modify | Support up to 5 total existing + pending photos and preserve state on upload/save error. |
| `packages/frontend/src/pages/ReportViewPage.tsx` | Modify | Use returned thumbnail URLs and keep link-open behavior. |
| `packages/frontend/src/components/reports/ExportPreview.tsx` | Modify | Remove PDF/TXT controls; keep WhatsApp text copy only. |
| `packages/frontend/src/i18n/locales/*.json` | Modify | Add count/validation/retry strings; remove exposed PDF/TXT labels from report export UI. |

## Interfaces / Contracts

- `ReportPhoto`: `{ id, report_id, file_path, file_size, mime_type, created_at, url, publicUrl }`.
- `POST /api/reports/:id/photos`: accepts one `file`; returns `201 { photo }`; rejects `413`, `415`, `403`, and `409` when total photos would exceed 5.
- `GET /api/reports/photos/public/:token`: unauthenticated image download with `Cache-Control: public, max-age=3600`.
- `generateWhatsAppText(db, reportId, publicBaseUrl)`: includes `📷 *Fotos:*` plus one public URL per photo.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Backend unit | Magic-byte validation, max 5, export photo URLs, retention cutoff | Extend `reports.routes.test.ts` and `export.service.test.ts`. |
| Backend integration | Public token serving and hard-delete DB+file cleanup | Use `buildTestApp` photosDir assertions. |
| E2E | Create/edit pending previews, removal, upload error preserves form, export only WhatsApp with links | Extend `photos.spec.ts` and `report-view-export.spec.ts`. |

## Migration / Rollout

Add `public_token` safely with `ensureColumn`, backfill existing rows with UUID tokens at startup, and keep existing authenticated photo URLs working. Deploy backend first, then frontend. Retention cleanup logs failures and deletes DB rows only after file deletion attempt is handled.

## Open Questions

- [ ] What externally reachable `PUBLIC_APP_URL` should production use for WhatsApp links?
