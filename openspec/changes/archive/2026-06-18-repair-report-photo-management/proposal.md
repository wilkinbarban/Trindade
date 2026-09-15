# Proposal: Repair Report Photo Management

## Intent
Photo handling in report creation/editing is broken or incomplete. Users and admins need a reliable way to add, preview, remove, and manage multiple report photos without upload errors, while keeping storage bounded via automatic cleanup.

## Scope
### In Scope
- Add optional photo upload to report create/edit flows for users and admins.
- Support up to 5 validated image files per report, with thumbnail previews and removal before save.
- Fix the current upload/send error path and make existing/new photo management work in edit mode.
- Include downloadable photo links in WhatsApp/export output and show at least one thumbnail per photo in the UI.
- Enforce 30-day photo retention with automatic deletion.

### Out of Scope
- Clipboard-based binary image sharing.
- Unlimited attachments or non-image media.
- Changes to report business data outside photo handling.

## Capabilities
### New Capabilities
- None

### Modified Capabilities
- `report-photo-management`: extend upload/delete/gallery behavior to multi-photo creation/editing, validation, previews, retention, and error recovery.
- `report-generation`: expose photo controls in create/edit UX and preserve photo state during edits.
- `report-export`: include downloadable photo links in exported report text without binary image transport.

## Approach
Reuse the existing report photo pipeline and extend it end-to-end: frontend form state for multiple optional images, backend validation for MIME/type/size/count, report edit reconciliation for existing + new photos, and export rendering of photo URLs. Add a retention job/cleanup path that hard-deletes photos older than 30 days.

## Affected Areas
| Area | Impact | Description |
|------|--------|-------------|
| `packages/frontend` | Modified | Create/edit report forms, thumbnails, remove actions, error states |
| `packages/backend` | Modified | Upload validation, report-photo persistence, serving, delete, cleanup job |
| `openspec/specs/report-photo-management/spec.md` | Modified | Expand photo behavior and lifecycle |
| `openspec/specs/report-generation/spec.md` | Modified | Add photo controls to create/edit flows |
| `openspec/specs/report-export/spec.md` | Modified | Add downloadable photo links to export text |

## Risks
| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Upload regressions block report saving | Medium | Preserve existing save path and add explicit photo error handling |
| Storage growth from abandoned photos | Medium | Enforce 30-day cleanup and hard limits on count/size |
| Broken export links or expired URLs | Medium | Generate stable report-relative links and verify serving/auth behavior |

## Rollback Plan
Disable the new photo UI controls, revert multi-photo validation and edit reconciliation, and stop the cleanup job. Keep existing report data intact; only photo-related paths are reverted.

## Dependencies
- Existing authenticated report upload/serve/delete endpoints and report edit-window logic.

## Success Criteria
- [ ] Users can add up to 5 optional photos while creating or editing a report.
- [ ] Invalid files and photos over 5 MB are rejected with clear errors.
- [ ] Selected/uploaded photos show thumbnails and can be removed before saving.
- [ ] Exported reports include downloadable photo links.
- [ ] Photos older than 30 days are automatically deleted.
