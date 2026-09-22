# ODD Feature: Android–Web functional parity

Status: in progress
Created: 2026-09-21
Engram mirror topic: `odd/android-web-parity/tasks`
Supersedes, for the Android client only: the `D2` exclusion in `odd/tasks/android-app-v1.md`
("Admin stays desktop-web") and its "Not in scope" line about admin management surfaces.

## Objective

Bring the Android client to **functional parity with the production SPA**: every surface the
web offers to a signed-in user exists in the app, gated by the same roles, against the same
backend and the same users.

## Problem

Opening the app, the operator does not find everything the web has. Measured against the SPA
router (`packages/frontend/src/App.tsx`) rather than assumed:

| Web route | Surface | Android today | Status |
| --- | --- | --- | --- |
| `/login` | `LoginPage` | `LoginScreen` | present |
| `/dashboard` | `DashboardPage` | — | **missing** |
| `/reports` | `ReportsPage` | `ReportGeneratorScreen` | present |
| `/reports/:id` | `ReportViewPage` | `ReportDetailScreen` | present (view, photos, export) |
| `/reports/:id/edit` | `ReportEditPage` | — | **missing** |
| `/loading` | `LoadingSchedulePage` | `LoadingScreen` | present |
| `/loading/history` | `LoadingHistoryPage` | `LoadingHistoryScreen` | present |
| `/loading/edit` | `LoadingEditPage` | — | **missing** |
| `/loading/reports-history` | `ReportHistoryPage` | `ReportsHistoryScreen` | present |
| `/admin` | `AdminDashboard` | — | **missing** |
| `/admin/audit` | `AuditPage` | — | **missing** |
| `SetupGate` | `SetupPage` | — | first-run only — see E2 |

The web's own `profile` tab inside `AdminDashboard` has no work of its own: the app's
`ProfileScreen` already covers it and exceeds it (password change, update check).

## Why

The crew works from the phone in the yard. Today a worker who needs to fix a report's
products, correct a schedule entry, or read the dashboard has to walk to a desktop. The
company's stated intent is one tool, not two.

## Constraints and decisions inherited

- `D1` native Kotlin + Compose, `D3` refresh token + revocation, `D4` signed downloadable APK,
  `D5` indicative fletero quota, `D6` day edit window — all still hold.
- **What changes is only `D2`'s scope boundary.** Admin and audit are no longer desktop-only.
- The backend needs **no functional change**: `modules/admin/admin.routes.ts` already enforces
  `adminGuard` (Administrador) and `catalogGuard` (Administrador + Trabalhador), and the SPA's
  tab filter mirrors those guards exactly. Authorization stays the server's, never the app's.
- The app must never treat a hidden control as authorization. Every surface it draws is one the
  server would accept for that role; a hidden tab is convenience, the guard is the truth.

## The measured role matrix (server truth, mirrored by the SPA)

| Surface | Read | Write | Tab for Administrador | Tab for Trabalhador |
| --- | --- | --- | --- | --- |
| categories | Administrador + Trabalhador | Administrador | yes | no |
| tasks (task types) | Administrador + Trabalhador | Administrador + Trabalhador | yes | **yes** |
| drivers | Administrador + Trabalhador | Administrador + Trabalhador | yes | **yes** |
| vehicles | Administrador | Administrador | yes | no |
| time slots | Administrador | Administrador | yes | no |
| users (workers) | Administrador | Administrador | yes | no |
| audit | Administrador | — | separate page | no |

A `Trabalhador` therefore gets `tasks` and `drivers` in the app exactly as in the web, and
loses nothing the web gives them. `profile` is covered by the existing `ProfileScreen`.

## Prerequisite, and it is not optional

`B2c` deliberately excluded admin and audit from the OpenAPI contract, and
`contracts/route-coverage.test.ts` asserts that exclusion. The Android client generates its
request/response types from that artifact, so **the app cannot call the admin surface with
typed, validated models until the contract covers it.** That was recorded in
`android-app-v1.md` as exactly this follow-up.

---

## Slice P — Backend: contract for the admin and audit surfaces

### P1b. Register admin and audit in the OpenAPI contract — DONE

**Delivered.** 22 operations across **12 distinct paths** — not the 15 or 16 this document first estimated by
counting route registrations rather than distinct URLs, since the methods share paths. The document went
**33 → 45 paths, 42 → 64 operations, 52 → 81 components**, verified add-only against HEAD: 0 paths removed,
0 changed, 0 components removed, 0 changed.

**The real work was a naming collision.** Eight component names were already taken by `loading` and
`reports`, and every one is a *narrower projection* than what the admin surface returns — `Driver` without
`is_active`, `created_at` or `created_by_user_id`; `Vehicle` without `is_active` or `created_at`;
`CreateDriverRequest` without `driver_type`. Reusing them would have published a shape the admin endpoints
do not serve, so everything the admin surface introduces carries an **`Admin` prefix**. `TimeSlotsResponse`
is the one genuine reuse: that endpoint really does serve the same setting under the same envelope.

`OUT_OF_SCOPE_PREFIXES` is now empty, and emptying it naively turns the suite red — the coverage test
asserted `excluded.length > 0` with the message "the out-of-scope set matched nothing, so it may be stale".
The guarantee is kept in the form the new state allows: the test now asserts the set is *deliberately*
empty, which forces a future exclusion to arrive with its reason. Both coverage directions and the
no-`:`/template-parameter assertions still pass. `TimeSlotsResponseSchema` moved to
`contracts/common.schema.ts` with one definition and no re-export chain.

**The blocker, and it is the most instructive part of this slice.** The app would not compile: twelve
errors, all one shape — the generated request models carried
`enum class IsActive(val value: java.math.BigDecimal) { _0("0"), _1("1") }`, a String literal passed to a
BigDecimal parameter. **The cause was in the document:** `z.union([z.literal(0), z.literal(1)])` was
emitted as `type: number` for a flag that is an integer, and openapi-generator rendered each one-member
number enum as a BigDecimal-valued Kotlin enum. No request schema had used a numeric literal union before,
so the path had never been exercised. It is **7 enums over 6 files**, not the 5 this document first said:
`is_active` on the five update requests and `role_id` (1|2) on the two user ones.

**The obvious fix cannot work**, and it was disproved rather than assumed:
`.openapi({ type: 'integer', enum: [0, 1] })` beside the union fails with
`z.union(...).openapi is not a function`, because `extendZodWithOpenApi(z)` is a *statement in the body* of
`contracts/openapi.ts`, ESM evaluates a module's imports before its body, and `.openapi()` returns a copy.
The only way to keep that prescription is to patch Zod from a module the server imports — which would load a
codegen library into the server process, the exact thing `B2.1` avoided by keeping the library a
devDependency.

So the **document builder normalizes it**, unconditionally, behind a guard that rewrites only when *every*
value is an integer so a genuine decimal enum is left alone, with five tests that make the rule falsifiable:
the admin flags become integer enums, no numeric literal union survives in the built document, a fractional
enum and a mixed union are both left untouched, and the step is idempotent. Chosen over attaching the claim
per registration because six admin surfaces remain to be built, each full of `is_active`-shaped flags, and a
rule that must be remembered at every registration is one that will be forgotten — the same reasoning that
made `EditWindow` a required parameter and the log seam's throwable non-optional.

