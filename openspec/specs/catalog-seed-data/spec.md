# Catalog Seed Data Specification

## Purpose

Defines the initial baseline dataset for the Trindade platform to provide a complete, realistic demo environment. The seed data covers categories, tasks, products, and sample users/resources required by the foundation, reports, and loading schedules modules.

## Requirements

### Requirement: Idempotent Seeding

Fresh-install initialization MUST be repeatable without duplication. Existing production upgrades MUST NOT run demo seeding and MUST use preservation-safe reference upgrades instead.
(Previously: One seed operation could run against any partial or full database using ignore-on-conflict behavior.)

#### Scenario: Re-running fresh initialization
- GIVEN a database positively classified as a fresh installation
- WHEN fresh seed initialization runs again
- THEN reference records MUST NOT duplicate
- AND no fixed user credentials or sample operational history are added

#### Scenario: Seed requested against existing production
- GIVEN a database is classified as an existing production installation
- WHEN fresh or demo seed initialization is requested
- THEN the operation MUST fail before writes
- AND direct the operator to the preservation-safe upgrade path
### Requirement: Backward-Compatible IDs

The system MUST preserve the primary keys of historically seeded entities to prevent breaking existing End-to-End (E2E) tests.

#### Scenario: Initializing historical tasks and products
- GIVEN the database seed process is running
- WHEN tasks and products are inserted
- THEN existing tasks MUST retain IDs 1 through 8
- AND existing products MUST retain IDs 1 through 4

### Requirement: Full Operational Scope Data

The system MAY provide realistic demo categories, tasks, products, users, drivers, and vehicles only in explicitly selected non-production demo environments. Fresh production installation MUST receive required reference data only; existing production MUST never receive demo users or resources through upgrade.
(Previously: Every initialized database received a complete PRD-style demo dataset.)

#### Scenario: Explicit demo environment is seeded
- GIVEN an empty non-production database explicitly classified as demo
- WHEN demo seeding runs
- THEN the documented demo dataset is created
- AND credentials are clearly non-production and isolated

#### Scenario: Production initialization excludes demo data
- GIVEN a fresh production installation
- WHEN required reference initialization completes
- THEN no sample users, drivers, vehicles, or operational records are created
### Requirement: Demo Database Reset

The system MUST provide documentation and a mechanism to fully reset the database for local development.

#### Scenario: Developer refreshes demo environment
- GIVEN a developer with an outdated local SQLite database
- WHEN the developer runs `make db-reset`
- THEN the system MUST recreate the database from scratch and apply the latest seed data

### Requirement: Preservation-Safe Reference Upgrades

Existing production upgrades MUST distinguish required reference additions from demo seed data. They MUST add only versioned, missing reference records, preserve stable identities and user-modified values, and MUST NOT delete, overwrite, reseed, or renumber operational data.

#### Scenario: Existing production receives a missing reference
- GIVEN recovery gates passed and an existing database lacks a required reference
- WHEN the approved reference upgrade runs
- THEN only the missing reference is added with stable identity
- AND existing rows and relationships remain unchanged

#### Scenario: Existing value conflicts with packaged seed
- GIVEN production contains a user-modified or related catalog row
- WHEN upgrade compares it with packaged data
- THEN production data MUST be preserved
- AND the conflict MUST be reported for explicit resolution

#### Scenario: Recovery gate is closed
- GIVEN backup or isolated restore evidence has not passed
- WHEN a reference upgrade is requested
- THEN the upgrade MUST refuse to write
- AND produce evidence that production was not mutated

### Requirement: Export Contract Alignment

Report and loading exports MUST resolve catalog labels and relationships from the same canonical schema and reference truth used by runtime and fixtures. WhatsApp export text MUST remain Brazilian Portuguese. Missing required references MUST fail explicitly rather than emit misleading substituted data.

#### Scenario: Canonical export succeeds
- GIVEN valid canonical catalog and operational records
- WHEN a report or loading export is generated
- THEN labels, relationships, ordering, and totals match the approved contract
- AND WhatsApp text is Brazilian Portuguese

#### Scenario: Required export reference is missing
- GIVEN an export row has a missing required catalog relationship
- WHEN export generation is requested
- THEN generation MUST fail with the missing reference identified
- AND no misleading final export MUST be presented

### Requirement: Seed and Upgrade Acceptance Evidence

Fresh-seed and existing-upgrade verification MUST record database classification, dataset/version identity, inserted/skipped/conflicting rows, invariant queries, and before/after counts. Evidence MUST demonstrate that production records were preserved.

#### Scenario: Existing upgrade evidence passes
- GIVEN an approved reference upgrade completed
- WHEN acceptance evidence is evaluated
- THEN before/after invariants prove no existing row was altered or removed
- AND every inserted reference is attributable to an approved version

## Out of Scope

- New API routes for managing catalog entities.
- User interfaces (UI) or Admin CRUD screens for adding, updating, or deleting seed data.
- Database schema structure modifications (new tables or columns).
