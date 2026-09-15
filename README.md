# Trindade Massas Operações

Sistema web operativo para Trindade Massas — relatórios operativos, horários de carregamento, histórico operacional e exportação otimizada para WhatsApp.

## Tech Stack

- **Frontend**: React + TypeScript + Vite + TailwindCSS + shadcn/ui
- **Backend**: Node.js 24 + Fastify + TypeScript + Zod + JWT
- **Database**: SQLite (better-sqlite3)
- **Infra**: Docker + Docker Compose

## Prerequisites

- Node.js >= 24.0.0
- npm >= 10

## Getting Started

```bash
make install   # Install all dependencies (monorepo)
make dev       # Start both frontend and backend in dev mode
```

The backend starts on `http://localhost:3099` and the frontend on `http://localhost:5173`.

Copy `.env.example` to `.env`, generate a unique `JWT_SECRET` with at least 32 UTF-8 bytes, and keep the value outside source control. The backend rejects missing, weak, known default, and test secrets before opening the database.

### Fresh installation

When `DATABASE_PATH` has no database or SQLite sidecars, startup creates the approved schema and required reference data without users, demo operational records, or fixed credentials. Open the web application and complete the one-time administrator setup. The submitted password must contain at least eight characters; it is hashed before storage. After the first administrator is created, setup closes atomically.

### Existing installation

When `DATABASE_PATH` identifies an established SQLite database, stage-one startup opens it read-only. It does not migrate, reset, seed, or replace accounts. Login remains available; endpoints that require persistent writes are intentionally unavailable until a separately approved upgrade passes the recovery gate.

An empty, corrupt, partial, sidecar-only, inaccessible, or otherwise ambiguous target fails closed. Resolve the target identity; do not delete files to force fresh classification.

## Recovery and stage-one verification

Stage one does **not** authorize production mutation or deployment. Before any later upgrade:

1. Record the absolute database, WAL/SHM, photo, mount, image/configuration, ownership, and destination identities. Missing inventory closes the gate.
2. Create a SQLite-consistent online snapshot plus associated files in a non-overlapping protected destination. A raw live database copy is not sufficient.
3. Verify database integrity, manifest completeness, SHA-256 checksums, storage ACL/encryption, retention, and secret-free evidence.
4. Restore into an isolated non-production target and verify integrity, schema identity, representative row counts, associated files, and application-level reads.
5. Compare production database and non-expired associated-file fingerprints captured before and after read-only verification. Policy-expired report photos may be deleted by the 30-day startup retention cleanup; any other difference fails the gate.
6. Record the recovery-set ID, restore procedure, rollback triggers, verification commands, approver, and evidence expiry. Obtain explicit approval for the exact future mutation and target.

The repository harness exercises capture and isolated restore only with temporary fixtures:

```bash
npm run build --workspace=packages/backend
node packages/backend/dist/recovery.js --verify-isolated
npm exec --workspace=packages/backend -- node --test --import tsx src/stage-one-invariants.test.ts
```

The harness output is regression evidence, not a production recovery set. Never run reset commands against an existing installation. Production inspection must remain read-only and must not expose secret values in manifests or logs.

## Testing

```bash
# Backend unit tests
npm run test --workspace=packages/backend

# E2E tests (requires Playwright and a running backend)
npm run test:e2e --workspace=packages/frontend
```

## Project Structure

```
Trindade/
├── packages/
│   ├── backend/         # Fastify API server
│   │   └── src/db/      # Schema, seed data, and DB init
│   └── frontend/        # React SPA
│       └── src/__e2e__/ # Playwright E2E tests
├── openspec/            # SDD change artifacts
├── docker/              # Docker configuration
├── docker-compose.yml
├── Makefile
└── PRD_Trindade.md      # Product Requirements Document
```

## License

Private — all rights reserved.
