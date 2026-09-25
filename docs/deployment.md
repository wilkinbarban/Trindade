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

Revision 3 is a controlled cutover, not an additive live migration. Stop the API before
capturing the final consistent recovery set and running the explicit migration. Validate the
new schema before starting the new API. Never run a revision-2 binary against a revision-3
database; it cannot safely enforce the new security-version rules. Every existing session, including
administrator sessions, is invalidated at cutover. Operators must sign in again. Rollback after
migration requires restoring the verified pre-cutover database **and** its matching old code;
never restore old sessions into a revision-3 database.

---

### First administrator (SEC-01)

Public `POST /api/auth/setup` is retired and always returns 410; `GET /api/auth/setup/status`
only reports whether users exist. No browser or mobile client can create the first administrator.
Provision from the operator container only, after an approved recovery snapshot and after
`db:status` reports a **current revision-3** schema with `auth_sessions`. The CLI refuses an existing user,
missing database, or obsolete session schema, and rechecks those conditions inside an immediate
SQLite transaction. Run it only on the intended empty installation; never run `db:reset` against
production. Keep the account password out of arguments, environment, shell history, and logs.

In a restricted operator session, prepare a one-line password on stdin using a protected pipe or
secret manager that does not expose it as an argument. For an interactive Bash shell, use a
private terminal session. The subshell prevents an existing `set -x` in the operator shell from
tracing the password; its first command disables tracing before the read. Cleanup restores
terminal echo and clears the variable on normal exit, read/command failure, or interruption:

```bash
(
  set +x
  cleanup_admin_password() {
    stty echo
    unset ADMIN_PASSWORD
  }
  trap cleanup_admin_password EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set -o pipefail
  stty -echo || exit 1
  IFS= read -r ADMIN_PASSWORD || exit 1
  stty echo || exit 1
  printf '\n'
  printf '%s\n' "$ADMIN_PASSWORD" | docker compose run --rm --no-deps -T --entrypoint node api \
    packages/backend/dist/db/provision-admin.js /app/packages/backend/data/trindade.db owner 'Administrator'
)
```

Do not paste a password into a command line or keep this shell variable longer than necessary.
The CLI refuses terminal stdin to prevent echoed secrets. Confirm the administrator can log in
through the usual login endpoint; subsequent provisioning must fail. If provisioning fails,
inspect schema status and user counts without dumping password material. Roll back only through
the verified pre-write recovery set; restoring it also removes the new administrator and any
post-snapshot data, so obtain approval before restoring. Rolling back application code alone
reopens the old public setup endpoint **if the users table is empty**: never deploy that version
against an empty live installation.

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
   - All backend tests passing.
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
   Confirm the running service is healthy and record its database revision **and the revision
   supported by that running image**. The old revision-2 image normally reports `current` at
   revision 2; that does **not** mean the new revision-3 image has no migration to run. Section
   5 reclassifies the stopped database using the new image and determines the pending steps.
   If the old service reports `incompatible` or `newer`, stop and investigate before building.

---

## 3. Pre-Deployment Recovery Set Capture (Backup)

Capture a preliminary verified recovery set before touching container images or database files.
Capture a **second, final** set after stopping the API in Section 5; only that final set is the
rollback boundary for the cutover:

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

Build the new images while the old API is still serving. Revision 3 always requires the
controlled cutover in Section 5.

1. Build the updated production container images:
   ```bash
   docker compose build
   ```
   **Nothing is redeployed yet.** The old containers serve only until the Section 5 stop.

2. Confirm the new image carries the migration this release needs. The status command prints
   the revision it supports, so Section 5's classification command answers this as a side effect:
   a line reading `schema revision: <n> (this build supports <m>)`. If `<m>` is not the revision
   this release introduces, the image is stale and will refuse to migrate.

3. If either the running-image check in Section 2 or new-image check in Section 5 reports
   `incompatible` or `newer`, **stop**. Neither is repaired by deploying, and the migration
   command refuses both.

---

## 5. Controlled Revision-3 Cutover (Deliberate Operator Write)

Schedule a maintenance window. Stop the old API **before** the final backup and migration.
Do not permit any other database writers during the window. Existing sessions, administrator
sessions included, will not survive the revision-3 migration.

> **Important**: Never run migrations without the approved recovery set from Section 3. Migrations mutate production data and must remain an explicit, operator-invoked step.

1. Stop the API and capture the final consistent recovery set with Section 3's `make db-backup`.
   Record its approved `setId` and verify the isolated restore proof. Do not continue without it:
   ```bash
   docker compose stop api
   make db-backup
   ```

2. Classify the database read-only, **from the new image**, without starting the service:
   ```bash
   docker compose run --rm --no-deps --entrypoint node api \
     packages/backend/dist/db/status.js
   ```
   Compare the database revision, **not the verdict**, with Section 2: revision 2 is normally
   `current` for the old image and `outdated` for the new revision-3 image. `docker compose run` mounts the same
   `trindade_sqlite_data` volume and inherits the service's `DATABASE_PATH`, so it inspects the
   real production database and not a fresh one. Do not use `docker exec` here: the new
   container is not running yet, and the old one carries the old code and its old migrations.

