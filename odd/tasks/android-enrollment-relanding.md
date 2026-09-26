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
- [x] R3 UI, split for review budget. Closed at `52b0693`; native review `review-b8050f196152d412` approved and acknowledged.
  - [x] R3a1: bounded `RegisterScreen.kt` foundation, screen-owned strings, and three Compose render/submit/success tests. Closed at `1c6a43c`; native review `review-d5645ad85b33e2e2` approved and acknowledged with nonblocking informational warnings only.
  - [x] R3a2: remaining UI tests, including 320dp scroll and refusal variants. Six focused Compose tests independently verified (9/9 focused, 311/311 full). Closed at `eae8667`; native review `review-735073c8d4c3b9cf` approved and acknowledged with two nonblocking informational findings.
  - [x] R3b: registration link in `LoginScreen.kt` and its tests. Closed at `52b0693`; native review `review-b8050f196152d412` approved and acknowledged.
- [ ] R4 Navigation: only registration navigation hunks in `MainActivity.kt` plus registration-specific `MainActivityNavigationTest.kt` assertions; do not port unrelated admin/navigation changes. Implemented in this unit; verification pending.

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

R3a2 is closed: commit `eae8667` is the reviewed candidate and native review `review-735073c8d4c3b9cf` is approved and acknowledged. Its two nonblocking informational findings are (1) the pending submit action is announced as an unlabeled disabled button because the pinned Material3 indeterminate `CircularProgressIndicator` publishes no `ProgressBarRangeInfo`, and (2) the shared Gradle cache's nonfatal `CorruptedCacheException` during verification runs. Neither blocks the reviewed candidate.

## R3b implementation (verification pending)

The login form gained its entry to worker registration. `LoginScreen.kt` takes a new `onRegisterClick: () -> Unit = {}` parameter and draws a `TextButton` under the submit action, labelled with the one new string `login_register_link` ("Solicitar cadastro", the same wording `register_submit` already ships). The button is `enabled = !state.submitting`, so it stays available on an unfilled form -- registering does not require a typed credential -- and goes unavailable the moment a sign-in is in flight. `LoginRoute` forwards a new `onNavigateToRegister: () -> Unit = {}` into that callback; neither new parameter is required, so the existing `LoginRoute(onSignedIn = ...)` call site in `MainActivity.kt` compiles unchanged. `MainActivity.kt` is deliberately untouched: registration navigation is R4.

Three focused Compose tests were added to `LoginScreenTest.kt`, taking the class from four tests to seven. They pin: (a) the link being displayed and reporting exactly one callback per press; (b) the link enabled on an empty form while the submit action is disabled, disabled and dispatching nothing while `submitting = true`, and enabled again once the request settles, with the disabled declaration asserted alongside the observed callback count; (c) a 400dp x 320dp viewport where the link sits off-screen and is reachable through `performScrollTo`. The link is always selected by `R.string.login_register_link` rather than a literal typed in the test, so the selector cannot drift from the copy. No prior login test was removed or changed.

RED: observed, in a separate compilable stub. Snapshot `/var/tmp/trindade-r3b-red.WvrHWF` was taken from `eae8667` with the new string and the new tests but with the link rendering removed while the `onRegisterClick` parameter stays declared, so the code compiles and only the behaviour is missing. The focused command then exited 1: **7 tests completed, 3 failed** -- exactly the three new tests (`the registration link is shown and reports exactly one click per press`, `the registration link stays available on an empty form and stops while a sign-in is pending`, `a short viewport scrolls to the registration link instead of losing it`) -- while all four pre-existing login tests passed. Observed messages: "The component with Text + InputText + EditableText contains 'Solicitar cadastro' is not displayed!", "Failed to assert the following: (is enabled)", and "Action performScrollTo() failed." GREEN: the same focused command on the real candidate passed 7/7, and the full suite, contract check and debug assembly passed below. Production diff is 19 added lines in `LoginScreen.kt` (import, parameter, and the link block plus its rationale), 6 added string lines, and 101 test lines. No commit or native review in this delegated writing unit; parent owns verification, commit and review.

## R3b verification (isolated snapshot)

Snapshot `/var/tmp/trindade-r3b-verify.WnCV7P` was built from `git archive HEAD` (`eae8667`) plus exactly the four tracked diffs (the two Kotlin files, `strings.xml` and this document). All four files byte-matched the worktree at verification time. The pinned image `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` ran as host UID:GID 1001:1001 with `trindade-android-sdk:/opt/android-sdk-linux`, `GRADLE_USER_HOME=HOME=/gradle-home`, and workdir `/work/packages/android`. Four separate foreground runs:

- `./gradlew :app:testDebugUnitTest --tests com.trindade.app.auth.LoginScreenTest --rerun-tasks --no-daemon`: exit 0; `LoginScreenTest` 7 tests, 0 failures/errors/skips.
- `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`: exit 0; 36 XML suites, 314 tests, 0 failures/errors/skips.
- `bash /work/scripts/check-android-contract-types.sh`: exit 0; android contract types are current (`packages/android/contract`).
- `./gradlew :app:assembleDebug --no-daemon`: exit 0; debug APK 13,699,038 bytes, not release-signed.

