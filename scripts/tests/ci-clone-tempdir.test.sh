#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
# Exercise the real recipe's clone setup without invoking git, Docker, or cleanup.
setup=$(awk '
  /^ci-clone:/ { in_target = 1; next }
  in_target && /cleanup\(\)/ { exit }
  in_target {
    sub(/^[[:space:]]*@?/, "")
    sub(/[[:space:]]*\\[[:space:]]*$/, "")
    gsub(/\$\$/, "$")
    print
  }
' Makefile)
[[ -n $setup ]] || { printf 'ci-clone setup not found\n' >&2; exit 1; }

run_case() {
  local name=$1 selected=$2 expected=$3 output status
  if [[ $selected == DEFAULT ]]; then
    output=$(env -u TMPDIR bash -c '
      mktemp() { printf "mktemp:%s\n" "$*" >&2; printf "/mock/clone\n"; }
      export -f mktemp
      bash -c "$1"
    ' _ "$setup" 2>&1) && status=0 || status=$?
  else
    output=$(TMPDIR="$selected" bash -c '
      mktemp() { printf "mktemp:%s\n" "$*" >&2; printf "/mock/clone\n"; }
      export -f mktemp
      bash -c "$1"
    ' _ "$setup" 2>&1) && status=0 || status=$?
  fi
  if [[ $expected == success ]]; then
    [[ $status -eq 0 && $output == *"mktemp:-d -- $name/trindade-ci.XXXXXX"* ]] || {
      printf 'FAIL %s: status=%s output=%s\n' "$name" "$status" "$output" >&2; exit 1;
    }
  else
    [[ $status -ne 0 && $output == *"$expected"* && $output != *'mktemp:'* ]] || {
      printf 'FAIL invalid %s: status=%s output=%s\n' "$name" "$status" "$output" >&2; exit 1;
    }
  fi
  printf 'PASS %s\n' "$name"
}

run_case /tmp DEFAULT success
run_case /var/tmp /var/tmp success
run_case missing /definitely-not-a-ci-clone-directory 'TMPDIR must be an available directory'
run_case file Makefile 'TMPDIR must be an available directory'
