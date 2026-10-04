# ODD Feature: Security, implementation quality, and usability remediation

**Status:** SEC-01–SEC-04 authorized for implementation before Android v0.3.0 and final API deployment; other findings remain planned. The original planning-only scope below describes the 2026-09-22 audit, not the current authorization.

**Created:** 2026-09-22  
**Local artifact:** `odd/tasks/security-quality-remediation.md`  
**Engram mirror topic:** `odd/security-quality-remediation/tasks` — pending a uniquely resolvable runtime session  
**Delivery strategy:** `ask-on-risk`; forecast is about 1,600 authored source/test/config lines, so obtain one chain-delivery decision before the first implementation commit.

## Objective

Remove the validated security weaknesses and the audited implementation, interaction-design, and accessibility defects without weakening the existing server-authoritative authorization model or the Android/web parity work already in progress.

This plan turns the audit into small, independently verifiable work units. It deliberately separates **confirmed defects** from **product and deployment decisions that source code cannot answer**.

## Evidence and audit boundary

Primary security evidence: the partial static review at
`/tmp/codex-security-scans-3TJfol/Trindade/8aa5ec1db02e146516dcc1d26544e62d685ca173_20260922T234912Z_miv9yie1/report.md`, revision `8aa5ec1`.

The review validated six security findings (one high, four medium, one low), and found quality/usability problems in both clients. It did **not** execute the application or tests, inspect live infrastructure, proxy settings, TLS, secrets, filesystem ACLs, or exhaustively review all 562 indexed source paths. Completion therefore requires fresh runtime and deployment evidence; a green static review alone is not acceptance.

### Confirmed security findings

| ID | Priority | Finding | Evidence location |
| --- | --- | --- | --- |
| SEC-01 | P0 | Any anonymous caller can win first-administrator setup while the users table is empty. | `packages/backend/src/modules/auth/bootstrap.routes.ts`, `packages/backend/src/routes.ts` |
| SEC-02 | P0 | Login, refresh, and setup have no observed application-level abuse budget; setup hashes before its final setup-state check. | `packages/backend/src/server.ts`, `packages/backend/src/modules/auth/auth.routes.ts`, `bootstrap.routes.ts` |
| SEC-03 | P0 | Administrative password reset leaves existing refresh sessions valid. | `packages/backend/src/modules/admin/admin.service.ts`, `packages/backend/src/modules/auth/auth.sessions.service.ts` |
| SEC-04 | P0 | Role changes leave the role embedded in an existing access JWT effective for up to its 15-minute lifetime. | `packages/backend/src/modules/admin/admin.service.ts`, `packages/backend/src/modules/auth/auth.middleware.ts` |
| SEC-05 | P1 | Admin create/update accepts four-character passwords, unlike other account flows. | `packages/backend/src/modules/admin/admin.schema.ts` |
| SEC-06 | P1 | Login work differs for unknown/inactive versus active accounts, exposing an account-state timing signal. | `packages/backend/src/modules/auth/auth.routes.ts` |

### Confirmed client quality and usability findings

| ID | Priority | Finding | Primary location |
| --- | --- | --- | --- |
| WEB-01 | P1 | Worker-facing report/loading history UI exposes destructive controls that the backend rejects; errors are not handled coherently. | `packages/frontend/src/pages/ReportHistoryPage.tsx`, `LoadingHistoryPage.tsx`, `history-permissions.ts` |
| WEB-02 | P1 | A failed setup-status request leaves `SetupGate` in an indefinite loading state. | `packages/frontend/src/App.tsx` |
| WEB-03 | P1 | Export previews are visual overlays rather than accessible dialogs: no dialog semantics, focus management, Escape handling, or focus restoration. | `components/reports/ExportPreview.tsx`, `components/loading/ScheduleExportView.tsx` |
| WEB-04 | P2 | History filters, icon-only actions, and the loading spinner lack adequate labels/status semantics. | history pages and `components/ui/loading-spinner.tsx` |
| WEB-05 | P3 | Setup content bypasses the app translation system. | `packages/frontend/src/pages/SetupPage.tsx` |
| AND-01 | P1 | Changing the loading date shows old schedules and actionable controls under the new date until refresh completes. | `packages/android/app/src/main/java/com/trindade/app/loading/LoadingViewModel.kt`, `LoadingScreen.kt` |
| AND-02 | P1 | The report generator gives an actionable message for only one missing prerequisite; other missing data can look like an empty/inert form. | `report/ReportGeneratorViewModel.kt`, `ReportGeneratorScreen.kt` |
| AND-03 | P2 | Loading-screen radio labels are not selectable and do not offer the full row semantics/touch target used elsewhere. | `loading/LoadingScreen.kt` |
| AND-04 | P1 | Android parity Slice C1 (task types) is incomplete; its fake API behavior and ViewModel expectations must be reconciled before a screen is trusted. | `admin/TasksViewModel.kt`, `admin/FakeAdminApi.kt`, `TasksViewModelTest.kt`, `odd/tasks/android-web-parity.md` |

