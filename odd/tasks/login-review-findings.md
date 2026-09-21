# ODD Feature: Login Review Findings (Seven Open Findings)

Status: **complete, verified, uncommitted**
Created: 2026-09-21
Engram mirror topic: `odd/login-review-findings/tasks`
Source: `review-4c6e679f08feb21b`, the review of commit `0117242`
("fix(android): a login that fails now says which failure it was, in the result and in logcat").
The review was **approved** — none of these blocked delivery — so they are later work, never a
reason to re-run a review of the same candidate.

## Objective

Close the seven findings in one pass, because six of them are two root causes.

## Root cause 1 — the taxonomy contradicted the screen

`UnreachableCause.UnreadableBody` was the `else` bucket for any unclassifiable `Throwable`
while its name and KDoc described one specific case, and `toMessage()` collapsed `Tls` and
`UnreadableBody` into a single `LoginMessage.Unreachable` that renders
*"Sem conexão com o servidor. Verifique a rede e tente de novo."* A connection that **was**
made and a reply that **did answer** were both reported as a missing network.

**This is the shape of the incident that started the whole line of work:** the real failure was
an unreadable body (`MissingFieldException` from a stale backend), and the screen would still
have told the operator to check a network that was working.

## Root cause 2 — the test setting was broader than its reason

`testOptions { unitTests.isReturnDefaultValues = true }` is **suite-wide**: it makes every
unmocked Android framework method return a default instead of throwing, so unrelated JVM tests
can silently pass while exercising stubs. It was added for one `android.util.Log` call, and the
comment beside it described only that, hiding the real effect.

The fix is not a better comment. The fix is to remove the need for the setting.

## Checklist

- [x] **T1 — Log seam (R2-001, R3-001).** `AppLogger` with `AndroidAppLogger` and
      `NoOpAppLogger`; `LoggingModule` binds it in `SingletonComponent`; `AuthRepository`
      takes it as a constructor parameter defaulting to `NoOpAppLogger`, so production gets the
      Android implementation from Hilt and a test that does not care gets silence.
      `Log.w` in `AuthRepository` is gone; `unitTests.isReturnDefaultValues = true` is gone.
- [x] **T2 — Taxonomy and sentences (R2-003, R2-002, R4-001).** `UnreachableCause.Unknown`
      holds the residue; `UnreadableBody` is now strictly `SerializationException`.
      `LoginMessage` gained `Tls` and `UnreadableBody` with their own strings. The timeout
      sentence became *"O servidor não respondeu a tempo. Tente de novo."* — true of both
      OkHttp's connect timeout and its read timeout, because the exception type cannot tell
      them apart.
- [x] **T3 — Coverage and assertions (R2-004, R3-002).** `RecordingAppLogger` makes the log
      line assertable for the first time. New tests: a refused connection (`ConnectException`)
      and a reset connection (`SocketException`) both classify `NoRoute`; a residue `IOException`
      classifies `Unknown`; a failed handshake and an unreadable body reach the screen as their
      own messages. The `IOException` comment that called it "the bucket case" now calls it what
      it is.
- [x] **T4 — Verification.**

## Verification evidence

| Check | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest --no-daemon --rerun-tasks` in `ghcr.io/cirruslabs/android-sdk:35` | **BUILD SUCCESSFUL in 4m 51s**, 33/33 tasks executed |
| Parsed from `build/test-results/testDebugUnitTest/*.xml` | **200 tests, 0 failures, 0 errors, 0 skipped** |

195 tests before this pass, 200 after: five new tests, none removed, none skipped.

## The check that matters, and why it is the XML rather than the exit code

`BUILD SUCCESSFUL` says the task ran. It does not say how many tests ran. The two numbers are
read from the JUnit XML on purpose, because a test file that fails to *load* is reported by
`node:test`-style runners as a file-level failure while its tests simply do not exist — the
count drops silently. `android-app-v1.md`'s `F11` records the same trap on the backend side.
Watching the total is what makes "200" a claim about the suite rather than about the build.

## Known, recorded, and deliberately not fixed here

`ProfileScreen.kt:317` still calls `android.util.Log.w` directly for the unopenable-link line.
It does not force `isReturnDefaultValues` back, because that line is inside a private
`openReleasesPage` reachable only from the composable, and **no JVM test touches that path** —
checked, not assumed. Extending the seam there would mean threading a logger through a
`@Composable` for one line in a path no test exercises. Recorded as an observation for whoever
next touches that screen; it is not a regression this pass introduced.

## Route declaration

Delegated direct: the writer trigger fired (six non-trivial files), so the work went through a
`gentle-ai-worker`-equivalent delegated writer, which never commits, stages or pushes. This
parent verified the bytes and ran the suite.

## Next step

Work-unit commit, then the review preflight for that commit as its own candidate.