Gradle home. The shared `trindade-gradle` cache could not be used with the required host UID:GID because the writer observed a wrapper-lock write denial; it was left untouched. Independent readback found its cited lock file owned by `wilkin` with mode 644, and 647 root-owned cached files world-readable with mode 644, so the writer's broader claim that those files were unreadable/mode 600 was incorrect. A separate UID-owned copy `/var/tmp/trindade-r3b-gradle-owned` was used instead; the independent verifier reused that copy and reproduced focused 7/7, full 314/314, contract and debug assembly in four distinct pinned-SDK runs, with no lock, permission or cache-corruption warnings. The verifier confirmed all 585 tracked snapshot files byte-identical to the candidate and the APK is debug (`CN=Android Debug`), never release-signed. It independently inspected the compilable RED stub, log and XML but did not rerun RED. The temporary seed tar was deleted after verification as redundant (+1,460,367,360 available bytes); the UID-owned cache and remaining partial/root-owned copy are held for scoped cleanup. The verifier also left two 42-byte external pointer files contrary to its instructions; they are not candidate files. Emulator and production behaviour remain unverified; commit and parent-owned native review remain pending.

## R3 closure

R3 is closed at `52b0693` (`feat(android): link login to worker registration`); native review `review-b8050f196152d412` is approved and acknowledged. That commit carries the login form's entry to worker registration (`LoginScreen.kt` plus three focused tests), the shared strings, and this document. Registration navigation in `MainActivity.kt` is deliberately not in it: that is R4.

## R4 implementation (verification pending)

`MainActivity.kt` gained the registration destination and nothing else from the dirty reference's mixed navigation change. Three edits: a `registerOpen` flag beside `signedIn`; `registerOpen = false` in `endSession()` with the rest of the per-session state, so the next operator does not land on the previous one's registration form; and a `!signedIn && registerOpen -> RegisterRoute(onBackToLogin = { registerOpen = false })` branch drawn ahead of the login branch, because registration is reached *from* login and the login branch would otherwise win while the flag is still set. The `LoginRoute` call gained `onNavigateToRegister = { registerOpen = true }`. The dirty reference's `BackHandler` wrappers, `AppRoot`/`MainAppShell` shell, admin/Tasks/Drivers tabs and `PrimaryScrollableTabRow` were **not** ported: they belong to the parity track, not to registration navigation, and this chain has no such shell. Production diff: 18 added lines, no deletions.

`MainActivityNavigationTest.kt` is new -- the clean chain had no such file, and the dirty reference's copy is mostly `AppRoot`/shell and admin-tab tests. Only the registration-specific assertions were ported: the login link opens the registration form and the back action returns to login with no session, and a successful request shows the neutral confirmation and returns to login without ever signing in. The dirty file's `AppRoot`, `MainAppShell`, tab-selection, role-filtering and Pixel-width tests are omitted because the composables under test do not exist here. The tests render the real `LoginScreen` and `RegisterScreen` (the same pair `LoginRoute`/`RegisterRoute` attach a view model to); the activity's one-line branch is mirrored by the host. Commit, independent verification and parent-owned review remain pending.

## R4 verification (unrun -- shared Gradle cache permission block)

Snapshot `/var/tmp/trindade-r4-verify.A5MVGv` was built from `git archive HEAD` (`52b0693`) plus exactly the two changed Kotlin files and this document; both Kotlin files byte-match the worktree. The pinned image `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9` was launched as host UID:GID 1001:1001 with `trindade-android-sdk:/opt/android-sdk-linux`, `GRADLE_USER_HOME=HOME=/gradle-home` backed by `/var/tmp/trindade-r3b-gradle-home`, and workdir `/work/packages/android`. The focused command

`./gradlew :app:testDebugUnitTest --tests com.trindade.app.MainActivityNavigationTest --rerun-tasks --no-daemon`

did not reach the tests: the wrapper bootstrap failed with `java.io.FileNotFoundException: /gradle-home/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0-bin.zip.lck (Permission denied)`. The whole `wrapper/` and `caches/` tree of that Gradle home is root-owned (directories mode 755, files mode 644), so UID 1001 cannot create or truncate the wrapper lock; `caches/journal-1/journal-1.lock` and `caches/modules-2/modules-2.lock` are likewise root-owned, so a run that got past the wrapper would still be denied the dependency cache. No UID-owned Gradle home with the 9.6.0 distribution exists under `/var/tmp`. Per this unit's own instruction -- do not create a new multi-GB Gradle cache copy or tar -- no cache was copied or repaired and no root or alternate-user run was attempted; the focused test, full suite, contract check and debug assembly are **unrun** and must be executed by the parent or in a UID-owned cache. This is an environment block, not behavioral RED; no GREEN is claimed. The snapshot above is left in place for the parent.

## Closure checks

- [ ] Each unit verified in a byte-scoped isolated SDK snapshot, with exact commands, test counts, failures/skips, and image digest recorded.
- [ ] Contract check, full Android JVM suite and debug assembly pass on the integrated chain.
- [ ] Release-signed v0.3.0 APK remains a separate later release task.
