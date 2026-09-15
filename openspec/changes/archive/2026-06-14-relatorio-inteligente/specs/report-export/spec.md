# Report Export Specification

## Purpose

Generador Inteligente text engine formatting reports for WhatsApp export.

## Requirements

### Requirement: WhatsApp Export Format

The system MUST generate a structured text report formatted for WhatsApp.

#### Scenario: Successful Generation

- GIVEN a saved report with completed tasks
- WHEN the user requests a WhatsApp export
- THEN the system MUST generate text with emoji section headers, bulleted lists for tasks, and clear spacing

### Requirement: Language Enforcement

The exported report text MUST be strictly in Brazilian Portuguese (pt-BR).

#### Scenario: UI Language Independence

- GIVEN the user's UI is set to Spanish or English
- WHEN the user requests a WhatsApp export
- THEN the system MUST generate the exported text in Portuguese using `name_pt` fields and Portuguese static strings

### Requirement: Excluded Capabilities

The system MUST NOT provide PDF, TXT, or Photo export options at this time.

#### Scenario: Export Options

- GIVEN a saved report
- WHEN the user views the export options
- THEN the system MUST ONLY provide the WhatsApp export option
