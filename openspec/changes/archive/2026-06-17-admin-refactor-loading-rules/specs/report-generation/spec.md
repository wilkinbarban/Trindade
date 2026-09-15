# Delta for report-generation

## MODIFIED Requirements

### Requirement: Builder Element Types
The system MUST support 'check' and 'temperature' task element types.
(Previously: Supported four types, including list and quantity tasks which are removed)

#### Scenario: Check Task
- GIVEN a 'check' task
- WHEN viewed in builder
- THEN system displays a checkbox input

#### Scenario: Temperature Task
- GIVEN a 'temperature' task
- WHEN viewed in builder
- THEN system displays a numeric temperature input
