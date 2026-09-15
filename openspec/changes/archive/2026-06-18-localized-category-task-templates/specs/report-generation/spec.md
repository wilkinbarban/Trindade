# Delta for Report Generation

## ADDED Requirements

### Requirement: Fixed Product Selection for Assai and Normal Checks

`check_assai` and `check_normal` report items MUST allow selecting mounted products from fixed lists. Assai MUST use: Nhoque Kg, Quadrada, Rolo 500, Rolo kg, Rolo 2kg, Lashana, Disco 200g, Disco 400g, Disgo 500g. Normal MUST use: Nhoque 400g, Nhoque Kg, Quadrada.

#### Scenario: Assai products selected
- GIVEN a `check_assai` item
- WHEN the user selects mounted products
- THEN only fixed Assai products are available
- AND selected products are saved

#### Scenario: Normal products selected
- GIVEN a `check_normal` item
- WHEN the user selects mounted products
- THEN only fixed Normal products are available
- AND selected products are saved

### Requirement: Selected Products Round Trip

Report create/edit/history/reload MUST preserve `selected_products` for Assai and Normal checks.

#### Scenario: Report is reopened
- GIVEN a saved report has selected products
- WHEN the report is edited or viewed in history
- THEN selected products MUST be restored

### Requirement: WhatsApp Export Language Stability

WhatsApp export text MUST remain Brazilian Portuguese regardless of UI locale.

#### Scenario: Spanish UI exports
- GIVEN the UI language is ES
- WHEN the user exports to WhatsApp
- THEN the exported text MUST be PT-BR

## MODIFIED Requirements

### Requirement: Builder Element Types
The system MUST support `check`, `temperature`, `check_assai`, and `check_normal` task element types.
(Previously: The builder supported only `check` and `temperature`.)

#### Scenario: Check Task
- GIVEN a `check` task
- WHEN viewed in builder
- THEN system displays a checkbox input

#### Scenario: Temperature Task
- GIVEN a `temperature` task
- WHEN viewed in builder
- THEN system displays a numeric temperature input

#### Scenario: Assai Check Task
- GIVEN a `check_assai` task
- WHEN viewed in builder
- THEN system displays a check input with fixed Assai product selection

#### Scenario: Normal Check Task
- GIVEN a `check_normal` task
- WHEN viewed in builder
- THEN system displays a check input with fixed Normal product selection
