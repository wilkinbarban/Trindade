# ODD Task: ReportDetailScreenTest Phase-2 Isolated Staging & Verification

Status: STAGED — Awaiting independent review and parent execution dispatch.
Target: wilkin@10.50.0.3:/home/wilkin/trindade-hilt-root-ym2yq4lo
Class under test: `com.trindade.app.reports.ReportDetailScreenTest` (9 tests)
Suite: `:app:testDebugUnitTest`

## 1. Authorized Scope

- **Target Staging Root**: `/home/wilkin/trindade-hilt-root-ym2yq4lo` on remote host `wilkin@10.50.0.3`.
- **Authorized Staging Files**:
  1. `odd/tasks/report-detail-phase2.md` (task contract & ODD tracking)
  2. `snapshot/diagnostic/tests.init.gradle.kts` (Gradle init script configuring Test filter and task graph guards)
  3. `preflight-phase2.py` (fail-closed isolation runner and barrier verifier)
- **Target Task & Class**:
  - Gradle task: `:app:testDebugUnitTest`
  - Exact test class: `com.trindade.app.reports.ReportDetailScreenTest`
  - Snapshot test count: exactly 9 `@Test` methods located under `app/src/testDebug/java/com/trindade/app/reports/ReportDetailScreenTest.kt`
  - Test runner: `AndroidJUnit4` with `GraphicsMode.NATIVE`, `isIncludeAndroidResources = true`, Robolectric 4.17, targetSdk 35 (`robolectric.properties`).
- **Dependencies & Mounts**:
  - Offline Robolectric dependency directory: `/home/wilkin/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7` mounted read-only to `/robolectric-deps` (JAR size 200,643,142 bytes).
  - Qualified host cache metadata (`metadata-2.107`) local-seeded into `run-phase2/gradle-home/caches/modules-2/metadata-2.107`.
  - Nested read-only bind for host artifact cache (`/home/wilkin/.gradle/caches/modules-2/files-2.1`) into guest `/home-gradle/caches/modules-2/files-2.1`.
  - Read-only toolchains: OpenJDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), Android SDK (`/home/wilkin/Android/Sdk`), Gradle 9.6.0 distribution (`/home/wilkin/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0`).
- **Strict Prohibitions**:
  - NO network access (loopback-only disconnected network namespace).
  - NO downloads or remote repository queries.
  - NO backend or external services.
  - NO ADB, emulator, docker, sysctl, or privileged fallback.
  - NO production code modifications.
  - NO git commits, branches, or tags.
  - Quarantines OPEN: NEVER read/stat/copy `/tmp/pi-bash-92478fc1be7ecb4b.log` or `/tmp/pi-bash-1dc07be63ff4e6c4.log`.

## 2. Checkpoints & Lifecycle

### Checkpoint A: Preparation (Staging Only)
- Pre-existing runs preserved (`run-phase1` intact).
- Runner mandates new exclusively-created `run-phase2` (aborts if exists, no deletion).
- Static AST parse of Python runner with `PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B`.
- Init script syntax and structure verified.
- NO JVM or Gradle executed during staging/preparation.

### Checkpoint B: Independent Barrier Review
- Parent dispatches independent reviewer to audit isolation barriers and init script.
- Review gates the single execution run.
- Zero runtime mutation occurs before review disposition.

### Checkpoint C: Single-Run Execution
- Exactly ONE test invocation authorized with timeout of 310 seconds.
- NO retries under any circumstance.
- Pre-JVM barriers executed in the SAME namespace immediately before `exec`:
  1. Effective read-only enforcement (`/rotest/probe` readable, write fails with EROFS).
  2. Loopback-only network namespace (`/proc/net/dev` contains only `lo`).
  3. Privilege bounding: `NoNewPrivs == 1`.
  4. Capability dropping: `CapEff == 0000000000000000`.
- Process tree stopped cleanly in `finally` upon completion, error, or timeout.
- Host cache fingerprint captured before and after in `finally`; any cache diff fails closed.
- XML verification:
  - Validates `TEST-com.trindade.app.reports.ReportDetailScreenTest.xml` produced in this run.
  - Asserts exact class, exactly 9 tests, 0 failures, 0 errors, 0 skipped.
  - Stale results excluded/cleaned.

## 3. Configuration & Scope Clarifications

- **`nativeExportGate` Flag**: Kept false by default. `nativeExportGate` governs `androidTest` instrumentation dependencies (`com.google.dagger:hilt-android-testing:2.60.1`, `AndroidJUnitRunner`, `.nativegate` suffix). `:app:testDebugUnitTest` is a host JVM Robolectric suite and does not run against `androidTest` runtime dependencies.
- **Contract Boundary**: Passing the 9 tests of `ReportDetailScreenTest` verifies ReportDetail screen rendering, edit button gating, loading states, clipboard operations, and touch targets under Robolectric native graphics mode. It does NOT prove MainActivity recreation or close unverified Use Cases (U/C).
