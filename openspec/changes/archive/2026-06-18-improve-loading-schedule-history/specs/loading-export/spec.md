# Delta for Loading Export

## MODIFIED Requirements

### Requirement: WhatsApp Format Generation
The system MUST generate a Brazilian Portuguese WhatsApp summary for a persisted creation-date batch. The header date MUST be the next day after the persisted batch date. The text MUST use the requested visual format, sorted by time, include `🚚 {vehicle_plate}` only for 'casa' drivers, and include `📌 Total de carregamentos: *N*`.
(Previously: export displayed the persisted date and used a simple table.)

#### Scenario: Generate next-day export text
- GIVEN a batch stored under 2026-06-17 with six rows
- WHEN exporting the batch
- THEN the date MUST be `Quinta-feira • 18/06/2026`
- AND include `📌 Total de carregamentos: *6*`

#### Scenario: Format rows
- GIVEN casa and fletero rows
- WHEN exporting
- THEN rows MUST show `clock emoji time │ driver`
- AND only casa rows append `🚚 {vehicle_plate}`

### Requirement: Export Interface
The system MUST let users copy, TXT-download, and PDF-download the generated export for the current batch date.
(Previously: export used the navigated selected date.)

#### Scenario: Access export modal
- GIVEN Cronograma de Carga is open
- WHEN export is clicked
- THEN the Portuguese text for today's persisted batch is shown
