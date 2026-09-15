# Delta for Report Generation

## ADDED Requirements

### Requirement: Worker-Created Tasks in Report Builder
The system MUST expose tasks created by workers as universal report-builder options. Worker-created tasks MUST be selectable by all authorized users in report creation and editing.

#### Scenario: Worker task appears in builder
- GIVEN a task was created by a worker under an existing category
- WHEN any authorized user opens the report builder
- THEN the task MUST be available for selection

#### Scenario: Worker task remains shared
- GIVEN a worker-created task is used in reports
- WHEN reports are generated or reloaded
- THEN the task MUST render like any other task and remain usable
