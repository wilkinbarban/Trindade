# Re-land Android worker enrollment on the clean v0.3.0 chain

Status: in progress
Engram mirror topic: `odd/release-cycle-closure/android-enrollment-ux-slices`
Source: dirty `/home/wilkin/proyectos/Trindade` at `cb9f7d0` (read-only).
Target: clean `/var/tmp/trindade-android-parity-v030`, after `00cc293`.

## Objective and boundaries

Port the worker registration UX from the original dirty checkout in four coherent, dependent work units without copying unrelated Android parity/admin changes. Backend enrollment is committed through `ec7bfda`; Android registration wire types are committed at `44828c2`; the stale setup wire declaration was retired at `00cc293`. Preserve the original dirty checkout, production, signing material and emulator. No push, tag, APK release or deployment in these four units.

The source `MainActivity.kt` and `strings.xml` contain mixed changes; take registration hunks only, never whole-file copies. Each unit requires an isolated pinned-SDK verification, work-unit commit, and native review as applicable. The review-size heuristic is about 400 changed lines; do not compress or omit tests to meet it. If an indivisible unit exceeds the PR gate after one honest split, record the exact size and request a size exception.

TDD: the generic Gentle AI skill prescribes RED/GREEN/REFACTOR when tests exist; the Android test runner is `./gradlew :app:testDebugUnitTest` in the pinned Android SDK image. Record any environment or setup limitations honestly.

## Units

- [x] R1 Repository: `AuthRepository.register` and `RegisterResult` plus `AuthRepositoryTest`. Closed at `b92f36e`; native review `review-8c0401a59001c145`.
- [ ] R2 ViewModel: `RegisterViewModel.kt` and `RegisterViewModelTest.kt`. Split into R2a (form state, updates/reset, basic validation and minimal callable submission/result contract), R2b (boundary/UTF-8 cases), R2c (detailed refusal/transport mapping). R2a closed at `3e2a31d`, acknowledged native review `review-9be6cf207d466d59`. R2b implementation awaits successful isolated verification; R2c and R2 overall remain pending. Do not mistake compile errors for behavioral RED.
- [ ] R3 UI: `RegisterScreen.kt`, `RegisterScreenTest.kt`, registration strings in `strings.xml`, and the registration link in `LoginScreen.kt`. About 457 lines; inspect actual PR budget.
- [ ] R4 Navigation: only registration navigation hunks in `MainActivity.kt` plus registration-specific `MainActivityNavigationTest.kt` assertions; do not port unrelated admin/navigation changes.

## R1 verification (isolated snapshot)

