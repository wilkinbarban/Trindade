# Exploration: Trindade Hardening, Deployment, and Android Delivery

## Executive Decision

Trindade is **not ready for proposal as one implementation batch, production deployment, GitHub publication, or Android packaging**. The production build succeeds, but the backend baseline is red (32/167 passing), authentication fixtures are inconsistent with seed credentials, database/catalog fixtures have drifted, production dependencies include three high-severity advisories, Git metadata is invalid, and deployment lacks a portable/recoverable operating model. Proceed with a staged proposal in the required order: restore a trustworthy baseline, harden the web system, establish deployment and repository controls, then package the proven responsive web client with Capacitor for Android.

## Current State

- npm-workspaces TypeScript monorepo: React 19/Vite SPA, Fastify 5 REST API, and SQLite/better-sqlite3 persistence.
- The SPA uses same-origin `/api` and `/p` paths, JWT bearer tokens stored in `localStorage`, lazy routes, and responsive Tailwind components.
- Docker Compose separates Nginx/frontend and API containers and persists backend data in one named volume.
- Backend logging is Fastify JSON logging; `/api/health` reports process availability only. There is no metrics, tracing, readiness/database probe, or alerting configuration.
- No web manifest or service worker exists, so the current frontend is responsive but not an installable/offline PWA.
- OpenSpec is established in hybrid mode. Git is not operational: `.git/` contains only `.atl/` and `git rev-parse` fails.

## Affected Areas

- `packages/backend/src/db/{schema.sql,seed.sql,index.ts}` — schema, seed truth, startup migrations, foreign keys, and persisted-data safety.
- `packages/backend/src/test-helper.ts` and backend `*.test.ts` — fixture/authentication contract and cascading failures.
- `packages/backend/src/modules/auth/*` — credentials, JWT policy, authorization, password policy, and audit behavior.
- `packages/backend/src/server.ts` — CORS, health/readiness, error policy, security headers, logging, lifecycle, and production guard.
- `packages/backend/src/modules/{reports,loading,admin,audit}/*` — data integrity, transactional behavior, uploads, authorization, and error contracts.
- `packages/frontend/src/{api,contexts,pages,components}` — token handling, error recovery, accessibility, mobile UX, and app-shell behavior.
- `packages/frontend/{index.html,vite.config.ts,playwright.config.ts,public}` — base paths, installability, browser/mobile test matrix, and asset delivery.
- `docker/*`, `docker-compose.yml`, `.env.example` — portability, image hardening, health checks, TLS/host integration, backups, and secrets.
- `README.md`, `Makefile`, repository metadata, and future `.github/` files — reproducible setup, CI, release, security, and publication.

## Executed Evidence

| Check | Result | Interpretation |
|---|---|---|
| `git rev-parse --show-toplevel` | Failed: not a Git repository | Publication and history validation are blocked. |
| `.git/` inspection | Contains only `.atl/` | This is malformed metadata, not a usable repository. |
| `npm run build --workspaces --if-present` | Passed for backend and frontend | TypeScript and production bundling currently compile. |
| Backend full test command | 32 passed, 135 failed, 167 total | Baseline is unsuitable as a release gate. |
| Auth test file | 7 passed, 9 failed | Valid-login expectation receives 401 and cascades into protected-route failures. |
| DB initialization test file | 4 passed, 3 failed | Seed expectations and current catalog/user seed disagree. |
| bcrypt verification | Seeded, documented, and test-only legacy credentials did not agree [REDACTED LEGACY CREDENTIAL] | Credential drift is the confirmed primary authentication-fixture root cause. |
| Playwright `--list` | 53 Chromium desktop tests in 10 files | E2E discovery works, but no mobile/browser matrix was found; execution is expected to be blocked by auth drift until repaired. |
| `npm audit --omit=dev --json` | 5 production vulnerabilities: 3 high, 2 moderate; fixes available | Dependency updates are a pre-release security gate. |
| `docker compose config --quiet` | Passed | Compose syntax/interpolation is valid in the current environment, not proof of runtime health. |

## Evidence-Backed Findings

### Confirmed