## Scope and non-goals

### In scope for the future implementation feature

- Backend account bootstrap, authentication-abuse resistance, password policy, session revocation, and authorization freshness.
- Public-photo capability policy and any selected technical remediation.
- Production-facing CORS, proxy, transport, secret, storage, and external-translation verification.
- The listed web reliability, interaction-design, accessibility, and localization defects.
- The listed Android state-integrity, prerequisite-feedback, selection-accessibility, and Slice C1 completion defects.
- Automated and manual proof for each behavior affected.

### Explicitly not performed by this planning artifact

- No source, dependency, schema, migration, test, configuration, environment, or remote-service change.
- No production access, secret inspection, deployment, push, pull request, release, or merge.
- No claim that unreviewed paths are free of defects.
- No extension of Android parity beyond the current C1 task-type work; C2–C6, audit, and final parity verification stay owned by `odd/tasks/android-web-parity.md` unless separately authorized.

## Decisions required before security implementation

These decisions are real security/product choices, not implementation details to guess. The parent must resolve them before the dependent task is started.

| Decision | Recommended default | Why it matters | Blocks |
| --- | --- | --- | --- |
| First administrator provisioning | **Selected 2026-09-25:** provision out of band during deployment; disable anonymous HTTP creation and update the web setup state to explain operator provisioning. | An anonymous race for Administrator is unacceptable. | SEC-01, SEC-02 |
| Role-change freshness | **Selected 2026-09-25:** immediate invalidation for role/password changes; at cutover revoke all pre-existing production access and refresh sessions, including administrator sessions. | A documented 15-minute privileged window is still a conscious risk acceptance, not a fix. | SEC-03, SEC-04 |
| Password standard | One shared policy, at least a long passphrase-style minimum; decide whether breached-password screening is local or an approved external service. | Avoids weak admin-created credentials and unapproved password-data egress. | SEC-05 |
| Public-photo links | Decide whether photos are confidential; if yes, require expiration, revocation/rotation, ≥128-bit opaque capabilities, and request throttling. | Current six-byte token has only 48 bits and no observed lifecycle policy; source alone cannot establish intended confidentiality. | SEC-07 |
| Browser / deployment policy | Name approved production origins, trusted proxy topology, host ACLs, TLS termination, and photo-storage ACL ownership. | `origin: true` and local source cannot prove the deployed perimeter. | OPS-01 |
| Translation data | Approve, prohibit, or replace external translation providers for operational text. | Translation can send user-entered data outside the system. | OPS-02 |

## Delivery model and quality gates

- **Implementation route:** every task below is `delegated direct` when started because it spans four or more meaningful files, crosses a trust boundary, or needs dedicated test evidence. The trigger evidence is recorded per task below. This planning-only task does not delegate a writer or change source.
- **TDD status:** not established by an explicit repository/session setting during this audit. Before the first source write, the implementer must record the effective mode and exact runners. If TDD is enabled, capture RED → GREEN → REFACTOR evidence; otherwise run the stated functional checks after each task.
- **Work units:** one task ID per coherent behavior; no checkbox is completed until its checks have been observed. Each implementation task receives a conventional work-unit commit with its tests and documentation.
- **Review and delivery:** before the first implementation commit, resolve the forecasted >400-line delivery strategy. Preserve the current receipt-driven-development setting; do not enable, disable, or bypass it from this document.
- **Migration safety:** credential/session schema changes require an upgrade and rollback plan that preserves valid accounts, logs no secrets, and states what happens to already-issued access and refresh tokens.
- **Client authority:** UI role visibility is a convenience only. The API remains the authorization authority; clients must render the same affordances the server will accept and handle a server refusal safely.

