# Retire the stale setup wire declaration from the Android client

Status: in progress
Owner: Android–Web parity (`odd/tasks/android-web-parity.md`), release gate
Engram mirror topic: `odd/release-cycle-closure/setup-contract-retirement`

## Objective

Make `scripts/check-android-contract-types.sh` pass, and remove a wire declaration the server
contract no longer supports.

`SEC-01` retired public `POST /api/auth/setup`: the route now always answers `410` and carries no
request or response body. The OpenAPI document reflects that exactly — `paths./api/auth/setup.post`
has no `requestBody` and a single `410` response of `ErrorEnvelope`.

The Android client did not follow. Two generated models, `SetupRequest` and `SetupResponse`, are
still committed even though the generator no longer produces them, and `AuthApi.setup` still
declares `@POST("api/auth/setup") suspend fun setup(@Body body: SetupRequest): Response<SetupResponse>`.
The contract check therefore fails on a `diff -r` of the committed models against a fresh
regeneration.

The failure is not only a stale artifact. `setup()` declares a call with a body and a success shape
that the contract says cannot exist, which is a false statement about the wire rather than merely an
out-of-date generated file. Living with a red gate also means every later Android slice inherits a
gate nobody can read.

## Decisions and boundaries

| Concern | Decision |
| --- | --- |
| Models | Delete `SetupRequest.kt` and `SetupResponse.kt` from the committed contract. The generator does not produce them; the contract has no shape for them. |
| `AuthApi.setup` | Remove it. It has no production caller and cannot succeed. Replacing it with `Response<Unit>` would keep a callable dead endpoint in the interface. |
| `AuthApi.setupStatus` | Keep. `GET /api/auth/setup/status` is still a live, documented operation and `SetupStatusResponse` is still generated. |
| Test fakes | The three fakes that implement `AuthApi` lose the now-removed override and its import. No test asserts on `setup()`. |
| Out of scope | Backend, OpenAPI source, generated `openapi.json`, frontend, production, and every other Android slice. |
| Commits | One work unit: behavior and its rationale together, conventional commit, verified in an isolated pinned-snapshot run before it is committed. |

## Tasks

- [x] Delete the two stale generated models and drop `setup()` from `AuthApi`, keeping `setupStatus()`.
- [x] Update the three `AuthApi` test fakes so the interface still compiles.
- [x] Build a pinned Android SDK snapshot with the change applied and record the contract check, full unit suite, and debug build. No separate focused subset was run; the full suite covers the affected test fakes.
- [x] Independently verify the applied overlay and the recorded evidence.
- [ ] Obtain native review closure for the committed work unit.

## Evidence

- `packages/contracts/openapi.json` → `paths["/api/auth/setup"].post` has no `requestBody`, one `410` response.
- `packages/contracts/openapi.json` → `components.schemas` contains `SetupStatusResponse` and no `Setup*Request`/`Setup*Response`.
- `grep` over `packages/android` shows the only references to the two models are `AuthApi.kt` and three test fakes.

## Observed verification (uncommitted overlay)

An independent read-only verifier exported `44828c2`, applied only the six tracked file changes and this task document into `/var/tmp/trindade-setup-verify.uNyD3d`, and ran three separate container invocations with pinned image `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` (image ID and repository digest matched):

- `bash /work/scripts/check-android-contract-types.sh`: exit 0, generated types current.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: exit 0; 33 XML reports, 273 tests, 0 failures, 0 errors, 0 skipped.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0, `BUILD SUCCESSFUL`; output `packages/android/app/build/outputs/apk/debug/app-debug.apk` **in the isolated snapshot only**. This is not the signed v0.3.0 release artifact.

No focused subset ran separately. The verifier found no remaining Android uses of `SetupRequest`, `SetupResponse`, or `AuthApi.setup`, and confirmed `setupStatus()` remains. `AuthInterceptor.kt` still lists `/api/auth/setup` under `TOKENLESS_PATHS`; this was observed but did not fail these checks and is outside this unit's authorized scope. One test path was repeated in the snapshot diff command; it did not duplicate the patch content. No original checkout or production files were changed by verification.

## Verification lane

Pinned Android SDK image `ghcr.io/cirruslabs/android-sdk:35` (the digest recorded in the parity lane),
isolated snapshot under `/var/tmp`, never against the working tree:

- `bash scripts/check-android-contract-types.sh` → expected exit `0` (this is the point of the unit).
- `./gradlew :app:testDebugUnitTest` → expected no failures, no errors, no skips.
- `./gradlew :app:assembleDebug` → expected `BUILD SUCCESSFUL`.
