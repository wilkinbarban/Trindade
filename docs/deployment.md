# Production Deployment and Rollback Runbook — Trindade Massas

This runbook defines the operational procedure for deploying updates to Trindade Massas in production with verifiable SQLite consistency, zero accidental data loss, and an immediate rollback guarantee.

---

## 1. Architectural Invariants

- **Topology**: Production runs behind a shared reverse proxy. The web container joins the
  external Docker network `portafolio_default`, and that project's Nginx reaches it **by
  container name** (`proxy_pass http://trindade-web-1:80`). Name resolution only works across a
  shared network, so the attachment is required by the running installation rather than
  preferred, and it is what `docker-compose.yml` declares. The standalone template in
  `docker/nginx-standalone-host.conf.example` describes an alternative topology; moving to it is
  a migration, not a configuration change (Section 10).
- **Stack**: Defined in `docker-compose.yml` (`trindade-api-1` for Fastify API on Node 24; `trindade-web-1` for Nginx SPA).
- **Data volume**: Named Docker volume `trindade_sqlite_data` mounted at `/app/packages/backend/data`.
  Declared `external: true` with an explicit `name:`, so Compose refuses to start when the volume
  is missing instead of silently creating an empty database, and `docker compose down -v` cannot
  delete production data.
  - Database: `/app/packages/backend/data/trindade.db` (SQLite in WAL mode).
  - Report photos: `/app/packages/backend/data/photos`.
- **Production Immutability Gate**: Server startup **never** runs automatic migrations, seeds, or table alterations (`openspec/specs/foundation/spec.md`). Existing databases are classified and reported at boot without modification. The schema is what is left untouched; startup does still perform two 30-day retention cleanups, one for expired report photos and one for dead session rows.
- **Recovery Gate**: Before any production mutation or deployment, a SQLite-consistent online snapshot and asset inventory must be captured and proven via isolated restoration (`README.md` and `openspec/specs/production-data-recovery/spec.md`). A raw file copy is not sufficient because SQLite in WAL mode keeps uncheckpointed pages in the `-wal` sidecar.

### Deployment order rule

The runbook migrates **before** switching traffic. That order is not a preference, it is a
requirement of how the two application versions tolerate each other, and it holds only while the
migration is **additive**:

- An **additive** migration (adds a table or a column that the old code never reads) can be
  applied first, because the old code keeps serving unchanged against the newer shape. Revision 1
  to 2 is additive: it adds `auth_sessions`, which the revision-1 code does not know about.
- A **destructive** migration (drops, renames, or rebuilds a table the old code reads) cannot be
  applied first, because the old code would break against the new shape. That case needs a
  bounded window: stop the API, migrate, start the new code.

Deciding which case applies is a per-release judgement made at Section 4, and getting it wrong
is what produces the failure this rule exists to prevent: the revision-2 code requires
`auth_sessions` **at request time**, because a successful login inserts into it, so running the
new code against an unmigrated database makes login answer `500 no such table: auth_sessions`
rather than degrade.

---

## 2. Pre-Deployment Gate (Code Verification)

1. Ensure the working tree is clean on the deployment machine:
   ```bash
   git status --porcelain
   # Must return empty output
   ```

2. Run the reproducible clean-checkout CI gate:
   ```bash
   make ci-clone
   ```
   **Acceptance criteria**:
   - Clean-checkout proof on `node:24-bookworm-slim`.
   - Zero deprecation or `install-scripts` warnings.
   - `found 0 vulnerabilities`.
   - All backend tests passing (296 tests).
   - Built schema CLIs verified (`scripts/verify-schema-clis.sh`).
   - The committed API contract matches the schemas it is generated from
     (`scripts/verify-openapi-artifact.sh`).

   `make ci-clone` clones HEAD, overlays any dirty working-tree files, and prints a loud warning
   naming each overlaid path. A run that overlays files is HEAD plus uncommitted changes, not a
   clean-checkout proof, so deploy only from a clean tree.

