# Delta for Loading Schedule

## MODIFIED Requirements

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
- GIVEN batches exist across dates, months, and creators
- WHEN filters are applied
- THEN only matching batches are shown

### Requirement: Loading History Lifecycle Controls
The system MUST expose status and server-computed actions for every loading batch. Batch deactivate/delete MUST affect all rows in that batch transactionally.
(Previously: lifecycle controls applied to individual loading records.)

#### Scenario: Admin deactivates loading batch
- GIVEN an active loading batch
- WHEN an Administrador deactivates it
- THEN all active records in that batch MUST become inactive

#### Scenario: Admin deletes loading batch
- GIVEN a loading batch
- WHEN an Administrador deletes it
- THEN all records in that batch MUST be removed transactionally
