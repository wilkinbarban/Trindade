#!/usr/bin/env bash
#
# Regenerate the Android client's contract types from packages/contracts/openapi.json.
#
# Why this is a script rather than a Gradle plugin: openapi-generator is a Java tool, and the npm
# wrapper around it is still a wrapper around the same JAR, so a JVM is needed either way -- and no
# image in this project carries both Node and Java. Invoking the JAR directly means this step and the
# freshness check in scripts/ci-android.sh both run wherever Java is guaranteed, which is the Android
# SDK image.
#
# The generated Kotlin is committed, the same way packages/contracts/openapi.json is, so a contract
# change shows up in a review diff instead of hiding inside a build directory. That is the shape the
# repository already trusts for the JSON, and the reason it gives -- a client author can work without
# building the backend -- applies here unchanged.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

generator_version='7.25.0'
# The upstream fingerprint of the release named above, checked against what is actually downloaded.
# Update it in the same commit as the version: an out-of-date constant here is a build that fails
# loudly rather than one that runs an unverified binary. The sha1 that Maven Central publishes
# alongside the JAR would catch corruption on its own, but it is a weak digest, so the sha256 is
# pinned as well and the published sha1 is fetched as a cross-check rather than trusted alone.
generator_jar_sha256='41ce4f6b07f196676439d710759fa1ced7a08066d06ff1bf314681470289efae'

contract='packages/contracts/openapi.json'

# A NESTED directory, never the module root. The generator writes gradle/wrapper/gradle-wrapper.jar,
# gradle-wrapper.properties and proguard-rules.pro into whatever directory it is given, so pointing it
# at packages/android would overwrite this module's own Gradle wrapper.
#
# Overridable so the freshness check in scripts/ci-android.sh can generate beside the committed tree
# and diff the two, which is how that check works without git inside the container.
out_dir="${OPENAPI_GENERATOR_OUT_DIR:-packages/android/contract}"

cache_dir="${OPENAPI_GENERATOR_CACHE_DIR:-${HOME:-/tmp}/.cache/trindade-openapi-generator}"
jar="$cache_dir/openapi-generator-cli-$generator_version.jar"
jar_url="https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$generator_version/openapi-generator-cli-$generator_version.jar"
jar_sha1_url="$jar_url.sha1"

if [[ ! -f "$contract" ]]; then
  printf 'Missing %s. Generate it first: npm run contracts:generate --workspace=packages/backend\n' "$contract" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  printf 'Regenerating the client types needs a JVM, and this host has none.\n' >&2
  printf 'Run it inside the Android SDK image instead:\n\n' >&2
  printf '  docker run --rm --user "$(id -u):$(id -g)" -v "%s:/work" -w /work \\\n' "$repo_root" >&2
  printf '    ghcr.io/cirruslabs/android-sdk:35 bash scripts/generate-android-contract.sh\n' >&2
  exit 1
fi

mkdir -p "$cache_dir"
if [[ ! -f "$jar" ]]; then
  printf 'Downloading openapi-generator %s\n' "$generator_version"
  curl -sSL --fail --retry 3 -o "$jar" "$jar_url"
fi

# Verified on every run, not only after a download, so a corrupted or substituted cached copy cannot
# be reused forever on the strength of merely existing.
actual_sha256="$(sha256sum "$jar" | awk '{print $1}')"
if [[ "$actual_sha256" != "$generator_jar_sha256" ]]; then
  printf 'The generator JAR at %s does not match the pinned sha256.\n' "$jar" >&2
  printf '  expected %s\n  actual   %s\n' "$generator_jar_sha256" "$actual_sha256" >&2
  printf 'Delete that file and re-run; if it is wrong again, the upstream release is not what this script expects.\n' >&2
  exit 1
fi

published_sha1="$(curl -sSL --fail --retry 3 "$jar_sha1_url" | tr -d '[:space:]' || true)"
actual_sha1="$(sha1sum "$jar" | awk '{print $1}')"
if [[ -n "$published_sha1" && "$published_sha1" != "$actual_sha1" ]]; then
  printf 'The generator JAR does not match the sha1 Maven Central publishes for it.\n' >&2
  printf '  published %s\n  actual    %s\n' "$published_sha1" "$actual_sha1" >&2
  exit 1
fi

# Generate into a scratch directory first, so a failure cannot touch the committed tree.
work_dir="$(mktemp -d)"
staging_dir="$out_dir.staging"
trap 'rm -rf "$work_dir" "$staging_dir"' EXIT

java -jar "$jar" generate \
  -i "$contract" \
  -g kotlin \
  -o "$work_dir" \
  --library jvm-retrofit2 \
  --additional-properties="serializationLibrary=kotlinx_serialization,packageName=com.trindade.app.contract" \
  --global-property models,modelDocs=false,modelTests=false,supportingFiles=

# Only the Kotlin is wanted. The generator also emits its own build scaffolding, and a directory whose
# entire job is to hold DTOs should not carry a second Gradle wrapper, a README and a .gitignore that
# compete with the module's own. Filtering here also means a future generator version cannot quietly
# add a file nobody reviews.
generated_count="$(find "$work_dir/src/main/kotlin" -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$generated_count" -eq 0 ]]; then
  # The guard that matters most. Without it, a generator that exits 0 having produced nothing would
  # be followed by the swap below, which would delete the committed types and put nothing in their
  # place -- a green run that destroys an artifact is worse than a red one.
  printf 'The generator produced no Kotlin, so %s is left untouched.\n' "$out_dir" >&2
  printf 'Inspect %s before re-running: an empty result is a failure, not a smaller API.\n' "$work_dir" >&2
  exit 1
fi

# Counted only if the directory exists, because the first run into a fresh destination has nothing to
# count and a bare find would fail there. That failure is silent under `set -e` and `pipefail`, which
# is how it went unnoticed until the freshness check regenerated into an empty directory.
previous_count=0
if [[ -d "$out_dir" ]]; then
  previous_count="$(find "$out_dir" -name '*.kt' | wc -l | tr -d ' ')"
fi
printf 'Regenerating %s: %s -> %s Kotlin files\n' "$out_dir" "$previous_count" "$generated_count"

# Replace by renaming rather than by deleting first, so the directory under its final name always
# holds a complete set of types. Deleting and then copying leaves a window in which it holds none.
rm -rf "$staging_dir"
mkdir -p "$staging_dir/src/main/kotlin"
cp -a "$work_dir/src/main/kotlin/." "$staging_dir/src/main/kotlin/"
mv "$out_dir" "$out_dir.replaced" 2>/dev/null || true
mv "$staging_dir" "$out_dir"
rm -rf "$out_dir.replaced"

printf 'Generated %s Kotlin files into %s\n' "$generated_count" "$out_dir"
