# Delta for loading-schedule

## MODIFIED Requirements

### Requirement: Schedule Management
The system MUST allow managing schedules by date. All entries MUST require a driver. A vehicle is required only if the driver type is 'casa'.
(Previously: Driver not always required; casa drivers and vehicles weren't linked)

#### Scenario: Add Casa schedule
- GIVEN a slot, active 'casa' driver, and vehicle
- WHEN scheduled
- THEN system records entry with both driver and vehicle

#### Scenario: Add Fletero schedule
- GIVEN a slot and active 'fletero' driver
- WHEN scheduled
- THEN system records entry with driver only and no vehicle

#### Scenario: Reject Casa schedule without vehicle
- GIVEN an active 'casa' driver
- WHEN scheduled without a vehicle
- THEN system MUST reject the assignment

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

### Requirement: Schedule Grid Interface
The system MUST show a visual grid with quota indicators.
(Previously: Quotas shown per discrete slot)

#### Scenario: View quota indicators
- GIVEN the schedule grid
- WHEN viewing slots
- THEN system displays active fleteros in rolling 60-minute window versus limit of 3