## Ordered remediation backlog

### Phase 0 — lock policies and establish reproducible baselines

- [ ] **DEC-01 — Record the six unresolved security and deployment decisions.**
  - **Outcome:** a short approved decision record for bootstrap, immediate role/session invalidation, password policy, photo capability privacy, CORS/proxy/TLS/storage policy, and translation-data policy.
  - **Implementation implications:** no fallback silently permits anonymous provisioning, stale privilege, or third-party data egress.
  - **Checks:** decision owner and rationale recorded; each dependent task explicitly references the selected rule.
  - **Route:** delegated direct; it spans product, backend, mobile/web, and deployment boundaries.

- [ ] **BASE-01 — Establish a clean, reproducible validation baseline.**
  - **Outcome:** record the exact Node/backend/frontend, browser E2E, Android JVM/Compose, and migration runners that work against a clean checkout before remediation.
  - **Checks:** preserve command output, versions, test counts, failures/skips, and known environmental limits; do not convert unavailable checks into a pass.
  - **Route:** delegated direct; the validation surfaces exceed four files and two runtimes.

### Phase 1 — close public-account takeover and authentication-abuse paths

- [ ] **SEC-01 — Replace anonymous first-administrator provisioning.**
  - **Targets:** `packages/backend/src/modules/auth/bootstrap.routes.ts`, `bootstrap.service.ts` if present, route registration, configuration/operations documentation, and focused route tests.
  - **Design:** implement the DEC-01 bootstrap model. Prefer deployment-bound provisioning; if a one-time secret is selected, compare it safely, keep it outside ordinary client configuration/logs, consume/disable it atomically, and return no setup-state detail that helps an attacker.
  - **Acceptance:** an unauthenticated caller cannot create an administrator without the approved deployment-only capability; concurrent setup attempts leave exactly one valid result; a completed setup cannot be replayed.
  - **Checks:** negative route tests, race/concurrency test, config-missing behavior, no secret in response/log assertion, operations runbook review.
  - **Route:** delegated direct — public routes, configuration, operations, and tests are all affected.

- [ ] **SEC-02 — Bound authentication and bootstrap abuse.**
  - **Targets:** `packages/backend/src/server.ts`, auth/bootstrap routes, deployment proxy configuration/runbook, and integration tests.
  - **Design:** establish layered rate limits for login, refresh, and setup. Key login budgets by both normalized account identifier and validated client network identity; define proxy trust precisely to avoid spoofed forwarding headers; use consistent `429` behavior and avoid logging credentials. Add only a non-authoritative early setup-complete read before bcrypt while retaining the transactional final check.
  - **Acceptance:** repeated credential and setup requests are bounded without blocking normal distributed users by default; spoofed client-IP headers cannot select the limiter key; terminal setup does not spend bcrypt unnecessarily.
  - **Checks:** request-budget and window tests, `429` contract tests, trusted-proxy tests, setup-complete fast-path test, load/abuse test in a non-production environment.
  - **Route:** delegated direct — runtime middleware, public routes, infrastructure assumptions, and tests.

- [ ] **SEC-06 — Remove the login account-state timing branch.**
  - **Targets:** `packages/backend/src/modules/auth/auth.routes.ts` and focused authentication tests.
  - **Design:** use a fixed dummy bcrypt hash for unknown and inactive accounts, keep a single generic failure response, and run the work before the identical failure path; rate limiting from SEC-02 is a prerequisite, not a substitute.
  - **Acceptance:** unknown, inactive, and wrong-password login outcomes have the same public message/status and exercise bcrypt comparison; no user or password value appears in diagnostics.
  - **Checks:** unit/integration branch tests plus a bounded timing-regression harness that compares distributions rather than asserting exact elapsed milliseconds.
  - **Route:** delegated direct — auth flow, security constants, and tests.

### Phase 2 — make identity changes take effect immediately and consistently