3. Inspect current production health and schema status (read-only):
   ```bash
   make db-status
   # Or inside the running container:
   docker exec trindade-api-1 node packages/backend/dist/db/status.js
   curl -sS https://trindademasas.duckdns.org/api/health
   ```
   Confirm the running service is healthy and **record the current schema verdict**. The verdict
   decides Section 4:
   - `unversioned` (revision 0) — predates versioning; the migration runs every step.
   - `outdated` (a lower stamped revision, e.g. revision 1) — the migration runs only the pending
     steps. This is the state of an installation running the pre-revision-2 code.
   - `current` — no migration is pending; Section 5 is a no-op.
   - `incompatible`, `newer` — stop. The migration refuses both, and deploying will not fix them.

---

## 3. Pre-Deployment Recovery Set Capture (Backup)

Capture a verified recovery set before touching container images or database files:

```bash
make db-backup
# Or specify a custom backup directory:
./scripts/db-backup.sh ./backups/recovery-$(date -u +%Y%m%d)
```

### What the backup tool executes:

1. **Online snapshot**: Mounts `trindade_sqlite_data` strictly read-only (`:ro`) and calls `better-sqlite3`'s `db.backup()`. This captures all active SQLite pages (including WAL pages) into a consistent, standalone `snapshot.db` without locking out active writers.
2. **Asset inventory**: Recursively copies all report photos from `/photos` to `/assets` in the recovery set.
3. **Checksum manifest**: Writes `manifest.json` containing the unique `setId`, timestamps, source database size and SHA-256, sidecar sizes and SHA-256, snapshot size and SHA-256, and individual asset digests.
4. **Isolated restore proof**: In a separate temporary directory, the tool restores `snapshot.db`, runs SQLite `PRAGMA integrity_check`, enumerates all tables and row counts, and verifies that all copied asset files match their recorded SHA-256 digests.
5. **Host integrity check**: Verifies on the host that `snapshot.db` matches the manifest SHA-256.
6. **Approval**: Prints `Status: APPROVED FOR ROLLBACK BASELINE` with the `setId`.

**Acceptance criteria**:
- Script exits 0.
- `manifest.json` and `snapshot.db` exist in the backup directory.
- `isolated proof` returns `integrity: "ok"`.

---

## 4. Build the New Images (No Traffic Switched)

Build the new images and decide, from the verdict recorded in Section 2, whether the pending
migration is additive or destructive (Section 1).

1. Build the updated production container images:
   ```bash
   docker compose build
   ```
   **Nothing is redeployed yet.** The currently running containers keep serving this whole step
   and the next one.

2. Confirm the new image carries the migration this release needs. The status command prints
   the revision it supports, so Section 5's first command answers this as a side effect:
   a line reading `schema revision: <n> (this build supports <m>)`. If `<m>` is not the revision
   this release introduces, the image is stale and will refuse to migrate.

3. If Section 2 recorded `incompatible` or `newer`, **stop here**. Neither is repaired by
   deploying, and the migration command refuses both.

---

## 5. Schema Migration (Deliberate Operator Write — Before Rollout)

If Section 2 recorded `unversioned` or `outdated`, migrate now, **while the previous application
version is still serving**. This is what makes the rollout in Section 6 have no login outage: the
old code keeps answering requests against the newer schema.

> **Important**: Never run migrations without the approved recovery set from Section 3. Migrations mutate production data and must remain an explicit, operator-invoked step.

1. Classify the database read-only, **from the new image**, without starting the service:
   ```bash
   docker compose run --rm --no-deps --entrypoint node api \
     packages/backend/dist/db/status.js
   ```
   This must report the same verdict Section 2 recorded. `docker compose run` mounts the same
   `trindade_sqlite_data` volume and inherits the service's `DATABASE_PATH`, so it inspects the
   real production database and not a fresh one. Do not use `docker exec` here: the new
   container is not running yet, and the old one carries the old code and its old migrations.

