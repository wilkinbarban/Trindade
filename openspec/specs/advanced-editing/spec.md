# Advanced Editing Specification

## Purpose
Allow authorized users to modify existing reports and loading schedules with strict business rule enforcement.

## Requirements

### Requirement: Report Editing UI
The system MUST allow users to edit existing reports using the same builder forms used for creation.

#### Scenario: User edits a report
- GIVEN an existing report within the allowed edit window
- WHEN an authorized user modifies the report details and submits
- THEN the system MUST update the report successfully

#### Scenario: Edit window enforcement
- GIVEN an existing report outside the allowed edit window (i.e., created before the previous São Paulo day)
- WHEN a user attempts to edit it
- THEN the system MUST reject the modification
- AND the UI MUST show the report as read-only

### Requirement: Loading Schedule Editing and Quota Revalidation
The system MUST allow editing loading schedules while strictly enforcing the max-3 fleteros per time slot quota within a single database transaction.

#### Scenario: Schedule edit successful
- GIVEN an existing loading schedule
- WHEN a user changes the time slot to a valid slot with available quota
- THEN the system MUST update the schedule successfully
- AND the new time slot's usage count MUST increment
- AND the old time slot's usage count MUST decrement

#### Scenario: Schedule edit fails due to quota violation
- GIVEN an existing loading schedule
- WHEN a user attempts to change it to a time slot that already has 3 fleteros scheduled
- THEN the system MUST reject the update
- AND the database transaction MUST roll back
- AND the UI MUST display an error message explaining the quota violation

#### Scenario: Schedule edit fails due to invalid time slot
- GIVEN an existing loading schedule
- WHEN a user attempts to change the time slot to an inactive or non-existent slot
- THEN the system MUST reject the update with a validation error