| Severity | Root cluster | Evidence | Impact | Recommended response |
|---|---|---|---|---|
| Critical release blocker | **C1 — Broken source-of-truth alignment** | Seeded, test/E2E, README, and OpenSpec authentication material disagreed [REDACTED LEGACY CREDENTIAL]. | Authentication fails and produces most 401 cascades; documentation cannot be trusted for access. | Choose one credential/bootstrap contract, remove fixed public production credentials, generate test users explicitly, and prove login/role tests before touching downstream failures. |
| Critical release blocker | **C2 — Untrustworthy test baseline and fixture coupling** | 135/167 backend tests fail; many later failures are `undefined.id` after failed authenticated setup. | Real defects are hidden by cascades and release evidence is meaningless. | Fail fixture setup immediately on login/create errors; centralize typed fixture builders; rerun by cluster and classify only residual failures. |
| High | **C3 — Seed/schema/catalog drift** | DB tests expect four users and at least seven categories; current seed creates two users and six categories. Export tests also miss expected Portuguese task content. | Fresh installs, tests, exports, and presumed production data have divergent contracts. | Reconcile PRD/specs/current operational truth; version migrations and seed reference data separately; add invariant and upgrade-path tests. Never reset production data as migration. |
| High | **C4 — Dependency and application security debt** | Production audit reports high advisories in `fast-uri`, `find-my-way`, and `react-router`, plus moderate advisories; fixes are available. Server CORS uses `origin: true`; no Helmet/security-header or rate-limit plugin is present. | Avoidable DoS/host-confusion/open-redirect exposure and permissive cross-origin API access. | Upgrade dependencies with regression tests; allowlist production origins; add security headers, login/API throttling, request limits, and proxy trust configuration. |
| High | **C5 — Authentication/session hardening gaps** | JWT fallback secret exists in auth modules; production startup guard mitigates only production mode. Tokens live in `localStorage`; access tokens last 15 minutes; passwords permit four characters; seeded credentials are static. | XSS can expose bearer tokens; weak/bootstrap passwords and configuration mistakes increase account takeover risk. | Centralize validated auth config; require strong first-run bootstrap and password change; decide short-lived token + secure HttpOnly cookie/CSRF model versus current bearer model; add brute-force and revocation tests. |
| High | **C6 — Repository/publication blocked** | Git commands fail and `.git/` is malformed; no CI/workflows were found. | Cannot safely determine history, tracked secrets, branch state, license provenance, or push to the requested repository. | Preserve a backup, recover/initialize valid Git metadata deliberately, inspect all files for secrets and generated data, establish main branch/history decision, then add CI and remote only after user approval. No GitHub mutation in this phase. |
| High | **C7 — Deployment durability and portability gaps** | Compose depends on external `portafolio_default`; no container health checks; one volume holds DB/photos; health endpoint does not check SQLite; no backup/restore procedure. | Deployment may fail on another host and can silently lose or corrupt operational data. | Make external network optional/environment-specific; add API/web health checks and readiness; define SQLite-safe backup/restore, volume ownership, retention, rollback, and restore drill. |
| Medium | **C8 — Migration safety debt** | Startup code performs ad-hoc conditional DDL and toggles foreign keys around a table rebuild; migrations have no ledger/version table. | Partial upgrades and schema drift are difficult to audit or recover. | Introduce ordered, transactional, idempotent migrations with a schema-version ledger, backup gate, `foreign_key_check`, and old-database upgrade fixtures. |
| Medium | **C9 — Error handling and observability gaps** | Fastify logger exists, but no shared error envelope/global error policy was found; cleanup errors are counted/warned; health is liveness-only. Frontend handles many errors locally. | Operators cannot correlate failures; clients receive inconsistent recovery behavior. | Define error codes/envelopes, request correlation IDs, redaction, readiness checks, structured audit/operational events, and deployment log/alert ownership. |
| Medium | **C10 — Quality-tooling gaps** | No configured linter, formatter, frontend unit runner, or coverage threshold; CI absent. | Bad practices and regressions depend on expensive full E2E/manual review. | Add formatting/lint/type/test/security gates; add frontend component/unit tests and targeted backend coverage before expanding E2E. |
| Medium | **C11 — Mobile/accessibility incompleteness** | Responsive components and several live regions exist, but icon-only history actions lack accessible names; Playwright only targets Desktop Chrome; no automated accessibility checks. | Mobile and assistive-technology regressions are unmeasured. | Audit WCAG 2.2 AA, name icon controls, verify focus/dialog/touch targets, add axe and Android-sized Chromium projects, then conduct real-device checks. |
| Medium | **C12 — Documentation/configuration drift** | README credentials conflict with seed/tests; README uses manual test commands and describes a sample worker absent from seed. `.env.example` intentionally contains the known dev secret and an environment-specific URL. | Operators may deploy incorrect credentials/settings or follow stale procedures. | Rewrite setup, bootstrap, test, backup, restore, deploy, and incident docs from verified commands; keep examples non-secret and clearly local-only. |
| Low/Medium | **C13 — Frontend delivery weight and duplication** | Build succeeds, but export chunk is about 594 kB; upload and normal API clients duplicate unauthorized/error parsing. | Slower mobile startup and inconsistent future fixes. | Measure on low-end Android, lazy-load export code at point of use, consolidate transport/error handling, and set bundle budgets. |

