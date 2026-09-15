# Extra Exports Specification

## Purpose
Provide TXT and PDF export capabilities for reports and loading schedules.

## Requirements

### Requirement: PDF Generation
The system MUST generate PDF exports for reports and loading schedules using a frontend library, capturing the UI representation.

#### Scenario: User exports report to PDF
- GIVEN a user viewing a report
- WHEN they click the "Export to PDF" button
- THEN the system MUST generate and download a PDF file
- AND the PDF MUST contain the report data formatted in Portuguese (pt-BR)

### Requirement: TXT Generation
The system MUST generate plain text (TXT) exports using UTF-8 BOM encoding for compatibility.

#### Scenario: User exports schedule to TXT
- GIVEN a user viewing a loading schedule
- WHEN they click the "Export to TXT" button
- THEN the system MUST generate and download a TXT file
- AND the TXT file MUST use UTF-8 BOM encoding
- AND the content MUST be formatted in Portuguese (pt-BR)
