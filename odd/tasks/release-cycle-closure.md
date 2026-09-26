# Release v0.3.0 closure

Status: in progress
Engram mirror: `odd/release-cycle-closure/tasks`
Branch: `feature/release-v0.3.0-android-parity` (clean candidate `cc5aceb` before this document)

## Scope and safety

Integrate the 28 dependent work-unit commits into `main` through a local-first feature-branch PR chain; preserve tests, docs, and the two previously authorized cohesive >400-line exceptions (`07601a5`, `b92f36e`). Do not push, merge, tag, publish, sign with private material, migrate production, or replace a container without separate explicit approval. The original dirty checkout is read-only. Preserve the production database, recovery sets, signing key, rollback image, and existing sessions until the approved cutover. A revision-3 cutover invalidates every session, including the administrator's.

The source planning document in the original checkout is untracked and remains untouched. This tracked release ledger covers the clean chain; do not claim the original document was committed. Release workflow and GitHub secret values remain unverified.

## Tasks

- [x] Audit release and deployment gates. `main` is `8aa5ec1`; feature HEAD `cc5aceb` contains 28 dependent commits; `v0.3.0` absent. GitHub secret/variable **names** are present, not their values. The keystore path exists but its content/password was not inspected.
- [x] Independently verify integrated candidate: isolated Node 24 `npm ci --ignore-scripts` (259 packages, zero vulnerabilities), backend build, backend 431/431 across 73 suites, OpenAPI freshness, frontend build (2,081 modules). Pinned Android SDK `:app:testDebugUnitTest :app:assembleRelease` passed 316/316 across 37 suites, zero failures/errors/skips. Unsigned release APK is 9,816,220 bytes; it is **not publishable**. The shared Gradle journal emitted nonfatal `CorruptedCacheException`, not a passing cache-health check. Production HTTPS `/api/health` reports `status: ok`; deployed database revision not yet observed.
- [ ] Make clean-checkout CI gate runnable on a host where `/tmp` is too small by honoring a safe caller-selected `TMPDIR` for its temporary clone; add focused regression proof and run the complete `make ci-clone` gate using `/var/tmp`. Initial preflight refused to run because `/tmp` had only 754,270,208 bytes free. Keep default behavior unchanged and prohibit unsafe temp path substitution. **Task-selected strict TDD:** run `bash scripts/tests/ci-clone-tempdir.test.sh` on the failing test-only candidate (RED), then on the implementation (GREEN); additionally run `git diff --check`.
- [ ] Reconcile the Android enrollment task's stale R4/closure checkboxes with verified `cc5aceb`, without claiming direct activity instrumentation: the two navigation tests render real screens but mirror the activity branch.
- [ ] Prepare local dependent PR boundaries/description ledger with per-commit additions+deletions and explicit size exceptions; no network publication or branch merge.
- [ ] Verify release-signed v0.3.0 from a tagged `main` commit with versionCode 300, expected certificate fingerprint, production HTTPS endpoint and GitHub release asset. This requires separate authorization for tag push/publication; no local signing password is available through the current environment.
- [ ] Capture verified production recovery sets, classify schema, obtain explicit cutover authorization, perform rev2→rev3 migration with session invalidation, deploy API image from the **same** release revision, and validate health/schema/auth without creating more worker accounts. Never run old revision-2 code on revision-3 data.
- [ ] Inventory and remove only closed, non-sensitive project temporary artifacts; measure recovered space. Preserve ambiguous `.env`, release artifacts, root-owned partial caches, active worktrees, backups and keys.

## Work-unit evidence

CI clone-tempdir candidate: strict RED confirmed the explicit `/var/tmp` case failed against the original hardcoded `/tmp` recipe while the default case passed; GREEN passed four focused shell cases (default, explicit, missing directory and file path) with `bash scripts/tests/ci-clone-tempdir.test.sh`, and `git diff --check` passed. Full clean-checkout CI is still pending and must not be claimed from these focused checks.

R4: `cc5acebc374b4acbf086216ace7daf7f083c595d`, 316/316 Android tests, contract check and debug assembly on isolated snapshot, native review `review-a909f6d080593b1b` approved and acknowledged. Integrated backend/frontend and unsigned release checks above are read-only gates, not a release-signing claim. No work-unit commit yet for the current release-gate repair.
