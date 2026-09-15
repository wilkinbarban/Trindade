# Delta for loading-export

## MODIFIED Requirements

### Requirement: WhatsApp Format Generation
The system MUST generate a Portuguese schedule summary for WhatsApp.
(Previously: Driver and vehicle formatted separately)

#### Scenario: Generate export text
- GIVEN schedules with 'casa' (with vehicle) and 'fletero' drivers
- WHEN exporting
- THEN system generates tabular text with driver name
- AND includes vehicle plate only for 'casa' drivers
