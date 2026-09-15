# Delta for Report Export

## MODIFIED Requirements

### Requirement: WhatsApp Export Format

The system MUST generate a structured WhatsApp text report.
The system MUST include downloadable photo links for report photos and MUST NOT require binary clipboard copy for images.
(Previously: Included products and quantities)

#### Scenario: Successful Generation
- GIVEN a saved report
- WHEN exporting
- THEN system generates text without product entities or quantities
- AND the export includes downloadable links for each report photo

### Requirement: Excluded Capabilities

The system MUST NOT provide PDF or TXT export options at this time.
The system MUST NOT require binary clipboard copy for photo sharing.
(Previously: The system excluded PDF, TXT, and Photo export options entirely.)

#### Scenario: Export Options

- GIVEN a saved report
- WHEN the user views the export options
- THEN the system MUST ONLY provide the WhatsApp export option
- AND the WhatsApp export text MUST include downloadable photo links when photos exist