**Evidence:** the app **compiles** (`:app:compileDebugKotlin` green, which was the whole blocker); the JVM
suite at 202 tests, 0 failures; `check-android-contract-types.sh` reports the types current; the backend
suite at **351 tests, 351 pass, 0 fail** (346 before, so the five new tests reconcile); `tsc --noEmit` clean;
and the full canonical gate passed with 53/53 Playwright E2E and the artifact-freshness check green.

**What remains open from P1a's findings**, now that the surface is published:

1. **`audit.service.ts`'s `AuditLogRow.user_id` is typed non-nullable** while `audit_logs.user_id`
   is nullable and `deleteUser` nulls it before deleting, so the LEFT JOIN can serve `user_id: null`.
   The schema documents the query's truth; the interface does not.
2. **`contracts/common.schema.ts`'s `PaginationSchema` is not reusable here**: it names the size
   field `pageSize` and the audit handler returns `limit`. Audit's block is declared in its own module
   rather than forcing one of the two to move.
3. **`CATEGORY_TYPES` is duplicated** between `admin.schema.ts` and `reports.schema.ts` (the same four
   values). Pre-existing.

---

### P1a. The admin response shapes are declared and proved — DONE

Split from P1 deliberately. **P1a does not touch the OpenAPI registry or the committed artifact**, so
the exclusion stays in place, the document stays exactly as true as it is today, and every
intermediate state is green. Registering the 22 operations is P1b.

Delivered: `modules/admin/admin.schema.ts` now declares the surface's response shapes as `.strict()`
Zod schemas with `z.infer` types (the row interfaces it used to hold had the same export names, so
`admin.service.ts` needed no change); `modules/audit/audit.schema.ts` is new and takes
`AuditQuerySchema` out of the router along with `AuditLogSchema` and the paginated envelope; and
`contracts/admin.contract.test.ts` parses **live responses from the running app** for all seven read
endpoints, plus an assertion of the two-guard role split.

**Measured, not assumed:** the surface is 22 operations across 16 paths. `audit` lives under the
`/api/admin` prefix (`routes.ts` registers `auditRoutes` with that prefix), which is why a single
prefix covers both. `drivers` has no `DELETE`. `tasks`' `DELETE` is `catalogGuard`, so a `Trabalhador`
can delete a task type.

**Evidence:** the full canonical gate passed — `make ci-clone`, i.e. `scripts/ci.sh` on
`node:24-bookworm-slim` with the dirty files overlaid, ending **53/53 Playwright E2E** and
`CI gate passed`. Separately measured on the declared engine: `tsc --noEmit` clean, and
**336 backend tests, 336 pass, 0 fail, 0 skipped**. The new contract file contributes **8 named
tests**, listed in its own `it(...)` blocks; the ledger's older "326" predates the commits between
C2 and now, so no baseline is asserted — only the total and the attributable delta.

**What P1a surfaced** — and the first two are now **closed by the follow-up pass** that the review of this
slice asked for (`R3-001`, `R3-002`, `R3-003`; the whole write contract was declared and unproved, and
registering it in P1b would have published it to the Android client):

1. **The task list and the task writes served different shapes — FIXED.** `listTasks` selects
   `rc.name_pt AS category_name` and the write paths do not, so a listed task carried a field a created
   one did not. Still modelled as two schemas rather than one widened with an optional field: the drift
   is real and an optional field would hide it.
2. **A PATCH with no fields changed returned an unprojected row — FIXED.** `updateTask`'s early return
   answered with `SELECT * FROM report_tasks`, which carries no `task_type` — that field only exists in
   the JOIN to `report_categories` — so it matched **neither** declared schema and the `as TaskRow` cast
   was what kept TypeScript quiet. **This is `F9` of `android-app-v1.md` again, one layer down:** one
   type describing several runtime shapes, made unfalsifiable by a cast. The fix is `F9`'s fix — a single
   `selectTaskById` helper both returns now project through, with the one unchecked cast SQLite forces
   moved into that helper instead of one per call site. The same early-return pattern in
   `updateCategory`, `updateDriver` and `updateVehicle` is deliberately **left alone**: for those,
   `SELECT *` equals the served shape, so there is no drift to remove.

   The test that holds it down is `answers a no-op task PATCH with the same declared shape as a real
   update`, which sends `PATCH /api/admin/tasks/:id` with an empty body — the one case that takes the
   branch that is not a write. It is the only test of the ten added that could have failed against the
   old code, and its comment says so, so deleting it restores the defect silently.

The remaining four are recorded and unfixed, because the contract's job is to describe what is served
and widening a schema to fit a handler is how drift disappears from view:

3. **`audit.service.ts`'s `AuditLogRow.user_id` is typed non-nullable** while `audit_logs.user_id`
   is nullable and `deleteUser` nulls it before deleting, so the LEFT JOIN can serve `user_id: null`.
   The schema documents the query's truth; the interface does not.
4. **`contracts/common.schema.ts`'s `PaginationSchema` is not reusable here**: it names the size
   field `pageSize` and the audit handler returns `limit`. Audit's block is declared in its own module
   rather than forcing one of the two to move.
5. **`TimeSlotsResponseSchema` is shared with `loading` but lives in `loading.schema.ts`.** Its
   conventional home is `common.schema.ts`; P1a re-exported it rather than duplicating it, and moving
   it belongs with P1b when `common.schema.ts` is next touched.
6. **`CATEGORY_TYPES` is duplicated** between `admin.schema.ts` and `reports.schema.ts` (the same four
   values). Pre-existing.

---

## Slice T — The Compose UI test lane

**Placed between Slice P and Slice B on purpose** (decided 2026-09-21): the contract work is backend
and touches no Compose, so this lane does not slow the thing that is blocking; and the lane then lands
before the first UI slice, which is where the renderable surfaces begin.

### T1. A Compose screen renders and is measured in a JVM test — DONE

**Delivered, and its first test is the case that motivated it.** The module can now render a screen inside
the existing `:app:testDebugUnitTest` task — no new Gradle task — and assert on the result.

**What the lane is, in its parts.** `androidx.compose.ui:ui-test-junit4` and `ui-test-manifest` (1.12.1,
from the Compose BOM) plus Robolectric 4.17 and `androidx.test.ext:junit` 1.3.0; `testOptions {
unitTests { isIncludeAndroidResources = true } }`, because a rendered screen resolves the strings, the
drawable and the theme through the app's own `R` rather than a shadow; and `ui-test-manifest` on the
**debug** variant only, since that artifact is what declares the `ComponentActivity` `createComposeRule()`
launches. That last one is a debug-only leak in principle, so it was checked in the merged manifests
rather than assumed: the activity is attributed to `ui-test-manifest` in the debug merge, is present in
the debug APK's manifest, and appears **zero** times in the release manifest.

**The API level is pinned in one place**: `app/src/test/resources/robolectric.properties` (`sdk=35`).
Inheriting it from the merged manifest's `targetSdk` would make a future bump change what every test in
this lane runs against, and Robolectric resolves a different `android-all` image per level — such a change
would arrive as a download and a new set of framework behaviours instead of as a decision. It ran on
`android-all-instrumented-15-robolectric-13954326-i7`, which Robolectric fetches **outside Gradle's
dependency graph**, into `~/.m2/repository`, at **200 MB**. In CI that `$HOME` is ephemeral, so the lane now downloads it on every run; this is recorded as a cost rather than mitigated, because the offline route
documented by Robolectric (`dependency.dir` plus a pinned artifact) adds a mechanism whose version has to
track Robolectric's own per-level pin — a rule with a second copy of itself in it, which is the shape this
project keeps deleting.

