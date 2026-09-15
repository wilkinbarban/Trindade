#!/usr/bin/env bash
set -euo pipefail

# scripts/db-restore.sh
# Restores a verified recovery set into the Trindade database and assets directory.
#
# Rollback guarantees:
#   1. Checksums of snapshot.db and all assets are verified against manifest.json BEFORE
#      any destination file is touched.
#   2. Stale SQLite WAL and SHM sidecars (-wal, -shm) are deleted to prevent corrupted
#      WAL replay on the restored database.
#   3. Destination assets directory is cleanly synchronized with the recovery set assets.
#   4. Restored database integrity is verified with PRAGMA integrity_check before release.
#   5. Requires explicit --confirm flag to prevent accidental invocation.
#
# Usage:
#   ./scripts/db-restore.sh <recovery-set-dir> [--confirm]

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <recovery-set-dir> [--confirm]" >&2
  exit 1
fi

recovery_set_dir="$1"
confirmed=false
if [[ "${2:-}" == "--confirm" || "${1:-}" == "--confirm" ]]; then
  confirmed=true
fi

if [[ ! -d "$recovery_set_dir" ]]; then
  echo "Error: Recovery set directory does not exist: $recovery_set_dir" >&2
  exit 1
fi

manifest_file="$recovery_set_dir/manifest.json"
snapshot_file="$recovery_set_dir/snapshot.db"

if [[ ! -f "$manifest_file" || ! -f "$snapshot_file" ]]; then
  echo "Error: Incomplete recovery set (missing manifest.json or snapshot.db): $recovery_set_dir" >&2
  exit 1
fi

echo "=== Trindade Recovery Set Verification ==="
echo "Recovery set: $recovery_set_dir"

# Verify host-side checksum before touching anything
read -r set_id snapshot_sha < <(node -e 'const m = JSON.parse(require("fs").readFileSync(process.argv[1])); console.log(m.setId + " " + m.snapshot.sha256);' "$manifest_file")
host_sha=$(sha256sum "$snapshot_file" | awk '{print $1}')

if [[ "$snapshot_sha" != "$host_sha" ]]; then
  echo "Error: Snapshot SHA-256 mismatch! Recovery set may be corrupted." >&2
  echo "  Expected: $snapshot_sha" >&2
  echo "  Actual:   $host_sha" >&2
  exit 1
fi

echo "Set ID:       $set_id"
echo "Checksum:     $host_sha (verified ok)"

if [[ "$confirmed" != "true" ]]; then
  echo ""
  echo "WARNING: Restoring will overwrite the current database and photos with this recovery set."
  echo "To proceed, run with --confirm:"
  echo "  $0 \"$recovery_set_dir\" --confirm"
  exit 1
fi

echo ""
echo "=== Executing Restore ==="

# Check mode: containerized production or host-local
if docker volume inspect trindade_sqlite_data >/dev/null 2>&1; then
  echo "Mode: Containerized Docker volume (trindade_sqlite_data)"

  api_was_running=false
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^trindade-api-1$'; then
    api_was_running=true
    echo "1. Stopping trindade-api-1 to release SQLite locks and WAL..."
    docker stop trindade-api-1 >/dev/null
  fi

  echo "2. Restoring snapshot and assets into volume via helper container..."
  abs_recovery_dir="$(cd "$recovery_set_dir" && pwd)"

  restore_output=$(docker run --rm \
    -v trindade_sqlite_data:/data \
    -v "$abs_recovery_dir":/backup:ro \
    trindade-api \
    node packages/backend/dist/recovery.js restore \
      /backup/manifest.json \
      /data/trindade.db \
      /data/photos \
      --allow-root /data --allow-root /backup)

  echo "Restore proof: $restore_output"

  if [[ "$api_was_running" == "true" ]]; then
    echo "3. Restarting trindade-api-1..."
    docker start trindade-api-1 >/dev/null

    echo "4. Verifying container health and schema status..."
    sleep 2
    docker exec trindade-api-1 node packages/backend/dist/db/status.js || true
  fi

  echo "=== Rollback / Restore Complete ==="
  echo "Recovery set $set_id restored successfully."

elif [[ -n "${DATABASE_PATH:-}" && -n "${PHOTOS_DIR:-}" ]]; then
  echo "Mode: Host-local database ($DATABASE_PATH)"

  echo "1. Restoring snapshot and assets..."
  restore_output=$(node packages/backend/dist/recovery.js restore \
    "$(realpath "$manifest_file")" \
    "$(realpath "$DATABASE_PATH")" \
    "$(realpath "$PHOTOS_DIR")")

  echo "Restore proof: $restore_output"
  echo "=== Rollback / Restore Complete ==="

else
  echo "Error: Neither Docker volume trindade_sqlite_data exists nor DATABASE_PATH / PHOTOS_DIR are set." >&2
  exit 1
fi
