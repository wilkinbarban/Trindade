# Delta for Admin Catalog Management

## ADDED Requirements

### Requirement: Active-Locale Category and Task Names

The admin UI MUST show one category/task name field for the active app locale. The backend MUST preserve the submitted locale and fill only the missing counterpart. Auto-translation MUST use Google Cloud Translation API credentials from environment only and MUST fall back without blocking saves.

#### Scenario: PT-BR name save
- GIVEN the admin language is PT-BR
- WHEN an admin saves a category or task name
- THEN only the PT-BR name is submitted
- AND PT-BR is preserved while ES is translated or safely copied

#### Scenario: ES name save
- GIVEN the admin language is ES
- WHEN an admin saves a category or task name
- THEN only the ES name is submitted
- AND ES is preserved while PT-BR is translated or safely copied

#### Scenario: Translation unavailable
- GIVEN translation config is absent or translation fails
- WHEN an admin saves one localized name
- THEN the save MUST succeed with fallback text for the missing locale

### Requirement: Complete Category Type Support

Catalog validation MUST accept `check`, `temperature`, `check_assai`, and `check_normal`.

#### Scenario: Supported type save
- GIVEN an admin edits a category
- WHEN they choose any supported type
- THEN frontend and backend validation MUST persist it

## MODIFIED Requirements

### Requirement: Role-Gated Admin CRUD
The system MUST restrict all catalog CRUD to users with the 'Administrador' role. Category/task create/edit MUST use active-locale names.
(Previously: CRUD was role-gated without single active-locale category/task name editing.)

#### Scenario: Admin accesses catalog
- GIVEN an 'Administrador'
- WHEN they access catalog UI
- THEN they see CRUD for categories, tasks, drivers, vehicles, and slots

#### Scenario: Worker attempts CRUD
- GIVEN an 'Operador' or 'Fletero'
- WHEN they attempt a catalog CRUD operation
- THEN the system MUST return a 403 error