- [ ] **SEC-03 — Revoke renewable sessions on administrative password reset.**
  - **Targets:** `packages/backend/src/modules/admin/admin.routes.ts`, `admin.service.ts`, `auth.sessions.service.ts`, database transaction layer/migration if needed, and tests.
  - **Design:** perform password replacement and revocation of every active refresh-session family for that user in one atomic security operation. Align administrator reset with the existing self-service change behavior and define failure atomicity.
  - **Acceptance:** a pre-reset refresh token cannot rotate after a successful administrator reset; no partial state leaves the old password or an active family ambiguously valid.
  - **Checks:** reset → refresh rejection, multiple-device/family revocation, transaction-failure rollback, self-service regression, audit event assertions without secrets.
  - **Route:** delegated direct — admin, auth sessions, persistence, and tests.

- [ ] **SEC-04 — Enforce authorization freshness after role and credential changes.**
  - **Targets:** `packages/backend/src/modules/auth/auth.middleware.ts`, JWT issuance/refresh code, admin user update service, session persistence/migration if selected, and client session-recovery paths.
  - **Design:** implement DEC-01’s immediate-invalidation rule with a server-authoritative credential/session version (or an equivalent authoritative role lookup for privileged requests). Role change, password reset, deactivation, and refresh must share one invalidation model. Do not rely only on shortening the JWT lifetime.
  - **Acceptance:** a pre-change access token is rejected or loses the revoked privilege immediately according to the approved policy; refresh cannot mint a replacement; clients receive a safe re-authentication outcome.
  - **Checks:** administrator demotion with a still-unexpired JWT, password reset with access and refresh tokens, deactivation, stale-refresh attempt, token-version migration/rollback, Android/web sign-out behavior.
  - **Route:** delegated direct — backend trust boundary, schema/migration, and both clients.

- [ ] **SEC-05 — Centralize and strengthen password validation.**
  - **Targets:** auth bootstrap/self-service schemas, `packages/backend/src/modules/admin/admin.schema.ts`, shared policy module, API contract, web/Android validation copy, and tests.
  - **Design:** one policy owns create, reset, bootstrap, and self-service validation. Apply the approved passphrase and breached-password policy consistently; client checks improve feedback but server validation is authoritative.
  - **Acceptance:** no account path accepts the prior four-character password; all paths return the same policy contract; no password is retained in state, logs, or audit records.
  - **Checks:** table-driven cross-flow tests, contract/schema generation check, localized client feedback tests, policy edge cases, migration behavior for existing weak passwords (do not silently lock users out).
  - **Route:** delegated direct — duplicated schemas, generated contract, both clients, and tests.

### Phase 3 — set and enforce public-data and deployment boundaries

- [ ] **SEC-07 — Define and implement the public-photo capability lifecycle.**
  - **Targets:** `packages/backend/src/modules/reports/reports.lifecycle.service.ts`, public photo route/controller, persistence/migration if lifecycle state is needed, web/Android link consumers, and tests.
  - **Design:** implement only the confidentiality model selected in DEC-01. For confidential photos, issue opaque ≥128-bit capabilities, scope them to one photo, support expiry and revocation/rotation, throttle public retrieval, and plan safe handling of legacy URLs. If public permanence is selected instead, document the explicit non-confidential classification and still use non-enumerable capabilities.
  - **Acceptance:** capabilities cannot be feasibly guessed; expired/revoked capabilities fail consistently; rotating one link cannot reveal another photo; legacy behavior has an explicit migration window.
  - **Checks:** entropy/format test, cross-photo denial, expiry/revocation tests, rate-limit test, legacy migration test, access-log privacy review.
  - **Route:** delegated direct — public route, storage lifecycle, two clients, and migrations.

- [ ] **OPS-01 — Verify and harden production request boundaries.**
  - **Targets:** backend server configuration, deployment manifests/reverse-proxy configuration when authorized, environment template, and operations runbook.
  - **Design:** replace permissive production CORS behavior with the approved explicit origin policy; define credentials/header/method rules; configure trusted proxies only for known hops; verify TLS termination, security headers as appropriate, host/firewall exposure, photo storage ownership/ACLs, and secret injection/rotation.
  - **Acceptance:** production configuration is explicit, reproducible, and least-privilege; development remains usable without broadening production policy.
  - **Checks:** configuration tests, pre-production origin/preflight probe, proxy-header spoofing probe, TLS/headers scan, file-permission inspection, secret-leak scan, deployment rollback test.
  - **Route:** delegated direct — application configuration, deployment material, and operations evidence. Remote verification requires separate explicit authorization.

