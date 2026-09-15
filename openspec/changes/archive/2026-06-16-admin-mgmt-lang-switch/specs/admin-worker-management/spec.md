# Admin Worker Management Specification

## Purpose
Allow administrators to list, create, and modify workers/users.

## Requirements

### Requirement: Worker CRUD Management
The system MUST allow authenticated users with 'Administrador' role to list, create, and modify workers (users) with name, role, status, and password fields.

#### Scenario: Admin views and manages workers
- GIVEN an authenticated Administrador
- WHEN listing workers in the dashboard
- THEN the system returns the worker list
- AND the admin can create, update, or toggle active status of workers

#### Scenario: Non-admin worker CRUD access denied
- GIVEN a user with 'Trabajador' or other non-admin role
- WHEN attempting to access the worker CRUD endpoints
- THEN the system MUST return a 403 Forbidden error
