# Catalog Seed Data Specification

## Purpose

Defines the initial baseline dataset for the Trindade platform to provide a complete, realistic demo environment. The seed data covers categories, tasks, products, and sample users/resources required by the foundation, reports, and loading schedules modules.

## Requirements

### Requirement: Idempotent Seeding

The system MUST support running the seed data initialization multiple times without failure or duplication.

#### Scenario: Running seed script on an existing database
- GIVEN a database that already contains partial or full seed data
- WHEN the seed script is executed
- THEN the script MUST use `INSERT OR IGNORE` (or equivalent) to skip existing records
- AND the system MUST NOT duplicate records or throw unique constraint errors

### Requirement: Backward-Compatible IDs

The system MUST preserve the primary keys of historically seeded entities to prevent breaking existing End-to-End (E2E) tests.

#### Scenario: Initializing historical tasks and products
- GIVEN the database seed process is running
- WHEN tasks and products are inserted
- THEN existing tasks MUST retain IDs 1 through 8
- AND existing products MUST retain IDs 1 through 4

### Requirement: Full Operational Scope Data

The system MUST populate a full PRD-compliant set of categories, tasks, products, and operational resources. 

#### Scenario: Seeding products
- GIVEN the system initializes the database
- WHEN product entities are seeded
- THEN the products MUST include exactly: "Caixas Assaí Grandes", "Caixas Pequenas", and "Recebimento"
- AND all product names MUST be in Portuguese

#### Scenario: Seeding categories
- GIVEN the system initializes the database
- WHEN category entities are seeded
- THEN the categories MUST include: "Recebimento de Mercadorias", "Abastecimento", "Produção", and "Lotes"

#### Scenario: Seeding users and resources
- GIVEN the system initializes the database
- WHEN users and operational resources are seeded
- THEN the system MUST include at least one sample Worker ("Trabalhador") user
- AND the system MUST include sample drivers ("fleteros")
- AND the system MUST include sample company vehicles

### Requirement: Demo Database Reset

The system MUST provide documentation and a mechanism to fully reset the database for local development.

#### Scenario: Developer refreshes demo environment
- GIVEN a developer with an outdated local SQLite database
- WHEN the developer runs `make db-reset`
- THEN the system MUST recreate the database from scratch and apply the latest seed data

## Out of Scope

- New API routes for managing catalog entities.
- User interfaces (UI) or Admin CRUD screens for adding, updating, or deleting seed data.
- Database schema structure modifications (new tables or columns).
