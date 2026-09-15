# Delta for Report Generation

## ADDED Requirements

### Requirement: Report History Lifecycle Controls
The system MUST expose report history status and server-computed actions for every report record.

#### Scenario: Admin deactivates report
- GIVEN an authenticated Administrador views any active report history record
- WHEN the admin deactivates the report
- THEN the record MUST become inactive and remain visible as inactive in history

#### Scenario: Admin permanently deletes report
- GIVEN an authenticated Administrador views any report history record
- WHEN the admin deletes the report
- THEN the system MUST physically remove the report and related dependent data transactionally

### Requirement: Portuguese Report History Content
Report history views and exports MUST render registered report domain content in Brazilian Portuguese, while surrounding UI chrome MAY remain localized.

#### Scenario: Spanish UI views report history
- GIVEN the UI locale is ES
- WHEN the user opens report history or exports a history report
- THEN category, task, product, and persisted report content MUST be shown in PT-BR

## MODIFIED Requirements

### Requirement: Report History and Filtering
The system MUST allow all authenticated users, including Administradores and Trabajadores, to view all non-deleted reports under Cronograma de Carga > Historial de Reportes. Reports MUST be ordered newest first and paginated with a default page size of 30. Filters MUST include exact date, month, and user.
(Previously: The system allowed viewing/filtering past reports with period filters only.)

#### Scenario: Date Filtering
- GIVEN multiple reports exist across different dates
- WHEN the user applies an exact date filter
- THEN the system MUST display only reports from that date

#### Scenario: Month and user filtering
- GIVEN reports exist for multiple months and creators
- WHEN the user filters by month and user
- THEN the system MUST display only matching reports

#### Scenario: Newest-first paginated history
- GIVEN more than 30 reports exist
- WHEN the user opens Historial de Reportes
- THEN the system MUST show the newest 30 reports first with pagination metadata

### Requirement: Edit Window Validation
The system MUST allow report edits only for the creator during the first hour after creation. All other users and expired creator sessions MUST be read-only.
(Previously: The system restricted edits to the current day and previous day only.)

#### Scenario: Valid Edit Window
- GIVEN a report was created by the current user less than one hour ago
- WHEN the user attempts to edit the report
- THEN the system MUST allow the edit to proceed

#### Scenario: Expired Edit Window
- GIVEN a report was created by the current user at least one hour ago
- WHEN the user attempts to edit the report
- THEN the system MUST block the edit and expose read-only state

#### Scenario: Non-creator read-only
- GIVEN a report was created by another user
- WHEN the current user opens or updates the report
- THEN the system MUST deny mutation and expose read-only state