### Hypotheses Requiring Targeted Verification

- Production data may already use credentials/schema shapes different from both seed and tests. Verify only against a sanitized backup and never infer from `seed.sql`.
- Public photo tokens are 12 hex characters (48 bits). No exploit is confirmed, but enumeration/risk and retention expectations require threat-model review.
- Local `.env` may contain real secrets. It was intentionally not printed; after Git recovery, verify ignore/tracking history and rotate any value that may have escaped.
- Docker images may build and run correctly, but this phase only proved Compose parsing. Build, non-root operation, health, persistence, backup, and restore need an isolated deployment rehearsal.
- Full E2E behavior is not classified beyond discovery because the confirmed credential drift would dominate results. Execute it after C1/C2 repair.

## Root-Cause Clusters and Order

1. **Truth alignment (C1–C3):** credentials, fixtures, seed/catalog, migrations, docs.
2. **Release trust (C2, C10):** stop cascades, restore tests, CI, coverage and quality gates.
3. **Security and integrity (C4, C5, C8):** dependencies, auth/session, CORS/headers/rate limits, migration safety.
4. **Operability (C7, C9, C12):** health/readiness, logs, backups, restore, runbooks, portable Compose.
5. **Experience and maintainability (C11, C13):** accessibility, mobile matrix, bundle/transport cleanup.
6. **Publication (C6):** recover repository, secret scan, establish CI/release controls, then publish.
7. **Android delivery:** package only the stable, HTTPS-deployed system.

## Prioritized Remediation Roadmap

### Stage 1 — Reproduce, classify, and fix existing defects

1. Freeze destructive DB/reset and deployment actions; capture a sanitized data backup contract.
2. Define the canonical bootstrap/test credential contract. Replace implicit seed login assumptions with explicit test user factories and assert fixture login responses.
3. Restore auth tests first, then rerun each backend module to expose residual failures without 401 cascades.
4. Reconcile seed/catalog/export expectations against active OpenSpec/PRD and operational owner approval; separate reference seeding from migrations.
5. Repair FK fixture construction and verify transaction/rollback behavior using named tests, including `PRAGMA foreign_key_check`.
6. Upgrade vulnerable dependencies and run build, backend, and E2E regression gates.

**Exit gate:** all baseline tests green or every remaining failure explicitly accepted with a tracked owner; no cascading fixture failures; build and audit policy pass.

### Stage 2 — Broader project improvements

1. Add lint/format, frontend unit/component tests, coverage reporting, accessibility automation, mobile Playwright profiles, and deterministic CI.
2. Harden auth/session, CORS, headers, rate limits, validation, centralized error envelopes, and secret validation.
3. Add migration ledger, backup/restore tooling and drill, readiness checks, correlation IDs, redacted structured logs, and operational alerts.
4. Consolidate frontend API handling, enforce bundle budgets, and complete WCAG/mobile UX review on real devices.
5. Correct README/runbooks and document architecture, security assumptions, data lifecycle, rollback, and incident response.

**Exit gate:** approved security/data review, green CI from a clean checkout, successful restore drill, and accepted mobile/accessibility evidence.

### Stage 3 — GitHub publication and deployment

1. Decide whether to recover prior history from a trusted source or initialize a new repository; do not fabricate history.
2. Inventory files, exclude `.env`, databases, photos, build outputs, and local tooling state; scan commit candidates for secrets and licenses.
3. Add CI, dependency review, branch protection plan, release/version policy, deployment documentation, and rollback procedure.
4. Verify ownership/access to `https://github.com/wilkinbarban/Trindade`, repository visibility, default branch, and remote policy without mutating GitHub until approved.
5. Rehearse Docker deployment on a clean host with HTTPS, domain/base-path routing, health checks, persistent volume, backup/restore, and rollback.
6. After explicit approval, publish reviewable auto-chained slices under the 400-line budget and deploy an immutable release.

**Exit gate:** remote and production mutations remain prohibited until user approval of proposal/design/tasks and all previous gates.

### Stage 4 — Installable Android application

