#!/usr/bin/env bash
#
# Android lane. Separate from scripts/ci.sh on purpose: that gate is Node-only and deliberately
# refuses to run outside Node 24, while this one needs a JDK and an Android SDK. Keeping them apart
# means the fast gate stays fast and neither has to know about the other's toolchain.
#
# This script assumes the toolchain is already present and says so plainly if it is not, which is the
# same posture scripts/ci.sh takes towards Node. That is what makes it runnable both on a GitHub
# runner, where ubuntu-latest ships an Android SDK, and inside the SDK container that `make
# ci-android` uses on a host that has neither.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
android_dir="$repo_root/packages/android"
cd "$android_dir"
printf 'Android lane project root: %s\n' "$android_dir"

# A release build needs a base URL and the guard that enforces this refuses to guess one. The value
# used here is valid and unresolvable on purpose: nothing in this lane talks to a network, and a URL
# that cannot accidentally reach a real host is the right default for a build check.
release_base_url="${CI_ANDROID_RELEASE_BASE_URL:-https://ci.invalid/}"

# A release build also needs a version, enforced by a guard of the same shape, because the real release
# is built by the tag workflow and its version comes from the tag. These two are the lane's own
# placeholders: this lane is not a release, it only proves that a release builds. The encoding is the
# documented one (major * 10000 + minor * 100 + patch), so 0.0.1 is versionCode 1 and the pair here is
# consistent rather than merely present.
release_version_name="${CI_ANDROID_RELEASE_VERSION_NAME:-0.0.1}"
release_version_code="${CI_ANDROID_RELEASE_VERSION_CODE:-1}"

if ! command -v java >/dev/null 2>&1; then
  printf 'The Android lane needs a JDK on PATH and found none. Install one, or run it in a container with: make ci-android\n' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  printf 'The Android lane needs ANDROID_HOME or ANDROID_SDK_ROOT and found neither. Run it in a container with: make ci-android\n' >&2
  exit 1
fi

section() { printf '\n========== %s ==========\n' "$1"; }

merged_manifest_for() {
  local variant="$1" found
  # `|| true` matters under `set -e` with `pipefail`: without it a missing file kills the script with
  # a bare pipeline error instead of the named diagnosis below.
  # The unit-test variants are excluded, and that is load-bearing rather than tidy: since
  # `unitTests.isIncludeAndroidResources` was turned on for the Compose lane, AGP merges a manifest for
  # the test variant of the same build type too, and `head -1` over several candidates is decided by the
  # filesystem. Landing on `<variant>UnitTest` would leave this assertion reading a manifest no APK is
  # built from, while it claims a property of the shipped build -- so the file has to be the one the APK
  # is packaged from.
  found="$(find app/build/intermediates \
    -path "*${variant}*" -name AndroidManifest.xml \
    ! -path "*androidTest*" ! -path "*UnitTest*" 2>/dev/null | grep -i 'merged_manifest' | head -1 || true)"
  if [[ -z "$found" ]]; then
    # Deliberately fatal. If AGP ever moves these files, this lane must fail loudly rather than skip
    # the assertions that read them and report a green run that checked nothing.
    printf 'Could not find the merged %s manifest under app/build/intermediates. If AGP changed its output layout, update this lookup; do not delete the assertion it feeds.\n' "$variant" >&2
    exit 1
  fi
  printf '%s' "$found"
}

section 'Build the debug APK and run its unit tests'
./gradlew assembleDebug testDebugUnitTest --no-daemon

# The release build earns its place twice: it is the only path that exercises the base-URL guard, and
# its merged manifest is what the security assertions below read.
#
# Signing is deliberately left unconfigured, and that is the lane's whole posture towards the keystore:
# this lane has none and must not have one. The build therefore produces app-release-unsigned.apk on
# purpose, which is what keeps the unsigned path exercised -- -PrequireSigned=true, which the release
# workflow passes, is what turns an absent keystore into a failure there.
section 'Build the release APK'
./gradlew assembleRelease \
  "-PapiBaseUrl=$release_base_url" \
  "-PversionName=$release_version_name" \
  "-PversionCode=$release_version_code" \
  --no-daemon