2. Execute the migration:
   ```bash
   docker compose run --rm --no-deps --entrypoint node api \
     packages/backend/dist/db/migrate.js
   ```
   **The migration runner**:
   - Opens the database read-only first to classify, and prints the before state.
   - Refuses `incompatible` or `newer` databases without ever opening them read-write.
   - Runs a stepwise list driven by the revision the database reports, stamping each revision it
     produces, so an interrupted run resumes at the next pending step:

     | Step | What it does |
     | --- | --- |
     | 0 → 1 | Rebuilds the legacy `report_temperatures` table into the `reading_index` shape. Idempotent: it no-ops once `reading_index` exists. |
     | 1 → 2 | Additive only: creates `auth_sessions` and its indexes with `IF NOT EXISTS`. No rebuild and no data movement. |

   - A revision 1 database therefore runs only the second step and its temperature data is never
     touched.

   The old container keeps serving during this step. The migration takes a write lock only
   briefly, and `better-sqlite3`'s default `busy_timeout` of 5000 ms means a collision with a
   concurrent request retries instead of failing.

3. Confirm the database is now `current`, still from the new image:
   ```bash
   docker compose run --rm --no-deps --entrypoint node api \
     packages/backend/dist/db/status.js
   ```
   Must exit 0 and print `verdict: current`. **Do not proceed to Section 6 until it does.** At
   this point the database is ahead of the code that is still serving, which is safe precisely
   because the migration was additive.

4. If the migration failed, stop and go to Section 8, Scenario B. Because the old code tolerates
   a migrated database, a partially migrated database is still recoverable by restoring the
   Section 3 set.

---

## 6. Rollout (Traffic Switch)

1. Deploy the containers:
   ```bash
   docker compose up -d
   ```

2. Confirm containers are up and healthy:
   ```bash
   docker compose ps
   ```
   Both `trindade-api-1` and `trindade-web-1` should report `Up` and `(healthy)`.

3. Check the startup schema notice:
   ```bash
   docker logs trindade-api-1 | grep "database schema notice"
   ```
   - **For a database predating versioning**: `level=30` (info), `verdict=unversioned`,
     `revision=0`. Only if Section 5 was deliberately skipped.
   - **For a migrated database**: `level=30` (info), `verdict=current`, `revision=2`.
   - `level=40` (warn) with `verdict=outdated` or `newer` means the rollout is running against the
     wrong schema. Stop and go to Section 8. This is the notice that would have appeared instead
     of a login outage had Section 5 been skipped.

4. Verify the healthcheck endpoint:
   ```bash
   curl -sS https://trindademasas.duckdns.org/api/health
   ```
   Expect HTTP 200 with `{"status":"ok","timestamp":"..."}`.

5. Verify a real login, which is what exercises the new `auth_sessions` table end to end:
   ```bash
   curl -sS -X POST https://trindademasas.duckdns.org/api/auth/login \
     -H 'content-type: application/json' \
     -d '{"username":"<operator>","password":"<password>"}'
   ```
   Expect HTTP 200, a `token`, a `refreshToken` and `expiresIn`. A `500` naming
   `no such table: auth_sessions` means Section 5 did not run.

---

## 7. Post-Deployment Verification Checklist

Run through these checks before declaring deployment complete:

- [ ] HTTPS redirect: `curl -I http://trindademasas.duckdns.org` returns 301 to HTTPS.
- [ ] TLS certificate: `curl -I https://trindademasas.duckdns.org` returns 200 without SSL warnings.
- [ ] Backend health: `curl -sS https://trindademasas.duckdns.org/api/health` returns HTTP 200 with `status: "ok"`.
- [ ] Frontend SPA: `curl -sS https://trindademasas.duckdns.org/ | grep -q "Trindade"` returns 0.
- [ ] Real login succeeds and returns a `refreshToken` (see Section 6, step 5).
- [ ] Database schema: `make db-status` exits 0 with `current`.
- [ ] Container logs: `docker logs --tail 30 trindade-api-1` contains zero errors or unhandled rejections.

---

