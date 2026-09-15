# Auth Specification

## Purpose

Secure the application with JWT authentication, a login interface, and Role-Based Access Control (RBAC).

## Requirements

### Requirement: User Login

The system MUST authenticate users and issue a valid JWT upon providing correct credentials.

#### Scenario: Successful login

- GIVEN valid user credentials
- WHEN the user submits the login form
- THEN the system returns a valid JWT
- AND the frontend redirects the user to the dashboard

#### Scenario: Invalid login

- GIVEN invalid user credentials
- WHEN the user submits the login form
- THEN the system displays an authentication error
- AND denies access

### Requirement: Role Guards

The system MUST enforce access control distinguishing between 'Administrador' and 'Trabajador' roles based on JWT claims.

#### Scenario: Administrador access

- GIVEN an authenticated Administrador
- WHEN accessing an admin-only endpoint
- THEN the request succeeds

#### Scenario: Trabajador access block

- GIVEN an authenticated Trabajador
- WHEN accessing an admin-only endpoint
- THEN the system returns a 403 Forbidden error

### Requirement: Auth Context Persistence

The frontend MUST maintain the user's authentication state across the application shell.

#### Scenario: Persistent session

- GIVEN a user logged in with a valid JWT
- WHEN the browser is refreshed
- THEN the user remains logged in without re-authenticating
