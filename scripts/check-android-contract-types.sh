#!/usr/bin/env bash
#
# Prove the committed Android contract types are current.
#
# The types are generated from packages/contracts/openapi.json and committed, so a contract change is
# supposed to arrive with a regenerated client in the same commit. This regenerates into a scratch
# directory and diffs the two, which is the same shape as scripts/verify-openapi-artifact.sh and
# exists for the same reason: a committed generated artifact rots unless something regenerates it and
# fails on the difference.
#
# The diff is a `diff -r` rather than a `git diff` on purpose. The Android lane runs inside a
# container that has Java and no git at all, so the check cannot rely on the repository being
# queryable; two directories on disk are all it needs.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

committed='packages/android/contract'
if [[ ! -d "$committed" ]]; then
  printf 'Contract types check failed: %s does not exist.\n' "$committed" >&2
  printf 'Generate it with: bash scripts/generate-android-contract.sh\n' >&2
  exit 1
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
regenerated="$work_dir/contract"

# The same script the operator runs, pointed elsewhere so it cannot touch the committed tree. Its own
# guards apply unchanged, including the one that refuses an empty result.
if ! OPENAPI_GENERATOR_OUT_DIR="$regenerated" bash scripts/generate-android-contract.sh; then
  printf 'Contract types check failed: the types could not be regenerated.\n' >&2
  exit 1
fi

if ! diff -r "$committed" "$regenerated" >"$work_dir/types.diff" 2>&1; then
  {
    printf 'Contract types check failed: %s does not match packages/contracts/openapi.json.\n' "$committed"
    printf 'Regenerate it with: bash scripts/generate-android-contract.sh\n\n'
    head -40 "$work_dir/types.diff"
  } >&2
  exit 1
fi

printf 'android contract types are current: %s\n' "$committed"
