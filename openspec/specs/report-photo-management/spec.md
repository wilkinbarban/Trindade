# Report Photo Management Specification

## Purpose

Enable users to upload, view, serve, and delete photos associated with specific reports while respecting authentication and report edit-window boundaries.

## Requirements

### Requirement: Photo Upload

The system MUST allow authenticated users to add optional photos attached to a specific report during create and edit flows.
The system MUST accept up to 5 image files per report, MUST validate that each file is a real image/photo, and MUST reject files larger than 5 MB.
The system MUST provide thumbnail previews for selected and uploaded photos and MUST allow removing any selected photo before saving.
The system MUST preserve existing photos during edit and allow adding new photos in the same edit session.
The system MUST handle upload/send errors without losing the report form state.

#### Scenario: Successful upload
- GIVEN an authenticated user and a valid report ID within its edit window
- WHEN the user adds one or more valid image files and saves the report
- THEN the system saves the photos to local storage, records the metadata in `report_photos`, and returns the photo records
- AND the report remains savable even though photos are optional

#### Scenario: Multiple photos within limit
- GIVEN an authenticated user editing a report
- WHEN the user attaches up to 5 valid image files
- THEN the system accepts the files and shows a thumbnail preview for each selected photo

#### Scenario: Invalid file size
- GIVEN an authenticated user
- WHEN the user uploads an image file larger than 5MB
- THEN the system rejects the upload with a 413 Payload Too Large error.

#### Scenario: Invalid file type
- GIVEN an authenticated user
- WHEN the user uploads a file that is not a real image/photo
- THEN the system rejects the upload with a 415 Unsupported Media Type error.

#### Scenario: Edit window expired
- GIVEN an authenticated user
- WHEN the user attempts to upload a photo to a report whose `readOnly` state is true (edit window expired)
- THEN the system rejects the upload with a 403 Forbidden error.

#### Scenario: Remove mistaken photo before save
- GIVEN an authenticated user has selected photos in the create or edit form
- WHEN the user removes one selected photo before saving
- THEN the removed photo is excluded from the submitted report

#### Scenario: Upload/send error
- GIVEN an authenticated user is saving report photos
- WHEN the current photo send/upload operation fails
- THEN the system shows a clear error state
- AND the user can retry without losing the remaining report form data

### Requirement: Photo Serving

The system MUST serve uploaded photos securely, restricted to authenticated users.

#### Scenario: Successful retrieval
- GIVEN an authenticated user
- WHEN the user requests a valid photo URL
- THEN the system serves the image file with the correct content type.

#### Scenario: Unauthorized access
- GIVEN an unauthenticated user
- WHEN the user requests a photo URL
- THEN the system rejects the request with a 401 Unauthorized error.

#### Scenario: Photo not found
- GIVEN an authenticated user
- WHEN the user requests a photo URL that does not exist on disk
- THEN the system returns a 404 Not Found error.

### Requirement: Photo Deletion

The system MUST allow authenticated users to delete photos attached to reports, provided the report is still editable.

#### Scenario: Successful deletion
- GIVEN an authenticated user and a valid photo ID
- WHEN the user requests to delete the photo on a report within its edit window
- THEN the system removes the database record, deletes the file from disk, and returns a success response.

#### Scenario: Deletion when edit window expired
- GIVEN an authenticated user and a valid photo ID
- WHEN the user attempts to delete the photo on a report whose `readOnly` state is true
- THEN the system rejects the deletion with a 403 Forbidden error.

### Requirement: Photo Gallery UI

The system MUST display attached photos within the report views and MUST render at least one thumbnail per photo.
The system MUST show photo previews in create/edit flows for selected uploads and MUST allow removing selected or existing photos where edits are permitted.

#### Scenario: View report with photos
- GIVEN a report with associated photos
- WHEN a user views the report details or edit page
- THEN the system displays a gallery section showing the thumbnails/images
- AND the UI shows at least one thumbnail for each photo
- AND allows deletion/upload only on the edit page if the report is not read-only.

#### Scenario: Create or edit with pending selections
- GIVEN a user has selected photos but has not saved the report yet
- WHEN the user views the create or edit form
- THEN the system shows thumbnail previews for the pending photos
- AND allows removing any pending photo before save

### Requirement: Photo Retention Lifecycle

The system MUST retain report photos for 30 days and MUST hard-delete photos automatically after that period.
The system MUST delete photo records and files together when retention expires.

#### Scenario: Retention expires
- GIVEN a report photo was created more than 30 days ago
- WHEN the retention process runs
- THEN the system permanently deletes the file and its database record

#### Scenario: Retention not expired
- GIVEN a report photo was created less than 30 days ago
- WHEN the retention process runs
- THEN the system keeps the file and the database record
