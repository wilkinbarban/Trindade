# ODD Feature: Trindade Android Client v1 (+ backend prerequisites)

Status: in progress
Created: for the Android delivery of Trindade Massas Operações
Engram mirror topic: `odd/android-app-v1/tasks`

## Goal

Deliver a native Android client (Kotlin + Jetpack Compose) for Trindade Massas
Operações covering **field operations only**, with the backend prerequisites it
depends on landed first.

## Locked decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Client is **native Kotlin + Jetpack Compose**, not Capacitor | Chosen by the user for real camera, file and performance behavior |
| D2 | v1 scope is **field operations only** | Relatórios, Horários, photos, history, WhatsApp export. Admin stays desktop-web |
| D3 | Session is **refresh token + revocation** | The current 15-minute JWT with no refresh makes a mobile session unusable in the yard |
| D4 | Distribution is a **signed downloadable APK** | No Play Store, no offline sync |
| D5 | Fletero quota stays **informative only** (indicator, no rejection) | Deliberate company logistics decision, not technical debt. See C2 |
| D6 | Reports edit window is **current day + previous day** (PRD §16) | Replaces the ad-hoc 1-hour window. See C1 |

### Explicitly superseded

`openspec/changes/archive/2026-08-23-trindade-hardening-deployment-android`
decided Capacitor and listed "native client rewrite" as a **non-goal**. D1
reverses that knowingly. Cost accepted: two UI clients maintained in parallel
for i18n, forms and export surfaces.

### Not in scope

- Offline write queue / sync. No Room write path, no conflict resolution.
- Google Play distribution.
- Any change to the loading-schedule 1-hour edit window (see C1 note).
- Admin management surfaces (users, catalogs, vehicles, audit) on mobile.

---

## Slice A — Backend: session refresh and revocation (P1)

### A1. Schema revision 2 with auth session persistence — DONE

Add revision 2 to the schema-version mechanism and create the session table.
No endpoints yet: this slice is data-layer and revision-infrastructure only.

**Status: complete and verified.** Evidence: backend suite 250/250 (6 new tests),
`scripts/verify-schema-clis.sh` passed, and the full canonical gate `scripts/ci.sh`
passed in a fresh clone on `node:24-bookworm-slim` (build, typecheck, backend suite,
schema CLI verification, 53/53 Playwright E2E).

Target shape (final; adjust only with a recorded reason):

```sql
CREATE TABLE IF NOT EXISTS auth_sessions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash   TEXT    NOT NULL UNIQUE,   -- sha256 hex of the refresh token, never the raw value
  family_id    TEXT    NOT NULL,          -- rotation family, for reuse detection
  expires_at   TEXT    NOT NULL,
  revoked_at   TEXT,
  replaced_by  TEXT,                      -- token_hash of the successor
  created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
  last_used_at TEXT,
  ip_address   TEXT
);
```

Required work:

- `db/schema.sql`: add `auth_sessions` and its indexes.
- `db/schema-version.ts`: `SCHEMA_VERSION = 2`, append the revision-history
  comment entry, and add `auth_sessions` to `SCHEMA_TABLES`.
- `db/migrations.ts`: replace the single hardcoded step with a **stepwise list
  driven by `before.version`**, as the existing comment at
  `migrations.ts:24-26` already reserves. Revision 1 → 2 must be additive only
  (`CREATE TABLE IF NOT EXISTS`) and idempotent. An `unversioned` database must
  be able to reach 2 in one operator run.
- Tests: the hard-asserted table count of 14 and the rendered revision strings
  in `schema-version.test.ts`, `db-init.test.ts`, `migrations.test.ts` and
  `status.test.ts` all need updating for revision 2 and 15 tables.
- New tests: migrating a revision-1 fixture to 2, idempotency, and the
  immutability gate still holding for a `newer`/`incompatible` database.

Open risk to record, not necessarily fix in this slice: `db/index.ts` runs
`db.exec(schema)` **outside** the seed+stamp transaction, so a crash between
them leaves a schema-only, unstamped file that `classifyInstallation` then
treats as `existing` forever. With a new table in the fresh path this window
gets marginally wider. Decide explicitly whether to fix it here or file it.

### A2. Refresh token issuance, rotation and revocation

Split into three review units because together they exceed the 400-line budget.

Outcome union returned by rotation, which A2.2 must map to HTTP and audit:

    { outcome: 'rotated'; userId; refreshToken }
    { outcome: 'unknown' }
    { outcome: 'expired' }
    { outcome: 'reused' }
    { outcome: 'revoked' }
    { outcome: 'inactive-user' }

`reused` is the security-relevant one: it means a token that was already rotated away was
presented again, so the whole family was revoked. Every other failure maps to the same 401
so a caller cannot probe which of them happened.

#### A2.1. Session service — DONE

`modules/auth/auth.sessions.service.ts` plus its tests. Evidence: backend suite 259/259
(9 new), build clean, on Node 24.21.0.

Shape delivered: `hashRefreshToken`, `issueSession`, `rotateSession`, `revokeSession`,
`revokeUserSessions`, `purgeDeadSessions`. Opaque token is
`randomBytes(32).toString('base64url')`; only its `sha256` hex is persisted. Expiry is
decided in SQL (`expires_at <= datetime('now')`), never by parsing the SQLite datetime in
JavaScript, and lifetimes are applied through `datetime('now', ?)` so one clock computes
them. The whole of rotate is one `db.transaction`, so a crash cannot leave a token both
revoked and un-replaced.

**Design refinement found while writing the tests, and it matters for A2.2:** a token can be
dead in two different ways, and conflating them is a real bug. A token **rotated away**
(`replaced_by` set) means the client that consumed it already holds its successor, so
whoever presents it is not that client: the token leaked, and the family is revoked. A token
**explicitly revoked** (logout, password change) means the session simply ended. Treating
that second case as reuse would mean a client retrying after its own logout destroys every
other session of the same user, including a desktop that was never involved.

#### A2.2. Routes, schemas and configuration — DONE

Evidence: backend suite 270/270 (11 new: 8 route-level, 3 configuration), plus the full
canonical gate `scripts/ci.sh` passed in a fresh clone on node:24-bookworm-slim (build,
typecheck, backend suite, schema CLI verification, 53/53 Playwright E2E).

- `modules/auth/auth.schema.ts` (new): move the inline `loginSchema`,
  `changePasswordSchema` and `updateProfileSchema` out of `auth.routes.ts` and export them,
  adding schemas for refresh and logout. This is the auth half of what the plan used to
  call B1, folded here because A2 needs the refresh schema anyway.
- Access token stays a 15-minute JWT, and the numeric lifetime becomes the single source
  for both `expiresIn` and the signed claim, so they cannot drift. The SPA keeps working
  with zero changes.
- `POST /api/auth/login` additionally returns `refreshToken` and `expiresIn` (additive;
  the SPA ignores unknown fields).
- `POST /api/auth/refresh` with `{refreshToken}` returns `{token, refreshToken, expiresIn}`,
  or 401. It must NOT use the authenticate preHandler: the access token is expired by
  definition when a client refreshes.
- `POST /api/auth/logout` keeps its authentication and accepts an optional `{refreshToken}`
  to end that one session. Its body must be tolerated when absent or empty.
- `POST /api/auth/change-password` revokes all of that user's sessions.
- `REFRESH_TOKEN_TTL_DAYS` in `runtime-config.ts`, default 30, rejected when it is not a
  positive integer. **`.env.example` could not be updated:** the harness safety policy
  blocks that path, so this variable has to be added there by hand. The default is
  documented in code, so an operator who never sets it still gets correct behavior.
- Audit action `refresh` on success only. The `revoke` action was dropped from the plan
  deliberately: the refresh-failure path is unauthenticated, and writing one audit row per
  rejected attempt would let a single leaked refresh token amplify into unbounded audit
  writes. Failures are logged with their outcome instead. See A2.4.

#### A2.3. Wire session purge into startup

A2.1 ships the tested `purgeDeadSessions`; this slice decides and implements when it
runs. Purging expired session rows is a write to an existing database at startup, the same
class of act as the photo-retention cleanup that already runs there, so it must be
evaluated against the Production Immutability Gate rather than slipped in with the auth
endpoints. Cover it with the existing immutability tests.

#### A2.4. Durable audit record for reuse detection (follow-up)

Reuse detection is the one security event in this feature that leaves no durable record:
A2.2 logs it at warn level rather than writing to `audit_logs`, because doing that naively
creates an unauthenticated write path that a single leaked token can amplify.

The fix is not to skip the record but to bound it. Revoking a family only changes rows the
first time, so the audit write should happen exactly when the revocation actually ended
live sessions, and be skipped when it did not. That needs `rotateSession` to report whether
the family had live sessions when reuse was detected, which is an API change to A2.1 and
belongs in its own review.

Non-goals for A2 as a whole: no access-token denylist (access tokens stay stateless and
short), no device-management UI.

### A3. Auth documentation and spec alignment — DONE

Update `README.md`, `.env.example` and the relevant OpenSpec auth spec for the new session
model. Fresh-install and existing-install sections must keep claiming exactly what the code
does.

Delivered in `README.md`:

- Revision references corrected to **2**, with revision 2 described as adding `auth_sessions`.
- The migration section now describes the stepwise list (0 → 1 legacy rebuild, 1 → 2 additive)
  and states that a revision 1 database runs only the second step, so its temperature data is
  never touched.
- The revision-aware verdict rule is documented, because it is what keeps a revision 1
  installation migratable instead of refused as `incompatible`.
