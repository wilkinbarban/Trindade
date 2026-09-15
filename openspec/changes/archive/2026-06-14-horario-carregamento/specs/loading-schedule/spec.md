# Loading Schedule Specification

## Purpose
Core scheduling CRUD, driver assignment, and quota enforcement for daily loading operations.

## Requirements

### Requirement: Schedule Management
The system MUST allow users to manage schedule entries by date.

#### Scenario: List schedules for a date
- GIVEN a date with existing schedule entries
- WHEN the user views the loading schedule for that date
- THEN the system returns the entries sorted by time slot

#### Scenario: Add a schedule entry
- GIVEN a valid time slot and an active driver
- WHEN the user adds the driver to the time slot
- THEN the system records the entry for the selected date

#### Scenario: Delete a schedule entry
- GIVEN an existing schedule entry
- WHEN the user deletes the entry
- THEN the system removes it from the schedule
- AND frees up capacity if the driver was a fletero

### Requirement: Fletero Quota Validation
The system MUST restrict 'fletero' drivers to a maximum of 3 per time slot per date.

#### Scenario: Successful assignment within quota
- GIVEN a time slot with 2 assigned fleteros
- WHEN the user assigns a 3rd fletero
- THEN the system accepts the assignment

#### Scenario: Rejection when quota exceeded
- GIVEN a time slot with 3 assigned fleteros
- WHEN the user assigns a 4th fletero
- THEN the system rejects the assignment
- AND returns an appropriate error message

#### Scenario: Prevent duplicate driver assignment
- GIVEN a driver already assigned to a time slot
- WHEN the user attempts to assign the same driver to the same time slot
- THEN the system rejects the assignment

### Requirement: Driver Listing and Creation
The system MUST provide capabilities to list and quickly create fleteros.

#### Scenario: List active fleteros
- GIVEN the database contains active and inactive fleteros
- WHEN the system requests the driver list
- THEN the system returns only active fleteros

#### Scenario: Create a new fletero
- GIVEN a driver name
- WHEN the user submits the new fletero form
- THEN the system creates an active fletero
- AND makes them immediately available for scheduling

### Requirement: Schedule Grid Interface
The system MUST provide a visual grid for managing schedules.

#### Scenario: View quota indicators
- GIVEN a time slot with assigned drivers
- WHEN the user views the grid
- THEN the system displays the number of assigned fleteros versus the limit (e.g., "2/3")

#### Scenario: Navigate dates
- GIVEN the current schedule view
- WHEN the user selects the next day
- THEN the system updates the grid to show entries for the new date
