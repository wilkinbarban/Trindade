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
- [x] R2 ViewModel: `RegisterViewModel.kt` and `RegisterViewModelTest.kt`. Split into R2a (form state, updates/reset, basic validation and minimal callable submission/result contract), R2b (boundary/UTF-8 cases), R2c (detailed refusal/transport mapping). R2a closed at `3e2a31d`, acknowledged native review `review-9be6cf207d466d59`. R2b closed at `2d5792e`, native review `review-98a65f1822c4363d`. R2c1 HTTP result/refusal verified in isolation and native review `review-9eb94dff01b00bea` closed; R2c2 transport behavior verified in isolation. R2 closed at `6720364`; native review `review-8dd5b2bf1f2586f1` closed. Do not mistake compile errors for behavioral RED.
- [ ] R3 UI, split for review budget:
  - [ ] R3a1: bounded `RegisterScreen.kt` foundation, screen-owned strings, and three Compose render/submit/success tests. Closed at `1c6a43c`; native review `review-d5645ad85b33e2e2` approved and acknowledged with nonblocking informational warnings only. Leave the checkbox open until the parent closes R3.
  - [ ] R3a2: remaining UI tests, including 320dp scroll and refusal variants. Six focused Compose tests independently verified (9/9 focused, 311/311 full); parent commit/review pending.
  - [ ] R3b: registration link in `LoginScreen.kt` and its tests.
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

## R2c1 verification (isolated snapshot)

From `git archive HEAD` (`2d5792e`), snapshot `/var/tmp/trindade-r2c1-verify.gFvwpX` received only the allowed ViewModel and test tracked diffs. Pinned SDK `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` ran as UID:GID 1001:1001 with cached Gradle/SDK mounts, `GRADLE_USER_HOME=HOME=/gradle-home` and workdir `/work/packages/android`. The first focused run failed 1/14 because the test reused a consumed Retrofit error body across four submissions; this was a test fixture defect, not behavioral RED. Rebuilding the response on each iteration corrected it. No pre-implementation behavioral RED was observed: the provisional HTTP mapping already implemented these outcomes. The HTTP mapping was extracted into a named helper without changing the reset generation guard or 72-byte validation.

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterViewModelTest --rerun-tasks --no-daemon`: exit 0, 14 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: exit 0, 35 XML suites, 299 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0, contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0, debug APK assembled (not release-signed).

Six registration-specific tests cover conditional neutral success/password clearing, 400 default translation, 409 server words, 429 with numeric and unusable Retry-After, and clearing HTTP errors on editing each field. Independent verifier reconstructed `/var/tmp/trindade-r2c1-independent.AHtEJs` from `2d5792e` plus exactly these three diffs, confirmed byte identity and separately passed focused 14/14, full 299/299 (0 failures/errors/skips), contract check and debug assembly in the pinned SDK. Transport variants are explicitly reserved for R2c2; R2 overall remains pending. R2c1 native review `review-9eb94dff01b00bea` closed. No release-signed APK or production verification is claimed.

## R2c2 verification (isolated snapshot)

Snapshot `/var/tmp/trindade-r2c2-verify.HxQGaO` from `git archive HEAD` (`553983a`) received only tracked diffs for the two ViewModel paths and this task document. The pinned SDK image `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` ran as host UID:GID, with `GRADLE_USER_HOME=HOME=/gradle-home`, cached Gradle/SDK mounts and `/work/packages/android`. Separate foreground checks:

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterViewModelTest --rerun-tasks --no-daemon`: exit 0; focused suite 17 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: exit 0; 35 XML suites, 302 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0; Android contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0; debug APK assembled, not release-signed.

RED: no behavioral RED observed; pre-existing ViewModel transport mapping already satisfies the added tests. GREEN: focused tests pass with no production change. Five transport causes (Timeout, Tls, UnreadableBody, NoRoute, Unknown) are pinned with refusal state, no success/session outcome and password retention; a subsequent edit clears the failure and permits retry. Duplicate submit while pending and reset before late transport failure preserve initial state. Existing R2a/R2b/R2c1 tests cover validation, boundaries, neutral success/password clearing, HTTP refusals and late success. Independent verifier reconstructed `/var/tmp/trindade-r2c2-independent.D4Pr41` from `553983a` plus only the test/document diffs, confirmed byte identity, and passed focused 17/17, full 302/302 (0 failures/errors/skips), contract check and debug assembly in separate pinned-image runs. It created one extraneous external snapshot-path pointer at `/var/tmp/trindade-r2c2-snapshot-path`; this is not a repository change and remains for later cleanup inventory. R2 behavior is accounted for; leave the work-unit checkbox open until parent-controlled commit and review. No emulator or production claim.