- A new **Authentication and sessions** section covering the refresh token, hash-only
  persistence, rotation, the rotated-away versus explicitly-revoked distinction, logout
  scope, password-change scope, and `REFRESH_TOKEN_TTL_DAYS`.
- The **Existing installation** paragraph was corrected: it claimed startup "opens it
  read-only" and that write endpoints were unavailable, which was false. Startup does leave
  the schema alone, but it performs the 30-day photo retention cleanup and the application
  writes audit records, sessions, reports and schedules. The README's own recovery section
  already said policy-expired photos may be deleted at startup, so the old paragraph
  contradicted the rest of the document.
- The backend test count corrected to 280, the build-before-test prerequisite documented, the
  contract generation and verification commands added to the Testing section, and
  `packages/contracts` plus `odd/` added to the project structure.

Delivered in `openspec/specs/auth/spec.md`: a new **Session Refresh and Revocation**
requirement with eight scenarios covering rotation, reuse detection, the revoked-versus-reused
distinction, single-session logout, whole-user password-change revocation, hash-only storage,
indistinguishable failures, and deactivated users.

**`.env.example` could not be updated.** See F6; the harness blocks that path at the tool
level and does not accept a user grant, so it needs a human edit.

---

### A4. Fail clearly when the database is not migrated

Chosen when the deployment order was decided (F12). Today, running the revision-2 code against an
unmigrated database makes login answer
`500 {"code":"SQLITE_ERROR","message":"no such table: auth_sessions"}` — a raw driver error
naming an internal table. The migration warning *does* appear at boot (`verdict: outdated`, level
warn), but nothing connects the two for an operator who sees only the failed login.

Design: the startup schema notice already reads the table inventory through `observeSchema`, so
the presence of `auth_sessions` is known at boot without an extra query. Compute that boolean
once and pass it to `authRoutes` as an option, alongside `jwtSecret` and `refreshTokenTtlDays`.
When it is false, the three paths that touch sessions — login, refresh and logout — answer
**503** with an operator-facing message naming the required action, and the boot log states the
missing table next to the existing verdict line.

**Why gate on the table and not on the verdict**: `outdated` and `unversioned` both lack the
table today, but a future revision could be `outdated` for a reason unrelated to sessions.
Gating on the object the code actually needs cannot produce a false positive.

Non-goal: refusing to serve entirely. Reports, loading and dashboard never touch
`auth_sessions`, and refusing to boot would turn a diagnosability problem into an availability
one. This is a behaviour change and must not be bundled with the runbook edit.

Tests: a revision 1 database makes login answer 503 with the actionable message and never 500; a
current database still answers 200; and the boot notice names the missing table.

---

## Slice B — Backend: machine-readable contract (P2)

### Decision: B2c — scoped to field operations, with runtime validation

Resolved from three candidates:

| Option | Verdict |
|---|---|
| **B2a** — generate from request schemas, hand-declare responses, no runtime validation | **Rejected.** The TypeScript type could still lie about the runtime data, and a mobile client has no way to complain: it either crashes or renders nothing. That is the exact drift B2 exists to kill, so shipping it would not buy the thing we are paying for. |
| **B2b** — response validation in all modules | **Rejected for now.** Cleanest end state, but it drags in the 21 admin routes and the audit route, which have no mobile client by decision D2, for roughly 1000+ changed lines. |
| **B2c** — response validation for the 37 field-operations routes only (auth 8, reports 15, loading 13, dashboard 1), plus a route-coverage test | **Chosen.** Matches decision D2, covers every route the Android client actually calls, and stays reviewable. Additive: admin and audit join the same registry later without rework. |

Consequence accepted: admin and audit responses stay unvalidated. A future admin mobile
surface needs a follow-up that adds their response schemas and registry entries.

### Re-sliced by surface (decided while starting B2.1)

The original split was by concern — harness, then response schemas, then registration. That
cannot produce a green intermediate state: the coverage test requires every registered route
to appear in the document, so a harness shipped with only part of the surface documented
fails its own test. The document would also be half-true for several slices, which is worse
than not having it yet.

B2 is therefore sliced by **surface**, and each slice lands complete (requests and responses)
and green:

| Slice | Surface | Status |
| --- | --- | --- |
| B2.1 | harness + `auth` (8 paths / 9 operations) | DONE |
| B2.2 | `loading` (10 paths / 13 operations) | DONE |
| B2.3 | `reports` (10 paths / 15 operations) | DONE, as B2.3a and B2.3b |
| B2.4 | `dashboard` and the ungrouped routes (4 paths / 4 operations) + shared route registration + the coverage test | DONE |

The four rows reconcile against the artifact the CI check verifies: 32 paths and 41 operations,
which is 8+10+10+4 and 9+13+15+4. That agreement is worth keeping as a check on the table
itself, because a row that silently changes count is how a summary drifts from the thing it
summarises.

The coverage test lands in B2.4, once every field-operations path is registered and it can
pass. Until then the CI freshness check still guarantees the artifact matches the schemas,
but nothing fails when a route exists that was never registered. That gap is why B2.4 is not
optional.

### B2.1. Contract harness and the auth surface — DONE

Evidence: backend suite 280/280 (10 new contract tests), `scripts/verify-openapi-artifact.sh`
passes, and the full canonical gate `scripts/ci.sh` passed in a fresh clone on
node:24-bookworm-slim, now including a "Verify the generated API contract is current" step.
The freshness check was itself verified by tampering: replacing the committed artifact with a
stale document makes the check fail with a diff.

Delivered:

- `packages/contracts` is a real workspace member holding `openapi.json` (OpenAPI 3.1, 9 named
  schemas, 8 paths) and a README stating the artifact is generated.
- `modules/auth/auth.schema.ts` gained the response schemas (`AuthUserSchema`,
  `AuthProfileSchema`, `LoginResponseSchema`, `RefreshResponseSchema`, `ProfileResponseSchema`,
  `SuccessResponseSchema`, `SetupStatusResponseSchema`, `SetupResponseSchema`) and absorbed
  `setupSchema`, which was inline in `bootstrap.routes.ts`. That is the remainder of what the
  plan called B1. `audit.routes.ts` keeps its schema inline because audit is out of scope.
- `contracts/error.schema.ts` holds the shared error envelope; `contracts/openapi.ts` holds the
  registry and `buildOpenApiDocument()`; `contracts/generate.ts` is the CLI that writes the
  artifact.
- `contracts/openapi.test.ts` parses **live responses from the running app** against the
  declared schemas — login, refresh, profile, logout, a 401 envelope, and a 400 envelope with
  `details`. That test is what makes a response schema worth having at all.

Two things found by running it rather than assuming:

1. **The conversion library pins the Zod major.** `@asteasolutions/zod-to-openapi` 8.x and 9.x
   require Zod 4, and the project is on Zod 3.24, so the dependency is pinned at `^7.3.4`.
   Worth revisiting deliberately: Zod 4 ships `z.toJSONSchema()`, so upgrading Zod would
   remove this dependency entirely. That is its own migration, because Zod 4 changes error
   formatting and the `details` envelope that every module's tests assert.
2. **`registry.register(refId, schema)` requires `extendZodWithOpenApi(z)` first**, because it
   names a schema by calling `.openapi(refId)` on it. Without the extension it fails at runtime
   with `zodSchema.openapi is not a function`.

The dependency is a **devDependency of the backend**, so production dependencies and
`npm audit --omit=dev` are unchanged.

### B2.2. The loading surface — DONE

Evidence: backend suite 291/291 (11 new: 8 loading contract tests, 3 document tests), the
freshness check reports the artifact current, and the full canonical gate `scripts/ci.sh`
passed in a fresh clone on node:24-bookworm-slim (build, typecheck, 291 tests, schema CLI
check, contract freshness, 53/53 Playwright E2E).

Delivered:

- `loading.schema.ts` response shapes are Zod schemas with the TypeScript types derived by
  `z.infer`: `ScheduleSchema`, `LoadingBatchHistoryItemSchema`, `DriverSchema`,
  `VehicleSchema`, plus the response envelopes. Every one is `.strict()`.
- `contracts/common.schema.ts` holds the shapes more than one module returns:
  `SuccessResponseSchema`, `TextResponseSchema`, `PaginationSchema`. `SuccessResponseSchema`
  moved out of `auth.schema.ts`, and the auth response schemas were tightened to `.strict()`
  at the same time so the whole contract has one rule.
- `contracts/loading.contract.test.ts` parses live responses against the declared schemas for
  reference data, schedule creation, update, deletion, history, export, quick-add and a
  rejection.

**Change to the API, additive only.** `create` and `update` used to return the raw database
row while `listByDate` returned a projection, so one declared type described three different
runtime objects and an `as ScheduleRow` cast hid the difference. Both now project through the
same function, so a mutation response carries `creator` and the permission flags it was
missing. The contract test asserts a created schedule is **field-for-field identical** to the
listed one, because a `.strict()` schema would happily accept either shape on its own.

A rule adopted here and worth keeping: contract slices may make **additive** response changes
and must not make subtractive ones. That is why `is_active` and `creator_name` — raw columns
the projection spreads through, duplicating `isActive` and `creator` — are documented rather
than removed.

### B2.3. The reports surface — DONE (two slices)

**B2.3a** gave `reports` the schemas the plan called for: the response shapes plus a photo
shape, `parseSelectedProducts()` replacing two unguarded `JSON.parse` calls, and a named row
shape where an `as any[]` cast had been. Four advisories from an earlier review landed here
too. `reports.schema.ts` kept its own `HistoryQuerySchema` and `HistoryPagination`; the
duplication against `contracts/common.schema.ts` is recorded as an open decision rather than
resolved, because collapsing it belongs with a slice that touches both modules.

