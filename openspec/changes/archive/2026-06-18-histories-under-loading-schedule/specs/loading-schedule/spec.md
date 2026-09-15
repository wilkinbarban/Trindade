# Delta for Loading Schedule

## ADDED Requirements

### Requirement: Loading History and Filtering
The system MUST allow all authenticated users, including Administradores and Trabajadores, to view all non-deleted loading schedules under Cronograma de Carga > Historial de Cargamento. Records MUST be ordered newest first and paginated with a default page size of 30. Filters MUST include exact date, month, and user.

#### Scenario: Newest-first paginated history
- GIVEN more than 30 loading schedule records exist
- WHEN the user opens Historial de Cargamento
- THEN the system MUST show the newest 30 records first with pagination metadata

#### Scenario: Date, month, and user filters
- GIVEN loading schedule records exist across dates, months, and creators
- WHEN the user applies date, month, or user filters
- THEN the system MUST show only matching records

### Requirement: Loading Schedule Ownership and Edit Window
New loading schedules MUST store creator metadata. The system MUST allow edits only for the creator during the first hour after creation. Others MUST be read-only.

#### Scenario: New schedule stores creator
- GIVEN an authenticated user creates a loading schedule
- WHEN the schedule is saved
- THEN the system MUST persist the creator user

#### Scenario: Creator edits within one hour
- GIVEN a loading schedule was created by the current user less than one hour ago
- WHEN the user edits it
- THEN the system MUST allow the edit

#### Scenario: Non-creator or expired edit is read-only
- GIVEN the user is not the creator or the schedule is at least one hour old
- WHEN the user attempts mutation
- THEN the system MUST deny mutation and expose read-only state

### Requirement: Legacy Loading Schedule Ownership
Legacy loading schedules without creator metadata MUST be read-only for regular users and manageable by Administradores.

#### Scenario: Regular user views legacy schedule
- GIVEN a loading schedule has no creator metadata
- WHEN a Trabajador opens it
- THEN the system MUST expose it as read-only

#### Scenario: Admin manages legacy schedule
- GIVEN a loading schedule has no creator metadata
- WHEN an Administrador deactivates or deletes it
- THEN the system MUST allow the admin action

### Requirement: Loading History Lifecycle Controls
The system MUST expose loading history status and server-computed actions for every loading record.

#### Scenario: Admin deactivates loading record
- GIVEN an Administrador views any active loading history record
- WHEN the admin deactivates it
- THEN the record MUST become inactive and remain visible as inactive in history

#### Scenario: Admin permanently deletes loading record
- GIVEN an Administrador views any loading history record
- WHEN the admin deletes it
- THEN the system MUST physically remove it transactionally

### Requirement: Portuguese Loading History Content
Loading history views and exports MUST render registered loading domain content in Brazilian Portuguese, while surrounding UI chrome MAY remain localized.

#### Scenario: Spanish UI views loading history
- GIVEN the UI locale is ES
- WHEN the user opens loading history or exports a history record
- THEN registered loading content MUST be shown in PT-BR