- [ ] **OPS-02 — Decide and enforce translation-data handling.**
  - **Targets:** translation provider configuration and the affected UI/service call sites.
  - **Design:** use only the provider/data classification approved in DEC-01; minimize sent text, offer an approved local/opt-out alternative when required, and document retention/processor terms.
  - **Acceptance:** operators and maintainers can identify what data leaves Trindade, to whom, and under which policy; forbidden operational data cannot be sent by default.
  - **Checks:** configuration tests, request payload inspection in a controlled environment, privacy review, failure fallback behavior.
  - **Route:** delegated direct — external boundary, policy, and client behavior.

### Phase 4 — restore web behavior, accessible interaction, and design consistency

- [ ] **WEB-01 — Align history action affordances with server permissions.**
  - **Targets:** `packages/frontend/src/lib/history-permissions.ts`, `ReportHistoryPage.tsx`, `LoadingHistoryPage.tsx`, relevant error presentation, and role-matrix tests.
  - **Design:** derive visible actions from the server’s actual delete policy; do not show a worker a destructive action that will deterministically fail. Keep server guards unchanged, surface unexpected `403`/network failures accessibly, and preserve non-destructive history access.
  - **Acceptance:** Admin and Trabalhador see only the actions their role can use; a server refusal is actionable and does not create an unhandled rejection or optimistic data loss.
  - **Checks:** role-render tests, delete-success/error tests, `403` regression test, browser keyboard/screen-reader smoke test.
  - **Route:** delegated direct — shared policy, two pages, errors, and tests.

- [ ] **WEB-02 — Make `SetupGate` recoverable.**
  - **Targets:** `packages/frontend/src/App.tsx`, setup gate component/state if extracted, localization, and component/E2E tests.
  - **Design:** model `checking`, `setup-required`, `ready`, and `failed` states explicitly; a failure exposes a retry and a support-safe error, rather than a permanent spinner. Do not treat unknown status as authorization to enter the app.
  - **Acceptance:** network/server failure is visible, retryable, keyboard reachable, and cannot accidentally bypass setup; success after retry reaches the correct screen.
  - **Checks:** rejected request, retry success, retry failure, no infinite loading, status announcement, E2E route recovery.
  - **Route:** delegated direct — app bootstrap, state/UI, localization, and E2E tests.

- [ ] **WEB-03 — Replace export overlays with one accessible dialog primitive.**
  - **Targets:** `components/reports/ExportPreview.tsx`, `components/loading/ScheduleExportView.tsx`, shared UI primitive, and tests.
  - **Design:** use a semantic modal dialog with accessible name/description, initial focus, focus trap, Escape/close behavior, focus restoration, and a non-modal print/export path that remains usable.
  - **Acceptance:** both export flows have identical keyboard and assistive-technology behavior without focus escaping behind the overlay.
  - **Checks:** role/name assertions, Tab/Shift+Tab traversal, Escape, close-focus restoration, screen-reader manual pass, visual regression for desktop/mobile sizes.
  - **Route:** delegated direct — shared primitive, two consumers, and interaction tests.

- [ ] **WEB-04 — Complete history and feedback semantics.**
  - **Targets:** history filter controls, icon-only view/deactivate/delete controls, `components/ui/loading-spinner.tsx`, and test helpers.
  - **Design:** give every filter a programmatic label, every icon action an accessible name and confirmation context, and each loading/error region an appropriate live/status semantic without noisy duplicate announcements.
  - **Acceptance:** no essential control depends on sight or icon recognition; loading and error feedback is perceivable to keyboard and screen-reader users.
  - **Checks:** accessibility-tree assertions, keyboard-only path, automated accessibility scan, manual reader pass in Portuguese.
  - **Route:** delegated direct — two pages, shared feedback, tests.

- [ ] **WEB-05 — Bring setup text under the translation contract.**
  - **Targets:** `packages/frontend/src/pages/SetupPage.tsx`, locale catalogues, and localization tests.
  - **Design:** replace hard-coded UI copy with existing translation keys and preserve the selected locale through setup/error/retry states.
  - **Acceptance:** setup renders no fallback hard-coded Portuguese where a translation key is required; all supported locales have intentional text.
  - **Checks:** catalogue completeness and language-switch tests.
  - **Route:** delegated direct — page, locale files, tests.

