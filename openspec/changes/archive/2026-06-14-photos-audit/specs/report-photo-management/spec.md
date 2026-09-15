# Report Photo Management Specification

## Purpose

Enable users to upload, view, serve, and delete photos associated with specific reports while respecting authentication and report edit-window boundaries.

## Requirements

### Requirement: Photo Upload

The system MUST allow authenticated users to upload photos attached to a specific report.

#### Scenario: Successful upload
- GIVEN an authenticated user and a valid report ID within its edit window
- WHEN the user uploads a valid image file (< 5MB, valid MIME type)
- THEN the system saves the file to local storage, records the metadata in `report_photos`, and returns the photo record.

#### Scenario: Invalid file size
- GIVEN an authenticated user
- WHEN the user uploads an image file larger than 5MB
- THEN the system rejects the upload with a 413 Payload Too Large error.

#### Scenario: Invalid file type
- GIVEN an authenticated user
- WHEN the user uploads a file with an invalid MIME type
- THEN the system rejects the upload with a 415 Unsupported Media Type error.

#### Scenario: Edit window expired
- GIVEN an authenticated user
- WHEN the user attempts to upload a photo to a report whose `readOnly` state is true (edit window expired)
- THEN the system rejects the upload with a 403 Forbidden error.

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

The system MUST display attached photos within the report views.

#### Scenario: View report with photos
- GIVEN a report with associated photos
- WHEN a user views the report details or edit page
- THEN the system displays a gallery section showing the thumbnails/images.
- AND allows deletion/upload only on the edit page if the report is not read-only.
