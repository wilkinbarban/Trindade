# ODD: WhatsApp Export & Loading Schedule Enhancements (Release v0.4.0)

## Objective
Implement native WhatsApp export for Reports and Loading Schedules, add an optional phone field for external fleteros with 1-on-1 direct WhatsApp dispatch (sharing the full general schedule), and introduce a 15-hour operational shift window in the Dashboard for active loading schedule visibility.

---

## Technical Constraints & Invariants
- **TDD Rigor**: Observe RED (failing test) -> GREEN (passing test) -> REFACTOR with test suite passing.
- **Work Units <= 400 lines**: Keep diffs focused, small, and cleanly reviewed.
- **Conventional Commits**: Format `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`.
- **Zero AI Attribution**: NEVER include `Co-Authored-By` or AI tool metadata in commits.
- **Trunk-Based**: Deliveries targeted to `main` with zero stale orphaned branches.
- **WhatsApp Strategy**:
  - Reports & General Schedules: Native share trigger / WhatsApp chat picker (Option A) with pre-populated formatted text.
  - Fleteros (1-on-1 Private): `https://wa.me/<phone>?text=<encoded_text>` sending the full general schedule text to registered phone numbers.
- **Dashboard Window**: A 15-hour rolling shift window (18:00 to ~09:00 next day) so morning shift operators can see and access the active loading schedule directly from the dashboard card.
- **Access Control**: Retain `row.user_id === actor.sub` so only the schedule creator can edit the schedule.

---

## Tasks Breakdown

### Phase 1: Database Migration & Driver Phone Field (Backend)
- [ ] **T01: Drivers table schema migration & models**:
  - Add optional `phone TEXT` column to `drivers` table in `schema.sql` (and migration file if versioned).
  - Update `CreateDriverSchema` and `UpdateDriverSchema` in `packages/backend/src/modules/admin/admin.schema.ts` and `loading.schema.ts` to accept optional `phone`.
  - Update driver queries in `loading.service.ts` and `admin.service.ts` to return and update `phone`.
  - Add backend unit and contract tests asserting driver operations with and without phone.

### Phase 2: Dashboard 15-Hour Active Schedule Window (Backend)
- [ ] **T02: Operational shift window logic for loading schedules in dashboard**:
  - Update `packages/backend/src/modules/dashboard/dashboard.routes.ts`: calculate active loading date based on time of day (before 10:00 AM considers today's schedule created yesterday; after 10:00 AM considers tomorrow's schedule).
  - Enrich dashboard summary payload with `activeScheduleDate` and `activeScheduleCount`.
  - Add unit tests in `dashboard.routes.test.ts` verifying time boundaries and counts.

### Phase 3: WhatsApp Export Endpoints & Contracts
- [ ] **T03: Verify and align WhatsApp formatted text payloads**:
  - Ensure `GET /loading/export?date=YYYY-MM-DD` and `GET /reports/:id/export` return complete formatted text ready for URL encoding / intent injection.
  - Update OpenAPI contracts in `packages/contracts/openapi.json` and generated client definitions.

### Phase 4: Frontend Web Implementation
- [ ] **T04: Driver phone management & WhatsApp export in Web**:
  - Update `DriverForm.tsx` to include an optional Phone field (with input formatting/cleaning).
  - Update `LoadingSchedulePage.tsx` and `LoadingEditPage.tsx`:
    - Add native WhatsApp export button for full schedule.
    - For fleteros with registered phone, display a direct 1-on-1 WhatsApp button (`https://wa.me/<phone>?text=...`) sending the full schedule.
  - Update `DashboardPage.tsx`: Enrich the Cargamentos card to link directly to the active schedule and provide a WhatsApp export action.
  - Update `ReportViewPage.tsx`: Add native WhatsApp export button for reports.
  - Verify Web build and tests.

### Phase 5: Android App Implementation
- [ ] **T05: Android driver models, phone UI and WhatsApp sharing**:
  - Update Android contract models and repositories for `phone` in `AdminDriver` and `Driver`.
  - Update `DriversScreen.kt` and `DriversViewModel.kt` to edit and persist driver phone number.
  - In `LoadingScreen.kt`:
    - Add WhatsApp share action for the active schedule using `Intent.ACTION_SEND` targeting WhatsApp.
    - For fleteros with phone, add direct action launching `Intent.ACTION_VIEW` with `https://wa.me/<phone>?text=...` carrying the general schedule.
  - In `DashboardScreen.kt`: Link Cargamentos card to active schedule view.
  - In `ReportDetailScreen.kt`: Add WhatsApp share action for the report.
  - Verify Android unit tests and Compose preview tests.

### Phase 6: Final Verification, QA, Release & Production Deployment
- [ ] **T06: Integration verification & automated test suites**:
  - Run full test suite: backend (`npm test`), frontend e2e/unit, and Android unit tests.
  - Verify zero regressions in existing 32 certified tasks and security admission boundaries.
- [ ] **T07: Android APK release build & verification on Ubuntu emulator (`ssh ubuntu`)**:
  - Build clean release APK for Android with the new WhatsApp export and phone capabilities.
  - Validate and run verification in the Ubuntu emulator VM via WireGuard (`ssh ubuntu`).
- [ ] **T08: Complete release documentation update**:
  - Update CHANGELOG, bilingual README and release documentation for the new release version.
- [ ] **T09: Production Docker rebuild & hot-reload**:
  - Consolidate all validated commits to `main` and push to `origin/main` (CI green).
  - Rebuild production Docker image against `main` and hot-reload production containers (`https://trindademasas.duckdns.org/`).
- [ ] **T10: Safe disk & worktree cleanup**:
  - Purgar ramas satélites y worktrees temporales (`git worktree remove --force`).
  - Limpieza de capas descartables de Docker para ahorro de espacio en VPS con cero impacto a otros contenedores.
