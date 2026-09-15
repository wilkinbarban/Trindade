# Loading Schedule Specification

## Purpose
Core scheduling CRUD, driver assignment, and quota enforcement for daily loading operations.

## Requirements

### Requirement: Schedule Management
The system MUST manage one active loading batch per creation date. Entries MUST require a driver. A vehicle is required only for 'casa'. The same active driver MUST NOT be assigned more than once in the same creation-date batch.
(Previously: duplicate driver validation was limited to the same date and same slot.)

#### Scenario: Add Casa schedule
- GIVEN today's batch, active 'casa' driver, and vehicle
- WHEN scheduled
- THEN system records driver and vehicle under today's date

#### Scenario: Add Fletero schedule
- GIVEN today's batch and active 'fletero' driver
- WHEN scheduled
- THEN system records driver only and no vehicle

#### Scenario: Reject duplicate driver in another slot
- GIVEN a driver is active in today's batch at 04:00
- WHEN assigning the same driver at 05:00
- THEN the system MUST reject the assignment

### Requirement: Fletero Quota Validation
The system MUST restrict 'fletero' drivers to maximum 3 in any rolling 60-minute window per date.
(Previously: Limit was 3 fleteros per discrete slot)

#### Scenario: Assignment within quota
- GIVEN 2 active fleteros in a 60-minute window
- WHEN 3rd fletero scheduled
- THEN system accepts assignment

#### Scenario: Rejection when quota exceeded
- GIVEN 3 active fleteros in a 60-minute window
- WHEN 4th fletero scheduled
- THEN system MUST reject the assignment

### Requirement: Driver Listing and Creation
The system MUST provide capabilities to list and quickly create fleteros. Worker-created fleteros MUST be available as universal scheduling options.
(Previously: Driver listing/creation did not state worker-owned fleteros.)

#### Scenario: List active fleteros
- GIVEN the database contains active and inactive fleteros
- WHEN the system requests the driver list
- THEN the system returns only active fleteros

#### Scenario: Create a new fletero
- GIVEN a driver name
- WHEN the user submits the new fletero form
- THEN the system creates an active fletero
- AND makes them immediately available for scheduling

#### Scenario: Worker-created fletero is usable
- GIVEN a fletero was created by a worker
- WHEN any user opens scheduling
- THEN the fletero MUST appear as a valid option

### Requirement: Schedule Grid Interface
The system MUST show today's grid with quota indicators and no date navigation. Driver selectors MUST hide drivers already assigned anywhere in today's active batch.
(Previously: the page allowed date navigation and hid only current-slot drivers.)

#### Scenario: View today's batch only
- GIVEN the user opens Cronograma de Carga
- WHEN the page loads
- THEN schedules load for today's São Paulo date
- AND previous/next/today controls are not shown

#### Scenario: Hide assigned drivers across slots
- GIVEN André is assigned at 04:00
- WHEN opening the 05:00 selector
- THEN André MUST NOT appear as available

### Requirement: Loading History and Filtering
The system MUST show non-deleted loading batches under Historial de Cargamento. History MUST show one item per creation date, newest first, paginated by 30, with filters like Historial de Reportes.
(Previously: history showed one row per driver schedule record.)

#### Scenario: Batch history
- GIVEN multiple loading days exist
- WHEN history opens
- THEN the system MUST show one item per day with total loading count

#### Scenario: Date, month, and user filters
- GIVEN loading schedule records exist across dates, months, and creators
- WHEN the user applies date, month, or user filters
- THEN only matching batches are shown

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
The system MUST expose status and server-computed actions for every loading batch. Batch deactivate/delete MUST affect all rows in that batch transactionally.
(Previously: lifecycle controls applied to individual loading records.)

#### Scenario: Admin deactivates loading batch
- GIVEN an active loading batch
- WHEN the admin deactivates it
- THEN all active records in that batch MUST become inactive

#### Scenario: Admin deletes loading batch
- GIVEN a loading batch
- WHEN the admin deletes it
- THEN all records in that batch MUST be removed transactionally

### Requirement: Portuguese Loading History Content
Loading history views and exports MUST render registered loading domain content in Brazilian Portuguese, while surrounding UI chrome MAY remain localized.

#### Scenario: Spanish UI views loading history
- GIVEN the UI locale is ES
- WHEN the user opens loading history or exports a history record
- THEN registered loading content MUST be shown in PT-BR
