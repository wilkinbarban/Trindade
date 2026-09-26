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

- [ ] R1 Repository: `AuthRepository.register` and `RegisterResult` plus `AuthRepositoryTest`. Expected paths: `packages/android/app/src/main/java/com/trindade/app/auth/AuthRepository.kt` and new `packages/android/app/src/test/java/com/trindade/app/auth/AuthRepositoryTest.kt`. Implementation and verification passed; commit and native review still pending.
- [ ] R2 ViewModel: `RegisterViewModel.kt` and `RegisterViewModelTest.kt`. About 766 lines; find honest review slices or request an explicit size exception.
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

## Closure checks

- [ ] Each unit verified in a byte-scoped isolated SDK snapshot, with exact commands, test counts, failures/skips, and image digest recorded.
- [ ] Contract check, full Android JVM suite and debug assembly pass on the integrated chain.
- [ ] Release-signed v0.3.0 APK remains a separate later release task.
