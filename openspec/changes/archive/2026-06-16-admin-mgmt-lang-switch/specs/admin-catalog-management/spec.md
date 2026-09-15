# Delta for Admin Catalog Management

## MODIFIED Requirements

### Requirement: Soft Deletion and Toggle
The system SHOULD use soft deletion or active toggling for catalog items to preserve historical data references, but MUST support physical deletion of categories, tasks, products, drivers, and vehicles when no database constraints are violated. If a constraint is violated, the backend MUST return a friendly 400 error status instead of crashing.
(Previously: The system only used soft deletion or active toggling without physical deletion.)

#### Scenario: Admin toggles entity status
- GIVEN an active driver or vehicle with historical records
- WHEN an admin toggles its status to inactive
- THEN the system MUST NOT delete the record from the database
- AND it MUST hide the entity from active selection lists
- AND past reports referencing the entity MUST still display its data correctly

#### Scenario: Admin physically deletes unreferenced entity
- GIVEN a category, task, product, driver, or vehicle that is not referenced by any other record
- WHEN an admin requests to physically delete it
- THEN the system physically removes the record from the database
- AND returns success

#### Scenario: Admin physically deletes referenced entity (constraint violation)
- GIVEN a catalog entity that is referenced elsewhere (causing SQLITE_CONSTRAINT)
- WHEN an admin requests to physically delete it
- THEN the backend catches the reference constraint violation
- AND returns a 400 Bad Request error to the user without crashing