# The cleartext exemption is a debug-only overlay, and that is a security property rather than a
# detail: a release APK must carry no exemption at all, so the platform default -- cleartext
# forbidden -- applies. Asserting it here is the difference between a property and an intention.
section 'Assert the cleartext exemption is debug-only'
debug_manifest="$(merged_manifest_for debug)"
release_manifest="$(merged_manifest_for release)"

if ! grep -q 'networkSecurityConfig' "$debug_manifest"; then
  printf 'The debug manifest is missing android:networkSecurityConfig, so a debug build cannot reach the local backend and the failure surfaces as an opaque socket error rather than a policy refusal.\n' >&2
  exit 1
fi
if grep -q 'networkSecurityConfig' "$release_manifest"; then
  printf 'The release manifest carries android:networkSecurityConfig. The cleartext exemption has leaked out of src/debug/, which is the one thing that overlay exists to prevent.\n' >&2
  exit 1
fi
for manifest in "$debug_manifest" "$release_manifest"; do
  if ! grep -q 'allowBackup="false"' "$manifest"; then
    printf 'A merged manifest does not set android:allowBackup="false" (%s). Backup can carry a token store off the device once D2 adds one.\n' "$manifest" >&2
    exit 1
  fi
done
printf 'cleartext exemption present in debug and absent in release; allowBackup false in both.\n'

# The guard is a rule with no default, so its failure mode deserves a test. Without this, a future
# refactor could quietly remove the guard and every other check here would still pass.
#
# --rerun-tasks stays, but it is no longer what makes this check correct. The guard used to be a doFirst on
# the task that generates BuildConfig, and Gradle skips a task's actions when it considers that task up to
# date, so this check passed only because the step above ran with a different base URL and forced the task
# to run again. The guard is now its own task with no declared outputs, which is never up to date, so the
# outcome no longer depends on the previous step; the flag is kept because a release build that Gradle
# skipped entirely would be a weaker thing to assert about than one that actually ran.
#
# The version properties are passed here and the grep below is pinned to the base-URL guard's own
# sentence, and both halves are deliberate. The release build now has three rules and two of them open with
# the words "A release build requires", so a grep on that prefix would also be satisfied by a build that
# failed because no version was given -- a test passing for a reason it does not name. Passing the versions
# removes that ambiguity at its source, and the longer prefix makes the claim this section makes the only
# one that can satisfy it.
section 'Assert a release build refuses to run without a base URL'
guard_log="$(mktemp)"
# The log is read on both the success and the failure path below, so it is removed on exit rather than
# after the last read: a failed check exits early, and that is exactly when a stray /tmp file would be
# left behind. Same shape as the other temporary handling in this repository.
trap 'rm -f "$guard_log"' EXIT
if ./gradlew assembleRelease --rerun-tasks --no-daemon \
  "-PversionName=$release_version_name" \
  "-PversionCode=$release_version_code" >"$guard_log" 2>&1; then
  printf 'A release build succeeded without -PapiBaseUrl. The guard that stops a release from silently pointing at the development loopback is gone or bypassed.\n' >&2
  exit 1
fi
if ! grep -q 'A release build requires -PapiBaseUrl=' "$guard_log"; then
  printf 'A release build failed without a base URL, but not with the sentence the base-URL guard prints ("A release build requires -PapiBaseUrl="). Failing for an unknown reason is not the same as the guard working.\n' >&2
  tail -20 "$guard_log" >&2
  exit 1
fi
printf 'the release guard refuses to build without an https base URL.\n'

section 'Assert the client contract types are current'
# Absolute, because this script cd'd into packages/android above and the check lives beside it in the
# repository root. A relative path here resolved to packages/android/scripts/, which does not exist.
bash "$repo_root/scripts/check-android-contract-types.sh"

printf '\nAndroid lane passed.\n'
