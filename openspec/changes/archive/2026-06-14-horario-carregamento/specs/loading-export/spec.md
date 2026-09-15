# Loading Export Specification

## Purpose
WhatsApp export text generation for daily loading schedules.

## Requirements

### Requirement: WhatsApp Format Generation
The system MUST generate a schedule summary formatted for WhatsApp distribution.

#### Scenario: Generate export text
- GIVEN a date with multiple schedule entries across different time slots
- WHEN the user requests the WhatsApp export
- THEN the system generates a tabular text representation
- AND sorts the entries in ascending order by time slot
- AND outputs all text and headers in Portuguese (pt-BR)

#### Scenario: Export empty schedule
- GIVEN a date with no schedule entries
- WHEN the user requests the WhatsApp export
- THEN the system indicates there are no scheduled loadings for that date

### Requirement: Export Interface
The system MUST provide a UI mechanism to access and copy the export.

#### Scenario: Access export modal
- GIVEN the user is viewing a schedule
- WHEN the user clicks the export button
- THEN the system displays a modal containing the generated Portuguese text
- AND provides a "Copy to Clipboard" action
