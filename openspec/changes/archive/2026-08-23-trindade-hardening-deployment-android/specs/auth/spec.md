# Delta for Auth

## ADDED Requirements

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

## MODIFIED Requirements

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