## R3a1 verification (isolated snapshot)

Parent snapshot `/var/tmp/trindade-r3a1-verify.LDXYLo` from `git archive HEAD` (`6720364`) plus only four R3a1 paths first failed test compilation on an invalid `assertDoesNotExist` import, not a behavioral RED. After removing that import, the focused Compose tests passed. An independent verifier reconstructed `/var/tmp/trindade-r3a1-independent.UExNWp` from the same HEAD and exactly the four paths; all four copies byte-matched the worktree. Separate pinned SDK `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` runs as host UID:GID passed:

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterScreenTest --rerun-tasks --no-daemon`: 3 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: 36 XML suites, 305 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0; contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0; debug APK 13,698,646 bytes, not release-signed.

No behavioral RED observed; source and focused tests establish initial worker notice, disabled empty/pending submit, and success replacing the form with a back callback. Refusal rendering, short-viewport reachability, emulator and production remain outside R3a1. A nonfatal Gradle cache trace did not affect results. The verifier also left a 42-byte external snapshot pointer; it is not part of the candidate and will be handled in rolling cleanup after review.

R3a1 is closed: commit `1c6a43c` is the reviewed candidate, and native review `review-d5645ad85b33e2e2` is approved and acknowledged with only nonblocking informational warnings. R3a1 is not reopened by R3a2; R3a2 changes only the test file and this document.

## R3a2 implementation (verification pending)

Six focused Compose tests were added to `RegisterScreenTest.kt`, taking the class from three to nine tests. They pin: (a) `Validation` and `FromServer` refusals rendered verbatim, and the previous refusal leaving the screen when the message is replaced; (b) `RateLimited` with `retryAfterSeconds = 45` rendering the formatted `register_rate_limit_wait` sentence with the server's own words suppressed, and the same message with no seconds rendering the server words instead of the formatted sentence; (c) all five transport causes (`Timeout`, `Tls`, `UnreadableBody`, `Unreachable`, `Unknown`) rendering their own distinct sentence; (d) a 400dp x 320dp viewport where the submit action is off-screen, keeps its 40dp minimum height, and is reachable through `performScrollTo`; (e) both secret fields drawing the `PasswordVisualTransformation` mask (one U+2022 bullet per character) and staying declared as obscured through `SemanticsProperties.Password`; (f) a pending submission disabling the back action, with the click callback observed not to fire. The `text` helper gained a `vararg` argument list so the formatted rate-limit string is selected by resource id rather than by a literal typed in the test.

`RegisterScreen.kt` was **not** changed. None of the behaviors above revealed a gap: each one is the behavior the R3a1 screen already implements, so the slice is test-only rather than a speculative production edit. One candidate gap is deliberately left to the parent as a human decision instead of being guessed at here: while a submission is pending, the button's label is replaced by a bare `CircularProgressIndicator` (R3a1 pins the label's absence), and the pinned Material3 1.4.0 indeterminate circular indicator publishes no `ProgressBarRangeInfo` semantics, so the pending action is announced as an unlabeled disabled button. Fixing it needs either a new pending string (outside this unit's allowed edit surface) or a reuse decision, so it is recorded as an R3a2 finding with no code change.

The delegated writer did not run a check; the independent verifier reconstructed `/var/tmp/trindade-r3a2-independent.iiaGP7` from `git archive HEAD` (`1c6a43c`) plus exactly the two tracked diffs. Both source files byte-matched the worktree before and after four separate pinned SDK `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` runs as host UID:GID:

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.RegisterScreenTest --rerun-tasks --no-daemon`: 9 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: 36 XML suites, 311 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0, contract types current.
- `./gradlew :app:assembleDebug --no-daemon`: exit 0, 13,698,646-byte **debug** APK, not release-signed.

RED: no behavioral RED observed; the existing screen already implements these outcomes. The shared Gradle cache emitted a nonfatal `CorruptedCacheException` for `/gradle-home/caches/journal-1/file-access.bin` in all runs; no cache repair or deletion was performed. Emulator and production behavior remain unverified; commit and parent-owned native review remain pending.

## Closure checks

- [ ] Each unit verified in a byte-scoped isolated SDK snapshot, with exact commands, test counts, failures/skips, and image digest recorded.
- [ ] Contract check, full Android JVM suite and debug assembly pass on the integrated chain.
- [ ] Release-signed v0.3.0 APK remains a separate later release task.
