# Report Generation Specification

## Purpose

Core report builder UI, dynamic category-driven forms, history filtering, and edit window validation.

## Requirements

### Requirement: Builder Element Types
The system MUST support `check`, `temperature`, `check_assai`, and `check_normal` task element types.
(Previously: The builder supported only `check` and `temperature`.)

#### Scenario: Check Task
- GIVEN a `check` task
- WHEN viewed in builder
- THEN system displays a checkbox input

#### Scenario: Temperature Task
- GIVEN a `temperature` task
- WHEN viewed in builder
- THEN system displays a numeric temperature input

#### Scenario: Assai Check Task
- GIVEN a `check_assai` task
- WHEN viewed in builder
- THEN system displays a check input with fixed Assai product selection

#### Scenario: Normal Check Task
- GIVEN a `check_normal` task
- WHEN viewed in builder
- THEN system displays a check input with fixed Normal product selection

### Requirement: Turno (Shift) Auto-Detection

The system MUST automatically detect the active shift based on server time.

#### Scenario: Tarde Shift

- GIVEN the current server time is between 06:00 and 17:59
- WHEN the user creates a new report
- THEN the default turno MUST be set to 'tarde'

#### Scenario: Noite Shift

- GIVEN the current server time is between 18:00 and 05:59
- WHEN the user creates a new report
- THEN the default turno MUST be set to 'noite'

#### Scenario: Turno Override

- GIVEN the system has auto-detected the turno
- WHEN the user selects a different turno before saving
- THEN the system MUST use the user-selected turno

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
The system MUST allow report edits only for the creator, while the report belongs to the current São Paulo day or the previous one. All other users, and reports older than that, MUST be read-only.
The system MUST decide this by comparing the São Paulo date of the report rather than elapsed time, so that a report created at 22:00 is not treated as belonging to the following day.
The system MUST keep the photo management UI aligned with that read-only state so that photo add/remove actions are only available when edits are allowed.
(Previously: The system allowed edits only during the first hour after creation.)

#### Scenario: Valid edit window on the current day
- GIVEN a report was created by the current user earlier the same São Paulo day
- WHEN the user attempts to edit the report
- THEN the system MUST allow the edit to proceed
- AND photo management actions MUST be available

#### Scenario: Valid edit window on the previous day
- GIVEN a report was created by the current user on the previous São Paulo day
- WHEN the user attempts to edit the report
- THEN the system MUST allow the edit to proceed
- AND photo management actions MUST be available

#### Scenario: Expired edit window
- GIVEN a report was created by the current user two or more São Paulo days ago
- WHEN the user attempts to edit the report
- THEN the system MUST block the edit and expose read-only state
- AND photo management actions MUST be disabled

#### Scenario: Non-creator read-only
- GIVEN a report was created by another user
- WHEN the current user opens or updates the report
- THEN the system MUST deny mutation and expose read-only state
- AND photo management actions MUST be disabled

#### Scenario: Future timestamp fails closed
- GIVEN a report whose stored creation timestamp is later than the current time
- WHEN the user attempts to edit the report
- THEN the system MUST treat the report as read-only

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

### Requirement: Fixed Product Selection for Assai and Normal Checks

`check_assai` and `check_normal` report items MUST allow selecting mounted products from fixed lists. Assai MUST use: Nhoque Kg, Quadrada, Rolo 500, Rolo kg, Rolo 2kg, Lashana, Disco 200g, Disco 400g, Disgo 500g. Normal MUST use: Nhoque 400g, Nhoque Kg, Quadrada.

#### Scenario: Assai products selected
- GIVEN a `check_assai` item
- WHEN the user selects mounted products
- THEN only fixed Assai products are available
- AND selected products are saved

#### Scenario: Normal products selected
- GIVEN a `check_normal` item
- WHEN the user selects mounted products
- THEN only fixed Normal products are available
- AND selected products are saved

### Requirement: Selected Products Round Trip

Report create/edit/history/reload MUST preserve `selected_products` for Assai and Normal checks.

#### Scenario: Report is reopened
- GIVEN a saved report has selected products
- WHEN the report is edited or viewed in history
- THEN selected products MUST be restored

### Requirement: Worker-Created Tasks in Report Builder
The system MUST expose tasks created by workers as universal report-builder options. Worker-created tasks MUST be selectable by all authorized users in report creation and editing.

#### Scenario: Worker task appears in builder
- GIVEN a task was created by a worker under an existing category
- WHEN any authorized user opens the report builder
- THEN the task MUST be available for selection

#### Scenario: Worker task remains shared
- GIVEN a worker-created task is used in reports
- WHEN reports are generated or reloaded
- THEN the report MUST render like any other task and remain usable

### Requirement: WhatsApp Export Language Stability

WhatsApp export text MUST remain Brazilian Portuguese regardless of UI locale.

#### Scenario: Spanish UI exports
- GIVEN the UI language is ES
- WHEN the user exports to WhatsApp
- THEN the exported text MUST be PT-BR

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
