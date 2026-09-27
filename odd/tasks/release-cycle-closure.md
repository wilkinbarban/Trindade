# Release v0.3.0 closure

Status: in progress
Engram mirror: `odd/release-cycle-closure/tasks`
Branch: `feature/release-v0.3.0-android-parity` (clean candidate `cc5aceb` before this document)

## Scope and safety

Integrate the dependent work-unit commits into `main` through a local-first feature-branch PR chain; preserve tests, docs, and the two previously authorized cohesive >400-line exceptions (`07601a5`, `b92f36e`). The operator explicitly selected **full Android–web parity before v0.3.0 publication**: complete C1–C6, D1 and observed E1 from `odd/tasks/android-web-parity.md`, not a registration-only release. Do not push, merge, tag, publish, sign with private material, migrate production, or replace a container without separate explicit approval. The original dirty checkout is read-only. Preserve the production database, recovery sets, signing key, rollback image, and existing sessions until the approved cutover. A revision-3 cutover invalidates every session, including the administrator's.

The source planning document in the original checkout is untracked and remains untouched. This tracked release ledger covers the clean chain; do not claim the original document was committed. Release workflow and GitHub secret values remain unverified.

## Tasks

- [x] Audit release and deployment gates. `main` is `8aa5ec1`; feature HEAD `cc5aceb` contains 28 dependent commits; `v0.3.0` absent. GitHub secret/variable **names** are present, not their values. The keystore path exists but its content/password was not inspected.
- [x] Independently verify integrated candidate: isolated Node 24 `npm ci --ignore-scripts` (259 packages, zero vulnerabilities), backend build, backend 431/431 across 73 suites, OpenAPI freshness, frontend build (2,081 modules). Pinned Android SDK `:app:testDebugUnitTest :app:assembleRelease` passed 316/316 across 37 suites, zero failures/errors/skips. Unsigned release APK is 9,816,220 bytes; it is **not publishable**. The shared Gradle journal emitted nonfatal `CorruptedCacheException`, not a passing cache-health check. Production HTTPS `/api/health` reports `status: ok`; deployed database revision not yet observed.
- [x] Make clean-checkout CI gate runnable and green. **Independently verified at committed `2a51e8b` (2026-09-27): isolated `git clone --no-hardlinks` with empty porcelain, then `TMPDIR=/var/tmp make ci-clone` exited 0.** Commit `ec70819` honors a quoted caller-selected `TMPDIR` (default `/tmp`), with strict test-only RED on explicit `/var/tmp`, GREEN four focused cases and independent readback; native review `review-3eea2c73ba547853` approved and acknowledged. `TMPDIR=/var/tmp make ci-clone` proved a clean checkout with no overlays, then **failed** at backend typecheck: `Database` used as a namespace in registration admission and negative tests. Commit `127c5a6` fixed those two test-only type declarations; root typecheck and focused 8/8 passed in isolated Node 24, and native review `review-6eccabcbbd288c93` approved and acknowledged. A clean `TMPDIR=/var/tmp make ci-clone` rerun passed frontend build and root typecheck but **failed at schema CLI**: duplicate column `security_version` during an isolated migration. Backend test count and OpenAPI/browser E2E gate results were not established from this interrupted run. Diagnose and correct this real gate failure before checking off the task. **Task-selected strict TDD:** `bash scripts/tests/ci-clone-tempdir.test.sh` was the focused RED/GREEN runner, plus `git diff --check`.
- [x] Correct the two `better-sqlite3` registration-test type declarations exposed by clean CI. **Task-selected strict TDD:** root `npm run typecheck` in the same bounded Node 24 snapshot failed with exactly two TS2702 namespace errors (RED), then passed after changing both declarations to the imported `Database` type (GREEN); focused registration admission/negative suites passed 8/8, 0 failures. An initial attempt with `npm run typecheck --workspace packages/backend` did not reach TypeScript because only the root package owns that script; an offline install also failed before tests. Commit `127c5a6` and native review `review-6eccabcbbd288c93` are closed; this test-only correction is complete, but full clean CI remains blocked by the separate schema CLI failure. Preserve test intent; no production behavior changes.
- [x] Fix the schema CLI clean-gate `security_version` duplicate-column failure in its disposable adoption fixture; verify migration/rollback semantics and rerun full CI before release. **Verified at `2a51e8b`: all eight status/migration/adoption checks passed inside the clean gate.** Diagnosis: script currently takes a fresh rev3 DB, stamps `user_version=0` but retains the rev3 column, then migration correctly rejects duplicate `security_version`; production migrator must not be weakened. **Task-selected strict TDD:** `bash scripts/verify-schema-clis.sh` after backend build in an isolated Node24 snapshot failed with duplicate `security_version` after six checks (RED), then passed every check with a consistent pre-rev3 fixture and revision 3 adoption (GREEN). The verifier mounted the snapshot read-only for GREEN and confirmed the final status call left the database byte-identical. Full clean CI remains pending. No migration was applied to production.
- [x] Restore the Playwright clean gate without weakening production login limits. **Verified at `2a51e8b`: 55 passed / 0 failed in the clean gate; the production five-per-user default limit remains covered by its own tests.** At `b7574f1`, `TMPDIR=/var/tmp make ci-clone` passed backend 431/431, schema CLI and OpenAPI, but Playwright reported 7 passed/48 failed: 45 error lines say `Login failed: Too many requests`; other locator/assertion failures need independent follow-up. **Task-selected strict TDD:** isolated Node24 fixture test failed behaviorally on sixth E2E login (429 versus 200, RED), then passed 1/1 with E2E-only finite limiter injection (GREEN); focused admission 4/4, root typecheck and backend build+432/432 passed. An initial full suite attempt without building dist failed an unrelated startup test; rebuilding backend then rerunning passed. Directed Playwright and full clean CI remain pending. Production default five-per-user limit remains tested; do not reset global state during live requests. Remove private `/var/tmp/trindade-ci-b7574f1.log` after extracting evidence.
- [x] Stabilize the two setup Playwright mocks under React StrictMode. **Verified at `2a51e8b`: the previously failing `setup-provisioning` specs pass in the clean gate.** At `a5bb372`, full clean CI passed backend 432/432, schema CLI and OpenAPI, and Playwright 53/55; only `setup-provisioning.spec.ts` missed the initial heading/alert. The mocks use first-versus-second GET counts, but mount effects can issue multiple initial GETs and discard the first. Keep the initial response until an explicit verify/retry action, then switch response; assert requests by phase. **Task-selected strict TDD:** exact targeted runner `npm run test:e2e -w @trindade/frontend -- src/__e2e__/setup-provisioning.spec.ts --project=chromium` on a clean isolated Node24 snapshot first RED, then GREEN, followed by complete clean CI. No product behavior change unless independently demonstrated.
- [ ] Reconcile the Android enrollment task's stale R4/closure checkboxes with verified `cc5aceb`, without claiming direct activity instrumentation: the two navigation tests render real screens but mirror the activity branch.
- [ ] Implement Android C1–C6 as six bounded, dependent catalog/admin surfaces: tasks, drivers, categories, vehicles, time slots and users; preserve role guards, API error behavior and each surface's tests/docs. Build one cohesive reviewable work unit at a time. Do not infer implementation from contract types or RolePolicy declarations.
- [ ] Implement D1 read-only, administrator-only paginated/filtered audit surface with tests; finish E1 as an observed app/device matrix (dashboard/editors already implemented, but not yet visually accepted). Windows emulator access via `ssh windows` is authorized; `adb` is not on PATH there, so discover the installed SDK path read-only before device operations.
- [ ] Prepare local dependent PR boundaries/description ledger with per-commit additions+deletions and explicit size exceptions; no network publication or branch merge. **Delivery strategy chosen by the operator (2026-09-27): `feature-branch-chain` with a draft/no-merge tracker PR**, so `main` receives the whole chain only after parity C1–D1 and E1 are closed. Measured authored sizes per work-unit commit are recorded below; size exceptions are accepted explicitly, not silently.
- [ ] Verify release-signed v0.3.0 from a tagged `main` commit with versionCode 300, expected certificate fingerprint, production HTTPS endpoint and GitHub release asset. This requires separate authorization for tag push/publication; no local signing password is available through the current environment.
- [ ] Capture verified production recovery sets, classify schema, obtain explicit cutover authorization, perform rev2→rev3 migration with session invalidation, deploy API image from the **same** release revision, and validate health/schema/auth without creating more worker accounts. Never run old revision-2 code on revision-3 data.
- [ ] Inventory and remove only closed, non-sensitive project temporary artifacts; measure recovered space. Preserve ambiguous `.env`, release artifacts, root-owned partial caches, active worktrees, backups and keys.

