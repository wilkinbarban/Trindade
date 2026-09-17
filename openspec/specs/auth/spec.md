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

### Requirement: Auth Context Persistence

The frontend MUST maintain the user's authentication state across the application shell.

#### Scenario: Persistent session

- GIVEN a user logged in with a valid JWT
- WHEN the browser is refreshed
- THEN the user remains logged in without re-authenticating

### Requirement: Session Refresh and Revocation
The system MUST issue a refresh token alongside the short-lived access token, MUST persist only a hash of that token, and MUST rotate it on every refresh. Presenting a token that was already rotated away MUST revoke the entire session family. Presenting a token that was explicitly revoked by logout or a password change MUST NOT revoke any other session. Logout MUST end only the presented session. A password change MUST end every session of that user. Every refresh failure MUST answer with an identical error so a caller cannot distinguish an unknown token from an expired or a revoked one.

#### Scenario: Refresh rotates the session
- GIVEN a signed-in user holding a valid refresh token
- WHEN the client posts that token to `POST /api/auth/refresh`
- THEN the system MUST return a new access token and a new refresh token
- AND the presented refresh token MUST no longer be usable

#### Scenario: A rotated-away token is treated as leaked
- GIVEN a refresh token that was already rotated
- WHEN that previous token is presented again
- THEN the system MUST answer 401
- AND the system MUST revoke every live session in that family

#### Scenario: An explicitly revoked token is not treated as leaked
- GIVEN a session that was ended by logout
- WHEN its refresh token is presented again
- THEN the system MUST answer 401
- AND other sessions of the same user MUST remain usable

#### Scenario: Logout ends only the presented session
- GIVEN a user signed in on two devices
- WHEN one device logs out with its own refresh token
- THEN only that device's session MUST end

#### Scenario: Password change ends every session
- GIVEN a user with active sessions on more than one device
- WHEN that user changes their password
- THEN every session of that user MUST be ended

#### Scenario: The raw refresh token is never stored
- GIVEN any issued refresh token
- WHEN the session store is read
- THEN no row MUST contain the raw token, only its SHA-256 hash

#### Scenario: A refresh failure does not disclose why
- GIVEN an unknown, expired, or revoked refresh token
- WHEN it is presented
- THEN the system MUST answer with an identical 401 response in all three cases

#### Scenario: A deactivated user cannot refresh
- GIVEN a signed-in user whose account was deactivated
- WHEN their refresh token is presented
- THEN the system MUST answer 401
- AND the system MUST revoke that session family

### Requirement: User Password Change
The system MUST allow any logged-in user to change their password, enforcing at least 4 characters on the backend, and showing client-side visual recommendations (8+ characters, uppercase letter, digit) without blocking submission. The system MUST also allow users to update only their own name and display name.
(Previously: Password change existed without explicit self-profile authorization.)

#### Scenario: Successful password change
- GIVEN a logged-in user with a password of at least 4 characters
- WHEN they request to change their password
- THEN the system updates the password and returns success

#### Scenario: Update own profile name
- GIVEN a logged-in user
- WHEN they update their own name or display name
- THEN the system MUST save the change

#### Scenario: Other-user profile denied
- GIVEN a logged-in user
- WHEN they attempt to access or modify another user's profile
- THEN the system MUST return 403 Forbidden

### Requirement: Seed Credentials Update

The system MUST NOT seed fixed administrator credentials. Fresh installations MUST use first-run bootstrap; isolated tests MAY create non-production credentials through canonical fixtures.
(Previously: Fresh databases seeded [REDACTED LEGACY CREDENTIAL].)

#### Scenario: Fresh install has no fixed login
- GIVEN a fresh production database
- WHEN initialization completes before first-run setup
- THEN no fixed administrator credential authenticates
- AND the one-time setup assistant is available

#### Scenario: Existing administrator is preserved
- GIVEN an existing production database has administrator accounts
- WHEN the upgrade starts
- THEN all account credentials and roles remain unchanged
- AND no seeded account is added

### Requirement: Canonical Authentication and Fixture Truth

Runtime authentication, test fixtures, and operator documentation MUST derive roles, password policy, and outcomes from one approved contract. Tests MUST create isolated users and MUST NOT depend on production records or fixed credentials.

#### Scenario: Canonical fixture authenticates
- GIVEN an isolated contract database
- WHEN authentication tests run
- THEN expected users, roles, hashes, claims, and status codes agree
- AND production is not accessed

#### Scenario: Fixture truth drifts
- GIVEN a fixture, assertion, or document conflicts with the approved contract
- WHEN test setup validates prerequisites
- THEN setup MUST fail before feature assertions
- AND the failure MUST identify the conflicting contract field

### Requirement: Fail-Fast Fixtures

Fixtures MUST verify referenced rows before inserting users and MUST fail setup errors rather than convert them into downstream 401 responses.

#### Scenario: Fixture prerequisite is absent
- GIVEN an isolated database lacks a required role or reference
- WHEN an authentication fixture is installed
- THEN setup MUST stop with the missing prerequisite identified
- AND no test request MUST run with a partial fixture

#### Scenario: Valid fixture baseline
- GIVEN all fixture prerequisites exist
- WHEN the fixture is installed
- THEN all expected users can authenticate according to the canonical contract
- AND unauthorized cases remain testable

### Requirement: Secure First-Run Administrator Bootstrap

A fresh installation without users MUST expose a one-time assistant that creates the first active Administrador. Bootstrap MUST close atomically after success. Existing upgrades MUST bypass setup and preserve all accounts.

#### Scenario: Fresh installation creates first administrator
- GIVEN a fresh database has no users and bootstrap is incomplete
- WHEN an operator submits valid unique administrator details
- THEN exactly one active Administrador is created
- AND subsequent bootstrap attempts are denied

#### Scenario: Existing production upgrade starts normally
- GIVEN an installation contains users or completed bootstrap state
- WHEN the upgraded service starts
- THEN login remains available
- AND bootstrap MUST NOT appear, reset, replace, or modify any account

#### Scenario: Concurrent bootstrap attempts
- GIVEN bootstrap is available
- WHEN two valid creation requests race
- THEN at most one first administrator is created
- AND the other request is rejected without partial state

### Requirement: Authentication Secrets and Passwords

Production MUST require an explicit strong signing secret and MUST NOT use a fallback. Passwords MUST satisfy policy, be hashed before persistence, never be logged or returned, and use secret-safe input. Test secrets MUST be isolated.

#### Scenario: Production secret is absent
- GIVEN production mode lacks a valid signing secret
- WHEN the backend starts
- THEN startup MUST fail with remediation guidance
- AND no default secret MUST be selected

#### Scenario: Password handling succeeds
- GIVEN bootstrap or password change receives a policy-compliant password
- WHEN the request succeeds
- THEN only a non-reversible password hash is persisted
- AND responses and logs omit plaintext and secret values

#### Scenario: Weak password is submitted
- GIVEN a password violates the approved policy
- WHEN bootstrap, user creation, or password change is attempted
- THEN the request MUST fail consistently
- AND no credential state is changed