**B2.3b** registered the fifteen reports operations across ten paths, taking the artifact to
28 paths, 37 operations and 23 components.

Evidence: backend suite 312/312, artifact freshness check current, full canonical gate green
in a fresh clone.

The review caught the slice's real defect, and it is the one worth remembering:
`R3-double-plus-nan`. A string concatenation written as `+ + 'string'` parsed as *unary plus*
and produced `NaN`, which corrupted a description **inside the committed artifact**. Nothing
typed it, nothing tested it, and the generated JSON is exactly where a human reader is least
likely to look. The fix added a guard test for degenerate text, and the lesson is that a
generated artifact is still code that needs a test standing behind it.

### B2.4. The dashboard surface and the coverage harness — DONE

This closes B2c. The contract is now enforced rather than merely written down.

Delivered:

- `registerApiRoutes(fastify, options)` in `src/routes.ts` holds every route the API serves.
  `server.ts` keeps what is about running a process — configuration, the database handle,
  CORS, multipart, the startup notices, the retention cleanups and the listener. The
  extraction is what makes the coverage test possible: had the routes stayed in `server.ts`,
  the test would have had to repeat the list by hand, and a hand-repeated list is the drift
  the test exists to catch.
- `contracts/route-coverage.test.ts` registers the same set on a throwaway instance, collects
  what Fastify actually accepted through its public `onRoute` hook, and requires every route
  to appear in the document or in an explicit out-of-scope set. Both directions are asserted:
  nothing served is undescribed, and nothing described is unserved. Admin and audit are the
  declared exclusion, with the scope decision written next to it, so a new route forces a
  decision instead of escaping the contract.
- `modules/dashboard/dashboard.schema.ts`, so a count the summary never produces cannot pass
  as one it does.

Evidence: backend suite 315/315 (3 new coverage tests), artifact regenerated to 32 paths, 41
operations and 26 components, freshness check current, and the full canonical gate green in a
fresh clone including 53/53 Playwright E2E. Those E2E tests run against `server.ts`, so they
are also the proof that moving the registration out of it broke nothing at runtime.

**Change to the plan, forced by the harness.** Building the coverage test exposed four routes
that were served and undescribed, including an error of my own from B2.3b: the document
described `/api/reports/photos/public/{token}`, which answers 404 in practice, and not
`/p/{token}`, which is the route that works and the one the WhatsApp exports point at. All
four are now documented — the dashboard summary, the liveness probe, the user options the
history filters use, and that short public photo link. The parameter normalisation went as
planned: the registry writes OpenAPI templates and Fastify registers `:param` routes, so the
comparison maps one to the other.

**Review outcome.** `review-9ce78fe16bf0bb6f`, medium tier, one consolidated lens
(`review-reliability`), approved on the first pass with no correction. Its single finding was
the slice's real weakness: the test compared paths and ignored HTTP methods, so a document
naming the right path with the wrong verb passed as a match. The reviewer marked it
non-blocking, and it was fixed as the follow-up commit it asked for rather than as a
re-review. Proven by negative control instead of assertion: flipping the server's health route
from GET to POST while the document still said GET made the suite fail with both messages
(`these routes are served but not described with that verb`, `the document describes
operations that do not exist`). Before the fix that same mutation passed.

---

## Slice C — Truth alignment

### C1. Reports edit window: current day + previous day

**The complication, found before writing code.** `history-permissions.ts` is shared between the
reports and loading domains, and loading has its own one-hour window with its own approved
requirement. `projectHistoryPermissions` is called from seven production sites -- four reports,
three loading -- so replacing `isWithinOneHour` in place would silently re-write the loading
rule, which this slice is explicitly forbidden to touch.

The shared helper therefore gets an explicit, **required** window parameter instead of a default.
A default would let a new call site inherit whichever rule happened to be the default, which is
the same class of failure as the cast in F9: the code would agree with itself and nothing else
would be able to tell the difference. Required means every call site states its domain, and the
compiler refuses a new one that does not.

**Design decided:**

- `EditWindow` is a named union, exported: `'one-hour'` (loading) and
  `'sao-paulo-current-and-previous-day'` (reports).
- `isWithinOneHour` keeps its name and behavior, because it still describes what loading does.
- A new `isWithinReportsEditWindow` compares the São Paulo **date** of `created_at` against today
  and yesterday, using the existing `getSaoPauloDateString`.
- The future-timestamp guard stays in both. It has to: under a date-based rule a future timestamp
  lands on today's date and would otherwise pass as editable, so the existing fail-closed test
  would silently start passing for the wrong reason.
- Stored `created_at` is UTC (`datetime('now')`), so the comparison is between São Paulo date
  strings, never between raw timestamps.

**Also in scope, because the day window makes them wrong:** the three 403 messages that say
"durante a primeira hora" (`reports.command.service.ts:135`, `reports.routes.ts:370` and `:425`),
and the requirement text in `report-generation`, `advanced-editing` and `report-photo-management`.
`report-generation/spec.md:77` currently records the *opposite* migration -- its
`(Previously: ...)` line says the system used to use the day window. Reverting to the day window
means that line must now record the one-hour rule as the superseded behavior.

**Not in scope:** anything under `loading`, and `openspec/specs/loading-schedule/spec.md`.

**This is a revert, and it is recorded as one.** PRD §16 states the day window outright -- "Puede
editarse: Día actual, Día anterior" -- so D6 restores the document's rule rather than inventing a
new one. The part worth writing down is that the one-hour window was not an accident either: the
archived change `2026-06-18-histories-under-loading-schedule` introduced it deliberately, with the
rationale "Matches new business rule and removes client-side date guessing", and explicitly
flagged it as "a breaking business-rule change [that] will invalidate current tests/specs".

That business rule was never written down anywhere, which is why the two artifacts still disagree
and why the revert rests on the PRD and on the decision recorded above rather than on a
superseded-spec entry. The archived change is left untouched: an archive records what was decided
then, and rewriting it would destroy the evidence this note depends on.

The client-side concern does not argue for either window. The server returns `canEdit` and
`readOnly` itself and both the history and detail views render those flags, so no client computes
a boundary under either rule.

### C2. Document the fletero quota as deliberate indicative behavior — DONE

The implementation is informative only, and backend tests explicitly assert a
4th fletero is accepted. `openspec/specs/loading-schedule/spec.md` currently
requires the opposite (`MUST restrict ... maximum 3`, `MUST reject` the 4th).

Amend that requirement to record the deliberate decision and its business
reason (company logistics), using the spec's existing `(Previously: ...)`
convention. Update the PRD §20 statement so it stops promising an error the
system intentionally does not raise. The Android client shows a counter only.

The web UI already behaves this way, so nothing in the SPA changes. Two names must
be corrected, because they are the reason this was confusing in the first place:

- The E2E test `packages/frontend/src/__e2e__/loading-schedule.spec.ts`, which was named `4.2
  blocks 4th fletero with quota limit error` and asserted the OPPOSITE of its name: it expected the
  4th fletero to return `201`, expected the add buttons to stay visible, and expected the indicator
  to read `4/3` plus a warning. Renamed to describe the informative behavior.
- The loading-schedule spec requirement itself, as above.

**Two more places, found by mapping before writing.** Both overstate a rule that does not exist:

- `openspec/specs/advanced-editing/spec.md:22-37`, "Loading Schedule Editing and Quota
  Revalidation", is a **second** max-3 requirement -- this one for the edit path, requiring the
  update to be rejected and the transaction rolled back. The backend rejects neither; the only
  things it enforces in that transaction are driver- and vehicle-uniqueness per date. This
  requirement has to move with the other one or the spec keeps contradicting itself in two
  files.
- `packages/frontend/src/__e2e__/admin-edit-export.spec.ts:153-177` carries comments asserting a
  `409` rejection for a 4th fletero, citing test names and line numbers (`loading.routes.test.ts
  line 307`, `line 568`) that do not exist. The assertions are gone; only the claims remain. False
  comments are worse than none, so they go.

**Confirmed before documenting it:** no backend production code enforces the limit. The "3"
lives only in the SPA as a display and a warning, and `openapi.ts:324-327` already documents the
informative behavior. Six backend tests assert the 4th fletero is accepted; none asserts a
rejection. Documenting "no enforcement" is therefore accurate rather than aspirational.

**Delivered.** Seven artifacts, six of which claimed something the system does not do:

- `openspec/specs/loading-schedule/spec.md` -- the Fletero Quota Validation requirement now states
the limit is indicative, with `(Previously: ...)` recording what it used to require, its rejection
scenario replaced by an acceptance one, and its repository Purpose line corrected from "quota
enforcement" to "indicative quota display".
- `openspec/specs/advanced-editing/spec.md` -- the second requirement, which demanded rejection and
a rollback on the edit path, now states the opposite and keeps the rollback language scoped to
"on that ground", so the requirement stops implying the transaction enforces nothing at all.
- `PRD_Trindade.md` -- section 20 no longer promises an error message the system never raises, and
the MVP checklist item reads "Regla indicativa de 3 fleteros (no bloquea)".
- `packages/frontend/src/__e2e__/loading-schedule.spec.ts` -- test 4.2 renamed to what it asserts.
- `packages/frontend/src/__e2e__/admin-edit-export.spec.ts` -- test 4.3 was named "quota
enforcement ... rejected at API level" while asserting only that the indicators render, and its
comments cited two backend tests by name and line number (`loading.routes.test.ts` lines 307 and
568) that do not exist. Renamed, and the false citations replaced with the tests that do assert
this behavior.
- `packages/backend/src/modules/loading/loading.routes.schedules.test.ts` -- a section comment
reading "Quota Enforcement: Max 3 fleteros" sat above tests asserting acceptance.

