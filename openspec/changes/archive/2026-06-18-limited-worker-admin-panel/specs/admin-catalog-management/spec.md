# Delta for Admin Catalog Management

## MODIFIED Requirements

### Requirement: Role-Gated Admin CRUD
The system MUST restrict full catalog CRUD to users with the 'Administrador' role. The system MUST allow 'Trabajador' users to access a limited panel for creating and editing only their own tasks and fletero drivers. Workers MUST NOT delete, deactivate, or modify records they did not create.
(Previously: CRUD was reserved for Administrador only.)

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
