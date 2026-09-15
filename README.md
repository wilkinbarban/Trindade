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

### Schema revision and status

A fresh installation stamps the approved schema revision into `PRAGMA user_version`
(currently **1**) inside the same transaction that seeds reference data. An existing
database is never migrated, reset, seeded, or stamped by startup, so a database created
before versioning keeps reporting revision **0** until an explicit approved operation
changes it — even when its tables happen to match.

Ask whether a database is up to date with the read-only status command. It prints the
revision, integrity, table inventory, and verdict; it exits non-zero unless the verdict
is `current`; and it never creates, migrates, stamps, or repairs anything:

```bash
make db-status                                      # local database
DATABASE_PATH=/path/to/trindade.db make db-status   # explicit target
npm run db:status --workspace=packages/backend      # same command, unscoped
```

Inside a running container the compiled command is available without a toolchain. The API
image runs from `/app` and copies only `packages/backend/dist`, so the compiled commands live
under `packages/backend/dist/db/`:

```bash
docker exec trindade-api-1 node packages/backend/dist/db/status.js   # read-only inspection
```

The verdicts are `current`, `unversioned` (no stamp), `outdated` (older stamp, migration
pending), `incompatible` (failed integrity or missing tables), and `newer` (written by a
build this one does not know: never downgrade it). Unexpected extra tables are reported
without changing the verdict, because they cannot break the application.

Migrating or adopting an existing database is a deliberate, operator-invoked write and is
never part of startup:

```bash
make db-migrate                                       # local database
DATABASE_PATH=/path/to/trindade.db make db-migrate    # explicit target
docker exec trindade-api-1 node packages/backend/dist/db/migrate.js   # in the running container, once the image ships dist
```

The migration command reports the before state read-only, refuses a `newer` or
`incompatible` database without ever opening it for writing, and only then opens an
`unversioned` database read-write. The one migration that predates versioning is the legacy
`report_temperatures` rebuild, which is idempotent: it no-ops once `reading_index` exists and
then the revision is stamped. Because it writes, run it only after the recovery-gate
evidence recorded above (snapshot, isolated restore, approval) against the exact target you
authorized — it is a change to an existing production database, not an inspection.

Startup reports the verdict, and nothing else. The server logs one line at boot with the
verdict, the observed revision, the revision this build supports, the integrity result, and
the table counts, so a restart tells you which schema the running process found; find it
with `docker logs trindade-api-1 | grep 'database schema notice'`. It is a notice, not
enforcement: startup never migrates, stamps, or refuses, and a classification failure is
logged as a warning instead of failing the boot. `current` and `unversioned` are logged at
info level — an unversioned database is the expected state of an installation that predates
versioning — while `outdated`, `newer`, and `incompatible` are logged as warnings. Measured
on the production database (335 KB plus a 4 MB WAL): 8.8 ms for the whole notice, 6.0 ms of
which is the integrity check.

### Certificate renewal

The public certificates are shared with the other DuckDNS sites behind the Portafolio Nginx,
so they live in that project's Docker volumes (`portafolio_letsencrypt`,
`portafolio_certbot-www`) and are renewed by `scripts/renew-certbot.sh`, run daily at 03:17
from the user crontab with both streams appended to `certbot-renew.log`. The job renews
whatever sits inside the 30-day window, reloads Nginx so it serves the renewed files, and ends
with a timestamped heartbeat line: a log that stops growing means the job stopped running. It
never recreates the container, so it cannot deploy unrelated changes from the Portafolio
working tree; restarting that proxy is a deliberate deployment step.

Validate renewal without touching the live certificates:

```bash
cd /home/wilkin/proyectos/Portafolio
docker compose --profile ssl run --rm certbot renew --dry-run
```

TLS for `trindademasas.duckdns.org` belongs to that shared proxy: the API image never handles
certificates.

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

### Production recovery set and rollback commands

To capture an actual production recovery set and prove isolated restore before any deployment or schema mutation:

```bash
make db-backup                                    # captures to ./backups/recovery-<timestamp>
BACKUP_DIR=/custom/backup/path make db-backup     # explicit destination
./scripts/db-backup.sh                            # direct script invocation
```

The backup tool opens the database strictly read-only and calls SQLite online backup (`db.backup()`), copies report photos, generates a SHA-256 manifest, and immediately runs an isolated restore proof to verify that the snapshot and assets pass `PRAGMA integrity_check` and table/row verification.

If a deployment or migration fails, roll back using the verified recovery set:

```bash
make db-restore BACKUP_DIR=./backups/recovery-<timestamp> CONFIRM=--confirm
# Or directly:
./scripts/db-restore.sh ./backups/recovery-<timestamp> --confirm
```

The restore tool verifies checksums before writing, stops the container to release write locks, replaces the database and photos, deletes stale `-wal` and `-shm` sidecars to prevent WAL replay corruption, restarts the container, and verifies `PRAGMA integrity_check` and schema status.

For the complete step-by-step production rollout, pre-flight gate, schema adoption, and rollback runbook, see [`docs/deployment.md`](docs/deployment.md).

## Reproducible verification gate