Evidence: 326/326 backend tests, typecheck clean, contract artifact unchanged, full gate green in
a fresh clone including 53/53 Playwright E2E -- the renamed E2E tests still pass under their new
names, which is the check that a rename did not quietly change what runs.

**Left as an observation, not fixed here.** The edit path's real constraints -- driver and vehicle
uniqueness per date -- are enforced in the transaction and asserted by tests, but no requirement
states them. That is the mirror image of this slice: not a spec overstating the code, but a code
behavior no spec describes. It belongs with whoever next touches scheduling, and it was left out
rather than expanded into this slice's scope.

**Two advisories from the review, both about text and not behaviour.** `review-195244109ee1f0f8`
approved C2 on the first pass and left two SUGGESTIONs. Both hit claims made *by this slice* -- the
failure mode C2 exists to remove, reproduced twice in miniature -- so they are worth recording
rather than quietly absorbing.

- `R3-admin-edit-nondiscriminating` (`admin-edit-export.spec.ts`): the comment added here claimed
  the add-button assertion is where a future blocking rule would start failing. It is not. That
  test never fills a slot, so the button would be visible under a blocking rule too. Fixed by
  replacing the claim with what the test actually covers, and pointing at test 4.2, which does
  discriminate because it fills a slot past the limit first.
- `R3-advedit-indicator-unproven` (`advanced-editing/spec.md`): the new requirement asserts the
  edit interface MUST show the count and an indication that the quota was exceeded. Checked against
  the code instead of accepted on faith: `LoadingEditPage.tsx:228-234` renders `(N/3)` plus the
  quota warning once the count exceeds 3, so the requirement is **true**. What is missing is a test
  -- no E2E asserts that label. The requirement is kept, because softening a true requirement to
  match its test coverage is the wrong direction; the gap is recorded here instead.

---

## Findings recorded during A1

These were discovered while implementing and verifying A1. Each needs a decision;
none is silently fixed.

### F1. A revision-aware table expectation is mandatory (FIXED in A1)

`classifySchema` computed `missingTables` against the full current `SCHEMA_TABLES`
and evaluated the `incompatible` verdict **before** the revision comparison. Once
`SCHEMA_TABLES` included `auth_sessions`, every genuine revision 1 database — which
legitimately lacks that table — was classified `incompatible`, and `migrate.ts`
refuses that verdict without ever opening the file for writing. The 1 to 2 step was
unreachable, which defeated the entire point of the slice, and a real production
database would have been stuck.

Fixed by introducing `BASELINE_TABLES`, `REVISION_TWO_TABLES` and
`requiredTablesFor(version)`: missing tables are judged against what the database's
own revision requires, while unexpected tables are still judged against the full
current inventory. Two tests pin the behavior, including the regression (
`requires of a database only the tables its own revision defines`).

### F2. `make ci-clone` was broken on a host with no Playwright cache (FIXED)

**Root cause, isolated:** the browser pre-warm ran as root (`npx -p @playwright/test@1.60.0
playwright install ...`), and it only ran at all when the host had no `~/.cache/ms-playwright`.
Because `npm_config_cache` points at `/tmp/trindade-npm-cache` inside the container, root's npx
wrote root-owned entries into the shared npm cache; the unprivileged `npm ci` inside the gate
then could not write to that cache, failed with `EACCES`, and its rollback produced the
cascade of `ENOTEMPTY` cleanup errors that made the failure look like a node_modules problem.
The `ENOTEMPTY` noise was a symptom, not the cause.

**Fix:** the pre-warm now runs through the same unprivileged path as the gate, via an
`as_host()` helper wrapping `setpriv`, so nothing is ever written by root. The comment above
the `ci-clone` target records why, so it is not reintroduced.

**Verified against the exact reproduction condition:** this host has no `~/.cache/ms-playwright`,
which is what triggered the defect. `make ci-clone` now completes with `CI gate passed`
(280 backend tests, schema CLI check, contract freshness check, 53/53 Playwright E2E). Before
the fix the same command died at `npm ci`.

**Follow-up, not done:** the container home is ephemeral, so the downloaded browsers are
discarded after every run and each `make ci-clone` re-downloads them. Mounting the host browser
cache read-write when it is absent would make the first run populate it and later runs reuse it;
today the mount only helps when the host cache was populated by some other means.

### F3. The legacy temperature rebuild drops an index and never restores it (OPEN)

`db/report-temperatures-migration.ts` does `DROP TABLE report_temperatures` and
recreates it, which discards `idx_report_temperatures_report_id`; the migration never
recreates it. A migrated database therefore ends up without an index a fresh install
has. Pre-existing, unrelated to revision 2, and invisible to the verdict because
indexes are not part of the table inventory.

### F4. The fresh-install schema is created outside the seed and stamp transaction (OPEN)

Recorded in A1 as a pre-existing risk and still unfixed: `db/index.ts:20` runs
`db.exec(schema)` before the transaction that seeds and stamps (index.ts:24-27). A
crash in between leaves a schema-only, unstamped file that `classifyInstallation`
then classifies as `existing` forever — never seeded, never stamped, and now also
without the revision 2 table. This window did not widen, because `auth_sessions` is
part of the same `schema.sql` batch, but the underlying defect remains.

### F5. Documentation drift left deliberately for A3 (FIXED)

Closed by A3 plus a manual edit of `.env.example`: the README's revision references, the auth
model description, the migration story and the test counts are corrected, the auth spec gained
the session requirement, and `REFRESH_TOKEN_TTL_DAYS` is now in the template with an accurate
`DATABASE_PATH` comment.

What remains of it is F12's sibling: the *deployment* runbook still describes an ordering that
this release breaks.

`README.md` still states that a fresh installation stamps revision **1**, that
`user_version` is currently `1`, that `db-status` reports `1`, and that a database
created before versioning reports revision `0` until changed. All of it needs the
revision 2 story, including `auth_sessions`. It also still describes the auth model as a
bare 15-minute JWT with no refresh and no revocation, which A2 changed. `README.md` and
`.env.example` were outside A1's and A2's edit surfaces on purpose so those slices stayed
reviewable.

### F6. `REFRESH_TOKEN_TTL_DAYS` in `.env.example` (FIXED by hand)

The harness blocks write access to `.env.example` at the **tool** level and does not accept a
user grant, so the file had to be edited manually. Done and verified: the variable is present
with its comment, the `DATABASE_PATH` comment no longer claims an established target is opened
read-only, and the twelve declared variables are intact. Code default, template and README all
agree on 30.

Two cosmetic leftovers, neither functional: two blank lines remain at the end of the file, and
the template omits two variables the code reads — `LOG_LEVEL` (`server.ts:30`, not documented
anywhere) and `APP_URL` (`server.ts:25`, an undocumented fallback for `PUBLIC_APP_URL`).

### F7. JSON files cannot be edited by anchoring on what the file reader shows (WORKFLOW)

The file reader normalizes JSON, so a `.json` file that is pretty-printed across 33 lines is
reported as one compact line, and `edit` anchors built from that reading fail to match. The
same normalization appears in parts of the shell output, so two views of one JSON file can
contradict each other.

Confirmed empirically on `packages/backend/package.json`: `wc -l` reports 33 lines and
`grep -c '"db:migrate": "'` matches while `grep -c '"db:migrate":"'` does not, i.e. the
file uses a space after the colon that the reader does not show.

The reliable path is to rewrite such files with Python, which also makes formatting
verifiable: `json.dumps(data, indent=2, ensure_ascii=False) + "\n"` round-trips
`packages/backend/package.json` byte-for-byte, so a programmatic edit produces a minimal
diff. Use `cat -A` or `od -c` when the exact bytes matter.

### F8. The password-change requirement and the backend disagree on the minimum length (OPEN)

`openspec/specs/auth/spec.md` states the backend enforces **at least 4 characters** and that
the client shows 8+ recommendations "without blocking submission".
`packages/backend/src/modules/auth/auth.schema.ts:21` enforces `min(8)` and rejects anything
shorter with 400.

This is a product decision, not an obvious typo: the spec describes a deliberate do-not-block
posture, and the code blocks. Either the spec is stale or the backend is stricter than
intended, and a worker choosing a 6-character password gets a 400 the spec says should not
happen. Left unfixed on purpose.

Related, and not a defect: the backend test named `returns 400 for new password shorter than
4 characters` sends a 3-character password, so it passes under either rule while its name
describes the wrong threshold.

### F9. One type, three runtime shapes, hidden by a cast (FIXED in B2.2)

`loading.service.ts` returned the raw database row from `create` and `update` while
`listByDate` returned `projectScheduleRow(...)`. The declared `ScheduleRow` interface marked
nearly every field optional, which is what allowed one type to describe three different
objects: the mutation responses carried neither `creator` nor the permission flags, and the
`as ScheduleRow` casts made TypeScript agree while nothing else could tell the difference.

This is the clearest argument for response *schemas* over response *types*: the type was not
wrong, it was unfalsifiable. Fixed additively — both mutations now project through the same
function — and the contract test asserts a created schedule is field-for-field identical to a
listed one, because a `.strict()` schema alone would accept either shape.

### F10. The generator does not convert `:param` to `{param}` (FIXED in B2.2)

`@asteasolutions/zod-to-openapi` writes the path exactly as it is registered. OpenAPI 3.1
requires templates in `{param}` form, so a registry written with Fastify's `:id` produces a
document whose path templates are invalid and whose parameters no client generator would
recognise. Found by inspecting the generated artifact instead of trusting that generation
having succeeded meant it was correct.