**The rendering is real, not a shadow's idea of it.** `@GraphicsMode(GraphicsMode.Mode.NATIVE)` makes text
layout go through the framework's own implementation, so a screen measured here is measured the way a
device measures it. A lane that renders without measuring text would be a second test that cannot fail,
in a project that has already been bitten by one.

**The rule is the `v2` factory**, `androidx.compose.ui.test.junit4.v2.createComposeRule`. This was decided
from the artifact rather than from habit: in Compose 1.12.1 the unqualified `createComposeRule` carries a
`@Deprecated` whose message points at the `v2` one, the `v2` factory carries no experimental marker, and it
returns the same `ComposeContentTestRule`. The difference is the dispatcher composition is queued on —
`StandardTestDispatcher` instead of `UnconfinedTestDispatcher`, which queues rather than running
immediately. This file is the one the lane's next tests get copied from, so it does not start out
deprecated.

**The two tests, and what each one claims.**

1. `a short viewport scrolls to the submit button instead of squeezing it`. The screen is rendered inside a
   fixed 400×320dp box, so the viewport is a number the test states rather than a property of whichever
device Robolectric emulates. Three assertions: the button starts **off screen** (a precondition, and the
   thing that keeps the test from going quietly vacuous — if the content ever fits, "it can be scrolled to"
   stops meaning anything); its height is at least 40dp (`ButtonDefaults.MinHeight`, so a squeezed button
   is the failure rather than a coincidence); and `performScrollTo()` followed by `assertIsDisplayed()`,
   which is the claim the fix makes.
2. `the content still centres when the viewport holds it`. Centring is measured as a **difference**: the
   same screen is rendered in a 700dp box and then a 900dp one, and the submit button's bottom edge must
   move by half of what the viewport grew by (100dp, ±8). Centred content moves by half; content pinned to
   the top does not move at all. That needs no knowledge of how tall the content is, so it does not
   restate the layout it is testing, and the two hypotheses are 100dp apart.

**Both tests were made to fail before either was trusted**, each against exactly the line it names. The
method matters: the mutated file was bind-mounted over the container's view of that single path
(`--volume /tmp/<mutated>.kt:/work/.../LoginScreen.kt`), so **no tracked file was ever modified** and
`git status` on `app/src/main/` was empty after each run.

| Removed from `LoginScreen.kt` | Failing test | Its message |
| --- | --- | --- |
| `.verticalScroll(rememberScrollState())` | the short-viewport one | `Actual height is 0.0.dp, expected at least 40.0.dp` |
| `.heightIn(min = maxHeight)` | the centring one | `the submit button moved 0.0dp when the viewport grew by 200.0dp` |

Each mutation failed **one** test and left the other green, which is what makes them two claims rather than
one assertion wearing two names.

**Evidence.** Before the change, the lane's own baseline: `19` classes, **202 tests**, 0 failures, 0 errors,
0 skipped, and `:app:testDebugUnitTest --rerun-tasks` at **222s** (33/33 tasks executed). After: **20**
classes, **204 tests**, 0 failures, 0 errors, 0 skipped, and the same command at **281s** (38/38 executed).
The two new tests are the difference, and the arithmetic reconciles exactly. The new class's own suite time
is 44.9s, most of it Robolectric's first boot in the run; the remaining 14s of the task's growth is the
resource processing and recompilation the flag adds, which was not separated by measurement.

**And the same command again, on the bytes that carry the bounded correction**: `20` classes, **204 tests**,
0 failures, 0 errors, 0 skipped, **413s**, 38/38 tasks executed. Both numbers are stated because they are
the two revisions a reader would otherwise have to guess between, and because they measure what a single
sample of this task is worth: two identical invocations, 281s and 413s apart, with the corrected one also
recompiling the test source it changed. The lane's cost is real and it is not a constant.

**The canonical lane, and one thing this change broke in it.** `make ci-android` passed twice on the bytes
**before** the bounded correction below (debug APK, unit tests, release APK, the cleartext/`allowBackup`
assertions, the deliberate failing release build that proves the base-URL guard, and the contract-types
check): **`exit 0` both times**, at **629s** for the run in which every Gradle task executed and at **227s**
for the run in which `:app:testDebugUnitTest` was made to execute rather than report `UP-TO-DATE`, whose
regenerated XML reads **20 classes, 204 tests, 0 failures, 0 errors, 0 skipped**. **The lane was not re-run
after the correction**, which touched one test file — a type annotation and a comment — and whose own suite
was re-run in full, as the evidence above records. No workflow sets `timeout-minutes`, so the lane's 10.5
minutes sit against GitHub's 360-minute default; the composed cost is the 200 MB image plus Robolectric's
boot, and it is stated rather than left to be discovered.

**A second defect, caused by this one.** `unitTests.isIncludeAndroidResources` makes AGP merge a manifest for
the unit-test variant of the debug build type, and `scripts/ci-android.sh`'s `merged_manifest_for()`
chose with `head -1` over candidates decided by the filesystem — so the assertion that claims a property of
the shipped debug build began reading
`merged_manifest/debugUnitTest/mergeDebugUnitTestManifest/AndroidManifest.xml`, a manifest no APK is built
from. Both files happened to satisfy the assertions, so the lane was not lying, but it was no longer reading
the file it names. Fixed in the same work unit by excluding the unit-test variants from that lookup, with
the reason in the comment beside it.

**What the lane does not cover.** It renders the stateless composable: `LoginRoute`, Hilt's graph and the
navigation are not exercised by it, and the nine surfaces of this track still have no rendered test. Nor
does it replace `E1`'s fourth acceptance criterion — a Robolectric render is a better test than a compile,
not a substitute for an operator looking at the screen on the emulator.

### The review of this candidate, and how it ended

`review-6a9019bc7ee61beb`, **high** tier (the frozen risk reasons: an auth path, shell scripting and a
process boundary), four lenses, 332 changed lines and a correction budget of 166. It came back
`correction_required`, one bounded correction was spent, and the authority is now **`escalated`**: the
transition it offers is `stop` / `native_stop_required`, which is terminal — the maintainer inspects the
lineage, or the review switch is disabled for this clone. **This candidate was never approved.**

**Two of the four lenses found something true, and both are recorded here as the next work unit:**

1. **`R2-001` (readability, WARNING) — a comment that names the wrong detector, and the prose was mine.**
   `LoginScreenTest`'s KDoc says the third assertion (reachability through `performScrollTo()`) "is the one
   that fails without `verticalScroll`", while the mutation table above records the **second** one failing
   for exactly that mutation, at `Actual height is 0.0.dp, expected at least 40.0.dp`. Both do fail in fact
   — the height assertion fails first, and without a scrollable ancestor `performScrollTo()` could not be
   performed at all — but the comment picks the wrong one to name, and this file is the template the lane's
   next tests are copied from. This is the fourth time in this line of work that a comment describes the
   change inaccurately, and the sentence is the one I wrote in the spec the writer copied.
