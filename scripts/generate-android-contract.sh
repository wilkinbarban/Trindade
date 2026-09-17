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
contract='packages/contracts/openapi.json'

# A NESTED directory, never the module root. The generator writes gradle/wrapper/gradle-wrapper.jar,
# gradle-wrapper.properties and proguard-rules.pro into whatever directory it is given, so pointing it
# at packages/android would overwrite this module's own Gradle wrapper.
out_dir='packages/android/contract'

cache_dir="${OPENAPI_GENERATOR_CACHE_DIR:-${HOME:-/tmp}/.cache/trindade-openapi-generator}"
jar="$cache_dir/openapi-generator-cli-$generator_version.jar"
jar_url="https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/$generator_version/openapi-generator-cli-$generator_version.jar"

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

# Generate into a scratch directory first, so a failure cannot leave the committed tree half-written.
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

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
rm -rf "$out_dir"
mkdir -p "$out_dir/src/main/kotlin"
cp -a "$work_dir/src/main/kotlin/." "$out_dir/src/main/kotlin/"

printf 'Generated %s Kotlin files into %s\n' "$(find "$out_dir" -name '*.kt' | wc -l)" "$out_dir"