Fixed by registering `{id}` and `{date}` in the registry, with tests asserting that no
documented path contains `:` and that every template parameter is declared. B2.4's coverage
test has to normalise in the other direction when it compares against the `:param` routes
Fastify actually registered.

### F11. The backend test run does not typecheck test files (WORKFLOW, load-bearing)

`npm run test --workspace=packages/backend` runs through `tsx`, which strips types without
checking them, and `tsconfig.build.json` excludes `*.test.ts`. A test file with a broken
signature or an invalid cast therefore passes the suite and fails only the gate's
`npm run typecheck`.

B2.2 proved it twice in one slice: changing `create`'s third parameter from `number` to
`HistoryActor` broke twelve existing call sites, and a new test had an invalid
`as { data: unknown }` cast. The 291-test suite was green while the gate's typecheck was red.

Related and easy to miss: when a test file fails to *load*, `node:test` reports the file as a
failure but its tests do not exist at all, so the total count drops silently — `280` instead of
`291` — while the file's own failure is one line of noise. Watch the total, not only the
failures.

### F12. Deploying the revision 2 build before migrating breaks login (RESOLVED)

`docs/deployment.md` ordered the rollout (§4 Deployment Execution) **before** schema adoption
(§5 Schema Adoption / Migration, "Deliberate Operator Write"). That order was safe while the only
migration was the legacy `report_temperatures` rebuild, because the application worked against
either shape. Revision 2 changes that: `auth_sessions` is a table the code **requires at request
time**, because `issueSession` inserts into it on every successful login. Between §4 and §5 login
was therefore not degraded, it was **down**, and the error an operator saw named an internal
table rather than the action required.

Three answers were on the table: migrate before switching traffic; keep the order and document a
bounded login outage; or make the code answer clearly when the schema verdict is `outdated`.

**Decision: migrate before the rollout, with the third option split out as task A4.**
`docs/deployment.md` was rewritten accordingly: §4 builds the images without switching traffic,
§5 migrates from the new image and verifies `current` while the old container keeps serving, §6
switches traffic, and §7 verifies. Sections were renumbered through §10 and the rollback
scenarios were rewritten to match.

**Evidence that made this the right choice**, both measured directly:

| Check | Result |
| --- | --- |
| New code against a revision 1 database (rollout first) | `500 no such table: auth_sessions` — login down |
| Pre-revision-2 code against a revision 2 database (migrate first) | login **200**, health **200**, boot notice `newer` / warn |
| `better-sqlite3` default `busy_timeout` | 5000 ms, so the migration's brief write lock retries rather than failing |

So migrating first has **no login outage**, and a rollout that fails after migrating needs only
an image revert — no data restore. §1 now records the limit that makes that true: it holds for
**additive** migrations, where the old code never reads the new object. A destructive migration
would need a bounded window instead, and the criterion is stated per release.

**Split out rather than bundled**: making the code answer clearly when the database is
unmigrated is task A4, because it is a behaviour change and does not affect the ordering
requirement.

**Resolved separately**: the topology question this paragraph raised was settled afterwards. See
F14.

### F13. The documented session knob was inert in Docker (FIXED)

`REFRESH_TOKEN_TTL_DAYS` was documented in `.env.example` and in the README, and read by
`runtime-config.ts`, but `docker-compose.yml` never passed it to the container. Compose uses
`.env` only to interpolate `${...}` placeholders; it does not inject the file. So an operator
setting `REFRESH_TOKEN_TTL_DAYS=90` would have got 30 days in silence — the knob was inert
exactly where it would be used. `LOG_LEVEL` had the same problem and was not documented anywhere.

Fixed by adding both to the api service's `environment:` block with defaults, verified by
rendering the Compose configuration: they appear in the container environment, take the `.env`
value when set, and fall back to 30 and `info` when not.

**This one was introduced by the same work that documented it**, which is the point worth
keeping: a variable is not deployed until Compose passes it, and documenting a knob is not the
same as wiring it.

### F14. The committed Compose file could not start the topology production uses (RESOLVED)

The uncommitted `docker-compose.yml` change does two independent things: it attaches the web
service to the external network `portafolio_default`, and it makes the data volume external with
a fixed name. Both were undeclared decisions, and the commit at HEAD pointed the other way —
`be4abf7 feat(deploy): decouple standalone VPS topology`.

Evidence, all measured on the host:

| Fact | Evidence |
| --- | --- |
| The proxy reaches Trindade **by container name** | `proxy_pass http://trindade-web-1:80;` in the Portafolio project's site config |
| The network and the volume already exist | `portafolio_default` (37fe20895673) and volume `trindade_sqlite_data` |
| The volume holds real production data | `trindade.db` at 335,872 bytes — the same figure the README calls the production database — plus a 4 MB WAL and 25 photos |

**Decision: the shared proxy is the supported topology, and the volume stays external with a
fixed name.**

The consequence that settled it: container-name resolution only works across a shared Docker
network, so the committed Compose file — which had no network attachment — **could not start the
installation that is actually running**. Committing the change is a correctness fix rather than a
preference, and reverting it would break production by leaving the proxy unable to resolve
`trindade-web-1`.

The volume half is an independent data-lifecycle decision: `external: true` with an explicit
`name:` makes Compose refuse to start when the volume is missing instead of silently creating an
empty database, and it puts production data beyond `docker compose down -v`. Note that reverting
that half would not have renamed the volume — Compose derives `trindade_sqlite_data` from the
directory name — so the risk it addresses is the destructive command and project-name drift, not
an immediate rename.

Documentation updated to match: `docs/deployment.md` §1 now states the topology and the volume
rationale, §10 is relabelled as the standalone **alternative** (a migration that also requires
editing the Compose file, not a local variation), and the README stopped calling the live
topology "Legacy".

**Still needs the human**: the Compose change is approved but uncommitted. Per the earlier
commit-boundary decision it belongs in its own commit, separate from the two environment
variables that landed in the same file.

---

## Slice D — Android client

**Gate status.** A1, A2 and B2 are all done, so the backend prerequisites this slice was named as
waiting on are met. Two things still are not, and they gate different parts of D:

- **The revision 2 deployment has not happened.** Nothing is verifying the Android client against a
  real backend until it does. D1 does not need it; D2 onwards cannot be closed out without it.
- **On-device testing is impossible on this machine**: no `adb` on the host and no device or
  emulator. Everything up to a release APK can be built and checked in a container, but "works on a
  phone" remains a human step on the user's side.

The remote exists (`origin` → `github.com/wilkinbarban/Trindade`, local `main` level with
`origin/main`).

### Build toolchain — decided and verified

This host has no Java, no Gradle and no Android SDK: `java`, `javac`, `gradle`, `kotlinc`, `adb`,
`sdkmanager` and `aapt2` are all absent and `ANDROID_HOME` is unset. Decided with the user: build in
a container rather than installing 3-5 GB on the host.

Verified against `ghcr.io/cirruslabs/android-sdk:35` (2.72 GB, amd64 and arm64):

| Needs | Found |
|---|---|
| JDK | OpenJDK **21.0.6** |
| `ANDROID_HOME` | `/opt/android-sdk-linux` |
| Platform | `android-35` |
| Build tools | `35.0.0` |
| `sdkmanager`, platform-tools (incl. `adb`) | present |
| SDK licences | **already accepted** (all six), so no interactive step |
| Network to `dl.google.com` and `services.gradle.org` | 200, so the wrapper and AGP can resolve |

Gradle is deliberately not in the image: the project uses the wrapper, which is what keeps the
build reproducible outside the container too. Two named volumes cache the cost across runs:
`trindade-gradle` for the Gradle user home and `trindade-android-sdk` for anything the build
installs later.

The loop is the same shape as the backend sandbox, so it needs no new habit:

```
docker run --rm -v <repo>:/work -v trindade-gradle:/root/.gradle \
  -w /work/packages/android ghcr.io/cirruslabs/android-sdk:35 ./gradlew assembleDebug
```

### D1. Project skeleton — DONE (skeleton; the Android CI lane is still outstanding)

Delivered 18 files under `packages/android`, all source and configuration. `assembleDebug` and
`testDebugUnitTest` both green in the container, APK 12,652,707 bytes, 1 unit test passing, and the
canonical gate still green -- which is the empirical answer to whether adding a `packages/android`
directory disturbs the npm workspaces. It does not: `npm ci` and `npm run build` both succeed with
it present, because npm skips a workspace directory that has no `package.json`. That was checked
rather than assumed, since `packages/*` is a glob the root `package.json` owns.

Deliberately not in D1: any screen beyond a placeholder, any dependency on the live backend, and any
signing. **The Android CI lane is also not done** and remains outstanding: the canonical gate image
has no JDK, so it needs its own lane, and that lane has to provision the SDK (see below).

**Three deviations from the plan, each with its reason.**

1. **Gradle 9.1.0 -> 9.6.0.** Not a preference: AGP 9.4.0 fails hard with "Minimum supported Gradle
   version is 9.6.0". Changed in `gradle-wrapper.properties` only; the wrapper jar is untouched.
2. **compileSdk 35 -> 37, keeping targetSdk 35 and minSdk 26.** The pinned Compose BOM 2026.09.00
   and its transitive `androidx` versions require API 36/37 -- 17 hard errors otherwise. `compileSdk`
   is build-time only, so the runtime floor and the target are unchanged. This has an infra
   consequence: the SDK image ships only `platforms/android-35`.
3. **KSP is 2.3.12, not `2.4.20-<something>`.** The instruction I wrote assumed KSP still versions
   itself as `<kotlin>-<ksp>`. It does not: KSP decoupled from the Kotlin compiler version at 2.3.0,
   and the registry has 160 versions with **none** carrying a `2.4.20-` prefix. Verified against
   `maven-metadata.xml` rather than trusted, and the build proves it works, since Hilt's generated
   components only compile if KSP actually ran. This is the clearest argument for the instruction
   that caught it: resolve versions from the registry, never from memory, including the parent's.