### Phase 5 — prevent Android state mistakes and finish the bounded C1 surface safely

- [ ] **AND-01 — Make loading-date transitions transactional in the UI.**
  - **Targets:** `packages/android/app/src/main/java/com/trindade/app/loading/LoadingViewModel.kt`, `LoadingScreen.kt`, state models, and JVM/Compose tests.
  - **Design:** on a new date request, clear or quarantine prior-date schedules, slots, drivers, and vehicles; render a date-scoped loading state; disable/hide mutations until the matching response arrives; retain request-token protection so a late prior response cannot repopulate the new date.
  - **Acceptance:** the selected date can never display actionable data from another date; a failed refresh has a clear retry/error state rather than stale mutation controls.
  - **Checks:** exact date-change sequence test, delayed/out-of-order response test, failure/retry test, Compose assertion that old rows/actions disappear or are disabled.
  - **Route:** delegated direct — ViewModel, screen, models, tests.

- [ ] **AND-02 — Model report-generator prerequisites as explicit outcomes.**
  - **Targets:** `report/ReportGeneratorViewModel.kt`, `ReportGeneratorScreen.kt`, state models, strings, and tests.
  - **Design:** replace implicit empty collections with typed loading, ready, partial-configuration, and failed states for shift, categories, and product offers. Tell the operator exactly what is missing and offer the valid recovery path without losing legitimate form input.
  - **Acceptance:** every prerequisite failure is distinguishable, readable, retryable when applicable, and cannot yield a misleading usable-looking empty form.
  - **Checks:** one test per missing/failed prerequisite, retry, restored input, Compose semantics and Portuguese copy test.
  - **Route:** delegated direct — ViewModel, screen, models/strings, tests.

- [ ] **AND-03 — Give loading selection rows full selectable semantics.**
  - **Targets:** `loading/LoadingScreen.kt`, reusable selection-row component if extracted, and Compose tests.
  - **Design:** make the full label/row the control, match `LoadingEditScreen` semantics where appropriate, ensure a minimum touch target and selected state, and avoid nested conflicting click targets.
  - **Acceptance:** tapping label, radio, or row selects exactly one option and exposes correct role/state semantics to assistive technology.
  - **Checks:** Compose click tests for all hit areas, selected-state semantics, keyboard/accessibility action test, visual touch-target review.
  - **Route:** delegated direct — screen/component/tests.

- [ ] **AND-04 — Reconcile C1 task-type test doubles before rendering a screen.**
  - **Targets:** `admin/TasksViewModel.kt`, `admin/FakeAdminApi.kt`, `admin/TasksViewModelTest.kt`, repository/API tests, and `odd/tasks/android-web-parity.md`.
  - **Design:** determine whether create/delete should reload the canonical server list or update local state, then make the fake API model that contract honestly. The current fake list must not remain immutable while tests expect post-mutation counts. Do not hide the disagreement by weakening assertions.
  - **Acceptance:** create, update, activate/deactivate, and delete tests represent the API semantics and distinguish reload failure from a successful mutation; C1-1/C1-2 evidence is revalidated.
  - **Checks:** test double state-transition tests, ViewModel sequence tests, network/contract regression, documented result in the existing parity task file.
  - **Route:** delegated direct — fake, ViewModel, repository/API, tests, ODD tracker.

- [ ] **AND-05 — Complete C1 task-type Compose UI and navigation.**
  - **Depends on:** AND-04.
  - **Targets:** new `admin/TasksScreen.kt`, `TasksScreenTest.kt`, navigation in `MainActivity.kt`, strings, and `odd/tasks/android-web-parity.md`.
  - **Design:** render list, inline create/edit form, active toggle, delete confirmation/error, and loading/empty/retry states from the reconciled ViewModel. Use the server’s role model: administrator gets full permitted actions; trabalhador gets only the exact catalog actions the backend accepts, never a client-invented ownership rule. Add an accessible `CATALOG_TASKS` route.
  - **Acceptance:** the route is reachable for entitled roles, unavailable to others as a convenience, and every rendered mutation has a tested server-compatible outcome; server denials remain visible and safe.
  - **Checks:** Compose rendering/action/role tests, navigation test, ViewModel integration, JVM suite, emulator/manual screen review, update of C1-3/C1-4 evidence only after observed proof.
  - **Route:** delegated direct — new screen, navigation, role logic, tests, parity tracker.

