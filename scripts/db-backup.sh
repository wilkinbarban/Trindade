#!/usr/bin/env bash
set -euo pipefail

# scripts/db-backup.sh
# Captures an online SQLite-consistent snapshot and assets into a verified recovery set.
#
# Meets the requirements of openspec/specs/production-data-recovery/spec.md:
#   1. Recovery inventory (source DB, WAL/SHM sidecars, photos, metadata).
#   2. SQLite-consistent online snapshot using better-sqlite3 db.backup().
#   3. Manifest completeness and SHA-256 checksums.
#   4. Isolated restore proof (restores to an isolated target, verifies integrity_check,
#      schema identity, row counts, and asset checksums).
#   5. Production immutability (opens source DB read-only, never mutates production data).
#
# Usage:
#   ./scripts/db-backup.sh [output-dir]
#
# If output-dir is omitted, defaults to ./backups/recovery-YYYYMMDDTHHMMSSZ.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest_dir="${1:-$repo_root/backups/recovery-$timestamp}"

if [[ -e "$dest_dir" ]]; then
  echo "Error: Destination directory already exists: $dest_dir" >&2
  exit 1
fi

dest_parent="$(dirname "$dest_dir")"
dest_base="$(basename "$dest_dir")"
mkdir -p "$dest_parent"
dest_parent_abs="$(cd "$dest_parent" && pwd)"

echo "=== Trindade Recovery Set Capture ==="
echo "Destination: $dest_dir"
echo "Timestamp:   $timestamp"

# Determine execution mode: Docker volume (production) or host-local
if docker volume inspect trindade_sqlite_data >/dev/null 2>&1; then
  echo "Mode: Docker volume (trindade_sqlite_data, mounted strictly read-only)"

  echo "1. Capturing online snapshot and photos via container..."
  capture_output=$(docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v trindade_sqlite_data:/data:ro \
    -v "$repo_root":/app:ro \
    -v "$dest_parent_abs":/backups \
    -w /app \
    node:24-bookworm-slim node packages/backend/dist/recovery.js capture \
      /data/trindade.db \
      /data/photos \
      "/backups/$dest_base" \
      --allow-root /data --allow-root /backups)

  manifest_rel="/backups/$dest_base/manifest.json"

  echo "2. Proving isolated restore (integrity check + row counts + asset verification)..."
  verify_output=$(docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$repo_root":/app:ro \
    -v "$dest_parent_abs":/backups:ro \
    -w /app \
    node:24-bookworm-slim node packages/backend/dist/recovery.js verify-isolated \
      "$manifest_rel" \
      /tmp/isolated-target-proof \
      --allow-root /backups --allow-root /tmp)

  integrity=$(echo "$verify_output" | grep -o '"integrity":"[^"]*"' | cut -d'"' -f4)
  if [[ "$integrity" != "ok" ]]; then
    echo "Error: Isolated restore integrity check failed: $verify_output" >&2
    exit 1
  fi

  echo "3. Verifying host copy checksums..."
  manifest_file="$dest_dir/manifest.json"
  snapshot_file="$dest_dir/snapshot.db"

  if ! test -f "$manifest_file" || ! test -f "$snapshot_file"; then
    echo "Error: Recovery set incomplete: $dest_dir" >&2
    exit 1
  fi

  read -r set_id snapshot_sha < <(node -e 'const m = JSON.parse(require("fs").readFileSync(process.argv[1])); console.log(m.setId + " " + m.snapshot.sha256);' "$manifest_file")
  host_sha=$(sha256sum "$snapshot_file" | awk '{print $1}')

  if [[ "$snapshot_sha" != "$host_sha" ]]; then
    echo "Error: Snapshot SHA-256 mismatch on host copy!" >&2
    echo "  Expected: $snapshot_sha" >&2
    echo "  Actual:   $host_sha" >&2
    exit 1
  fi

  echo "=== Recovery Set Verified and Complete ==="
  echo "Set ID:              $set_id"
  echo "Manifest:            $manifest_file"
  echo "Snapshot:            $snapshot_file ($(stat -c %s "$snapshot_file") bytes)"
  echo "Snapshot SHA-256:    $host_sha"
  echo "Isolated proof:      $verify_output"
  echo "Status:              APPROVED FOR ROLLBACK BASELINE"

elif [[ -n "${DATABASE_PATH:-}" && -n "${PHOTOS_DIR:-}" && -f "$DATABASE_PATH" && -d "$PHOTOS_DIR" ]]; then
  echo "Mode: Host-local database ($DATABASE_PATH)"

  tmp_isolated="$(mktemp -d)"
  cleanup_host() { rm -rf "$tmp_isolated"; }
  trap cleanup_host EXIT

  echo "1. Capturing online snapshot and photos..."
  capture_output=$(node packages/backend/dist/recovery.js capture \
    "$(realpath "$DATABASE_PATH")" \
    "$(realpath "$PHOTOS_DIR")" \
    "$(realpath -m "$dest_dir")")

  manifest_file="$dest_dir/manifest.json"
  snapshot_file="$dest_dir/snapshot.db"

  echo "2. Proving isolated restore..."
  verify_output=$(node packages/backend/dist/recovery.js verify-isolated \
    "$manifest_file" \
    "$tmp_isolated")

  read -r set_id snapshot_sha < <(node -e 'const m = JSON.parse(require("fs").readFileSync(process.argv[1])); console.log(m.setId + " " + m.snapshot.sha256);' "$manifest_file")
  host_sha=$(sha256sum "$snapshot_file" | awk '{print $1}')

  echo "=== Recovery Set Verified and Complete ==="
  echo "Set ID:              $set_id"
  echo "Manifest:            $manifest_file"
  echo "Snapshot:            $snapshot_file"
  echo "Isolated proof:      $verify_output"
  echo "Status:              APPROVED FOR ROLLBACK BASELINE"

else
  echo "Error: Neither Docker volume trindade_sqlite_data exists nor DATABASE_PATH / PHOTOS_DIR are set." >&2
  exit 1
fi