3. Execute the migration:
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
     | 1 → 2 | Creates `auth_sessions` and its indexes. |
     | 2 → 3 | Adds `users.security_version`, revokes all live refresh sessions, and stamps revision 3. |

   - A revision-2 database runs only the 2 → 3 step. Never restart the old API after this step.

4. Confirm the database is now `current` at revision 3, still from the new image:
   ```bash
   docker compose run --rm --no-deps --entrypoint node api \
     packages/backend/dist/db/status.js
   ```
   Must exit 0 and print `verdict: current` at revision 3. **Do not start the API until it does.**

5. If migration or validation fails, keep the API stopped and use Section 8, Scenario B with
   the final recovery set. Do not boot any revision-2 binary against revision 3.

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
   - **For a migrated database**: `level=30` (info), `verdict=current`, `revision=3`.
   - `level=40` (warn) with `verdict=outdated` or `newer` means the rollout is running against the
     wrong schema. Stop the API and investigate before proceeding.

4. Verify the healthcheck endpoint:
   ```bash
   curl -sS https://trindademasas.duckdns.org/api/health
   ```
   Expect HTTP 200 with `{"status":"ok","timestamp":"..."}`.

5. Every user, including administrators, must sign in again. Verify a real login through
   the HTTPS web or Android client, without placing credentials or tokens in shell history,
   terminal output, or logs. A successful login must transition to the correct role. Any
   authentication failure after migration is a failed rollout gate; keep the API stopped
   while investigating or restore the final recovery set with matching old code.

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

Only use code-only rollback **before** revision-3 migration. After migration, stop the API
and use Scenario B with the final pre-cutover recovery set and matching old code. Never boot
revision-2 code on a revision-3 database or attempt to transplant old sessions into revision 3.

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

4. Confirm schema status is compatible with the reverted build. If migration already ran,
   use Scenario B instead; a `newer` verdict is a stop condition.

---

### Scenario B: Full Data Rollback (Migration failed or data was corrupted)

Use this if the migration failed, data was corrupted, or the release must be fully rolled back
after database writes.

1. Stop the API container to release all database locks and prevent concurrent writes:
   ```bash
   docker compose stop api
   ```

2. Restore the **final pre-cutover** recovery set captured after stopping the API in Section 5.
   Obtain approval for loss of any post-snapshot writes. Restore only with matching old code.
   **Restoring the pre-cutover snapshot also restores its live refresh sessions**; do not
   start the API until those sessions are revoked in the restored database:
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
   - Restarts `trindade-api-1` **only if it was running at invocation**. The API must
     already be stopped; confirm it stays stopped after restore. If it restarted, stop it
     immediately and treat any interim traffic as a security incident.
   - Verifies database integrity (`PRAGMA integrity_check`) and schema status.

3. While the API remains stopped, revoke every restored refresh session in the revision-2
   database and rotate `JWT_SECRET` to a new protected value before restart. Revoking
   refresh sessions alone does **not** invalidate previously issued access JWTs; rotating
   the signing secret does. This is a mandatory rollback gate, not optional cleanup.
   From the current image solely as a SQLite client, with the production database volume
   mounted (this command does not start the API or run its schema-dependent server):
   ```bash
   docker compose run --rm --no-deps --entrypoint node api -e \
     'const Database = require("better-sqlite3"); const db = new Database(process.env.DATABASE_PATH); db.prepare("UPDATE auth_sessions SET revoked_at = datetime(\x27now\x27) WHERE revoked_at IS NULL").run(); db.close()'
   ```
   Verify the session update succeeded and the new signing secret is configured before
   bringing traffic back. Never transplant the old refresh sessions into revision 3.

4. Revert code to the previous commit (Scenario A) if the rollback requires the previous application version:
   ```bash
   git checkout <previous-commit-or-tag>
   docker compose build
   docker compose up -d
   ```

5. Confirm health and schema status:
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
   # Do not start api yet: first complete Scenario B step 3 (revoke restored refresh
   # sessions and rotate JWT_SECRET), restore matching code, then validate before restart.
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
Build images first, then follow the controlled Section 5 sequence (stop API, final backup,
status, migrate if required, validate). Only after revision 3 is current:
```bash
docker compose up -d
```

**Seed behavior**:
A fresh volume loads the operational catalog. Confirm revision 3 with `db:status` before
starting the API; if outdated, stop the API and follow Section 5. Provision the first
administrator out of band with the operator CLI in Section 1. The browser cannot create it.

### Step 5: Transfer existing live database (Optional)

A database transferred from another host arrives at whatever revision it was stamped with. If it
predates the current build, follow Sections 4 and 5 after the transfer using the new image,
with the API stopped. Never start an older image on a revision-3 transferred database. Do not assume the transfer also migrated it.

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