### Phase 6 — prove closure and avoid a paper-only fix

- [ ] **VERIFY-01 — Run the security and functional regression matrix.**
  - **Outcome:** execute the runners established in BASE-01 after each relevant work unit and at feature close: backend/auth integration, contract generation, frontend typecheck/build/E2E/accessibility, Android JVM/Compose, and migration upgrade/rollback tests.
  - **Acceptance:** all required checks are green, or every unavailable/failed/skipped check has a reason, owner, and next action. No historical test count is presented as current proof.
  - **Route:** delegated direct — multi-runtime verification.

- [ ] **VERIFY-02 — Perform targeted manual and deployment verification.**
  - **Outcome:** validate keyboard/screen-reader flows, mobile date/error transitions, login abuse controls, setup provisioning, token invalidation, CORS/proxy/TLS/storage policy, photo capability lifecycle, and translation-data policy in an authorized non-production environment before release.
  - **Acceptance:** evidence is attached to the relevant work units; production deployment remains separately authorized.
  - **Route:** delegated direct — cross-boundary manual evidence. Remote work requires explicit destination, operation, and credential/session authorization.

- [ ] **CLOSE-01 — Reconcile audit evidence and close only verified items.**
  - **Outcome:** update this document and `odd/tasks/android-web-parity.md` with commit identifiers, reviewer/verification evidence, residual risks, and reopened items. Mirror the complete current document in Engram.
  - **Acceptance:** all six validated security findings and all nine client findings are either verified fixed or explicitly retained with an approved owner/risk decision; unreviewed-source and deployment limits remain disclosed.
  - **Route:** delegated direct — evidence spans the whole remediation feature.

## Sequencing and dependencies

1. **DEC-01 → BASE-01** establishes policies and trustworthy checks.
2. **SEC-01 and SEC-02** are first because they address anonymous administrator takeover and unbounded public credential work.
3. **SEC-03, SEC-04, SEC-05, and SEC-06** follow as one account-lifecycle safety slice; SEC-04 must not be released as “fixed” without proving refresh and client re-authentication behavior.
4. **SEC-07, OPS-01, OPS-02** follow selected policies and may not be inferred from source alone.
5. **WEB-01 → WEB-05** and **AND-01 → AND-03** can proceed in parallel once their shared regression lanes are established.
6. **AND-04 → AND-05** is a hard dependency; C1 UI/navigation must not make an inconsistent fake/test suite appear complete.
7. **VERIFY-01, VERIFY-02, CLOSE-01** close the work only after all preceding dependencies have evidence.

## Feature acceptance criteria

This feature is complete only when all conditions below are demonstrated:

1. An attacker cannot create the first administrator through an anonymous public race.
2. Public authentication/setup work has explicit, tested abuse limits and no avoidable account-state timing branch.
3. Password reset, role change, and deactivation obey the selected immediate-invalidation policy for both access and refresh credentials.
4. A single password policy is enforced by every account flow; existing-account migration behavior is deliberate.
5. Public-photo, CORS/proxy/TLS/storage, and translation-data policies are explicitly selected and verified at the appropriate deployment boundary.
6. Web history actions match server permission reality; setup failure, dialogs, feedback, and localization are usable with keyboard and assistive technology.
7. Android never presents a stale date as current actionable data; report-generation failures are intelligible; selection rows are usable; and C1 has honest API/VM/UI/navigation proof.
8. No task is marked complete on code review, compilation, or a checkbox alone: the stated automated and manual checks have observed evidence.

## Current progress and next authorized step

- [x] Audit findings were consolidated into this planning artifact.
- [ ] No remediation task has begun.
- [ ] Engram mirror has not yet been recorded because the runtime exposes multiple matching active sessions and no authoritative session ID.

**Next step:** approve the six DEC-01 policy choices and authorize the first implementation work unit. Until then, this document is a plan, not a claim of remediation.
