# Visual Audit Specification

## Purpose

Provide a centralized audit logging mechanism for tracking critical system actions (e.g., creates, updates, deletes, logins) and expose an administrative UI to review these logs.

## Requirements

### Requirement: Centralized Audit Logging

The system MUST record critical operations across backend services into the `audit_logs` table.

#### Scenario: Successful inline logging
- GIVEN a backend service operation completes successfully (e.g., photo uploaded, report created)
- WHEN the service calls the centralized `audit.log()` utility
- THEN a new record is inserted into `audit_logs` containing the action, entity, user ID, and timestamp.

### Requirement: Audit Log Querying

The system MUST expose a paginated endpoint to retrieve audit logs, restricted to users with the ADMIN role.

#### Scenario: Successful query by admin
- GIVEN an authenticated ADMIN user
- WHEN the user requests audit logs with pagination parameters
- THEN the system returns the corresponding page of audit logs, ordered by newest first, and total count.

#### Scenario: Unauthorized access by non-admin
- GIVEN an authenticated DRIVER or unauthenticated user
- WHEN the user requests the audit logs endpoint
- THEN the system rejects the request with a 403 Forbidden or 401 Unauthorized error.

#### Scenario: Empty logs page
- GIVEN an authenticated ADMIN user
- WHEN the user requests a page number that exceeds available logs
- THEN the system returns an empty array and total count.

### Requirement: Admin Visual Audit UI

The system MUST provide a standalone UI page for administrators to browse and filter audit logs.

#### Scenario: Admin views audit dashboard
- GIVEN an authenticated ADMIN user
- WHEN the user navigates to `/admin/audit`
- THEN the system displays a paginated table of audit logs.

#### Scenario: Non-admin attempts to view audit UI
- GIVEN an authenticated non-ADMIN user
- WHEN the user attempts to navigate to `/admin/audit`
- THEN the system denies access and redirects the user (or shows an unauthorized message).