## Delivery strategy and review-size record

**Chain strategy:** `feature-branch-chain`. Child PR #1 targets the tracker branch; each later child targets its immediate parent;
the tracker stays draft and is never merged until the chain is fully reviewed and parity is closed. Strategy is cached; do not mix
strategies. Every child PR must carry the dependency diagram with `📍` on itself.

**Rule that governs the numbers below:** the 400-line budget constrains how work is *sliced*, never the code. Tests stay with the
behavior they verify, so a cohesive unit whose tests cannot be separated without losing verification is recorded as an accepted
`size:exception` instead of being split or trimmed.

| Work-unit commit | Authored lines (add+del) | Disposition |
| --- | --- | --- |
| `07601a5` worker registration contract | 461 | pre-existing authorized exception |
| `b92f36e` auth repository registration | 457 | pre-existing authorized exception |
| `1241183` task catalog state and rules | 405 | pre-existing authorized exception |
| `1e773f1` category catalog state and rules | 659 | **`size:exception` accepted (operator, 2026-09-27)** — ViewModel and its behavioral tests are one unit; splitting tests from code would leave each half unverifiable |
| `9ae8431` category catalog controls | 563 | **`size:exception` accepted (operator, 2026-09-27)** — Robolectric rendered flow needs the real screen and its 13 assertions together |
| `dc267039` vehicle catalog state and rules | 595 | **`size:exception` accepted** — ViewModel plus its behavioral tests are one unit; the delete/toggle/validation policy is not verifiable in halves |
| `8cec02be` vehicle catalog controls | 532 | **`size:exception` accepted** — the Robolectric rendered flow needs the real screen and its 13 assertions together, including the non-admin fail-closed guard |
| all other commits | 7–405 | within budget |