2. **`R4-1` (resilience, WARNING) — the lane's new runtime dependency is a precondition of the whole test
   task.** Robolectric resolves `android-all-instrumented` through its own Maven resolver, outside Gradle's
   dependency graph, into `$HOME/.m2`; the image is 200 MB, CI's `$HOME` is ephemeral, so it is fetched on
   every run, and nothing in this change caches, retries or degrades around it. The consequence the reviewer
   names is the one worth keeping: the fetch is a precondition of `:app:testDebugUnitTest`, so a transient
   network or repository outage now fails **every** unit test in the module, not only the new Compose one.

The other two lenses found nothing: `risk` reported no security, authorization, secret or
dependency-vulnerability finding and confirmed the `ui-test-manifest` activity is debug-only, and
`reliability`'s single finding is the false positive below.

**`R3-001` (reliability, BLOCKER) is a false positive, and it is the fourth in this line of work.** Its
claim: `getBoundsInRoot()` returns a pixel `Rect` whose `bottom` is a `Float`, so the `.value` accesses
cannot compile and the test cannot run. **It compiles and it runs**, and the counter-evidence is the
artifact rather than an argument: this class compiled and executed in the full suite, in the canonical
lane, and in both mutation runs — the second of which printed an assertion message *computed from exactly
those `.value` accesses*. `getBoundsInRoot()` returns a `DpRect` whose `bottom` is a `Dp`; the
pixel-answering API it was confused with is `SemanticsNode.boundsInRoot`. The project's stance already on
record was applied: no code was changed to satisfy a premise the project's own artifact contradicts.

**What the correction did close is the mechanism the misreading rested on**, which is the same move made
the last time this happened: the unit is now in the code instead of only in the reader's head. Both bounds
are annotated `Dp` and the comment beside the first names the distinction. The provider's own ledger
records the plan as 12 diff lines and the actual as **9**, and that correction introduced no regression
(`correction_regression.passed`).

**Then the targeted validator repeated the misreading and the lineage escalated.** Its verdict, quoted:

> The corrected candidate still does not resolve R3-001. Instead of removing `.value` from Float
> coordinates, it adds `Dp` annotations to `getBoundsInRoot().bottom` results ... so the frozen finding's
> compile problem remains unmet.

`original_criteria.passed: false`, cause `targeted_validator_rejected`. A deterministic finding is
supposed to be self-evident, and this one is self-evidently wrong — the compiler is the evidence, and it
was never consulted. There is no refuter route for a deterministic claim and no second correction, so the
transaction is terminal with the candidate unapproved. **That is recorded as a limitation of the lane's
first outing rather than repaired by changing code that was never broken.**

### The second review, and this one approved

A corrected candidate is a different target, so the provider's answer to it is a new transaction:
`review-edc7124b2ab35bcf` covered the same seven paths again — **393** changed lines, budget **197**, the
same four lenses — and came back **approved**. The acknowledgement burned the authority
(`gentle-ai.review-acknowledged/v1`), so **the receipt stands**. Nothing carried over from the first
lineage's escalation: this time the lens that had produced the false BLOCKER read the build file's
dependency lines instead of the test's bounds.

It left **six findings, all non-blocking.** The receipt's own words are that none opened a correction, none
reopens the review, and no correction transition is offered — they are later work, never a reason to re-run
a review on this candidate:

| id | lens | where | what it says | assessment |
| --- | --- | --- | --- | --- |
| `R2-001` | readability | `LoginScreenTest.kt:78-82` | the KDoc names the wrong detector: the third assertion is called "the one that fails without `verticalScroll`", while the recorded mutation stops at the second | **true** — the second fails first, so the third never executes and the sentence claims something the evidence cannot show |
| `R2-002` | readability | `LoginScreenTest.kt:55` | `@Config(qualifiers = "w400dp-h1000dp")` is load-bearing and unexplained: a fixed-size `Box` is coerced into the window's constraints, so the centring measurement only means what it claims while the window is taller than the largest box the test declares | **true, and the sharpest of the six** — a clone that trims the window because "the box replaces the test window" measures a difference of 0 and reads it as the pinned-to-the-top failure |
| `R2-003` | readability | this document | "final bytes" denoted two revisions in one file, and nothing said which one the green runs belonged to | **true, and closed in this same commit**: every figure above is now attached to a revision, and the corrected bytes carry their own re-run |
| `R3-001` | reliability | `build.gradle.kts:506` | the test lives in the variant-agnostic `src/test` and its runner dependencies are `testImplementation`, while `ui-test-manifest` is `debugImplementation` only — so the release unit-test variant has no host activity and `testReleaseUnitTest` (or plain `./gradlew test`) would fail to launch the rule | **true and latent**: CI runs only `:app:testDebugUnitTest`, so nothing is red today |
| `R3-002` | reliability | `build.gradle.kts:500` | the Robolectric fetch is a precondition of the whole test task | duplicate of `R4-1` seen from the other lens |
| `R4-1` | resilience | `build.gradle.kts:500` | the 200 MB `android-all-instrumented` fetch outside Gradle's graph, re-downloaded every CI run, with no cache, retry or offline fallback, failing **every** unit test in the module on a transient outage | **true** |

**Five findings were closed or decided in one work unit, and they came from two reviews**: the four in the
table above that are not `R2-003` -- which was closed as that record was written, not later -- plus
`R3-003`, which the third line found. The shape of each closure is worth a line:

* `R2-001` — **fixed**: the first test's KDoc now names the second assertion as the one the recorded
  mutation trips first and the third as the one the same mutation makes impossible, which is what the
  evidence shows.
* `R2-002` — **fixed**: the window qualifier is now the lane's fourth stated deliberate choice, with the
  reviewer's reason written beside it -- a fixed-size `Box` is measured *under* the window's constraints.
* `R3-001` — **fixed as a statement about scope, and the measurement is worth more than the fix**: the test
  moved to `app/src/testDebug`, so the source set says what the dependency says. The breakage the finding
  predicted, though, is unreachable in this module as it stands: `:app:tasks --all` lists exactly one
  unit-test task, `testDebugUnitTest`, and the aggregate `test` covers only it — there is no release
  unit-test variant to fail. So this is not the repair of an observed failure; it is the test declaring
  which variant owns it, which is what keeps a future release unit-test variant from inheriting one that
  cannot launch.
* `R3-003` — **fixed, and it is the one that changes behaviour** (the finding that came from the third line
  below rather than from the table above), with the evidence a fix to a guard
  deserves. The centring test's guard asserted a height, and the scroll container this fix installs is
  exactly what keeps that height at 40dp in both regimes, so the guard held while the regime it named had
  already broken. It now requires both ends of the content to be on screen at once. Falsified in both
  directions by shrinking the test's box to 300dp so the content overflows: the new guard fails at the
  precondition (*"The component ... contains 'Entrar' ... is not displayed"*), while the guard it replaced
  let the height assertion pass and failed later at *"the submit button moved 467.0dp when the viewport grew
  by 200.0dp"* — a centring message for a precondition failure, which is the misattribution the finding
  named, observed rather than argued.
* `R3-002` ≡ `R4-001` — **decided, not fixed**: the 200 MB fetch stays, because the offline path the
  reviewer suggested would not remove it -- Robolectric's offline mode resolves the artifact through
  Gradle, and Gradle's cache is as ephemeral as `$HOME/.m2` in CI, so the download happens on the first
  run either way while the build gains a pinned artifact version that has to track Robolectric's own
  per-SDK table. The real mitigation is a cache in the workflow that keeps the image between runs, which
  belongs to the unit that next touches `.github/workflows/ci.yml`; writing it from here, where it cannot
  be run, would be an unverifiable claim of a fix.

