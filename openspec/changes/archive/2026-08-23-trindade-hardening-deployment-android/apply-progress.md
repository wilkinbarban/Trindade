# Apply Progress: Stage-One Recovery and Canonical Truth

## Mode and Scope

Standard (`strict_tdd: false`). Work Units 1-4/tasks 1.1-4.2 are complete. No production/persistent project database was touched; existing-install evidence intentionally used isolated temporary file-backed SQLite databases and removed them afterward. Publication, remotes, commits, and deployment remain out of scope.

## Completed Tasks

- [x] 1.1 Recovery execution threat-boundary RED tests, now individually reconstructed and observed.
- [x] 1.2 SQLite-consistent snapshot, inventory/checksums, and isolated restore harness.
- [x] 1.3 Fail-closed installation classifier.
- [x] 2.1 Fail-fast runtime configuration with UTF-8 byte strength and known-value rejection.
- [x] 2.2 Versioned required-reference contract and credential-free fresh seed.
- [x] 2.3 Prerequisite-checked isolated fixtures with field-specific drift errors.

## Reversible RED Reconstruction

The existing `recovery.ts` SHA-256 was recorded as `d1f16fe1e1d41335cce8141fdd38de03688c4ccc69f05fe825aa6f6c1238b656`. A temporary local edit neutralized only the four path/overlap guards in `captureRecoverySet` and the three result guards in `executeChecked`, while preserving exports and imports so every test executed its body. Each named test was run alone and failed at its intended `assert.rejects` with `ERR_ASSERTION: Missing expected rejection`, proving the corresponding unsafe behavior was no longer rejected. The implementation was then restored and its SHA-256 rechecked byte-for-byte.

Command template used once per exact name:

`npm exec --workspace=packages/backend -- node --test --import tsx --test-name-pattern='^<exact test name>$' src/recovery.test.ts`

## Per-Case RED and GREEN Evidence

| Case / exact test name | Reconstructed RED | Restored GREEN |
|---|---|---|
| Traversal — `rejects path traversal outside the allowlisted root` | exit 1; body reached line 28; missing rejection because traversal was accepted | exit 0; 1 passed, 0 failed |
| Metacharacters — `rejects shell metacharacters in recovery paths` | exit 1; body reached line 37; missing rejection because `bad;name` was accepted | exit 0; 1 passed, 0 failed |
| Symlink escape — `rejects a symlink escape from an allowlisted root` | exit 1; body reached line 48; missing rejection because the outside symlink target was accepted | exit 0; 1 passed, 0 failed |
| Production overlap — `rejects recovery output that overlaps production data` | exit 1; body reached line 57; missing rejection because nested production output was accepted | exit 0; 1 passed, 0 failed |
| Non-zero exit — `rejects a non-zero recovery process exit` | exit 1; body reached line 65; missing rejection for process exit 2 | exit 0; 1 passed, 0 failed |
| Timeout — `rejects a timed-out recovery process` | exit 1; body reached line 69; missing rejection for `timedOut: true` | exit 0; 1 passed, 0 failed |
| Partial output — `rejects partial recovery process output` | exit 1; body reached line 73; missing rejection for stdout lacking `complete` | exit 0; 1 passed, 0 failed |

## Work Unit Evidence

| Evidence | Exact command and result |
|---|---|
| Seven-case GREEN | `npm exec --workspace=packages/backend -- node --test --import tsx --test-name-pattern='rejects path traversal outside the allowlisted root\|rejects shell metacharacters in recovery paths\|rejects a symlink escape from an allowlisted root\|rejects recovery output that overlaps production data\|rejects a non-zero recovery process exit\|rejects a timed-out recovery process\|rejects partial recovery process output' src/recovery.test.ts` — exit 0; 7 passed, 0 failed. |
| Focused suite | `npm exec --workspace=packages/backend -- node --test --import tsx src/recovery.test.ts` — exit 0; 10 passed, 0 failed. |
| Backend build | `npm run build --workspace=packages/backend` — exit 0; `tsc` and SQL copy passed. |
| Isolated runtime | `node packages/backend/dist/recovery.js --verify-isolated` — exit 0; set `9d99c96b942aae6a9569e188ce9e127ef2d5182885e2452714b8396aacf705b7`, `integrity=ok`, `rowCounts.probe=1`, `assetsVerified=1`. |
| Full backend suite | `npm run test --workspace=packages/backend` — exit 0; 180 passed, 0 failed. |
| Production restoration | `sha256sum packages/backend/src/recovery.ts` — `d1f16fe1e1d41335cce8141fdd38de03688c4ccc69f05fe825aa6f6c1238b656`, exactly matching the pre-reconstruction hash. |

