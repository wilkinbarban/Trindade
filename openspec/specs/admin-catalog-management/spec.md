# Admin Catalog Management Specification

## Purpose
Manage system entities (categories, tasks, products, drivers, vehicles, time slots) via role-gated admin UI and endpoints.

## Requirements

### Requirement: Role-Gated Admin CRUD
The system MUST restrict all catalog CRUD to users with the 'Administrador' role. The system MUST allow 'Trabajador' users to access a limited panel for creating and editing only their own tasks and fletero drivers. Workers MUST NOT delete, deactivate, or modify records they did not create.
(Previously: CRUD was role-gated without single active-locale category/task name editing.)

#### Scenario: Admin accesses full catalog
- GIVEN an authenticated 'Administrador'
- WHEN they access catalog UI or endpoints
- THEN the system MUST allow full CRUD for categories, tasks, drivers, vehicles, and slots

#### Scenario: Worker accesses limited panel
- GIVEN an authenticated 'Trabajador'
- WHEN they access the limited panel for tasks or drivers
- THEN the system MUST allow create/edit only for owned tasks and owned fletero drivers
- AND the system MUST deny delete, deactivate, or cross-owner edits

#### Scenario: Worker attempts full catalog CRUD
- GIVEN an authenticated 'Trabajador'
- WHEN they attempt category, vehicle, or slot CRUD
- THEN the system MUST return 403 Forbidden

### Requirement: Soft Deletion and Toggle
The system MUST support physical deletion of categories, tasks, drivers, and vehicles when no constraints are violated, returning 400 on violation. Only 'Administrador' users MUST be able to delete or deactivate catalog records.
(Previously: Included products.)

#### Scenario: Admin physically deletes unreferenced entity
- GIVEN an unreferenced category, task, driver, or vehicle
- WHEN deleted by admin
- THEN the system removes it and returns success

#### Scenario: Worker cannot delete or deactivate
- GIVEN an authenticated 'Trabajador'
- WHEN they attempt to delete or deactivate any catalog record
- THEN the system MUST return 403 Forbidden

### Requirement: Dynamic Task Typing and Sorting
Tasks MUST inherit their type from the parent category. Order MUST be dynamic by category sort order then task ID.

#### Scenario: Dynamic task properties
- GIVEN tasks in categories
- WHEN listed or category updated
- THEN tasks inherit category type and sort by category order, then task ID

### Requirement: Driver Type Configuration
Drivers MUST be configured as 'casa' or 'fletero' for Administradores. Workers MUST create and edit only 'fletero' drivers.

#### Scenario: Admin sets driver type
- GIVEN an admin creating or editing a driver
- WHEN selecting 'casa' or 'fletero' type
- THEN the system persists the driver type

#### Scenario: Worker creates fletero only
- GIVEN a worker creating a driver
- WHEN they choose driver type
- THEN only 'fletero' creation and editing is allowed
- AND 'casa' is rejected

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
