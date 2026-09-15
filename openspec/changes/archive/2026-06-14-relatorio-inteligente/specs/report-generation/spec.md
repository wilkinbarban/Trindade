# Report Generation Specification

## Purpose

Core report builder UI, dynamic category-driven forms, history filtering, and edit window validation.

## Requirements

### Requirement: Builder Element Types

The system MUST support four dynamic task element types in the report builder based on the backend configuration.

#### Scenario: Check Task

- GIVEN a task configured as 'check' type
- WHEN the user views the task in the builder
- THEN the system MUST display a toggle or checkbox input

#### Scenario: List Task

- GIVEN a task configured as 'list' type
- WHEN the user views the task in the builder
- THEN the system MUST display a dropdown or list selection containing associated products

#### Scenario: Temperature Task

- GIVEN a task configured as 'temperature' type
- WHEN the user views the task in the builder
- THEN the system MUST display a numeric input optimized for temperatures

#### Scenario: Quantity Task

- GIVEN a task configured as 'quantity' type
- WHEN the user views the task in the builder
- THEN the system MUST display a numeric input optimized for item counts

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

The system MUST allow users to view and filter past reports.

#### Scenario: Date Filtering

- GIVEN multiple reports exist across different dates
- WHEN the user applies a date filter (Today, Yesterday, Last 7 days, Last 30 days, Custom)
- THEN the system MUST display only the reports matching the selected date range

### Requirement: Edit Window Validation

The system MUST restrict edits to the current day and previous day only.

#### Scenario: Valid Edit Window

- GIVEN a report was created today or yesterday
- WHEN the user attempts to edit the report
- THEN the system MUST allow the edit to proceed

#### Scenario: Expired Edit Window

- GIVEN a report was created more than one day ago
- WHEN the user attempts to edit the report
- THEN the system MUST block the edit and display an error
