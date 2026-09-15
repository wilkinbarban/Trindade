# Delta for Report Generation

## ADDED Requirements

### Requirement: Report Photo Controls

The system MUST expose optional photo controls in report creation and editing flows.
The system MUST allow users to select up to 5 image files, preview thumbnails, and remove pending photos before saving.
The system MUST preserve existing report photos while allowing new photos to be added during edit.

#### Scenario: Create report with photos
- GIVEN a user is creating a report
- WHEN the user selects valid image files
- THEN the system shows thumbnail previews for the selected photos
- AND the user can remove any pending photo before save

#### Scenario: Edit report with existing and new photos
- GIVEN a user is editing a report that already has photos
- WHEN the user adds additional valid image files
- THEN the system preserves the existing photos
- AND the system allows the user to add new photos in the same edit session

#### Scenario: Photo upload is optional
- GIVEN a user is creating or editing a report
- WHEN the user saves the report without adding photos
- THEN the system saves the report successfully

## MODIFIED Requirements

### Requirement: Edit Window Validation

The system MUST allow report edits only for the creator during the first hour after creation. All other users and expired creator sessions MUST be read-only.
The system MUST keep the photo management UI aligned with that read-only state so that photo add/remove actions are only available when edits are allowed.
(Previously: The system restricted edits to the current day and previous day only.)

#### Scenario: Valid Edit Window
- GIVEN a report was created by the current user less than one hour ago
- WHEN the user attempts to edit the report
- THEN the system MUST allow the edit to proceed
- AND photo management actions MUST be available

#### Scenario: Expired Edit Window
- GIVEN a report was created by the current user at least one hour ago
- WHEN the user attempts to edit the report
- THEN the system MUST block the edit and expose read-only state
- AND photo management actions MUST be disabled

#### Scenario: Non-creator read-only
- GIVEN a report was created by another user
- WHEN the current user opens or updates the report
- THEN the system MUST deny mutation and expose read-only state
- AND photo management actions MUST be disabled
