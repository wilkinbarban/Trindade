# Admin Catalog Management Specification

## Purpose
Manage system entities (categories, tasks, products, drivers, vehicles, time slots) via role-gated admin UI and endpoints.

## Requirements

### Requirement: Role-Gated Admin CRUD
The system MUST restrict all catalog CRUD operations (Create, Read, Update, Delete/Toggle) to users with the 'Administrador' role.

#### Scenario: Admin accesses catalog
- GIVEN a user with the 'Administrador' role
- WHEN they access the catalog management UI
- THEN they SHOULD see full CRUD options for categories, tasks, products, drivers, vehicles, and time slots

#### Scenario: Worker attempts to access catalog CRUD
- GIVEN a user with the 'Operador' or 'Fletero' role
- WHEN they attempt to perform a CRUD operation on catalog entities
- THEN the system MUST return a 403 Forbidden error
- AND the UI MUST hide the admin management links

### Requirement: Soft Deletion and Toggle
The system SHOULD use soft deletion or active toggling for catalog items to preserve historical data references.

#### Scenario: Admin toggles entity status
- GIVEN an active driver or vehicle with historical records
- WHEN an admin toggles its status to inactive
- THEN the system MUST NOT delete the record from the database
- AND it MUST hide the entity from active selection lists
- AND past reports referencing the entity MUST still display its data correctly