## PR Boundary, Accounting, and Rollback

- At Unit 1 completion, delivery was autonomous PR slice 1, `stacked-to-main`; later units were then pending.
- Final authored accounting: `recovery.ts` 115 lines + `recovery.test.ts` 112 + `install-lifecycle.ts` 35 + this progress artifact 55 + `tasks.md` 5 additions/5 deletions = 327 changed lines, within the cumulative 400-line authority.
- Rollback boundary: remove `packages/backend/src/recovery.ts`, `packages/backend/src/recovery.test.ts`, and `packages/backend/src/db/install-lifecycle.ts`, then revert only Unit 1 task/progress metadata. No unrelated startup wiring or later work is included.
- The temporary RED edit is fully rolled back; final production bytes are unchanged. Only this evidence artifact changed during reconstruction.
- The Unit 1 executor neither acquired nor settled its delegated token; the ledger records acquisition and settlement by the orchestrator.

## Deviations and Risks

- No production recovery set was created because production access was not authorized; the runtime proof uses isolated temporary data only.
- The recovery harness remains unwired from startup, as required by the Work Unit 1 boundary.

## Work Unit 2 Evidence

| Evidence | Exact command and result |
|---|---|
| Focused tests | `npm exec --workspace=packages/backend -- node --test --import tsx src/runtime-config.test.ts src/db/reference-data.test.ts src/db/db-init.test.ts src/modules/auth/auth.routes.test.ts src/modules/admin/admin.routes.test.ts src/modules/loading/loading.routes.test.ts` — exit 0; 108 passed, 0 failed. |
| Backend build | `npm run build --workspace=packages/backend` — exit 0; `tsc` and SQL copy passed. |
| Runtime harness | N/A: Unit 2 exposes internal validation and in-memory fixture boundaries only; startup/runtime wiring is explicitly Unit 3. Auth integration was exercised through Fastify injection. |
| Rollback boundary | Revert `runtime-config.ts`, `runtime-config.test.ts`, `db/reference-data.ts`, `db/reference-data.test.ts`, `db/db-init.test.ts`, `db/seed.sql`, and `test-helper.ts`, plus only Unit 2 task/progress metadata. |

## Unit 2 PR Boundary and Process Evidence

- Autonomous PR slice 2, `stacked-to-main`, starts after Unit 1 and ends before bootstrap/startup wiring.
- No production or persistent database was opened; all reference/auth evidence used in-memory SQLite.
- The Unit 2 executor neither acquired nor settled its delegated token; the ledger records acquisition and settlement by the orchestrator.
- No temporary RED mutations or background harness processes were created; cleanup is N/A.

## Unit 2 Phase-Contract Remediation

- Kept tasks 2.1-2.3 complete by extending the v1 reference authority with every required catalog category/task and validating every canonical field after seed loading and before fixture creation.
- Focused tests: `npm exec --workspace=packages/backend -- node --test --import tsx src/db/reference-data.test.ts src/db/db-init.test.ts` — exit 0; 12 passed, 0 failed.
- Backend build: `npm run build --workspace=packages/backend` — exit 0; `tsc` and SQL copy passed.
- Runtime harness remains N/A: this remediation only strengthens seed-to-fixture validation on in-memory SQLite; startup wiring remains Unit 3.
- Rollback boundary: revert only `db/reference-data.ts`, `db/reference-data.test.ts`, and this merged remediation section.
- The Unit 2 remediation executor neither acquired nor settled its delegated token; the ledger records acquisition and settlement by the orchestrator.

## Work Unit 3 Completion

- [x] 3.1 One-time atomic administrator bootstrap with hashed password, Administrador role lookup, setup status, and closed/race-safe repeat rejection.
- [x] 3.2 Mobile-first PT-BR first-run setup page and application setup gate.
- [x] 3.3 Report/loading exports identify missing required relationships explicitly while valid WhatsApp output remains PT-BR.
- [x] 3.4 Runtime config and installation classification precede database open; fresh targets initialize references, existing targets open read-only, and ambiguous targets fail before writes.

## Work Unit 3 Evidence

