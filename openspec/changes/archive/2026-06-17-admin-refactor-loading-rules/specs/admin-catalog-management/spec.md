# Delta for admin-catalog-management

## MODIFIED Requirements

### Requirement: Role-Gated Admin CRUD
The system MUST restrict all catalog CRUD to users with the 'Administrador' role.
(Previously: CRUD included products)

#### Scenario: Admin accesses catalog
- GIVEN an 'Administrador'
- WHEN they access catalog UI
- THEN they see CRUD for categories, tasks, drivers, vehicles, and slots

#### Scenario: Worker attempts CRUD
- GIVEN an 'Operador' or 'Fletero'
- WHEN they attempt a catalog CRUD operation
- THEN the system MUST return a 403 error

### Requirement: Soft Deletion and Toggle
The system MUST support physical deletion of categories, tasks, drivers, and vehicles when no constraints are violated, returning 400 on violation.
(Previously: Included products)

#### Scenario: Admin physically deletes unreferenced entity
- GIVEN an unreferenced category, task, driver, or vehicle
- WHEN deleted by admin
- THEN the system removes it and returns success

#### Scenario: Admin physically deletes referenced entity
- GIVEN a referenced catalog entity
- WHEN deleted by admin
- THEN the backend returns a 400 error

## ADDED Requirements

### Requirement: Dynamic Task Typing and Sorting
Tasks MUST inherit their type from the parent category. Order MUST be dynamic by category sort order then task ID.

#### Scenario: Dynamic task properties
- GIVEN tasks in categories
- WHEN listed or category updated
- THEN tasks inherit category type and sort by category order, then task ID

### Requirement: Driver Type Configuration
Drivers MUST be configured as 'casa' or 'fletero'.

#### Scenario: Set driver type
- GIVEN an admin creating/editing a driver
- WHEN selecting 'casa' or 'fletero' type
- THEN the system persists the driver type
