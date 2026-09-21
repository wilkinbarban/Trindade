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

### T1. Make a Compose screen renderable in a JVM test

`R3-001` of `review-380c84270c06db8c` found the login screen's scroll container covered by no test, so a
regression in its modifier order or its centring would go unnoticed. Nothing in the project can render a
Compose screen today, and the measurement says what closing that costs: **no `androidTest` source set
exists**, and an instrumented lane could not run here in any case because the emulator lives on the
Windows box and is unreachable. The JVM test dependencies are `junit`, `mockwebserver` and
`kotlinx.coroutines.test`, so the lane means `androidx.compose.ui:ui-test-junit4` (from the BOM),
`ui-test-manifest` and Robolectric, plus `unitTests.isIncludeAndroidResources = true`.

**It has to prove itself against the case that motivated it:** the first test in the lane asserts the
login screen's scroll container behaves as the fix claims — that a short viewport scrolls to the submit
button rather than squeezing it, and that the content still centres when it fits. That layout is the one
regression of this session that no test caught, and a lane that does not cover it would be infrastructure
built for a hypothetical.

**Acceptance:** the lane runs in the existing `:app:testDebugUnitTest` task with no new Gradle task,
the login scroll test fails if the `verticalScroll` modifier is removed, and the Android lane's runtime
stays within what the CI image already allows.

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

---

## Slice B — The operational gap

### B1. Dashboard

`DashboardPage` against `modules/dashboard` (already documented in the contract since `B2.4`, so
this slice needs no prerequisite). Read-only summary, the same role visibility as the web
(every signed-in user).

### B2. Report edit

`ReportEditPage` has no Android counterpart, and `ReportDetailViewModel` deliberately supports
view + photos + export only. The slice adds the edit surface: selected products and quantities,
observations, and whatever else the web form edits, against the existing reports routes.

**Constraint:** the day-window rule is `D6`, and the server returns `canEdit` / `readOnly`. The
app renders those flags; it never computes the boundary itself. A report the server marks
non-editable must not offer an edit action.

### B3. Loading edit

`LoadingEditPage` against the existing loading routes, on the same terms as its history surface:
the server decides what is editable, the 1-hour window is `loading`'s own rule and stays
untouched (`C1` in `android-app-v1.md` explains why that boundary matters).

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
regression of this session that no test caught.

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