| Evidence | Exact command and result |
|---|---|
| Focused tests | `npm exec --workspace=packages/backend -- node --test --import tsx src/modules/auth/bootstrap.routes.test.ts src/modules/auth/auth.routes.test.ts src/modules/reports/export.service.test.ts src/modules/loading/loading.routes.test.ts src/db/index.test.ts` — exit 0; 71 passed, 0 failed. |
| Full backend suite | `npm run test --workspace=packages/backend` — exit 0; 192 passed, 0 failed. |
| Build/typecheck | `npm run build --workspaces --if-present` — exit 0; backend `tsc`/SQL copy and frontend `tsc`/Vite build passed. |
| Runtime harness | Temporary database/server on `127.0.0.1:43119`: status `true`; first setup HTTP 201; repeat HTTP 409; status `false`; process stopped and temporary directory removed. |
| Rollback boundary | Revert Unit 3 bootstrap/setup UI, export validation, startup/database wiring, focused tests, and only Unit 3 task/progress metadata; Units 1-2 remain intact. |

## Unit 3 Boundary and Cleanup

- Autonomous stacked-to-main PR slice 3 starts after Unit 2 and ends before Unit 4 documentation/production verification.
- Authored Unit 3 accounting is 396 changed lines (additions + deletions), including tests and metadata, within the 400-line review budget.
- No production/persistent project database was accessed or mutated; the existing-install startup test intentionally created/opened an isolated temporary file-backed SQLite database and removed it afterward. The Unit 3 executors neither acquired nor settled their delegated tokens; the ledger records acquisition and settlement by the orchestrator. No deployment, publication, commit, or PR occurred.
- Runtime harness process stopped; `/tmp/trindade-unit3-*` test directory removed. `scripts/renew-certbot.sh` was not modified by this unit.
- Evidence digest: `sha256:81ad7373f1265a9d599237e799325ddfb82008f5721061f52d87d1026dc379de` (scoped Unit 3 diff snapshot before adding this self-referential digest line).

## Unit 3 Existing-Install Login Remediation

- Successful login now treats audit persistence as best-effort: read-only audit failure is warned with user context and does not invalidate authentication.
- The actual `server.ts` startup path is exercised against an existing SQLite file; login returns a JWT, emits the audit warning, and leaves database bytes unchanged.
- Bootstrap repeat-safety now uses two concurrent requests and proves exactly one 201 and one 409 response.
- Final Unit 3 accounting preserves 396 initial changed lines, 81 runtime/login remediation lines, and 4 wording/evidence correction lines; 394 was a premature pre-final-metadata snapshot.
- Remediation focused tests: `npm exec --workspace=packages/backend -- node --test --import tsx src/db/index.test.ts src/modules/auth/bootstrap.routes.test.ts src/modules/auth/auth.routes.test.ts` — exit 0; 21 passed, 0 failed.
- Full backend: `npm run test --workspace=packages/backend` — exit 0; 193 passed, 0 failed. Workspace build passed.
- Evidence revision: `sha256:031b4457d62d9a71d7a78e433701e0d9550acd6602d80adb8e6e548cb96f0911` (runtime/login production and behavior-test artifacts; progress metadata excluded to avoid self-reference).
- Remediation rollback boundary: revert only `auth.routes.ts`, the new startup-path test in `db/index.test.ts`, the concurrent assertion in `bootstrap.routes.test.ts`, and this section.

## Unit 3 Fresh Validation and Final Evidence Correction

- Fresh focused Unit 3 validation: 72 passed, 0 failed.
- Fresh full backend validation: 193 passed, 0 failed.
- Fresh workspace build: PASS. Fresh `git diff --check`: PASS.
- Final wording/evidence correction: structural readback PASS and scoped `git diff --check` PASS.
- Correction digest: `sha256:871bd2b4d15c805a8a9fbab3924111ecdeb311b17f43919320eabc9a54066c82`.
- Canonical cumulative accounting: 396 initial + 81 runtime/login + 4 wording/evidence lines.
- Historical delegated-token authority and the reconciled distinction between production/persistent project databases and isolated temporary file-backed SQLite evidence remain unchanged.
- Units 1-3 were complete before the autonomous Unit 4 slice.
- Final evidence-only rollback: revert this section and the two remediation evidence lines above; implementation, tests, databases, environment, native authority state, Git state, and Unit 4 remain untouched.

## Work Unit 4 Completion

- [x] 4.1 Operator configuration and deployment documentation now define strong secret handling, fresh bootstrap, existing-install read-only startup, fail-closed ambiguity, recovery gates, fingerprint checks, and rollback evidence. Compose explicitly injects database and photo paths.
- [x] 4.2 Automated regression proof covers fresh required-reference-only initialization and an existing database plus associated-file fingerprint remaining unchanged across recovery capture, isolated restore, read-only open, and a rejected write.

