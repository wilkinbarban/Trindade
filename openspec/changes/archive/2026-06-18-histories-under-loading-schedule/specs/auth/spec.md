# Delta for Auth

## MODIFIED Requirements

### Requirement: Role Guards
The system MUST enforce access control distinguishing between 'Administrador' and 'Trabajador' roles based on JWT claims. Deactivation and physical deletion of report history and loading history records MUST be admin-only operations.
(Previously: Role guards covered generic admin-only endpoint access only.)

#### Scenario: Administrador access
- GIVEN an authenticated Administrador
- WHEN accessing an admin-only endpoint
- THEN the request succeeds

#### Scenario: Trabajador access block
- GIVEN an authenticated Trabajador
- WHEN accessing an admin-only endpoint
- THEN the system returns a 403 Forbidden error

#### Scenario: Admin deactivates or deletes history records
- GIVEN an authenticated Administrador
- WHEN deactivating or physically deleting a report or loading history record
- THEN the system MUST authorize the request

#### Scenario: Trabajador cannot deactivate or delete history records
- GIVEN an authenticated Trabajador
- WHEN deactivating or physically deleting a report or loading history record
- THEN the system MUST return 403 Forbidden and leave the record unchanged