## 8. Rollback Procedures

If any gate or verification fails, execute the appropriate rollback procedure immediately.

### Scenario A: Application/Code Rollback

Use this if the new container fails to boot, crashes, or exhibits a frontend regression.

**This scenario also covers a rollout that failed AFTER the migration ran.** That is the point of
migrating first: the previous application version serves a migrated database normally, because
the migration was additive and it does not read the table that was added. Rolling the code back
therefore needs no data restore. Verified against a revision-2 database running the pre-revision-2
code: login returned 200, health returned 200, and the boot notice reported `verdict: newer` with
`level=40` (warn), which is the expected and harmless report of a database ahead of the code.

1. Revert Git to the previous known-good commit:
   ```bash
   git checkout <previous-commit-or-tag>
   ```

2. Rebuild and redeploy containers:
   ```bash
   docker compose build
   docker compose up -d
   ```

3. Verify application health:
   ```bash
   curl -sS https://trindademasas.duckdns.org/api/health
   ```

4. Expect `make db-status` to exit non-zero with `verdict: newer` while the reverted code runs.
   That is correct, not a failure: the reverted build supports revision 1 and is reporting a
   database it must not touch. Do not "fix" it by reverting the data.

---

### Scenario B: Full Data Rollback (Migration failed or data was corrupted)

Use this if the migration failed, data was corrupted, or the release must be fully rolled back
after database writes.

1. Stop the API container to release all database locks and prevent concurrent writes:
   ```bash
   docker compose stop api
   ```

2. Restore the pre-deployment recovery set:
   ```bash
   make db-restore BACKUP_DIR="./backups/recovery-<timestamp>" CONFIRM=--confirm
   # Or directly:
   ./scripts/db-restore.sh "./backups/recovery-<timestamp>" --confirm
   ```

   **What the restore tool guarantees**:
   - Verifies the manifest and snapshot SHA-256 checksums **before** touching any volume file.
   - Overwrites `/app/packages/backend/data/trindade.db` with the verified `snapshot.db`.
   - **Deletes stale `-wal` and `-shm` sidecars**: SQLite WAL mode requires that sidecars from a prior session are removed, so SQLite does not replay mismatched WAL pages onto the restored database.
   - Synchronizes `/app/packages/backend/data/photos` from the recovery set assets.
   - Restarts `trindade-api-1`.
   - Verifies database integrity (`PRAGMA integrity_check`) and schema status.

3. Revert code to the previous commit (Scenario A) if the rollback requires the previous application version:
   ```bash
   git checkout <previous-commit-or-tag>
   docker compose build
   docker compose up -d
   ```

4. Confirm health and schema status:
   ```bash
   docker exec trindade-api-1 node packages/backend/dist/db/status.js
   curl -sS https://trindademasas.duckdns.org/api/health
   ```

---

## 9. Emergency Manual Recovery (No Helper Scripts)

In the unlikely event that scripts or Docker Compose tooling fail:

1. Inspect the volume path directly:
   ```bash
   docker volume inspect trindade_sqlite_data
   ```

2. Emergency manual backup using a throwaway container:
   ```bash
   docker run --rm -v trindade_sqlite_data:/data:ro -v "$PWD":/backup alpine \
     tar czf /backup/emergency-backup-$(date +%s).tar.gz -C /data .
   ```

3. Emergency manual restore:
   ```bash
   docker compose stop api
   docker run --rm \
     -v trindade_sqlite_data:/data \
     -v "$PWD/backups/recovery-<timestamp>":/backup:ro \
     alpine sh -c '
       cp /backup/snapshot.db /data/trindade.db && \
       rm -f /data/trindade.db-wal /data/trindade.db-shm && \
       rm -rf /data/photos && \
       cp -r /backup/assets /data/photos
     '
   docker compose start api
   ```

---

## 10. Standalone Deployment on a New VPS (Quick Start)