**The build must not run as root — this cost a full gate failure.** The container was first run as
root, so it wrote `build/` into the mounted repository owned by root. Those directories are
`gitignore`d, which is why `git status` showed nothing, but the gate's clean-checkout step copies
the working tree and died with `cp: cannot open 'packages/android/build/reports/problems/
problems-report.html': Permission denied`. It is the same class of defect as F2, where
`make ci-clone` ran the npm pre-warm as root and poisoned the shared cache.

The working recipe therefore runs as the invoking user, with the Gradle user home on a path that is
already theirs rather than a root-owned volume:

```
docker run --rm --user $(id -u):$(id -g) \
  -v <repo>:/work -v ~/.cache/trindade-gradle:/g -e GRADLE_USER_HOME=/g -e HOME=/g \
  -v trindade-android-sdk:/opt/android-sdk-linux \
  -w /work/packages/android ghcr.io/cirruslabs/android-sdk:35 ./gradlew assembleDebug
```

The named SDK volume is populated once with `sdkmanager --install "platforms;android-37.0"` and
persists across runs, so no build needs to install anything again. Recorded because it is not
evident: mounting a volume over `/opt/android-sdk-linux` initialises it from the image, which is what
makes the persistence work without a derived Dockerfile.

**A defect found while fixing the above.** The generated `.gitignore` covered `build/` and `.gradle/`
but not `.kotlin/`, which the Kotlin Gradle plugin writes into the project directory -- so D1 would
have committed the compiler's project-local cache. Added.

Two decisions made while writing it, both stated before the first file rather than after:

- **The base URL is configuration.** A Gradle property read into a `BuildConfig` field with a
documented local default, so the same source can point at a deployment that does not exist yet.
- **Where the contract types come from** was NOT resolved and is deferred: the skeleton carries no
  API types, so nothing forced the choice between build-time codegen and a checked-in generated
  module. It belongs with D2, the first slice that actually needs a type from
  `packages/contracts/openapi.json`.

**Review outcomes, and what they cost.** D1 needed two reviews, both approved on the first pass with
no correction, and both came back at **high tier with all four lenses** for reasons worth recording:

- `review-bede9994afd438d7` (the skeleton) was escalated by an **executable permission change**:
  `packages/android/gradlew` going from `000000` to `100755`. Committing any new executable script
  promotes a candidate to the four-lens tier, which is a lot of review for a skeleton but not an
  unreasonable signal. Nine advisories, all informational.
- `review-eebff4f384b7d82b` (the fixes) was escalated by **`hot_path`/`security`** on
  `network_security_config.xml`. Touching a network security config is security-relevant by
  construction, so the escalation is the system working. Seven advisories.

The first review's convergence was the valuable part: **four lenses independently landed on one
line**, `app/build.gradle.kts:26`, the local default base URL. That was not four complaints but one
defect with four faces -- risk saw cleartext, reliability an unvalidated value, resilience a default
with no failure path -- and it was right twice over, because the cleartext default was also
non-functional under `targetSdk 35`.

**Outstanding advisories, none blocking, none fixed.** Recorded deliberately rather than collapsed
into the fixes, because two of them describe the fragility of the fix I chose:

- **`R3-release-guard-uncovered` (WARNING) and `R3-release-guard-task-scope` (SUGGESTION)**, both on
  `app/build.gradle.kts:69`. The release guard matches the task names `assembleRelease` and
  `bundleRelease` exactly, so any other release-producing task -- `publishReleaseBundle`,
  `installRelease`, a future flavoured variant -- bypasses it silently. This is the price of moving
  the check to execution time, and it is the right complaint: name matching is a weaker guarantee
  than the configuration-time `require` I had to abandon. A robust version would hook the release
  variants through `androidComponents` instead of matching task names.
- **`R3-build-validation-incomplete` (WARNING)** on `app/build.gradle.kts:32`: the shape check
  verifies a scheme prefix and a trailing slash, not that the URL is otherwise well formed, so
  values like `http://` or one containing a space still pass.
- **`R4-001` (WARNING)** on `gradle-wrapper.properties:4-5`: the raised timeout and retries still do
  not cover every distribution-download failure mode.
- **`R2-001` (WARNING)** on `app/build.gradle.kts:69`, **`R2-002` (SUGGESTION)** on
  `ApiConfigurationTest.kt:44`, **`R2-003` (SUGGESTION)** on `AndroidManifest.xml:8`: readability
  notes on the same guard and on the comments this slice added.

**Also still outstanding from D1**: the Android CI lane (the canonical gate image has no JDK), and
the deferred decision about where the client's API types come from.

### D2 prerequisite: where the client's types come from — DECIDED

Not implemented yet. This section exists so the next person executes the decision instead of
re-opening it.

**Decision**: generate the Kotlin and **version it**, with the Android lane regenerating and failing
on any difference. That is deliberately the same shape the repository already uses for
`packages/contracts/openapi.json`, whose README gives the reason in the client author's own terms:
the artifact is committed so a client author can work without building the backend. The reasoning
transfers unchanged, and the freshness check is an idiom this repo already trusts rather than a new
mechanism to invent. The alternative — codegen during every Gradle build — was rejected because it
hides the generated code from review, which is the lesson `R3-double-plus-nan` taught about the JSON
artifact.

**Scope**: models only. The Retrofit client is written by hand, narrow, exposing only the
field-operations surfaces. The mechanical and drift-prone part is generated; the part a human reads
better stays hand-written. Numbers measured against the real contract, not estimated:

| scope | `.kt` files | contents |
|---|---|---|
| full client | 188 | all 41 operations, plus generated tests and scaffolding |
| models only | **97** (484K) | the DTOs plus ~14 serialization infrastructure files |

**The generator, and the reason it is not a Gradle plugin**: `openapi-generator` is a Java tool.
`@openapitools/openapi-generator-cli` exists on npm (Apache-2.0, 2.41.0) but it is a wrapper around
the same JAR, so it needs a JVM regardless — and no single image in this project has both Node and
Java. The working invocation therefore uses the JAR directly with the JDK the Android SDK image
already carries (7.25.0, a 31 MB download), which also means the freshness check can run in the
Android lane where Java is guaranteed:

```
java -jar openapi-generator-cli-7.25.0.jar generate \
  -i packages/contracts/openapi.json -g kotlin -o <nested output dir> \
  --library jvm-retrofit2 --additional-properties=serializationLibrary=kotlinx_serialization \
  --global-property models,modelDocs=false,modelTests=false,supportingFiles=
```

**A trap to respect in the layout**: the generator writes `gradle/wrapper/gradle-wrapper.jar`,
`gradle-wrapper.properties` and `proguard-rules.pro` into its output directory. Pointed at
`packages/android` it would overwrite this module's own Gradle wrapper. The output must go to a
nested directory, and the freshness check must catch any change outside it as well.

**Still to choose while implementing**: the package name (currently `org.openapitools.client`, which
should become something under `com.trindade.app`), and whether the generation step lives as a script
run by hand plus a check in `scripts/ci-android.sh`, which is the shape the decision implies.

The verified output uses `kotlinx.serialization` correctly — `@Serializable`, `@SerialName`,
`@Contextual` — so it composes with the serialization dependency the D1 skeleton already carries.

### D3. Relatórios — sliced into four

The four element types are `check`, `check_assai`, `check_normal` and `temperature`, read from the
SPA's renderer rather than guessed: `CategorySection.tsx` switches on exactly those four. The
contract types `task_type` as a bare string with no enum, so the code is where the set lives.
`selectedProducts` belongs to the assaí variant, and `temperature_readings` on a task is how many
readings that task expects.

Sliced the same way D2 was, and for the same reason: each slice is verifiable on its own, and the
whole feature at once would be neither reviewable nor safely editable.

- **D3a — the data layer.** A hand-written `ReportsApi` and a `ReportsRepository`: the categories with
their tasks, the shift the server detects, reading one report, creating and replacing one, and the
export text. Narrow on purpose — the history belongs to D5 and is not in this interface.
- **D3b — the generator screen.** Categories and tasks rendered as the four element types, with the
  form's state local until it is submitted.
- **D3c — photos.** Capture, compression **before** upload, and the attach/remove calls.
- **D3d — the export.** The WhatsApp text comes from the server and is never assembled on the client;
  this slice is the screen that fetches it and the copy action.

**Verified surface, from the contract**: `GET /api/reports/categories` → `CategoriesResponse`;
`GET /api/reports/turno` → `TurnoResponse`; `POST /api/reports` takes `CreateReportRequest` and answers
`ReportResponse`; `GET|PATCH /api/reports/{id}` → `ReportResponse` with `UpdateReportRequest`;
`GET /api/reports/{id}/export` → `TextResponse`; the three photo operations answer `PhotosResponse`,
`PhotoResponse` and `SuccessResponse`.

**A note on verifying an interface**: Retrofit paths and verbs are checked at runtime, not at compile
time, so a hand-written interface that is never called compiles and proves nothing about the contract
it claims to speak. The repository and a MockWebServer test that asserts the paths are what turn this
into evidence — the same trap that made a mis-wired source directory look green.

- **D1** Project skeleton: Gradle Kotlin DSL, version catalog, Compose + Material 3,
  Hilt, Retrofit + OkHttp + kotlinx.serialization, module in the monorepo with a
  CI lane that has a JDK (the canonical gate image has none).
