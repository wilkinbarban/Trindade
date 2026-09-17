#!/usr/bin/env bash
set -euo pipefail

# The OpenAPI artifact is committed so a client author can read the API surface without
# building the backend. This script proves the committed artifact is current: it regenerates
# the document from the schemas and fails when the result differs from what is in the
# repository. That is what stops a schema change from being merged without regenerating, and
# what makes a hand-edited artifact impossible to keep.
#
# The generator is invoked through the same entry point the operator command uses, with an
# explicit output path so the committed artifact is never touched by a check.

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

artifact='packages/contracts/openapi.json'
if [[ ! -f "$artifact" ]]; then
  printf 'openapi check failed: missing contract artifact %s\n' "$artifact" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
regenerated="$work_dir/openapi.json"

if ! (cd packages/backend && node --import tsx src/contracts/generate.ts "$regenerated" >/dev/null); then
  printf 'openapi check failed: the contract could not be generated from the schemas\n' >&2
  exit 1
fi

if ! diff -u "$artifact" "$regenerated" >"$work_dir/artifact.diff"; then
  {
    printf 'openapi check failed: %s does not match the schemas it is generated from.\n' "$artifact"
    printf 'Regenerate it with: npm run contracts:generate --workspace=packages/backend\n\n'
    cat "$work_dir/artifact.diff"
  } >&2
  exit 1
fi

printf 'openapi artifact is current: %s\n' "$artifact"
