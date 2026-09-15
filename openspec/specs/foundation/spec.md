# Foundation Specification

## Purpose

Establish the core infrastructure, database schema, and deployment architecture for Trindade Massas Operations.

## Requirements

### Requirement: Database Schema Availability

For a fresh installation, the system MUST initialize the approved SQLite schema without fixed credentials or demo operational data. For an existing production upgrade, startup MUST preserve all schema content and MUST NOT perform schema or data mutation unless the Production Immutability Gate authorizes a separately approved operation.
(Previously: Backend startup created all 16 PRD tables in any clean deployment without an upgrade-safety distinction.)

#### Scenario: Initializing a fresh database
- GIVEN the target is positively classified as fresh
- WHEN initialization runs
- THEN the approved schema and required references are created
- AND first-run administrator setup remains pending

#### Scenario: Starting existing production during stage one
- GIVEN an existing production database with real records
- WHEN the stage-one version starts for read-only verification
- THEN existing schema, records, users, and non-expired associated files remain intact
- AND report photos older than the documented retention period MAY be deleted by startup cleanup
- AND no automatic migration, reset, or seed write occurs
### Requirement: Docker Orchestration

The system MUST provide a Docker Compose configuration to orchestrate the API, web frontend, and data volumes.

#### Scenario: Starting the stack

- GIVEN Docker Compose is installed
- WHEN the user runs the start command
- THEN the backend API, frontend reverse proxy, and SQLite volume become available

### Requirement: Monorepo Build

The system MUST support building both frontend and backend from a single monorepo root.

#### Scenario: Building the application

- GIVEN the monorepo root
- WHEN the build command is executed
- THEN both backend and frontend artifacts are generated successfully

### Requirement: Production Immutability Gate

The active production installation MUST remain read-only for this stage. Any migration, seed, reference upgrade, account mutation, deployment, or other production write MUST be blocked until current backup integrity, isolated restore proof, rollback evidence, and explicit planning approval all pass.

#### Scenario: Every prerequisite passes
- GIVEN current recovery and rollback evidence passes
- AND the user has approved the implementation plan
- WHEN a later approved mutation requests authorization
- THEN the gate MAY authorize only that named operation and target

#### Scenario: A prerequisite is absent or failed
- GIVEN any recovery proof or approval is missing, stale, or failed
- WHEN a production mutation is requested
- THEN the operation MUST stop before writes
- AND evidence MUST identify the unmet prerequisite

#### Scenario: Stage-one verification runs
- GIVEN this first-stage specification is being verified
- WHEN checks inspect active production
- THEN checks MUST be read-only
- AND production database and non-expired associated-file fingerprints remain unchanged
- AND report photos older than the documented retention period MAY be deleted by startup cleanup

### Requirement: Installation Classification

Startup MUST distinguish a fresh installation from an existing production upgrade before initialization. Existing data, users, bootstrap state, or operational history MUST classify the target as existing; ambiguous classification MUST fail closed.

#### Scenario: Empty target is fresh
- GIVEN a new target has no prior database, users, or operational data
- WHEN startup classifies the installation
- THEN it is eligible for fresh reference initialization and first-run setup

#### Scenario: Existing target upgrades
- GIVEN the target contains any existing user or operational record
- WHEN startup classifies the installation
- THEN it is treated as an existing upgrade
- AND fresh bootstrap and demo seeding remain disabled

#### Scenario: Classification is ambiguous
- GIVEN evidence cannot prove the target is fresh or existing
- WHEN startup attempts initialization
- THEN startup MUST refuse mutating initialization
- AND request operator resolution without changing data

### Requirement: First-Stage Exit and Future Gates

Stage one MUST exit only when recovery proof passes, production fingerprints remain unchanged, canonical auth/fixture and seed/export acceptance evidence passes, and exceptions are explicitly approved. GitHub publication, broader hardening, deployment changes, and Android work MUST NOT begin under this stage authorization.

#### Scenario: Stage one passes
- GIVEN all named stage-one evidence is complete and successful
- WHEN exit review occurs
- THEN the result records evidence identities and approved exceptions
- AND separately records user approval before implementation planning advances

#### Scenario: Baseline retains failures
- GIVEN auth, fixture, seed, or export checks still fail without approved exceptions
- WHEN exit review occurs
- THEN stage one MUST remain blocked
- AND failures MUST be grouped by root class rather than waived implicitly

#### Scenario: Later-stage work is requested early
- GIVEN stage one has not passed and received explicit approval
- WHEN publication, broader hardening, deployment, or Android work is requested
- THEN that work MUST remain gated and out of scope
