#!/usr/bin/env bash
set -euo pipefail

# Exercise the schema commands the way the API image runs them (node dist/db/*.js) and the
# way the README documents them (npm run db:status), then re-assert their safety contract on
# those artifacts:
#
#   * a missing database is refused and is never created,
#   * a fresh installation reports `current`,
#   * the operator adoption path stamps an unversioned database without rebuilding it,
#   * the read-only status command leaves the file byte-identical.
#
# The backend test suite covers the same commands from source through tsx; this script is
# about the built artifacts and the npm script wiring, which the suite does not exercise.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

status_cli='packages/backend/dist/db/status.js'
migrate_cli='packages/backend/dist/db/migrate.js'
work_dir="$(mktemp -d)"
db_path="$work_dir/trindade.db"

CLI_RC=0
CLI_OUT=''

fail() {
  printf 'schema CLI check failed: %s\n' "$1" >&2
  if (( $# > 1 )); then printf '%s\n' "$2" >&2; fi
  exit 1
}

run_cli() {
  local rc=0
  CLI_OUT="$("$@" 2>&1)" || rc=$?
  CLI_RC="$rc"
}

require_cli() { # expected_exit needle description
  if (( CLI_RC != "$1" )); then
    fail "$3: expected exit $1, got $CLI_RC" "$CLI_OUT"
  fi
  if [[ "$CLI_OUT" != *"$2"* ]]; then
    fail "$3: output did not contain '$2'" "$CLI_OUT"
  fi
  printf '  %s\n' "$3"
}

[[ ! -e "$db_path" ]] || fail "the fixture path already exists: $db_path"

run_cli node "$status_cli" "$db_path"
require_cli 1 'no database at' 'status: refused a missing database (exit 1) and created no file'
[[ ! -e "$db_path" ]] || fail "schema status created $db_path"

run_cli node "$migrate_cli" "$db_path"
require_cli 1 'no database at' 'migrate: refused a missing database (exit 1) and created no file'
[[ ! -e "$db_path" ]] || fail "schema migrate created $db_path"

# A fresh installation is created and stamped by the built database module.
DB_MODULE="$repo_root/packages/backend/dist/db/index.js" DB_PATH="$db_path" \
  node -e "import(process.env.DB_MODULE).then((module) => { module.openDatabase(process.env.DB_PATH).close(); })"

run_cli node "$status_cli" "$db_path"
require_cli 0 'verdict: current' 'status: reported a fresh installation as current (exit 0)'

run_cli npm run --silent --workspace=packages/backend db:status -- "$db_path"
require_cli 0 'verdict: current' 'npm run db:status: reached the same verdict through the operator script'

run_cli node "$migrate_cli" "$db_path"
require_cli 0 'already current' 'migrate: left a current database alone (exit 0)'

# The operator adoption path: an existing database in the current shape, created before
# versioning, is stamped rather than rebuilt. This is the production adoption case.
# It runs every step above revision 0, which for a database already in the current shape
# is a no-op rebuild plus the additive revision 2 step, and ends at the supported revision.
DB_PATH="$db_path" node -e "
  const { createRequire } = require('node:module');
  const Database = createRequire(process.cwd() + '/package.json')('better-sqlite3');
  const base = new Database(process.env.DB_PATH);
  base.pragma('user_version = 0');
  base.close();
"

run_cli node "$status_cli" "$db_path"
require_cli 1 'verdict: unversioned' 'status: reported an unversioned database (exit 1)'

run_cli node "$migrate_cli" "$db_path"
require_cli 0 'stamped user_version = 2' 'migrate: adopted an unversioned database by stamping revision 2 (exit 0)'

run_cli node "$status_cli" "$db_path"
require_cli 0 'verdict: current' 'status: reported the adopted database as current (exit 0)'

# The status command is documented as read-only, so the file must not change under it.
before="$(sha256sum "$db_path" | cut -d' ' -f1)"
node "$status_cli" "$db_path" >/dev/null 2>&1 || true
after="$(sha256sum "$db_path" | cut -d' ' -f1)"
[[ "$before" == "$after" ]] || fail 'schema status modified the database file'
printf '  %s\n' 'status: left the database byte-identical'

rm -rf "$work_dir"
printf 'schema CLI check passed\n'