**A third review line, and the loop it exposed.** The record of the second review was itself committed,
which made a new candidate, and the provider answered a changed candidate the way it says it does: a third
transaction (`review-ffe231dab8aa0534`) re-ran all four lenses over 427 lines whose only delta was that
record -- approved and burned, with `R3-003` as its one new finding. **The rule this track takes from it: a
commit that only records a review is a candidate like any other, so a record is batched into the next
commit that carries work.** The alternative is paying a review per paragraph.

**A fourth review line carried the record with the code**, which is the rule above doing its work:
`review-53b867913f1d19a4` approved the one-commit unit that closed the advisories, and left three
findings of its own -- two of them about the prose that unit had just added.

### The three findings the fourth review left, closed

* `R2-001` — **fixed, and it was a contradiction inside a single candidate**: the build file's comment and
  the class KDoc both asserted, as an experienced fact, that the shared source set "compiled and then
  could not launch" under `testReleaseUnitTest` or a plain `./gradlew test`, while this record held the
  same breakage to be unreachable. The record was right -- `:app:tasks --all` lists one unit-test task --
  so both sentences now say what the move is: a source set stating the scope of its own dependency, not
  the repair of an observed failure. Two accounts of one event, and the one written from the artifact was
  the correct one.
* `R2-002` — **fixed**, with its basis measured instead of argued: running the centring test with the
  allowance at zero passes, so the movement it measures is exactly the 100dp that separates the two
  hypotheses, and the 8dp is room for the sub-dp rounding a different density or fractional text metrics
  could introduce. An allowance an order of magnitude below the gap cannot admit the behaviour the
  assertion exists to reject.
* `R3-002` — **fixed, and it named the real gap in the previous fix**: excluding the unit-test variant
  stopped the lookup from reading the wrong file, but nothing in the lane could tell the corrected lookup
  from the broken one, because both candidates satisfied the assertions. The lookup no longer selects
  with `head -1` at all: it anchors the path shape (`/merged_manifest/<variant>/process*MainManifest/...`)
  and requires exactly one match, so a wrong selection matches nothing and fails the lane, and a second
  candidate fails it too instead of being settled by the order `find` walked the tree.

---

## Slice A — Role gating in the app

### A1. One source of truth for "what this role may see"

The app currently has no role model at all: `MainActivity` draws a fixed two-tab shell and the
`role` string the backend returns is only rendered as a label on `ProfileScreen`.

- A small `RolePolicy` (or equivalent) that maps `role` → the surfaces that role may open, with
  the server guard each surface corresponds to named in a comment. `Administrador` and
  `Trabalhador` are the two the database has; an unrecognised role gets the worker set, never
  the admin one — fail closed, because the server is the authority on what actually succeeds.
- Navigation draws from that policy, not from a hardcoded list.
- A test per role asserting the visible set, and one for the unknown role asserting it does not
  include an admin-only surface.

**Acceptance:** a `Trabalhador` build drawn from the policy shows no admin-only entry point;
flipping the policy to grant one makes that test fail.

**Why it lands before the admin surface and not with it:** every later slice asks this question,
and answering it in six places is how a hidden control quietly becomes a relied-upon one.

### A1 — DELIVERED

**The table exists, the navigation asks it, and the cost this slice did not name was that the app had to be
told the role at all.** It knew the role only as a word the profile screen drew, so the navigation had no way
to ask a question about a value it never held.

**`auth/RolePolicy.kt` is that table.** `ADMIN` and `WORKER` are `roles.name`'s own spellings, and
`EntryPoint` lists the destinations with the server guard behind each one written beside it -- `adminGuard`
(`requireRole('Administrador')`) and `catalogGuard` (`requireRole('Administrador', 'Trabalhador')`) quoted
from `admin.routes.ts`, so the table is checkable against the server rather than against itself. The
administrator's set is written as the worker's set **plus** the administrator-only entries, so a surface added
to the worker's set cannot be forgotten on the other side -- which is the failure this table exists to prevent,
one entry at a time. An absent or unrecognised role gets the worker's set: fail closed, so a value this client
does not understand can only ever offer less. The entries also carry the one nuance the guards alone do not
explain: `CATEGORIES` is administrator-only as a *tab* while a worker may read categories through the
generator, which is why the entry point -- the tab -- is what the table gates.

**The role now travels with the session.** `TokenStore` gained `role()` and `saveRole()`, `KeystoreTokenStore`
stores it through the same encrypted path as the token pair (`clear()` already wipes the whole file, so
signing out forgets it), and `AuthRepository.login()` records `body.user.role` while `sessionRole()` reads it
back. Stored rather than kept in memory, because a cold start has no sign-in to learn it from -- the app comes
back to a stored session and the navigation still has to know what to draw -- and recorded once per session
rather than refreshed, because the refresh response carries no role: a rotation is the same operator. The
server remains the authority for every call, so a role changed server-side takes effect at the next sign-in,
when the server hands the new one over; what this value decides is what is *offered*.

**The row is drawn from the policy.** `MainActivity` keeps `destinations` -- what this build can draw, which
today is the two tabs it already had -- and asks `RolePolicy.visibleDestinations(role, destinations)` which of
them this role may open. The admin surfaces are absent from that list because they are not built yet, not
because nobody may see them: the day one lands it joins the list and the policy decides who gets it. The
account button is deliberately not in the table -- it is a control hanging off the row rather than a surface
the app is about, and no role's guard decides it.

**Evidence.** `RolePolicyTest` asserts the table once per role, the unknown role's equality with the worker's
set, and the acceptance test itself with a stand-in for a destination the app does not have yet:
`visibleDestinations(WORKER, [REPORTS to ..., AUDIT to ...])` is `[reports]`. The acceptance's second half was
run rather than assumed -- **granting `AUDIT` to the worker in the policy fails exactly three tests**, the row
test among them with `expected:<[reports]> but was:<[reports, audit]>`. The focused set was 6 classes and 80
tests green; the full suite went from 20 classes / 204 tests to **21 and 210**, 0 failures, 0 errors and
0 skipped, with the two new members of the session store covered by a test that signs in, reads the role back
and forgets it on the way out.

**What A1 does not do, stated so a later slice does not assume it.** It gates no call: every request still
meets the server's guard, and this table decides only what is offered. It adds no admin screen. And
`MainActivity` still opens on `Tab.REPORTS` unconditionally -- valid while both current destinations are
visible to every role, and the day an administrator-only entry comes first in the row, the starting tab has to
come from the policy too.

### The three findings A1's review left, closed

A1's own review (`review-7615f7fc754b9448`) approved it and left three findings, all three true:

* `R2-001` — **fixed**: the set is now `SHARED_ENTRY_POINTS`. The reasoning was exact -- it is the base the
  administrator's set is built from, so a name that said "worker" invited the one edit that would hand a
  worker-only surface to the administrator, and nothing downstream would have caught it. The name contradicted
  its own KDoc, which had said "what both roles see" from the start.
* `R2-002` — **fixed as prose, and the state it names is real rather than hypothetical**: `null` from `role()`
  now means *no role recorded*, not *no session*, because an app updated from the version before this one has a
  stored session with its token pair and no role. The consequence is stated where a caller reads it:
  `hasSession()` is true while `sessionRole()` is null, and the navigation then falls back to what the policy
  answers for a role it does not recognise -- the direction that offers less. It resolves at the next sign-in,
  which records the role the server hands over.
