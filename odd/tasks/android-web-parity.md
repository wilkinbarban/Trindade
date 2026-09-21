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

### P1b. Register admin and audit in the OpenAPI contract — PENDING

What remains, in one work unit (it cannot be split further and stay green: emptying the exclusion makes
the coverage test demand every admin operation in the document, so the registry entries and the exclusion
have to move together):

- Register the **22 operations across 16 paths** — 15 admin paths plus `/api/admin/audit` — in
  `contracts/openapi.ts`, reusing the schemas P1a declared rather than restating them. The registry
  currently documents 32 paths / 41 operations / 26 components; this takes it to 48 paths and 63
  operations.
- Regenerate `packages/contracts/openapi.json`.
- **Empty the out-of-scope set** in `contracts/route-coverage.test.ts`. There is a trap here: that file
  asserts `excluded.length > 0` with the message "the out-of-scope set matched nothing, so it may be
  stale", so emptying the set fails that assertion until it is updated in the same commit. The
  assertion existed to catch a stale prefix; with the set empty, `assert.deepEqual(OUT_OF_SCOPE_PREFIXES,
  [])` and a comment recording why nothing is out of scope any more is what replaces it — the guard must
  keep failing in both directions, not lose a direction.
- Keep the coverage test failing in both directions: nothing served may be undescribed, and nothing
  described may be unserved.
- Move `TimeSlotsResponseSchema` to `contracts/common.schema.ts` while that file is open, since it is
  shared with `loading` and P1a could only re-export it (finding 5 above).
- Regenerate the Android contract types (`scripts/generate-android-contract.sh`) in the same work unit, so
  the artifact and the client cannot drift.

The live-response contract tests already landed in P1a and do not repeat here. Write responses change
 data, so they stay covered by the route-coverage test plus the existing backend admin suite.

**Acceptance:** `scripts/verify-openapi-artifact.sh` reports current; the backend suite is green; the
route-coverage test proves nothing served is undescribed and nothing described is unserved, with the
admin/audit exclusion gone; and the regenerated Android types compile.

**Not in scope:** changing any admin behavior. This slice describes what is already served.

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

**What P1a surfaced and deliberately did not change** (all outside its authority, all recorded here
because the next person touching the admin surface needs them):

1. **The task list and the task writes serve different shapes.** `listTasks` selects
   `rc.name_pt AS category_name` and the write paths do not, so a listed task carries a field a
   created one does not. Modelled truthfully as two schemas rather than one widened with an optional
   field — the drift is real and an optional field would hide it.
2. **A PATCH with no fields changed returns an unprojected row.** `UpdateTaskSchema` is fully
   optional, and `updateTask` returns the `SELECT *` row when nothing changed — which carries neither
   `task_type` nor `category_name` and matches **neither** schema. The handler is what looks wrong;
   the schema was not widened to fit it. The same early return exists for categories, drivers and
   vehicles, but for those `SELECT *` happens to equal the served shape, so tasks is the only one.
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