- **D2** Auth: login, refresh-on-401 interceptor with single-flight, encrypted
  token storage in the Keystore, configurable base URL.
- **D3** Relatórios: category/task driven generator, the four element types,
  photo capture with mandatory pre-upload compression, server-produced export text.
- **D4** Horários: date, time slots, drivers/vehicles, create/edit/delete,
  informative N/3 counter, server-produced export text.
- **D5** Historial, perfil and change-password.
- **D6** Signing, release APK and the update path.

Shared rules for every Android slice: WhatsApp export text is only ever fetched
from the server (`/api/reports/:id/export`, `/api/loading/export?date=`), never
reformatted on the client. Send `Accept-Language`. Never hardcode the base URL.

### D4. Horários — sliced into four

Sliced the way D2 and D3 were: each slice verifiable on its own, and the whole feature at once
neither reviewable nor safely editable.

- **D4a. Data layer — DONE.** Commit `e227518`. A hand-written `LoadingApi` with twelve endpoints and a
  `LoadingRepository`. The payoff to name: `ContractCoverageTest` grew from 3 tests to 4 and now
  reflects over `LoadingApi` too, so its twelve paths are **verified against `openapi.json` rather
  than asserted** — the first slice where a new interface got that verification with no extra work.
  Two facts stated in the interface where a caller meets them: the fletero limit is **informative**
  (nothing returns an error for exceeding it, decision D5), and a schedule belongs to a **date**, not
  a week.
- **D4b. The grid — DONE.** Commits `3681cf8` (logic + counter) and `af25a7f` (screen). The date is
  **today in São Paulo, not on the device**; the slots drawn are the configured ones **union** the
  ones an entry already occupies, because a slot removed from settings would otherwise hide entries
  that still exist on the server. The counter is **strictly-less-than-60-minutes**, ported from the
  SPA rather than inferred: a `<=` would silently show a four where the product shows a three.
- **D4c. Mutations — DONE.** Commit `86be1a1`. Create, delete and quick-add. The driver type comes
  **from the chosen driver** rather than from a control, so the request cannot contradict the driver
  it names; the vehicle is required only for a `casa` driver, and switching to an external one clears
  it.
- **D4d. The export — THIS SLICE.** The server-rendered WhatsApp text for the day on screen, fetched
  on demand and copied verbatim.

**Coverage carried with the slices.** `LoadingViewModel` arrived after the ViewModel test gap was
closed, so it was covered one slice later (`8dbdebc`); the standard is 65 JVM tests before this slice,
and D4d adds its own rather than inheriting a green lane as evidence for behaviour no test exercises.

**Not in D4, and named rather than omitted**: `LoadingViewModel.onDateChange` has no control on the
screen, because the SPA's loading page also fixes the date to today in São Paulo (`todayDate()` in
`LoadingSchedulePage.tsx`). A date picker is a product change, not a gap in the client.

### D5. Historial, perfil y cambio de contraseña — sliced into four

The surfaces, read from the backend, the contract and the SPA rather than inferred:
`GET /api/reports/history` and `GET /api/loading/schedules/history` (same `HistoryQuerySchema` shape:
optional `date`, `month`, `userId`; `page` default 1; `pageSize` default 30, max 100), plus
`GET|PATCH /api/auth/profile` (`display_name` is the only writable field, and the body is `.strict()`),
`POST /api/auth/change-password` and `POST /api/auth/logout`.

- **D5a. The history data layer — DONE** (commits `8d3b03f` and `c7a361f`). `ReportsApi.history` and `LoadingApi.history` — both deliberately absent
  since D3 and D4, whose comments say so — with the repository methods, the paging and filter arguments,
  and `ContractCoverageTest` extended to reflect over both new calls. The same slice carries the
  **refusal-text fix**: most backend refusals answer `{error}` with **no `message`**, and
  `AuthRepository.errorMessage()` decodes only `message`, so every one of them collapses to the generic
  sentence. Decode `message ?: error` and test it, because that is the difference between "the server said
  why" and "it failed".
- **D5b. The reports history screen — DONE** (commit `38e0139`, the logic half, and the commit that carries
  this line, the screen half). Columns: date (`dd/mm/yyyy`), the turno, an inactive badge, the
  author's `display_name`, and `notes` truncated at 60 characters. Actions: open the report's **detail
  screen, which already exists** from D3 — so editability is decided there, from the detail payload, and not
  recomputed from the list item.
- **D5c. The loading history screen — DONE** (commits `1f7bc4c` and `b053380`). One row per **batch date** (the endpoint groups by `schedule_date`),
  showing the batch date, the computed `loading_date`, `total_loadings`, the creator and the inactive badge.
  The row opens the **existing grid for that date**, which is what finally gives `LoadingViewModel`'s
  `onDateChange` its first caller — a control arrives through history rather than through a picker, which is
  the same conclusion D4 recorded from the other side.
- **D5d. Profile and change-password — DONE** (commits `072d2a0` and `6d08ecc`). `display_name` read and
  edited, `username` and `role` shown read-only, the password change, and a sign-out action.
  **A claim this line used to make was wrong and is corrected here**: it said the logout was "the one place
  the Android client is better placed than the SPA, which stores no refresh token and therefore passes no
  body and revokes nothing server-side". The client did put the refresh token in the body — but it cleared
  the token store **before** making the call, and the interceptor reads the Authorization header from that
  same store, so the request went out unauthenticated, the server answered 401 from its `authenticate`
  preHandler, the revoking handler was never entered, and **the session stayed alive on the server: exactly
  the SPA's outcome.** The intent was right and the order defeated it, and nothing noticed for several slices
  because no screen called it. The order is fixed in D5d, and the negative experiment is the evidence:
  restoring the old order turns the sign-out test red.

**Three product decisions, taken with the user rather than assumed**:
1. **A successful password change signs the operator out and returns to login.** The server revokes **every**
   live session of that user (`revokeUserSessions`) and does not spare the current one; access tokens are
   stateless 15-minute JWTs, so keeping the session would only buy up to fifteen minutes before the first
   401 tries a refresh that is now dead. The SPA keeps going and takes that confusing detour. **The client
   follows the server's rule instead of the SPA's habit.**
2. **Profile and password belong to whoever is signed in**, whatever the role. The endpoint serves both, and
   hiding a security action is worse than showing it: the SPA's worker-only tab leaves an Administrator
   unable to change their password from the phone.
3. **Filters are date and month only** (mutually exclusive, as the SPA), with server paging. The `userId`
   filter is desktop work: it adds `/users/options`, another control, and it is the filter under which the
   loading counts behave inconsistently (see the finding below).

**Findings recorded, deliberately not fixed here** (they are backend or contract work, and a slice that
fixes its neighbours is a slice nobody can review):
- **`canDelete` is overstated for a `Trabalhador`** on the batch and report routes:
  `history-permissions.ts` grants it to admin **or** worker, while `reports.routes.ts` and
  `loading.routes.ts` require `Administrador` for those routes. The SPA therefore renders a delete button
  that answers 403. **The client follows the flag and gives the refusal its own sentence** rather than
  hardcoding a role — the flag is server-owned, and fixing it there fixes every client at once.
- **The report-history contract is looser than the code**: the five lifecycle flags
  (`isActive`, `readOnly`, `canEdit`, `canDeactivate`, `canDelete`) are **optional** in
  `ReportHistoryResponse` while the loading one requires all eleven of its item fields. The server always
  sends them. The generated DTO gives them as `Boolean? = null`, so the client tests `canEdit == true` —
  the shape `ReportDetailViewModel` already uses — and never treats null as permission.
- **`role` is still a bare `string`** in the contract while the database has exactly two values. The same
  class of looseness as the old bare `task_type`, and the only one of that class left.
- **`POST /api/auth/change-password` can answer 503** when the database has no session store, and the
  contract documents only 200/400/401/404.
- **`total_loadings` and `isActive` are computed over the rows surviving the filters while `creator` is
  not**, so a future `userId` filter would show inconsistent numbers. Leaving that filter out of v1 keeps
  the inconsistency out of the screen.
- **A deactivated batch opens an empty grid**: `/api/loading/schedules` filters `is_active = 1`, so the
  history row invites a tap that lands on a day with no entries. This is the SPA's behaviour too, recorded
  as parity rather than discovered as a defect.
- **`readOnly` is exactly `!canEdit`** and is dropped from the client's model. Keeping both invites a
  contradiction that cannot exist on the server.
- **THE SERVER CONTRADICTS ITSELF ABOUT A FRIDAY BATCH, and both sides are pinned by tests.** The
  history says the loading happens on the **Friday itself** (`loading.service.ts`: `CASE strftime('%w',
  schedule_date) WHEN '5' THEN schedule_date ELSE date(schedule_date, '+1 day') END`, and `%w` 5 is
  Friday). The WhatsApp export says the **following Monday** (`loading.export.service.ts`: `if (utcDay
  === 5) date.setUTCDate(getUTCDate() + 3)`, rendered in the message header). So the same batch is
  described as Friday on the history screen and as Monday in the text the operator sends to the group.
  Both are asserted in `loading.routes.export-history.test.ts`, and the browser carries a **third copy**
  of the export's rule in `nextLoadingDayDisplay()`. Verified independently, not inferred. **The client
  renders what it is given and re-derives nothing**, so D5c must not try to reconcile the two: this is
  backend work with a product decision behind it, and inventing the answer in a phone would hide it.
  Recorded beside it: the export special-cases **only Friday** (`=== 5 → +3`), so a Sunday batch also
  moves to Monday through the ordinary `+1`, which is a separate oddity of the same rule.
