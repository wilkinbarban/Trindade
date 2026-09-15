# Delta for report-export

## MODIFIED Requirements

### Requirement: WhatsApp Export Format
The system MUST generate a structured WhatsApp text report.
(Previously: Included products and quantities)

#### Scenario: Successful Generation
- GIVEN a saved report
- WHEN exporting
- THEN system generates text without product entities or quantities
