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
  found="$(find app/build/intermediates \
    -path "*${variant}*" -name AndroidManifest.xml \
    ! -path "*androidTest*" 2>/dev/null | grep -i 'merged_manifest' | head -1 || true)"
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
section 'Build the release APK'
./gradlew assembleRelease "-PapiBaseUrl=$release_base_url" --no-daemon

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
section 'Assert a release build refuses to run without a base URL'
guard_log="$(mktemp)"
if ./gradlew assembleRelease --no-daemon >"$guard_log" 2>&1; then
  printf 'A release build succeeded without -PapiBaseUrl. The guard that stops a release from silently pointing at the development loopback is gone or bypassed.\n' >&2
  exit 1
fi
if ! grep -q 'A release build requires' "$guard_log"; then
  printf 'A release build failed without a base URL, but not for the reason this lane expects. Failing for an unknown reason is not the same as the guard working.\n' >&2
  tail -20 "$guard_log" >&2
  exit 1
fi
printf 'the release guard refuses to build without an https base URL.\n'

printf '\nAndroid lane passed.\n'