- **`ContractCoverageTest` is automatic here, and the verification named what it does not cover**: it
  reflects over verb and path, so the two new history calls were covered with no edit — and a **typo in a
  `@Query` name would pass it**. The query names were checked by hand against the contract and the zod
  schemas instead (`date`, `month`, `page`, `pageSize`).
- **Two premises of my own corrected by the verifier**, because a project that keeps its mistakes keeps
  them usefully: the Android build does **not** treat deprecation as an error (there is no
  `allWarningsAsErrors`; deprecations compile as `w:`), which is the opposite of what an earlier slice
  recorded from a single compile error; and `LoginViewModelTest` carries three unused imports
  (`AuthProfile`, `AuthUser`, `assertTrue`) that are **pre-existing at the parent commit**, not a habit of
  this slice.

**Naming to respect**: the generated DTOs carry orphan duplicates (`ReportListItem`, `LoadingBatchHistoryItem`,
`AuthProfile`) beside the classes the live schemas actually reference (`ReportsResponseReportsInner`,
`ScheduleHistoryResponseItemsInner`, `ProfileResponseUser`). Bind to the live ones.

**The D5b review left five advisories, and their dispositions are recorded here rather than lost**:

- **The page could be overwritten by the answer that arrived late — FIXED.** Two history requests can be
  in the air at once (the filter buttons are not withheld during a load), and without a token the older
  answer landing last draws the previous filter's rows under the new filter's name. A request token now
  makes a superseded answer a no-op in full. **The test that pins it exists only because the fake learned
  to hold an answer open, and the negative experiment is the evidence**: with the guard disabled, exactly
  one test fails and it is that one. A green suite walked past this for a whole slice.
- **The delete was irreversible with no confirmation — FIXED.** It is the only action on the screen that
  asks twice, and it departs from the SPA on purpose: parity with a desktop workflow is not worth a lost
  report from a phone in the yard.
- **`runCatching` swallows `CancellationException` — OWN SLICE, by the user's decision.** The idiom is in
  every repository in this app (Loading, Reports, Auth), so it is a project decision and not a patch to
  one slice.
- **Nothing proves the two lifecycle calls' response types on the wire — RECORDED.** `ContractCoverageTest`
  compares verb and path only, and the fake's "204" is really a 200 with a body.
- **The `Unreachable` and non-403/404 refusal branches are unexercised — RECORDED.**

**The fix slice's own review left two more, and the first one is worth a decision later**:

- **A confirmed delete can silently do nothing** (WARNING, `ReportsHistoryScreen.kt:247-268`). The view
  model's gate returns without a message when the row is no longer in the loaded page or a call is already
  in flight, and the dialog's confirm button is not gated on `busy` the way every row button is. So the
  operator can confirm and see nothing happen, with no sentence explaining it. Narrow -- the dialog is
  modal, so the list cannot change under it by a tap -- but "nothing happened" is the one answer this
  screen never gives anywhere else.
- **A suggestion on the request token itself** (`ReportsHistoryViewModel.kt:143`). Recorded with its
  location because the provider surfaces ids and lines, not prose, and inventing the sentence would be
  worse than saying so.

### The cancellation idiom, app-wide — DONE

`kotlin.runCatching` catches `Throwable`, and `CancellationException` is one, so every repository in this
app turned a cancelled call into an ordinary failure: the caller got `null` or `Unreachable`, and — the
half that matters more — **the coroutine reported success instead of cancelled**, which breaks structured
concurrency and loses the real cause. One helper now fixes all 26 sites at once
(`network/Cancellation.kt`, `runCatchingCancellable`, rethrowing the cancellation and otherwise behaving
exactly like its namesake), applied to the 12 + 11 + 3 call sites that wrap a suspend call in
`LoadingRepository`, `ReportsRepository` and `AuthRepository`. Six `runCatching` uses survive on purpose and
are named: they wrap blocking or pure work with no suspension point (`errorMessage`'s body read and JSON
parse, the EXIF read in `AndroidPhotoCompressor`, the two date parses in `ReportsHistoryScreen`, the
Keystore decrypt).

**The wiring is proven per repository, not only the helper**, because a correct helper nothing calls fixes
nothing: three tests drive the real repositories with a fake whose call throws and **fail if the call returns
at all**, which is the only assertion that can tell "rethrew" from "returned a failed Result".

**The interceptor boundary, and a correction to my own first answer.** `AuthInterceptor` clears the dead
token and answers 401 when the refresh answers `false`; an exception escaping `refreshSession()` skips both.
My first response was to catch the cancellation inside `refreshBlocking()` and answer `false` there, and an
independent verifier refuted it on three counts, all of which hold: `refresh()`'s only consumer is
`refreshBlocking()`, so the refresh path already answers `false` on every path reachable today and my catch
was **dead code**; in the shape where it would matter — a future context or timeout — `runBlocking`
**rethrews the cancellation cause even though the block returned `false`**, so the safety net would not have
caught anything; and it left two opposite rules inside one call chain, on a `public` method of a singleton.
**The lesson is where the swallow belongs**: if that boundary ever needs to be total, the place for it is the
interceptor that must produce a response, not the repository it calls. It was reverted, and the test that
came with it went too, because it **could not fail on the defect either** — with plain `runCatching` at that
site `refresh()` returns `false` anyway, so it pinned my own addition rather than the bug. A test that cannot
fail on the thing it names is worse than no test, which is the same standard this project applies to
verifiers.

Also corrected: "unreachable" was an overclaim. `runBlocking` **does** react to a JVM thread interrupt by
cancelling its coroutine, so the accurate statement is "not reachable by any cancellation this app can
produce" — nothing interrupts the OkHttp dispatcher thread today.

**The D5d review by an independent verifier found two defects in code that had already shipped, and both
are recorded here because that is what they are worth**:

- **The logout revoked nothing — FIXED** (see D5d above). The lesson is the order of two local calls, and
  that a client which *reads* a token before clearing the store has still not used it.
- **The second sign-in of a process was swallowed — FIXED, and the interesting one.** `LoginViewModel` is
  activity-scoped, so its `signedIn` flag outlived the login screen, and the route's effect was keyed on that
  flag: after a sign-out the flag was still true, so signing in again stored the tokens without the key ever
  changing and the effect never fired. **An event was modelled as state.** There is now a consumer, the effect
  reports and then consumes, and the `hasSession()` gate that had been compensating for the sticky flag is
  gone — its false branch was reachable only through the bug it was papering over.
- **A residual on the logout, recorded rather than fixed**: when the access token has expired at sign-out,
  the interceptor refreshes first, which rotates the session family; the retried request still carries the
  **old** refresh token in its body, so the presented token dies by rotation while its successor stays live
  until TTL — discarded by the client and unreachable by anyone. Low risk, same class as the defect above,
  and the clean fix is server-side: the endpoint could identify the session by the refresh token alone rather
  than requiring a live access token.
- **What the suite does not pin, named**: deleting the consumption call from the login route leaves all 149
  tests green. The view model's contract is pinned; the composition-level wiring is read, not tested, because
  this module has no Compose or Robolectric host.

---

## Open decisions

1. **B2a vs B2b vs B2c** — RESOLVED: B2c, see the Slice B decision table.
2. **Deployment order for a schema revision** — RESOLVED: migrate before the rollout, for
   additive migrations; a destructive migration needs a bounded window instead. See F12 and the
   rewritten `docs/deployment.md`.
3. **`db/index.ts` transaction gap** — unresolved, see finding F4.
4. **Session purge at startup** — unresolved. Deleting expired session rows is a startup write
   against an existing database, the same class of act as the photo-retention cleanup, so it
   deserves its own review against the Production Immutability Gate. See A2.3.
5. **Runbook topology** — RESOLVED: the shared reverse proxy is the supported topology and the
data volume stays external with a fixed name. See F14. The approved `docker-compose.yml` change
still needs committing in its own commit.
6. **Stage 2 publication status is stale** — the remote exists and local `main` is level with
   `origin/main`, so the README's "this repository has no remote yet" and the Stage 2 "Prepared /
   assign upstream remote" milestone are out of date.

## Review workload notes

Slice A alone exceeds 400 changed lines if A1 and A2 land together. A1 (data
layer and revision infrastructure) and A2 (endpoints and behavior) are
independent review units and must land as separate commits.

A2 is itself the largest remaining slice: a new schema file, a new session service,
changes to three auth handlers, config, and tests. If it crosses roughly 400 lines, split
it as "session service and its tests" then "route wiring and login/logout/change-password",
keeping tests with the code they cover rather than separating them.

B2 is split into four slices precisely because B2b (all modules, ~1000+ lines) was rejected
as unreviewable. If B2.2 or B2.3 grows past its module boundary, stop and re-slice; do not
let the harness grow into the whole surface in one review.

B2.1 landed at roughly 450 changed lines including the generated artifact, which is a
mechanical JSON document no reviewer reads line by line. Judge the remaining B2 slices by
the hand-written portion only: the artifact's size should not drive the slicing.

B2.2 came in at roughly 730 hand-written lines, above the 400 budget. Three things pushed it
there, and only the third was predictable: the `.strict()` rule had to be applied to the auth
response schemas for consistency, the shared shapes had to move into `common.schema.ts`, and
unifying the schedule projection forced a signature change that rippled into twelve existing
call sites in `loading.service.test.ts`. The thirteen registry entries with full response
documentation are verbose on their own.

B2.3 should therefore be split up front rather than after the fact: **B2.3a** the response
schemas plus the `JSON.parse` and `as any[]` fixes with their tests, and **B2.3b** the fifteen
registry entries and the regenerated artifact. `reports` is the largest remaining module and
the one with real unvalidated parsing, so it deserves the smaller units.