* `R3-001` — **decided, and the decision is a measurement rather than an argument.** The finding is right that
  the real `KeystoreTokenStore` has no test: the one that saves and reads a role goes through a fake. So it was
  asked of the lane instead of assumed, with a probe test mounted into `src/testDebug`:
  **`java.security.KeyStoreException: AndroidKeyStore not found`**. Robolectric does not provide the Android
  Keystore, so this lane cannot exercise that class at all -- and the untested surface is two members delegating
  to the same `read` and `encrypt` helpers the token pair already used, so this change added no new *kind* of
  unproven code. What would close it is either an instrumented lane (the emulator lives on a box this project
  cannot reach, which is why this JVM lane exists) or a seam that lifts the cipher out of the store so that the
  encoding, the junk-tolerant `decrypt` and `clear()` become testable. Both are units of their own, and neither
  is worth inventing to satisfy a warning.

---

## Slice B — The operational gap

### B1 — DELIVERED

**The dashboard exists, it is the app's first tab, and it is the screen the app opens on.** `EntryPoint.DASHBOARD`
had been in the policy since A1 with nothing drawing it; this is the case the table was waiting for, and the tab
that appears is the policy's answer rather than a third hardcoded button.

**What it is.** `dashboard/DashboardRepository` over the `SystemApi.dashboardSummary()` the client already had --
the contract has documented this endpoint since `B2.4`, so this slice needed no backend, no contract and no
regeneration -- plus `DashboardViewModel` with a three-field `UiState`, and `DashboardScreen`, stateless, with
`DashboardRoute` collecting. Five cards in the web's order and with the web's own words; the first carries the
higiene/recepção breakdown, and the two links the web has are the two actions here: the reports card opens the
report the summary names, or the reports list when the day has none, and the schedules card opens the schedule.

**Two deliberate divergences, both stated where a reader meets them.** The emoji the web puts on each card are
decoration and are not drawn -- five glyphs are not worth an icon dependency -- and `reportsToday`, which the
contract carries and the endpoint computes, is not drawn either: the web's own card shows the breakdown under
that title and never this count. `higieneTotal` and `recepcionTotal` reach no pixel, exactly as on the web, whose
two strings interpolate only the done count.

**The finding this slice produced, and it is why the view model does not read once.** The first version loaded
from `init`, and the correction came from reading the app rather than the screen: these view models are
**activity-scoped**, because there is no `NavHost` in `main` and `hiltViewModel()` therefore resolves
`LocalViewModelStoreOwner` to the activity. The instance and its state outlive both the tab that drew them and the
session that filled them, so two things were true of the first version and only the second is why it had to
change:

1. a read that failed stayed failed for the life of the activity, with nothing on the screen able to try again;
2. **the next operator to sign in on the same activity would have been shown the previous operator's numbers** --
   on the screen this slice had just made the first one.

The read therefore belongs to the arrival, and the correction a review asked for moved two more things with it.
`DashboardRoute` refreshes on every entry and `refresh()` **cancels the read in flight** before starting the next
one -- two live reads are two answers that can land in either order, and the older one landing last would write
the last refresh's answer back over the newer one -- and it still drops the previous answer before asking, which
is the web's own shape (that page fetches on mount). The session half is `sessionGeneration`, the counter
`ProfileViewModel` also reads, but here it is the **key the view model is built under**
(`hiltViewModel(key = sessionKey)`) rather than something compared: the retained instance of a previous session is
then never the one this screen draws, not even for the frame that `collectAsState()` would otherwise have composed
before the effect ran.

**A pre-existing gap, recorded and not fixed here.** The same activity-scoping means the other read screens
(`LoadingViewModel`, `LoadingHistoryViewModel`, `ReportsHistoryViewModel`) keep a previous session's data until
their activity dies. That is not this slice's doing, and the fix for them is the shape this screen now has;
applying it to three screens is a unit of its own rather than three quiet edits inside a dashboard slice.

**Evidence.** `DashboardViewModelTest` (3) and `DashboardScreenTest` (6) are the new instruments. The second is
that lane's first *data* screen -- `LoginScreenTest` was the lane's first consumer and renders the login form,
while this one renders a screen whose content arrives from the server -- and it asserts the two actions it offers
fire with the right argument: the five labels with the values their strings produce, the reports card calling back
with the id the summary names and with the list when it names none, the schedules card calling back for the
schedule, that a state without a summary draws no zeroes, and that the failed state draws the sentence and nothing
else. The focused run was 3 classes and 14 tests green; the full suite went from 21 classes and 210 tests to
**23 and 219**, 0 failures, 0 errors, 0 skipped, in 6m56s, with every XML of that run its own.
### B2. Report edit

**Split into two units, and the first one is delivered.** Measuring the slice showed it could not be a pure
addition: the form it edits lives inline and `private` inside the generator screen, while the web shares one
component -- `CategorySection` -- between creating and editing, and two copies of a form drift in exactly the
validation the server later refuses.

#### B2a. The report form is one piece, and it draws the children — DELIVERED

**What moved.** `reports/ReportForm.kt` holds `ReportFormState` (the answers plus the product offers) and
`CategoryForm(category, children, form, readOnly, callbacks, depth)`, which is this app's `CategorySection`. The
generator screen keeps what surrounds the form -- the way to the history, the message, the created id and the
submit button -- and builds the state once. `readOnly` is the server's answer rendered rather than recomputed,
which is `D6`'s rule arriving at the last surface that lacked it.

**The defect the extraction found, and it is why the children are drawn.** The generator selected
`categories.filter { it.parentCategoryId == null }`: it drew the roots only, while the endpoint serves every
active category and orders a child right after its parent (`ORDER BY COALESCE(parent_category_id, id), sort_order,
id`), the web renders two levels, and the production seed has **two active children this app never drew** --
`report_categories` ids **9 `Caixas pequenas`** and **10 `Caixas Assai`**, both `parent_category_id = 8`. Its KDoc
also claimed a recursion that nothing performed. That is not cosmetic: `PATCH /api/reports/:id` replaces the
`items` array when the field is present, so an edit form that cannot see a child category's task would submit a
list without it and delete the item.

**Evidence.** `ReportFormTest` renders `CategoryForm` directly -- no ViewModel, no Hilt -- and asserts that a
child category's task is drawn beside its parent, that each task type draws what the form holds (the product
chips from the offers, one field per declared reading and no third one), that a tap reports the task its box
belongs to, and that `readOnly` disables the check box and the temperature field and a tap reports nothing. The
reports package ran at 51 tests green. One test defect was caught by the run rather than by reading: the helper
that asserts a node is drawn requires a unique label, and the data had named a category after its own task --
`Temperaturas` and `Câmara fria`, as production names them, is the shape the data has.

**Two of those assertions were asked for by the review rather than written first**, and both are of the kind a
form test cannot make. `ReportGeneratorScreenTest` renders the real screen and asserts that a child category's
task reaches it -- `ReportFormTest` hands the form its children itself, so it could never see the argument the
screen passes -- with a second root's own task asserted beside it, which is what separates a missing argument
from a wrongly keyed one. And the chip's read-only guard is asserted beside the same chip made editable, because
`assertIsNotEnabled` also passes on a chip that never worked. Both assertions were then **falsified**: passing
`children = emptyList()` in the screen fails the two screen tests with `could not find any node ... 'Caixas
pequenas'`, and making the chip ignore `readOnly` fails its test with `Failed to assert the following: (is not
enabled)`.

