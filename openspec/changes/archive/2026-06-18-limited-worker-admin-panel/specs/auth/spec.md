# Delta for Auth

## MODIFIED Requirements

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
