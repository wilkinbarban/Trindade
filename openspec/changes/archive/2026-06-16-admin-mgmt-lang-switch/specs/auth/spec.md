# Delta for Auth

## ADDED Requirements

### Requirement: User Password Change
The system MUST allow any logged-in user to change their password, enforcing at least 4 characters on the backend, and showing client-side visual recommendations (8+ characters, uppercase letter, digit) without blocking submission.

#### Scenario: Successful password change
- GIVEN a logged-in user with a password of at least 4 characters
- WHEN they request to change their password
- THEN the system updates the password and returns success

#### Scenario: Visual suggestion shown
- GIVEN a logged-in user entering a new password not meeting 8+ chars, uppercase, and digit
- WHEN typing in the change password form
- THEN the UI displays a visual recommendation warning but does not disable the submit button

### Requirement: Seed Credentials Update
The historical change required [REDACTED LEGACY CREDENTIAL].

#### Scenario: Seed login success
- GIVEN a freshly seeded database
- WHEN the user logs in with [REDACTED LEGACY CREDENTIAL]
- THEN the authentication succeeds and returns an 'Administrador' JWT
