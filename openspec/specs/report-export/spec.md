# Report Export Specification

## Purpose

Generador Inteligente text engine formatting reports for WhatsApp export.

## Requirements

### Requirement: WhatsApp Export Format
The system MUST generate a structured WhatsApp text report.
The system MUST include downloadable photo links for report photos and MUST NOT require binary clipboard copy for images.
(Previously: Included products and quantities)

#### Scenario: Successful Generation
- GIVEN a saved report
- WHEN exporting
- THEN system generates text without product entities or quantities
- AND the export includes downloadable links for each report photo

### Requirement: Language Enforcement

The exported report text MUST be strictly in Brazilian Portuguese (pt-BR).

#### Scenario: UI Language Independence

- GIVEN the user's UI is set to Spanish or English
- WHEN the user requests a WhatsApp export
- THEN the system MUST generate the exported text in Portuguese using `name_pt` fields and Portuguese static strings

### Requirement: Excluded Capabilities

The system MUST NOT provide PDF or TXT export options at this time.
The system MUST NOT require binary clipboard copy for photo sharing.

#### Scenario: Export Options

- GIVEN a saved report
- WHEN the user views the export options
- THEN the system MUST ONLY provide the WhatsApp export option
