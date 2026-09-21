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

### P1. Register admin + audit in the OpenAPI contract

- `modules/admin/admin.schema.ts`: Zod request and response schemas for the admin surface,
  `.strict()` like every other module, with the TypeScript types derived by `z.infer`. The
  OpenAPI registry currently documents 32 paths / 41 operations; this slice adds the admin and
  audit paths and their components.
- Register in `contracts/openapi.ts`, regenerate `packages/contracts/openapi.json`, and
  **shrink the declared out-of-scope set** in `contracts/route-coverage.test.ts` to empty (or
  to whatever genuinely remains undocumented, with the reason written next to it). The coverage
  test must keep failing in both directions.
- Contract tests that parse **live responses** for each admin read endpoint, in the shape
  `contracts/loading.contract.test.ts` established. Write responses change data, so they are
  covered by the route coverage test plus the existing backend admin suite rather than by a
  new live-mutation contract test.
- Regenerate the Android contract types (`scripts/generate-android-contract.sh`) in the same
  work unit so the artifact and the client cannot drift.

**Acceptance:** `scripts/verify-openapi-artifact.sh` reports current; the backend suite is green;
the route-coverage test proves nothing served is undescribed and nothing described is unserved,
with the admin/audit exclusion gone.

**Not in scope:** changing any admin behavior. This slice describes what is already served.

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
2. **Slice B** — the daily operational gap: B1 Dashboard, B2 Report edit, B3 Loading edit
3. **Slice C** — admin panel, C1 → C6
4. **Slice D** — audit
5. **E1** — parity walk

The operational edits go before the admin panel because they are the work the crew already does
on the phone every day, they sit next to screens that already exist, and each is a smaller review
than any admin surface.

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