Target HEAD `00cc293`; snapshot `/var/tmp/trindade-r1-verify.pSOSQR` from `git archive HEAD`, with only the tracked `AuthRepository.kt` diff and copied `AuthRepositoryTest.kt` and this task document. Docker SDK image: `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9`; user `1001:1001`, `GRADLE_USER_HOME=HOME=/gradle-home`, cached Gradle and SDK mounts, `/work` snapshot mount, working directory `/work/packages/android`.

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.AuthRepositoryTest --rerun-tasks --no-daemon`: initially failed compilation because the dirty source test referenced retired `SetupRequest`/`SetupResponse` and an absent `setup` API override; removed those unrelated stale declarations. Rerun passed; focused XML: 12 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: passed; 34 XML suites, 285 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: passed; contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: passed; debug APK packaged.

RED: no pre-implementation failure observed; first attempted focused run was after implementation and failed on stale source-only setup references, not the intended registration behavior. GREEN: observed focused rerun passing after adapting the test to the target API. R1 code/test diff: 101 + 314 = 415 added lines, indivisible repository behavior and its fixture-backed tests. No commit or native review in the delegated writing unit.

Independent verifier reconstructed `/var/tmp/trindade-r1-independent.AUFBEB` from `00cc293`: both Kotlin files byte-match the target, and all 577 other versioned files match the base. Its first full run timed out and a retry encountered the shared Gradle cache lock; no test-suite claim is made from that attempt. After a read-only incident diagnosis found no remaining Gradle process or container, a fresh foreground run in the same pinned image passed: `:app:testDebugUnitTest --rerun-tasks --no-daemon` exit 0, **34 XML, 285 tests, 0 failures/errors/skips**; `:app:assembleDebug --no-daemon` exit 0, debug APK 13,677,102 bytes. Contract check exit 0 was observed on the independent snapshot before the timeout. This debug APK is not the signed release artifact.

The complete R1 work unit (101 production + 314 test + 38 planning lines before this evidence addendum) exceeds the 400-line PR gate. The operator explicitly authorized a **size exception for R1** rather than splitting the inseparable behavior/tests; preserve test coverage and record the final changed-line count when the commit is formed.

## R2a verification (isolated snapshot)

Snapshot `/var/tmp/trindade-r2a-verify.YJiLFX` from `git archive HEAD` (`b92f36e`), with only the two new ViewModel Kotlin files and the task document copied; pinned SDK image and cache mounts match R1, Docker runs as host uid:gid. Focused `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterViewModelTest --rerun-tasks --no-daemon` passed (4 tests); full `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon` passed (35 XML suites, 289 tests, 0 failures/errors/skips). `bash /work/scripts/check-android-contract-types.sh` passed and `./gradlew :app:assembleDebug --no-daemon` passed. RED: no legitimate pre-implementation behavioral failure observed; a missing new class would only cause compilation failure, not behavioral RED. GREEN: focused test run passed after implementation. R2a changed lines: 106 production + 111 tests + 2 doc substitutions (4 diff lines) before this evidence paragraph; below 400. R2b/R2c remain pending. No commit or review in this delegated slice.

## R2a reset-in-flight correction (new isolated snapshot)

The earlier R2a verification above describes the **pre-correction** snapshot with four focused tests and 289 full-suite tests; it does not establish reset safety. New snapshot `/var/tmp/trindade-r2a-reset-verify.cldutS` was created from `git archive HEAD` (`b92f36e`) and copied exactly the two ViewModel Kotlin files and this document. Verification used pinned `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9`, host uid:gid, `GRADLE_USER_HOME=HOME=/gradle-home`, cached Gradle/SDK mounts and `/work/packages/android`.

- RED: test-only snapshot, `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterViewModelTest --rerun-tasks --no-daemon` exited 1: 5 tests completed, 1 failed (`reset ignores completion of an in-flight registration`, assertion at `RegisterViewModelTest.kt:80`); the late success overwrote reset state.
- GREEN: after copying the production generation guard into the same snapshot, the identical focused command exited 0 (5 tests, 0 failures/errors/skips).
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: exit 0; 35 XML suites, 290 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0; contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0; debug APK packaged, not a signed release artifact.

The generation is incremented by reset, and completion updates only when its captured generation is current; ordinary successful completion remains covered by the existing focused test. Independent verifier reconstructed `/var/tmp/trindade-r2a-independent.GNlpYs` from `b92f36e` plus exactly the three changed files, confirmed byte identity, and independently passed focused 5/5, full 290/290 (0 failures/errors/skips), contract check and debug assembly in separate pinned-image invocations. R2b/R2c remain pending; this is not release APK evidence.

## R2b verification (pending)

Added focused exact-limit, transmitted-trim, 73 UTF-16-unit and 25-euro-sign (75 UTF-8-byte) cases. A test-only isolated snapshot `/var/tmp/trindade-r2b-verify.FarVZ7` was built from `git archive HEAD` (`3e2a31d`) with only the allowed tracked test diff. The attempted focused RED command did not reach tests: Gradle wrapper could not create `/gradle-home/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0-bin.zip.lck` under host uid:gid (permission denied), both with the local `.gradle` bind and the `trindade-gradle` volume. This is an environment failure, not behavioral RED. Independent verifier then reconstructed `/var/tmp/trindade-r2b-independent.UqNPUK` from `3e2a31d` plus exactly the three tracked diffs, confirmed byte identity and a writable wrapper lock under host UID:GID 1001:1001, and ran separate pinned-image checks: focused 8/8, full 293/293 across 35 XML (0 failures/errors/skips), contract check exit 0, and debug assembly successful. The prior failed test-only attempt remains **no observed behavioral RED**; no emulator or production behavior was verified. R2c remains pending.

## Closure checks

- [ ] Each unit verified in a byte-scoped isolated SDK snapshot, with exact commands, test counts, failures/skips, and image digest recorded.
- [ ] Contract check, full Android JVM suite and debug assembly pass on the integrated chain.
- [ ] Release-signed v0.3.0 APK remains a separate later release task.
