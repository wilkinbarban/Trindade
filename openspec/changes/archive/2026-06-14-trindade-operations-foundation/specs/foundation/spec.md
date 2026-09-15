# Foundation Specification

## Purpose

Establish the core infrastructure, database schema, and deployment architecture for Trindade Massas Operations.

## Requirements

### Requirement: Database Schema Availability

The system MUST initialize an SQLite database containing all 16 tables defined in the PRD, even for deferred modules.

#### Scenario: Initializing the database

- GIVEN a clean deployment environment
- WHEN the backend starts
- THEN the SQLite database is created
- AND all 16 tables (including reports, schedules, etc.) are present

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