#### B2b. The edit surface — DELIVERED

**What it is.** `ReportEditViewModel` loads the report with the categories and the offers, seeds the form from
the report and submits an `UpdateReportRequest` through the same payload rules the generator uses -- those rules
moved to `ReportPayload.kt` in this unit, so creating and editing cannot drift in the body the server validates.
`ReportEditScreen` draws the shift, the notes, the message and the shared `CategoryForm`, and the operator reaches
it from the detail's own edit action through an overlay in `MainActivity`. The web's edit page also manages photos;
this screen does not, because the app's detail already does.

**The rule the slice existed for, and where it is rendered.** `D6`: the server computes `canEdit` and `readOnly`
and the app renders them. So the detail's edit action is drawn **only when `canEdit == true`** -- an absent flag is
not an affirmative one -- and **the read-only screen draws no save action at all**. A disabled action is still an
offer, and there is nothing for the operator to fix because the boundary is the server's window; the controls
above it are drawn disabled so the screen cannot even collect an edit it has no action to send. The derivation
mirrors the web's `readOnly ?? !canEdit` as `report.readOnly ?: (report.canEdit != true)`, which fails closed.

**Nothing stale after an edit.** The detail's view model is activity-scoped and used to return early for a report
it had already loaded, so coming back from a save would have shown the values from before it. `load` gained a
`force` flag and the detail's route reads on arrival, which is also what makes the edit's result visible. The same
activity-scoping is why the edit route awaits `saved` on a flow rather than keying an effect to it: a flag left
true by a previous visit would close the screen before its own load landed -- the sticky-flag defect this codebase
has already recorded once, for the login flag.

**Three findings the work produced, all recorded.** (1) A **403** for this endpoint *is* the edit-window refusal
(`reports.command.service.ts`), so answering it with the generator's "try again" sentence would send the operator
to retry something no retry can fix; the view model borrows the detail's own window sentence instead of inventing
a second one. (2) The notes are trimmed and sent as null when empty, mirroring the web's `notes.trim() || null`,
because "nothing" and the empty string are different statements to the server. (3) A **recorded wart**: the
`temperatures` field is omitted when the form produces no readings, so blanking every reading leaves the stored
ones in place while blanking one sends the field and deletes it. Clearing them all is not expressible -- a blank
reading has no wire representation, because the schema requires a value -- and it is recorded beside the call
rather than repaired.

**Evidence.** `ReportEditViewModelTest` covers the seeding from a report, the read-only derivation in three flag
combinations, the update carrying the form rather than the report, and the two refusals; `ReportEditScreenTest`
renders the stateless screen and asserts that an editable report offers a save that submits once, that a
**read-only one draws no save action at all** and its controls are disabled, and that the message is drawn; and
`ReportDetailViewModelTest` gained the forced-load test. The reports package ran at **67 tests green** and the
full suite is recorded below. Five assertions were falsified rather than trusted: drawing the save in read-only
fails with `found '1' node ... 'Atualizar Relatório'`, dropping the 403 arm fails with
`expected:<Fora do prazo de edição...> but was:<Não foi possível salvar...>`, dropping the notes rule fails with
`expected:<null> but was:<   >`, and -- from the previous unit -- emptying the children and making a chip ignore
`readOnly` each fail their own test.

**Three findings the review of this unit left, closed in the commit after it.** Two were one rule read twice, and
the third was a save that outlived its screen:

1. The detail drew its edit action on `canEdit == true` while the edit surface answered read-only with
   `readOnly ?: (canEdit != true)`, so a server answering both flags affirmatively -- which the wire allows, the
two are independent -- would have offered a way into a screen that draws no save. The rule now lives once, as
   `ReportResponseReport.isReadOnly()` in `ReportWindow.kt`, and both screens call it.
