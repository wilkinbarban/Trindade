#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"
printf 'CI gate repository root: %s\n' "$repo_root"

required_major=24
node_major="$(node -p 'process.versions.node.split(".")[0]')"
if (( node_major < required_major )); then
  printf 'CI gate requires Node.js %s or newer; running Node.js %s. Run it in node:24-bookworm-slim (or install Node 24) and retry.\n' "$required_major" "$(node --version)" >&2
  exit 1
fi

if [[ -e node_modules ]]; then
  printf 'CI gate refuses to reuse pre-existing node_modules. Run it from a fresh clone (or remove the directory deliberately) so npm ci starts from a clean dependency tree.\n' >&2
  exit 1
fi

run_step() {
  local name="$1"
  shift
  printf '\n========== %s ==========%s' "$name" $'\n'
  "$@"
}

run_step 'Install dependencies' npm ci
run_step 'Build all workspaces' npm run build
run_step 'Typecheck backend and frontend' npm run typecheck
run_step 'Run backend tests' npm run test --workspace=packages/backend
run_step 'Verify the built schema CLIs' bash scripts/verify-schema-clis.sh
if [[ "${SKIP_E2E:-}" == "1" ]]; then
  printf '\nSkipping Playwright E2E tests (SKIP_E2E=1)\n'
else
  run_step 'Run frontend Playwright E2E tests' npm run test:e2e --workspace=packages/frontend
fi

printf '\n========== CI gate passed ==========%s' $'\n'

# npm run lint is intentionally absent: no workspace defines lint, so the root
# --if-present lint command is a green no-op rather than a verification check.