1. Stabilize a canonical HTTPS origin and same-origin API/public-photo behavior.
2. Add web installability foundations (manifest, icons, service worker strategy, offline/error shell) even if Capacitor is selected.
3. Build a Capacitor shell around the production SPA, using native plugins only where justified: camera/photo selection, file export/share, network status, secure credential storage, and Android back/deep-link handling.
4. Define online/offline scope explicitly. “Fully operate” currently requires the hosted Fastify/SQLite backend; full offline mutation would require a separate synchronization architecture and conflict model and is not recommended initially.
5. Validate permissions, uploads, downloads/PDFs, WhatsApp sharing, session expiry, low-connectivity behavior, accessibility, and supported Android versions on emulator and physical devices.
6. Produce signed AAB/APK through protected CI secrets, Play integrity/privacy disclosures as applicable, release notes, update/rollback policy, and downloadable distribution path.

## Android Approach Comparison

| Approach | Fit with actual architecture | Advantages | Costs/limitations | Effort |
|---|---|---|---|---|
| Responsive PWA | High after adding manifest/service worker | Lowest cost, one web codebase, instant updates, home-screen install | Current app is not a PWA; browser storage/share/file limits; APK/Play distribution generally needs TWA; no backend offline capability | Low–Medium |
| Trusted Web Activity | Medium–High after production PWA + verified HTTPS/domain | Small Play-distributed shell, web remains source of truth | Requires Digital Asset Links and strong PWA quality; browser-dependent; limited native integrations and secure-storage control | Medium |
| **Capacitor** | **Highest** for the current React/Vite SPA | Reuses UI/business flows, produces APK/AAB, supports camera/files/share/network/secure storage, allows incremental native code | Native project/release maintenance; plugin lifecycle; localStorage auth should be redesigned; still requires hosted backend | **Medium** |
| React Native | Low–Medium | Strong native UX/ecosystem and offline potential | Rewrites the frontend and duplicates behavior/tests; web components are not reusable directly; large divergence risk | High |
| Native Kotlin/Compose | Low for current architecture | Maximum Android integration, performance, and lifecycle control | Full UI/client rewrite, separate team skillset and release train; highest parity burden | Very High |

### Android Recommendation

Choose **Capacitor after the hardened web deployment is accepted**. It is the smallest architecture change that yields a genuinely downloadable APK/AAB while preserving the existing React/Vite investment and enabling the native capabilities needed for photos, exports, and WhatsApp-oriented sharing. Build PWA fundamentals first because they improve resilience and keep TWA as a lower-maintenance fallback. Do not promise full offline operation: the current authoritative SQLite database lives behind Fastify, so offline writes would create a new distributed synchronization system.

## Deployment Readiness

**Current verdict: Not ready.** Prerequisites for the specified GitHub repository and production deployment are:

- valid Git repository/history decision and clean secret/data inventory;
- user-confirmed repository ownership, visibility, default branch, and protection rules;
- green clean-checkout CI for build, backend tests, frontend tests/E2E, lint, audit, and image checks;
- fixed production dependency advisories and documented security acceptance;
- immutable container images, non-root runtime where feasible, health/readiness checks, and portable network configuration;
- HTTPS/domain/base-path verification for `/trindade/`, `/api`, `/p`, BrowserRouter, and public links;
- SQLite and photo backup/restore procedure with tested rollback and ownership/permissions;
- secret injection/rotation policy, production bootstrap account procedure, CORS allowlist, and rate limiting;
- observability, retention, alerting, incident response, privacy/data handling, and release runbooks.

## Delivery and Review Strategy

The work will exceed the 400 changed-line review budget. Use `auto-chain` and slice by root cluster, not by individual failing test. Suggested chain: (1) fixture/credential truth, (2) seed/migration/data integrity, (3) residual behavior defects, (4) security/dependencies, (5) quality/CI, (6) operability/deployment, (7) accessibility/mobile web, (8) GitHub publication, (9) PWA foundation, (10) Capacitor Android shell. Each slice needs its own named tests, rollback boundary, and clean diff.

## Risks

- Repairing tests to match the wrong production truth could institutionalize a data or credential defect; operational ownership must resolve contracts first.
- Ad-hoc migration changes can irreversibly damage SQLite data without backup and upgrade fixtures.
- Git reconstruction can accidentally publish secrets, databases, photos, or fabricated history.
- Packaging the current web app would turn known security/reliability defects into a harder-to-update installed client.
- Android offline requirements could multiply scope into synchronization, conflict resolution, encryption, and migration work.
- Public URL/base-path differences can break SPA navigation, API calls, public photo links, PWA scope, TWA verification, and Android deep links.

## Ready for Proposal

**Yes, conditionally — ready for a staged proposal, not implementation.** The proposal must preserve the required sequence and define explicit quality gates between stages. The first proposal scope should be baseline restoration and data/auth truth alignment; deployment and Android tasks must remain gated future stages rather than parallel implementation.