2. A save in flight was not stopped by leaving the screen, and `load` reset `saved` but not `submitting`: the next
   arrival showed a spinner it had never started, and when the stale write landed it set `saved`, which the new
   arrival's own `first { it.saved }` read as its own and closed on. `load` resets both flags, and the write
   a previous arrival started is kept off this screen by a request token rather than by a cancellation: a read
   is cancelled (the dashboard's own shape -- an answer nobody awaits is waste) while a write the operator
   asked for is left to finish, so the server applies it either way and what the token drops is that write's
   claim on a screen it no longer belongs to.
3. The reset the route's comment relies on had no test at any level. It has two now, one of them releasing a
   held write *after* a load to prove the claim is dropped rather than merely cleared, and a rendered detail test
   whose read-only leg is paired with an editable one so the missing action cannot pass on an empty screen.

A fourth read of those same two flags survives in `ReportDetailViewModel.deleteRefusal`, where it answers a
different question -- which sentence a refused photo deletion gets. Recorded rather than folded in, because
folding it would change a refusal message this unit never touched.

### B3. Loading edit

`LoadingEditPage` against the existing loading routes, on the same terms as its history surface:
the server decides what is editable, the 1-hour window is `loading`'s own rule and stays
untouched (`C1` in `android-app-v1.md` explains why that boundary matters).

**The shape, established before writing any of it.** Loading is a schedule of driver/vehicle/time-slot
rows and not a report: there are no loading items, no categories, no photos and no temperatures, so
nothing of `ReportForm`/`ReportPayload` transfers and a second, small component is what is right --
sharing would import report semantics into a schedule, which is the drift this track keeps warning
about. What the web's page does is one field: it draws the day's rows
(`GET /api/loading/schedules?date=`, `GET /api/loading/time-slots`), offers the way in only where the
server says `entry.canEdit`, replaces that row with an inline `<select>` of the time slots annotated
with the quota each one has left, keeps its save dead until the slot changes, and sends
`PATCH /api/loading/schedules/:id` with `{ time_slot }` **and nothing else** -- a partial merge on the
server, which resolves every field it receives against the existing row. A refusal surfaces the
server's own sentence, and the window half of it is the backend's
(`loading.service.ts`, `projectHistoryPermissions(actor, existing, 'one-hour')` -> 403), which is
exactly C1's boundary: the app renders the flags and never recomputes the window.

Tasks, each closing with its own commit:

- **B3-1. Establish what the client puts on the wire, before it writes one.** `UpdateScheduleRequest`'s
  nullable fields default to null, and the server reads an explicit null as a value: a partial update
  whose omitted fields serialize as explicit nulls would clear a driver or a vehicle the operator never
  touched. Prove the serializer's behaviour with a test that asserts the body of an update carrying only
  a time slot contains exactly that field, and fix the configuration or the way the request is built if
  it does not.
- **B3-2. The view model.** Read the row and its time slots, seed the selected slot, submit the partial
  update, map the refusal arms to the sentences the operator reads (the window's own 403 sentence
  verbatim, and whatever arms the repository's existing result types already tell apart), and keep an
  older arrival from claiming this screen -- the token for the write, the cancellation for the read, the
  shape the reports unit ended on.
- **B3-3. The rendered surface.** The way in only where the server says `canEdit`; the picker with each
  slot's remaining quota; a save that is dead until the slot changes; a read-only arrival that draws no
  edit at all. This is loading's first Compose-lane test.
- **B3-4. The way in.** `MainActivity`'s overlay flag beside `editingReportId`, and the row's edit action
  gated on the same predicate the other screens read.
- **B3-5. Batched: the reports unit's own evidence.** `ReportEditViewModelTest` proves that a superseded
  save does not announce itself, not that it still reaches the server -- a fake that records its own
  completion is what tells the two apart, because under a cancellation the gate's `await` throws and the
  counter stays at zero. Falsified by putting the `cancel()` back and watching it fail.

**Delivered.** B3-1 is `487c7a5`, B3-5 is `c1f6cca`, and B3-2 is `bda06e8`; the rendered surface,
its way in and the refresh described below close in the final B3 commit. The wire test uses the app's
real Retrofit/Json stack and proves a slot-only update is exactly `{"time_slot":"06:30"}`; its
`encodeDefaults = true` mutant reproduces the destructive null body. The reports fake records only an
update that resumed after its gate, and putting the cancellation back makes exactly that assertion fail.
The edit view model carries only the slot, leaves a write to finish behind a request token, cancels the
read it replaces, renders the server's flags without recomputing the one-hour window, and shows the
backend's 403 sentence byte for byte.

Two deliberate details are stated rather than hidden. Loading's read-only predicate is
`readOnly || canEdit != true`: both fields are required in this contract, the web gates on `canEdit`,
and an inconsistent pair fails closed. The picker's count always excludes the row and then adds it back
when it is a fletero, so its own slot agrees with the grid; the web's own-slot expression counts that row
and adds it again. Copying that double count would make the number change merely by opening the editor.

The rendered surface draws the row, the configured slots and each slot's fletero quota, marks an
exceeded option without disabling it, reads `state.canSubmit` instead of rebuilding its rule, and draws
no save action at all where the server withheld editing. Its row-level way in reads the same predicate.
Returning from a save also carries one fact back to the grid: `loadingStale`. The next arrival consumes
it and forces exactly one read through `LoadingViewModel.onDateChange(date, force)`, then ordinary tab
arrivals keep the old no-double-read rule. Without that one-shot, the row the operator just moved was
drawn in the slot it moved out of, which read as the edit having done nothing. A mutation that ignores
`force` fails exactly the view-model and rendered arrival legs.

---

## Slice C — The admin panel

One surface per work unit, in dependency order: `tasks` and `drivers` are the two a
`Trabalhador` can write, so they are the two whose role gating must be right first.

### C1. Task types (`tasks`) — Administrador + Trabalhador
### C2. Drivers (`drivers`) — Administrador + Trabalhador
### C3. Categories (`categories`) — read for both, write Administrador only
### C4. Vehicles (`vehicles`) — Administrador
### C5. Time slots (`time-slots`) — Administrador, `PUT` replaces the set
### C6. Users (`users`) — Administrador

Each surface: list, create, edit, deactivate/delete where the web allows it, an inline form
matching the web's fields, and the same validation the server enforces stated where the operator
types instead of discovered as a 400. Each carries its own review unit.

**Shared risk to handle once, in C1, then reuse:** the web's `InlineForm` and its per-tab field
sets. Rebuilding that six times in Compose is how six surfaces drift apart in validation and
error handling. C1 establishes the form/validation convention; C2–C6 follow it.

---

## Slice D — Audit

### D1. Audit log (`/admin/audit`, Administrador)

Read-only, paged, with the same filters the web offers. Depends on `P1` for its schemas.

---

## Slice E — Close

### E1. Parity verification matrix

Walk the route table above against the built app, one row at a time, and record for each whether
it is present, role-gated as the server enforces it, and reachable. A parity claim nobody walked
is the claim this feature exists to stop making.

**Acceptance criteria for the feature as a whole:**

1. Every row in the gap table is present or has a recorded, user-accepted reason not to be.
2. No surface is visible to a role the server would refuse.
3. The contract covers every admin and audit operation the app calls.
4. The JVM suite is green, and the app was **looked at** on the emulator for each new surface,
   not only compiled — the icon review in `android-app-v1.md` is the precedent: a tool reading
   an APK is not an operator reading a screen.

---

## Locked order (confirmed by the user, 2026-09-21)

1. **P1** (contract precondition) → **A1** (role gating)
2. **Slice T** — the Compose UI test lane, decided before any UI slice lands (see below)
3. **Slice B** — the daily operational gap: B1 Dashboard, B2 Report edit, B3 Loading edit
4. **Slice C** — admin panel, C1 → C6
5. **Slice D** — audit
6. **E1** — parity walk

The operational edits go before the admin panel because they are the work the crew already does
on the phone every day, they sit next to screens that already exist, and each is a smaller review
than any admin surface.

### Slice T — why a test lane sits between the contract and the first screen

`R3-001` of `review-380c84270c06db8c` found that the login screen's scroll container is covered by no
test, so a regression in its modifier order or its centring would go unnoticed. Closing that needs a
lane that can render Compose on this machine, and this track is what makes it worth paying for: it
adds nine renderable surfaces — Dashboard, two edit screens, six admin panels and audit — and nothing
in the project can render one today.

`P1` stays ahead of it deliberately: it is backend and contract work and touches no Compose, so the
lane does not slow it. Slice T then lands before Slice B, which is where the screens start.

What it costs, measured rather than guessed: there is **no `androidTest` source set**, and an
instrumented lane could not run here anyway because the emulator lives on the Windows box and is
unreachable. The JVM test dependencies are `junit`, `mockwebserver` and `kotlinx.coroutines.test`,
with no Robolectric and no `ui-test-junit4`, so the lane means `androidx.compose.ui:ui-test-junit4`
(from the BOM), `ui-test-manifest` and Robolectric, plus `unitTests.isIncludeAndroidResources = true`
— three dependencies, a build flag, and a first test that establishes the conventions.

It also has to prove itself against the case that motivated it: the first test in the lane should
assert the login screen's scroll container behaves as the fix claims, because that layout is the one
regression of this session that no test caught. That is the test T1 shipped, and both halves of it were
made to fail before either was trusted — see `T1` above.

## Locked decisions

### E2. `SetupPage` is excluded, and this is the reason

The SPA gates the whole application on it. Production is already bootstrapped, so no operator
reaches it from a phone — and the app needs a session to reach anything at all, which means the
setup surface would sit behind the login it exists to enable. Recorded here rather than left
silent, so a later reader finds a decision instead of an omission. Revisit only if a new
installation must be bootstrapped without a desktop.

### E3. One APK, gated by role

Same binary for every operator; the navigation is built from the role the server returns. This
matches the web and the server guards, keeps one release channel and one signature, and avoids
two pipelines that would drift in version and signing. It is **not** a relaxation of
permissions: the server remains the authority, and a hidden control authorizes nothing. `A1`
aims the app at the server's own role matrix rather than at a copy of it.

## Open decisions

None outstanding. New ones are recorded here as they appear.

## Evidence so far

- The backend role guards were read, not assumed: `adminGuard` / `catalogGuard` in
  `modules/admin/admin.routes.ts`.
- The SPA tab filter (`AdminDashboard.tsx:158-162`) matches those guards exactly.
- The gap table was derived from `App.tsx` routes and the Android screen inventory
  (`find ... -name "*Screen.kt"`), not from memory.