> **This section describes the standalone alternative, not production.**
>
> Production runs behind the shared reverse proxy described in Section 1, where that project's
> Nginx reaches `trindade-web-1` by container name over the external network
> `portafolio_default`. Because both that network and the data volume are declared
> `external: true`, Compose needs them to exist before the first `up`:
>
> ```bash
> docker network create portafolio_default
> docker volume create trindade_sqlite_data
> ```
>
> On the production host both already exist. Creating them is only necessary when this Compose
> file is used somewhere the shared proxy does not run.
>
> Following the steps below as a standalone installation is therefore a **migration**, not a local
> variation: it needs a host Nginx and certbot, and it needs the `portafolio_default` attachment
> removed from `docker-compose.yml`, because Compose still insists that network exist. The port
> binding (`WEB_BIND:WEB_PORT`, default `127.0.0.1:8080`) exists for this path; the shared proxy
> reaches the container over the Docker network and does not use it.

### Step 1: Install prerequisites
- Docker Engine & Docker Compose (v2)
- Git
- Host Nginx & Certbot (for public HTTPS termination)

### Step 2: Clone repository & configure environment
```bash
git clone https://github.com/wilkinbarban/Trindade.git
cd Trindade

cp .env.example .env
# Edit .env and configure:
#   JWT_SECRET=<strong-unique-secret-at-least-32-chars>
#   PUBLIC_APP_URL=https://trindademasas.duckdns.org
#   WEB_BIND=127.0.0.1
#   WEB_PORT=8080
#   REFRESH_TOKEN_TTL_DAYS=30   (optional; defaults to 30)
```

> **Why port 8080?** Host Nginx binds TCP port 80/443 to receive internet traffic and handle Certbot challenges. The Trindade container listens privately on `127.0.0.1:8080`, completely preventing port 80 conflicts.

### Step 3: Two-step Host Nginx and TLS Setup
To avoid bootstrap cycles (Nginx refusing to start because SSL certificates do not yet exist):

```bash
# 1. Prepare ACME challenge webroot directory
sudo mkdir -p /var/www/certbot

# 2. Deploy the HTTP-only bootstrap configuration
sudo cp docker/nginx-standalone-host.conf.example /etc/nginx/sites-available/trindademasas
# Ensure only the Step 1 (HTTP) server block is active initially, then symlink:
sudo ln -s /etc/nginx/sites-available/trindademasas /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# 3. Issue the SSL certificate via Certbot webroot
sudo certbot certonly --webroot -w /var/www/certbot -d trindademasas.duckdns.org

# 4. Activate the Step 2 (HTTPS) block in /etc/nginx/sites-available/trindademasas
# (uncomment the HTTPS server block pointing to http://127.0.0.1:8080)
sudo nginx -t && sudo systemctl reload nginx
```

### Step 4: Launch container stack
```bash
docker compose up -d --build
```

**Seed behavior**:
A fresh volume automatically loads `seed.sql`, creating the complete operational catalog: all 6 categories, all 57 tasks, all 6 company vehicles, and all 30 drivers/fleteros, stamped to `user_version = 2`. Open `https://trindademasas.duckdns.org` to create the administrator account.

### Step 5: Transfer existing live database (Optional)

A database transferred from another host arrives at whatever revision it was stamped with. If it
predates the current build, run Sections 4 and 5 after the transfer, from the same image that is
serving, before declaring the installation complete. Do not assume the transfer also migrated it.

```bash
# On the source server:
make db-backup  # captures to backups/recovery-<timestamp>

# Copy to the new VPS:
scp -r backups/recovery-<timestamp> user@new-vps:~/Trindade/backups/

# On the new VPS:
make db-restore BACKUP_DIR=backups/recovery-<timestamp> CONFIRM=--confirm
```

### Step 6: Automated certificate renewal
Set up daily renewal in root crontab:
```bash
(sudo crontab -l 2>/dev/null; echo "17 3 * * * /home/wilkin/proyectos/Trindade/scripts/renew-certbot.sh >> /var/log/trindade-certbot.log 2>&1") | sudo crontab -
```
