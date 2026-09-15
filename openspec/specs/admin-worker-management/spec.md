# Admin Worker Management Specification

## Purpose
Allow administrators to list, create, and modify workers/users.

## Requirements

### Requirement: Worker CRUD Management
The system MUST allow authenticated users with 'Administrador' role to list, create, and modify workers (users) with name, role, status, and password fields. The system MUST deny 'Trabajador' users access to user administration beyond their own profile actions.
(Previously: Worker CRUD was admin-only without limited self-profile rules.)

#### Scenario: Admin views and manages workers
- GIVEN an authenticated Administrador
- WHEN listing workers in the dashboard
- THEN the system returns the worker list
- AND the admin can create, update, or toggle active status of workers

#### Scenario: Worker cannot manage users
- GIVEN a user with 'Trabajador' role
- WHEN attempting to access worker list, create, update, or status endpoints
- THEN the system MUST return 403 Forbidden

#### Scenario: Worker cannot access other users
- GIVEN a user with 'Trabajador' role
- WHEN attempting to read or modify another user's data
- THEN the system MUST return 403 Forbidden
