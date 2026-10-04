# Android worker enrollment with administrator approval

Status: in progress
Owner: Android–Web parity follow-up (`odd/tasks/android-web-parity.md`)

Workers can request an account from Android login without an existing session. A request never grants operational access: the backend creates only a `Trabalhador` (`role_id=2`) with `is_active=0`; an administrator activates the account through the existing Users panel before normal login succeeds.

## Decisions and boundaries

| Concern | Decision |
| --- | --- |
| Duplicate username | Return the same neutral, conditional confirmation as a successful request. Only UNIQUE constraint violation of `users.username` is treated as duplicate; FK, NOT NULL, and schema failures fail closed with a server error (500) rather than neutral success. |
| Authority | Request excludes role and active status. Backend hardcodes role 2 and inactive state; no access/refresh token in the response. Existing inactive login and session gates remain unchanged. |
| Abuse protection | Bound username/password/display name before hashing (password minimum 8 characters, matching setup and password-change); enforce route-wide and normalized-username limits before CPU-intensive work. Do not trust arbitrary `X-Forwarded-For` or enable wildcard `trustProxy`: current two-proxy chain appends spoofable values. Avoid a global limiter that permits unchecked concurrent bcrypt bursts. |
| Bootstrap protection | Atomic immediate transaction inside `registerWorkerUser` guarantees that the first worker cannot preempt administrator setup under concurrent requests, while preserving the fast route check. |
| Audit logging | Registration success attempts an audit log; persistence failure is logged as a warning to the route logger without failing registration or leaking into the public neutral response. |
| Approval | Existing administrator Users screen can activate inactive accounts. It must present pending enrollment clearly without treating all intentionally inactive users as unapproved; if that distinction is not encoded, avoid a false pending badge. |
| Scope | Backend, OpenAPI artifact, generated Android contract, Android login/registration UI and tests. No production deployment, account creation, Git staging/commit/push, or Windows emulator validation in this unit. |

## Reviewable work units

- [x] Backend endpoint/schema, bounded admission controls, duplicate-neutral response, inactive account and no-token tests. Verify Node 24 backend suite.
- [x] OpenAPI registration and artifact generation. Verify route coverage, freshness and generated Android types.
- [x] Android login link, request form and confirmation, role-safe API/repository/UI tests. Verify pinned Android SDK suite in an isolated snapshot.
- [x] Independent security check: duplicate and race outcomes, rate-limit exhaustion, forged forwarding header, no token before approval, existing administrator approval. Android registration upper bounds and HTTP 400 localization were corrected and independently verified. No live production writes.
- [ ] Pixel_8 registration/approval acceptance: a disposable administrator-created worker account was used to verify worker login and read-only screens, then deleted via the Users panel; this did **not** exercise public registration or activation. The current production-HTTPS debug validation APK is recorded in `odd/tasks/android-web-parity.md`; a read-only GET to `/api/auth/register` returned HTTP 404 (not a conclusive POST route probe); the route is absent from committed HEAD, and its production availability is unverified. The user selected no backend deployment in this cycle. Do not treat a compiled APK as deployment of the backend.

### Work Unit 3 Implementation and Verification Record

- **Login Screen Link:** Added 'Solicitar cadastro' link on `LoginScreen`, routing to `RegisterScreen` via `MainActivity` unauthenticated navigation.
- **Worker Registration Form:** Implemented `RegisterScreen` and `RegisterViewModel` with `username`, `display_name`, `password`, and `confirm_password` fields, clear role notice stating only `Trabalhador` access is requested and administrator approval is required, client-side validation (non-blank, username ≤50, display name ≤100, password 8–72 UTF-16 units and ≤72 UTF-8 bytes, password confirmation match), and secure password entry (`PasswordVisualTransformation`, `KeyboardType.Password`).
- **Network and Session Safety:** `AuthRepository.register` invokes `AuthApi.register` via `POST api/auth/register` with `RegisterRequest`. Wire tests confirm payload excludes `role`, `role_id`, and `is_active`. Registration never stores tokens, never alters `TokenStore`, and never increments `sessionGeneration`; returning to login screen preserves unauthenticated state with no session.
- **Response and Error Handling:** Displays the server's neutral conditional response verbatim for new or duplicate account requests; localizes the backend's generic English HTTP 400 `Invalid input` into Portuguese while preserving meaningful server messages; gracefully surfaces 409 bootstrap required, 429 rate limit (with `Retry-After` seconds formatted when present), and transport causes (`Timeout`, `Tls`, `UnreadableBody`, `NoRoute`, `Unknown`). Passwords are immediately cleared from memory upon success.
- **Verification Evidence (Pinned Android SDK in isolated `/tmp` snapshot):**
  - `:app:assembleDebug`: BUILD SUCCESSFUL in 3m 26s, packaging debug APK.
  - Focused unit tests: 59 tests passed, 0 failures (`RegisterViewModelTest` 15, `AuthRepositoryTest` 9, `RegisterApiPayloadTest` 5, `RegisterScreenTest` 5, `ContractCoverageTest` 6, `MainActivityNavigationTest` 19).
  - After the UX correction, independent pinned-SDK isolated-snapshot `:app:testDebugUnitTest :app:assembleDebug`: 642 tests across 71 suites passed, 0 failures, 0 errors, 0 skipped; APK assembled successfully in 12m 44s. Focused `RegisterViewModelTest` 23/23 and `AuthRepositoryTest` 12/12 passed. Previous baseline was 631/631.
- **Repository Note:** Because Retrofit delegates default interface methods to Java reflection rather than routing HTTP requests, `AuthApi.register` must remain abstract. Test fakes implementing `AuthApi` (`class FakeAuthApi : AuthApi`) require stubbing `register`.

Delivery strategy: reviewable work units; no commit or publication until explicitly authorized. Strict TDD was not activated; existing test runners are Node 24 `npm test --workspace=packages/backend` and pinned Android SDK `:app:testDebugUnitTest`.