One honest slicing pass was performed for `1e773f1` and `9ae8431` before asking; no cohesive split brings both under 400, so the
accepted exceptions above are the recorded outcome, and the commits are not to be shrunk by removing comments, blank lines, docs or
tests. New units after C3 (C4–C6, D1) are planned to stay near the budget; any new overage is recorded in this same table.

**Blocker recorded 2026-09-27: the native reviewer relay is failing deterministically.** Five of five review captures returned
`pi-host-relay-transport-failure` (stage `pi`, "WebSocket error", 83–85 s elapsed, `mutation: none`), so the candidate stays frozen and
the slot is reoffered after each fresh STATUS. Refuted with evidence: concurrent load, `gentle-shell` version skew (realigned to 3.7.0 and
the stale host killed), and a wedged relay host; `gentle-ai doctor` is healthy. Because this blocks every new review, the vehicle units
above are committed and independently verified but their native reviews cannot close until the transport is restored. The one untried
route is the in-process reviewer transport, which needs a fresh session without `GENTLE_PI_REVIEW_RELAY_EXTENSIONS` and
`GENTLE_PI_REVIEW_RELAY_CONTRACT`.

## Review-candidate risk assessment standard

The native `gentle-ai review assess` verb is read-only and usable, but it refuses with exit 1 whenever the repository has eligible
untracked files and the caller did not declare the untracked scope. Its refusal is itself a valid `gentle-ai.review-assessment/v1`
envelope carrying `risk: high` and an actionable `unassessable` reason, yet the Pi facade only accepts a *failure* envelope on a
non-zero exit, so the actionable reason surface as an opaque `schema-incompatible` and the candidate is treated as unassessable.

Standard for this feature, so a real tier is obtained instead of a fail-closed default:

1. Assess **at a clean point**: immediately after a work-unit commit and before launching the next writer, when the tree has no
   eligible untracked files. Verified on this repository: a clean clone assessed the C4a candidate as `medium` (7 paths, 203 lines).
2. When untracked files do exist, **declare the scope** in the assess input:
   `{"baseRef":"<ref>","committedOnly":true,"untrackedScope":"exclude","expectedUntrackedInventory":"sha256:<eligible_untracked_inventory from STATUS>"}`.
   Use `untrackedScope: "select"` with `intendedUntracked: [...]` when the new files are part of the candidate. Verified: the same
   input that failed without the declaration returned exit 0 and `risk: passive` with the declaration.
3. Pass `writerModelId` and `writerEffort` when the writer's real profile is known. An unknown profile is treated as small, which
   raises a medium tier to high for verification purposes and adds an independent verifier run that the profile would not require.

Until the facade returns the native envelope on a non-zero assess exit, an unassessable result is never evidence of a low tier: it
keeps the fail-closed path, so this standard is an efficiency and fidelity improvement, never a way to lower the verification bar.

## Work-unit evidence

**Clean-checkout CI gate at committed `2a51e8b` (independent verification, 2026-09-27).** Isolated `git clone --no-hardlinks` of the active repository, checkout of the exact commit, empty `git status --porcelain`, then the repository's own gate `TMPDIR=/var/tmp make ci-clone`: exit **0**. Per-gate results: `npm ci` 259 packages and zero vulnerabilities; root typecheck 0 errors; backend build; **backend 432/432, 0 failed, 0 skipped, 73 suites**; schema CLI all eight checks; OpenAPI artifact current; frontend build 2 081 modules; **Playwright 55 passed / 0 failed**. Private log: `/var/tmp/trindade-ci-verify-2a51e8b.log` (mode 600, remove after this evidence is consumed). This closed the earlier `b7574f1` failure (7 passed / 48 failed, mostly `Login failed: Too many requests`) through `a5bb372` and `c003085`. The gate must be re-run once C4–C6, D1 and E1 land, and the Android lane still needs a JDK-capable CI job before the release candidate is final.

CI clone-tempdir candidate: strict RED confirmed the explicit `/var/tmp` case failed against the original hardcoded `/tmp` recipe while the default case passed; GREEN passed four focused shell cases (default, explicit, missing directory and file path) with `bash scripts/tests/ci-clone-tempdir.test.sh`, and `git diff --check` passed. Full clean-checkout CI is still pending and must not be claimed from these focused checks.

R4: `cc5acebc374b4acbf086216ace7daf7f083c595d`, 316/316 Android tests, contract check and debug assembly on isolated snapshot, native review `review-a909f6d080593b1b` approved and acknowledged. Integrated backend/frontend and unsigned release checks above are read-only gates, not a release-signing claim. No work-unit commit yet for the current release-gate repair.