The canonical CI gate is `scripts/ci.sh`. It requires Node.js 24, installs the
lockfile dependencies with `npm ci`, builds both workspaces, runs real TypeScript
checks for both workspaces, runs the complete backend test suite, and verifies the built
schema commands with `scripts/verify-schema-clis.sh`. It starts
from a clean dependency tree and refuses to reuse an existing `node_modules`.
The declared container target is `node:24-bookworm-slim`.

```bash
make ci          # Run the gate on the host (requires Node.js 24 and no node_modules)
make ci-clone    # Clone HEAD, run the gate in node:24-bookworm-slim, then clean up
```

`make ci-clone` clones the local repository and removes its throwaway clone afterward.
When the working tree is clean relative to HEAD, it runs a genuine clean-checkout proof
of HEAD on the declared engine. If any files are uncommitted or dirty, it automatically
overlays all dirty working-tree files onto the clone, prints a loud warning listing every
overlaid path, and proves only HEAD plus uncommitted changes—not a clean checkout. This
keeps work bootstrappable without manual overlay lists while making contaminated evidence
explicit. It uses the current local HEAD because this repository has no remote yet.

The root `lint` script is currently a documented no-op: it uses npm's
`--workspaces --if-present`, and no workspace defines a `lint` script. It is
therefore not part of the verification gate or evidence of lint coverage.

The gate runs the full backend test suite, the built schema CLI verification,
and the complete frontend Playwright E2E test suite (53 tests). To run the gate
without E2E (e.g. for rapid local iteration), use `SKIP_E2E=1 make ci` or
`SKIP_E2E=1 make ci-clone`.

**Current status: the gate is green on the declared engine.** The red state this file
previously documented was a native driver and engine compatibility defect, now
understood and fixed. Node.js 24.19.0 changed the internal `node::ObjectWrap`
teardown: its destructor now calls `RemoveEnvironmentCleanupHook`, and during
environment teardown that call aborts the process on a native assertion
(`(env) != nullptr`). An addon is affected only if it was compiled against those
headers; a binary built earlier is not. `better-sqlite3@11.10.0` publishes no
prebuilt binary for the Node 24 ABI (`node-v137`), and its install script is
`prebuild-install || node-gyp rebuild`, so `npm ci` fell through to `node-gyp` and
every run inherited the aborting teardown. The dependency is now on the 13.x line, `better-sqlite3@^13.0.3`, the first N-API
release: it removed the deprecated `prebuild-install` dependency by design and ships
the prebuilt binaries inside the package itself (`prebuilds/**`). 13.x declares no
install script, so npm injects its default `node-gyp rebuild`, which needs `python3`
only to read `binding.gyp`; `allowScripts` in the root `package.json` (npm >= 11.19,
which the `node:24` images ship) records an explicit denial for
`better-sqlite3@13.0.3`, so npm skips it. `lib/binding.js` resolves
`prebuilds/linux-x64.node` at require time and falls back to the node-gyp output only
when no prebuild exists, so skipping the build is safe by design. Because nothing
compiles, neither the API image nor the gate container installs `build-essential` or
`python3`; the runner used to install python3 claiming better-sqlite3 needed it at
runtime, which was never true. A missing prebuild fails the build loudly instead of
quietly compiling a binary against the running Node headers. Measured on the declared
engine: 240/240 tests across 33 files and zero native assertions, installed and built
on Node.js 24.21.0 with no compiler and no python3 present; `npm ci` reports no
deprecation warnings and `npm audit` reports no vulnerabilities. Node.js 24 remains the
declared target: the container images and `engines.node` both require it, so the
host's Node.js 22 is the anomaly, not the target.

## Testing

```bash
# Backend unit tests
npm run test --workspace=packages/backend

# Frontend Playwright E2E tests (automatically starts backend + frontend test servers)
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

## Milestones and Status

- **Stage 1 (Production Hardening & Operations)**: **Complete**
  - Schema revision management (`user_version = 1`, `db:status`, `db:migrate`).
  - Node 24 ABI compatibility (`better-sqlite3@13.0.3` prebuilt N-API).
  - Production immutability gate (zero automatic startup mutation, strict recovery proofs).
  - Operator recovery tooling (`make db-backup`, `make db-restore`, WAL sidecar cleanup).
  - Pruned production container images (zero test or E2E artifacts, 146 dist files).
  - Docker container log rotation (json-file, 10m max-size, 3 files max).
  - Automatic TLS renewal daily cron with zero-downtime Nginx reload and heartbeat.
  - Automated CI gate: 244 backend unit tests + 53 Playwright E2E tests + 9 schema CLI checks.
  - Dynamic, zero-fragility clean-checkout verification (`make ci-clone`).
- **Stage 2 (Publishing & Distribution)**: **Prepared**
  - First tagged release: `v0.1.0`.
  - Canonical GitHub Actions CI workflow (`.github/workflows/ci.yml`) prepared for remote push.
  - Next operational step: assign upstream remote (`git remote add origin <url>`) and push `main` + tags.
- **Stage 3 (Android / Mobile Web)**: Gated behind Stage 2 publication.

## License

Private — all rights reserved.