## Work Unit 4 Evidence

| Evidence | Exact command and result |
|---|---|
| Focused tests | `npm exec --workspace=packages/backend -- node --test --import tsx src/stage-one-invariants.test.ts` — exit 0; 2 passed, 0 failed. |
| Runtime harness | `npm run build --workspace=packages/backend && node packages/backend/dist/recovery.js --verify-isolated` — exit 0; build passed; scenario `verify-isolated`, integrity `ok`, `rowCounts.probe=1`, `assetsVerified=1`, set ID `c114a5c3a9bab85c44c5a3531ebd2e3f723a0a6b3389ca22426d08022f69e7d7`. |
| Full backend | `npm run test --workspace=packages/backend` — exit 0; 195 passed, 0 failed. |
| Full build/typecheck | `npm run build --workspaces --if-present` — exit 0; backend `tsc`/SQL copy and frontend `tsc`/Vite build passed. |
| Diff hygiene | `git diff --check -- README.md .env.example docker-compose.yml packages/backend/src/stage-one-invariants.test.ts openspec/changes/trindade-hardening-deployment-android/tasks.md openspec/changes/trindade-hardening-deployment-android/apply-progress.md` — exit 0. |
| Cleanup | No `/tmp/trindade-stage-one-*` fixture remained and no exact recovery/stage-one Node process remained after the harness. |
| Rollback boundary | Revert only `README.md`, `.env.example`, `docker-compose.yml`, `packages/backend/src/stage-one-invariants.test.ts`, and Unit 4 task/progress metadata. Units 1-3 remain intact. |

## Unit 4 Expired-Photo Policy Remediation (Generation 13)

- The canonical preservation boundary now explicitly preserves SQLite bytes and non-expired associated assets. Report-photo files older than the documented 30-day retention period MAY be deleted during existing-install startup cleanup.
- [x] The checked-in existing-install startup regression starts the compiled `dist/server.js` against isolated SQLite and photo fixtures, verifies health and login return HTTP 200, verifies the expired photo is deleted, and verifies database bytes and the non-expired photo remain byte-identical.
- This remediation is limited to the authoritative policy artifacts, the startup regression, task wording, and cumulative evidence. No production or persistent project database, environment, deployment, commit, remote, or unrelated renewal script is included.


## Unit 4 Boundary and Process Evidence

- Autonomous stacked-to-main PR slice 4 starts after Unit 3 and ends with stage-one documentation and read-only regression proof. No Unit 1-3 implementation file was changed for this unit.
- Authored Unit 4 accounting is 151 changed lines: 138 additions and 13 deletions across operator/configuration docs, the regression test, task checkboxes, and this evidence section; it remains below the 400-line review budget.
- Evidence revision: `sha256:4386fdee3ad6cd12648537839c0d2a374cc00471de6a133375e641a0b850e51f` over the Unit 4 deliverables except this self-referential apply-progress artifact.
- Production/persistent project databases and environment files were not opened or changed. All runtime evidence used self-cleaning temporary fixtures; no deployment, publication, commit, PR, remote, RDD, Unit 5, native acquisition, settlement, or token mutation occurred.
- `scripts/renew-certbot.sh` remains a pre-existing unrelated worktree change and is excluded from Unit 4 evidence and accounting.
- Units 1-4/tasks 1.1-4.2 are complete and ready for `sdd-verify` after parent-side settlement.

## Unit 4 Remediation Evidence (Generation 13)

| Evidence | Exact command and result |
|---|---|
| Focused runtime regression | `npm --workspace packages/backend run build && node --test --import tsx src/db/index.test.ts` — exit 0; 4 tests passed. The compiled server returned health/login HTTP 200, deleted the expired fixture, and retained both the SQLite bytes and non-expired photo bytes. |
| Full backend | `npm --workspace packages/backend run test` — exit 0; 195 passed, 0 failed. |
| Workspace build | `npm run build --workspaces --if-present` — exit 0; backend TypeScript/SQL copy and frontend TypeScript/Vite build passed. |
| Diff hygiene | `git diff --check -- <Unit 4 remediation paths>` — exit 0. |
| Cleanup | The child server was stopped and the temporary SQLite/photo fixture directory was removed by test cleanup. |
| Rollback boundary | Revert the authoritative policy wording, `packages/backend/src/db/index.test.ts`, and this remediation evidence only; retain all Units 1-4 implementation and prior evidence. |
