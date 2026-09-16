# Production Deployment and Rollback Runbook — Trindade Massas

This runbook defines the operational procedure for deploying updates to Trindade Massas in production with verifiable SQLite consistency, zero accidental data loss, and an immediate rollback guarantee.

---

## 1. Architectural Invariants

- **Stack**: Defined in `docker-compose.yml` (`trindade-api-1` for Fastify API on Node 24; `trindade-web-1` for Nginx SPA; reverse-proxied via external network `portafolio_default`).
- **Data volume**: Named Docker volume `trindade_sqlite_data` mounted at `/app/packages/backend/data`.
  - Database: `/app/packages/backend/data/trindade.db` (SQLite in WAL mode).
  - Report photos: `/app/packages/backend/data/photos`.
- **Production Immutability Gate**: Server startup **never** runs automatic migrations, seeds, or table alterations (`openspec/specs/foundation/spec.md`). Existing databases are classified and reported at boot without modification.
- **Recovery Gate**: Before any production mutation or deployment, a SQLite-consistent online snapshot and asset inventory must be captured and proven via isolated restoration (`README.md` and `openspec/specs/production-data-recovery/spec.md`). A raw file copy is not sufficient because SQLite in WAL mode keeps uncheckpointed pages in the `-wal` sidecar.

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
   - All backend tests passing (244+ tests across 33+ suites).
   - Built schema CLIs verified (`scripts/verify-schema-clis.sh`).

3. Inspect current production health and schema status (read-only):
   ```bash
   make db-status
   # Or inside the running container:
   docker exec trindade-api-1 node packages/backend/dist/db/status.js
   curl -sS https://trindademasas.duckdns.org/api/health
   ```
   Confirm the running service is healthy and note the current schema verdict (`unversioned` or `current`).

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

## 4. Deployment Execution (Build & Rollout)

1. Build the updated production container images:
   ```bash
   docker compose build
   ```

2. Deploy the containers:
   ```bash
   docker compose up -d
   ```

3. Confirm containers are up and healthy:
   ```bash
   docker compose ps
   ```
   Both `trindade-api-1` and `trindade-web-1` should report `Up` and `(healthy)`.

4. Check the startup schema notice:
   ```bash
   docker logs trindade-api-1 | grep "database schema notice"
   ```
   - **For a database predating versioning**:
     Expect `level=30` (info), `verdict=unversioned`, `revision=0`. The database is intact and unmigrated.
   - **For an already-adopted database**:
     Expect `level=30` (info), `verdict=current`, `revision=1`.

5. Verify the healthcheck endpoint:
   ```bash
   curl -sS https://trindademasas.duckdns.org/api/health
   ```
   Expect HTTP 200 with `{"status":"ok","timestamp":"..."}`.

---

## 5. Schema Adoption / Migration (Deliberate Operator Write)

If this deployment includes schema adoption or migrations:

> **Important**: Never run migrations without the approved recovery set from Section 3. Migrations mutate production data and must remain an explicit, operator-invoked step.

1. Classify the database read-only:
   ```bash
   docker exec trindade-api-1 node packages/backend/dist/db/status.js
   ```
   If the database is unversioned, this prints the report and exits 1.

2. Execute the migration:
   ```bash
   docker exec trindade-api-1 node packages/backend/dist/db/migrate.js
   # Or via host Makefile:
   make db-migrate
   ```
   **The migration runner**:
   - Opens the database read-only first to classify.
   - Refuses `incompatible` or `newer` databases without opening them read-write.
   - If `unversioned`: performs the idempotent legacy temperature rebuild (if required), stamps `PRAGMA user_version = 1`, and reports the before and after states.

3. Confirm the database is now `current`:
   ```bash
   docker exec trindade-api-1 node packages/backend/dist/db/status.js
   ```
   Must exit 0 and print `verdict: current`.

---

## 6. Post-Deployment Verification Checklist

Run through these checks before declaring deployment complete:

- [ ] HTTPS redirect: `curl -I http://trindademasas.duckdns.org` returns 301 to HTTPS.
- [ ] TLS certificate: `curl -I https://trindademasas.duckdns.org` returns 200 without SSL warnings.
- [ ] Backend health: `curl -sS https://trindademasas.duckdns.org/api/health` returns HTTP 200 with `status: "ok"`.
- [ ] Frontend SPA: `curl -sS https://trindademasas.duckdns.org/ | grep -q "Trindade"` returns 0.
- [ ] Database schema: `make db-status` exits 0 with `current`.
- [ ] Container logs: `docker logs --tail 30 trindade-api-1` contains zero errors or unhandled rejections.

---

## 7. Rollback Procedures

If any gate or verification fails, execute the appropriate rollback procedure immediately.

### Scenario A: Application/Code Rollback (No database migration was run)

Use this if the new container fails to boot, crashes, or exhibits a frontend regression, and `db:migrate` **was not executed**.

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

---

### Scenario B: Full Data Rollback (Database was migrated or corrupted)

Use this if `db:migrate` failed, data was corrupted, or the release must be fully rolled back after database writes.

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

## 8. Emergency Manual Recovery (No Helper Scripts)

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

## 9. Standalone Deployment on a New VPS (Quick Start)

Trindade is fully autonomous and requires no external Docker networks or shared proxy stacks.

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
A fresh volume automatically loads `seed.sql`, creating the complete operational catalog: all 6 categories, all 57 tasks, all 6 company vehicles, and all 30 drivers/fleteros, stamped to `user_version = 1`. Open `https://trindademasas.duckdns.org` to create the administrator account.

### Step 5: Transfer existing live database (Optional)
To replicate the live database with all historical reports, past loading schedules, audit logs, and photos from your existing server:

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
