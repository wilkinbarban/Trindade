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

## Reproducible verification gate

The canonical CI gate is `scripts/ci.sh`. It requires Node.js 24, installs the
lockfile dependencies with `npm ci`, builds both workspaces, runs real TypeScript
checks for both workspaces, and runs the complete backend test suite. It starts
from a clean dependency tree and refuses to reuse an existing `node_modules`.
The declared container target is `node:24-bookworm-slim`.

```bash
make ci          # Run the gate on the host (requires Node.js 24 and no node_modules)
make ci-clone    # Clone HEAD, run the gate in node:24-bookworm-slim, then clean up
```

`make ci-clone` clones the local repository and removes its throwaway clone afterward.
When `scripts/ci.sh`, `package.json`, `README.md`, and `Makefile` are clean relative
to HEAD, it runs a genuine clean-checkout proof of HEAD on the declared engine. If
any of those gate paths are dirty, it overlays the dirty paths, prints a loud warning,
and proves only HEAD plus the working-tree gate files—not a clean checkout. This
keeps the unit bootstrappable while making contaminated evidence explicit. It uses
the current local HEAD because this repository has no remote yet.

The root `lint` script is currently a documented no-op: it uses npm's
`--workspaces --if-present`, and no workspace defines a `lint` script. It is
therefore not part of the verification gate or evidence of lint coverage.

The gate does not run the frontend Playwright E2E suite; run that separately with a
running backend.

**Current status: the gate is green on the declared engine.** The red state this file
previously documented was a native driver and engine compatibility defect, now
understood and fixed. Node.js 24.19.0 changed the internal `node::ObjectWrap`
teardown: its destructor now calls `RemoveEnvironmentCleanupHook`, and during
environment teardown that call aborts the process on a native assertion
(`(env) != nullptr`). An addon is affected only if it was compiled against those
headers; a binary built earlier is not. `better-sqlite3@11.10.0` publishes no
prebuilt binary for the Node 24 ABI (`node-v137`), and its install script is
`prebuild-install || node-gyp rebuild`, so `npm ci` fell through to `node-gyp` and
every run inherited the aborting teardown. The dependency is now pinned to the 12.x
line, `better-sqlite3@^12.11.1`: `prebuild-install` downloads the prebuilt binary for
the running ABI, so nothing compiles, and the caret range cannot reach 13.x, which
declares `node-gyp rebuild` and therefore requires a compiler. Because nothing
compiles, neither the API image nor the gate container installs `build-essential` or
`python3` any more; the runner used to install python3 claiming better-sqlite3 needed
it at runtime, which was never true. A missing prebuild now fails the build loudly
instead of quietly compiling a binary against the running Node headers. Measured on
the declared engine: 218/218 tests across 29 files and zero native assertions, built
on Node.js 24.21.0 with no compiler and no python3 present. Node.js 24 remains the
declared target: the container images and `engines.node` both require it, so the
host's Node.js 22 is the anomaly, not the target.

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
