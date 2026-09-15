# Production Data Recovery Specification

## Purpose

Establish recoverability evidence before any change can affect the active production installation.

## Requirements

### Requirement: Recovery Inventory

Before backup, the operator MUST record the SQLite database, journal/WAL files, uploaded or generated assets, mounted data, configuration needed for restoration, exclusions, source identifiers, sizes, and timestamps. Unknown or inaccessible items MUST fail the inventory.

#### Scenario: Complete inventory
- GIVEN the active production installation is readable
- WHEN recovery inventory runs
- THEN every required data item is recorded with source identity, size, and timestamp
- AND exclusions and associated files are explicit

#### Scenario: Inventory cannot prove completeness
- GIVEN a required path is unknown, missing, or inaccessible
- WHEN inventory is evaluated
- THEN the recovery gate MUST fail
- AND production MUST remain unchanged

### Requirement: SQLite-Consistent Backup

The backup MUST use a SQLite-consistent snapshot while the service may be active, MUST capture associated files from the same recovery point, and MUST NOT treat a raw live database copy alone as sufficient.

#### Scenario: Online backup succeeds
- GIVEN the inventory is complete and production remains active
- WHEN the backup is created
- THEN the database snapshot is SQLite-consistent
- AND associated files and a timestamped manifest belong to the recovery set

#### Scenario: Snapshot or file capture fails
- GIVEN backup capture has started
- WHEN any database or associated-file operation fails
- THEN the set MUST be marked unusable
- AND no mutation gate MAY open

### Requirement: Backup Integrity

Each recovery set MUST pass database integrity checks, manifest completeness checks, file checksums, and secret-safe storage checks. Evidence MUST identify commands, timestamps, results, and recovery-set identity without exposing secret values.

#### Scenario: Integrity passes
- GIVEN a completed recovery set
- WHEN integrity verification runs
- THEN all database checks, manifest entries, and checksums pass
- AND durable acceptance evidence is recorded

#### Scenario: Corruption is detected
- GIVEN a missing, changed, or corrupt backup item
- WHEN verification runs
- THEN verification MUST fail closed
- AND the set MUST NOT authorize restore or production mutation

### Requirement: Isolated Restore Proof

The recovery set MUST be restored to an isolated, non-production target. Proof MUST include successful database opening, integrity checks, schema identity, representative record counts, associated-file availability, and application-level read verification; it MUST NOT write to production.

#### Scenario: Restore is proven
- GIVEN an integrity-approved recovery set and isolated target
- WHEN restoration and read verification complete
- THEN restored data and associated files match the manifest
- AND production paths and records remain unchanged

#### Scenario: Restore leaks toward production
- GIVEN restoration cannot prove target isolation
- WHEN restore is requested
- THEN restoration MUST stop before writing
- AND the recovery gate MUST remain closed

### Requirement: Rollback Evidence

Before any later mutation, a rollback record MUST name the approved recovery set, restore procedure, responsible approver, rollback trigger, verification steps, and immutable pre-change evidence. A backup without successful isolated restore MUST NOT qualify.

#### Scenario: Rollback is actionable
- GIVEN backup integrity and isolated restore have passed
- WHEN rollback readiness is reviewed
- THEN the record identifies executable recovery and verification steps
- AND links all acceptance evidence to one recovery set

#### Scenario: Evidence is stale or incomplete
- GIVEN production changed after the recovery point or evidence is incomplete
- WHEN mutation approval is requested
- THEN approval MUST be denied until a current recovery set passes all gates
