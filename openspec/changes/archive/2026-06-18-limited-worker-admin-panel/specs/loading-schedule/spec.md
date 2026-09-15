# Delta for Loading Schedule

## MODIFIED Requirements

### Requirement: Driver Listing and Creation
The system MUST provide capabilities to list and quickly create fleteros. Worker-created fleteros MUST be available as universal scheduling options.
(Previously: Driver listing/creation did not state worker-owned fleteros.)

#### Scenario: List active fleteros
- GIVEN the database contains active and inactive fleteros
- WHEN the system requests the driver list
- THEN the system returns only active fleteros

#### Scenario: Create a new fletero
- GIVEN a driver name
- WHEN the user submits the new fletero form
- THEN the system creates an active fletero
- AND makes them immediately available for scheduling

#### Scenario: Worker-created fletero is usable
- GIVEN a fletero was created by a worker
- WHEN any user opens scheduling
- THEN the fletero MUST appear as a valid option
