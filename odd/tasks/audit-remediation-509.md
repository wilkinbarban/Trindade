# Audit remediation (Web + Android + API) and release 509

Implement every phase of the audit remediation while keeping the backend strict on Massas ownership and moving all user-facing error text to the clients, then ship a signed 509 APK and rebuild production.

## Specs

S1 — Web refresh token. `packages/frontend/src/api/client.ts` rotates the session with the refresh token (30 days) against `/api/auth/refresh`; the refresh token is stored in `localStorage` at login. On a 401 caused by an expired access token, the client renews silently and retries the original request; only when the renewal fails does it clear the session and redirect to login. Parity with Android.

S2 — Android admin i18n. Replace the 71+ hardcoded Portuguese texts in the administration screens (`UsersScreen.kt`, `VehiclesScreen.kt`, `CategoriesScreen.kt`, `DriversScreen.kt`, `TasksScreen.kt`, `TimeSlotsAdminScreen.kt`, plus `AuditScreen.kt`) with `stringResource(R.string...)`. Complete the missing keys in `values/strings.xml` and `values-es/strings.xml` keeping 100% parity.

S3 — Android ViewModel errors. `MassasLaunchViewModel`, `StockViewModel` and `ReportDetailViewModel` stop emitting raw Portuguese strings; errors are modeled and resolved with localized strings. No new Portuguese literal may reach the UI in Spanish.

S4 — Massas future dates. Backend Massas (`massas.routes.ts` / `massas.schema.ts`) rejects `targetDate > getSaoPauloDateString()`: future launch dates are not allowed.

S5 — Web date inputs. Add `max={saoPauloToday()}` to the Massas and Stock date inputs.

S6 — Android date pickers. Unify every date picker on `America/Sao_Paulo` (`saoPauloToday()`), never the device time zone.

S7 — Silent failures. Fix the silent catches in `DriverSelect.tsx`, `ShareAppPage.tsx` and `LoadingViewModel.kt`: the user must see the failure and be able to retry.

S8 — Role magic numbers. Remove the literal `2` role id from `AdminDashboard.tsx`.

S9 — Error contract (architect objection 2). The Fastify backend does not translate or read `Accept-Language`: it answers with a semantic `code` next to `error` (`{ error: string, code: string }`), and each client translates. No permission change in `massas.service.ts`: `existing.user_id === params.userId` stays strict.

S10 — Release 509. `versionCode=509`, `versionName="0.5.1"`; backend `pnpm test`, frontend typecheck/build, `./gradlew testDebugUnitTest`; signed release APK built in the Docker SDK 35 (`-PversionCode=509 -PversionName=0.5.1`); APK Signature Scheme v2 and SHA-256 validated; GitHub release `v0.5.1` assets replaced with `gh release upload v0.5.1 --clobber` (`trindade-0.5.1.apk`, `app-release.apk`, `.sha256`); identical verified APK copied to `/home/wilkin/Desktop/trindade-0.5.1.apk`. `adb install` on the Ubuntu emulator is forbidden.

S11 — Production. Rebuild `docker compose build api web && docker compose up -d api web` and validate health checks on ports 3005 and 8085. Trunk-based commits on `main` with conventional messages, zero AI attribution, `git push origin main`; clean orphan containers/build.

## Tasks

- [x] T1 (S1) Web refresh-token rotation — `packages/frontend/src/api/client.ts`, `contexts/AuthContext.tsx` — commit `178d03b` (`feat(web): rotate the session with the refresh token`). 146 frontend tests, tsc clean.
- [x] T2 (S9, S4) Backend Massas: semantic error codes and future-date rejection — `modules/massas/*`, `utils/api-error.ts` — commit `dd9ae63`. 520 backend tests.
- [x] T3 (S2) Android admin i18n — `admin/*Screen.kt`, `res/values*/strings.xml` — commit `7044abc`, 622 keys in pt and es, no Portuguese literal left in `admin/`.
- [x] T4 (S3) Android ViewModel error modeling — `massas/*`, `stock/*`, `reports/*` — commit `7044abc` (`UiText` resolved in the screen).
- [x] T5 (S5, S7, S8) Web date limit, visible failures, role constants — `pages/*`, `components/loading/*`, `lib/date.ts`, `lib/roles.ts` — commit `bf9fe6f`.
- [x] T6 (S6) Android São Paulo date pickers — `stock/*`, `reports/*`, `loading/*` — commit `7044abc`.
- [x] T7 (S10) versionCode 509, changelog and release docs — `build.gradle.kts`, `CHANGELOG.md`, `docs/release-android.md`.
- [x] T8 (S10, S11) Tests, signed APK, GitHub assets, Desktop copy, Docker rebuild, push — see the Log.

## Log

### L1 — User directive (verbatim)

Directiva de Implementación y Remediación Integral (Fases 1 a 4)

El arquitecto revisó tu informe de auditoría y validó la exactitud técnica de tus hallazgos. Se autoriza la ejecución y remediación integral de todas las fases, pero con dos OBJECIONES ARQUITECTÓNICAS MANDATORIAS que debés respetar estrictamente:

1. RESTRICCIONES Y OBJECIONES DEL ARQUITECTO (INMUTABLES):
- Objeción 1 (Massas - Hallazgo 1.1): NO modificar permisos ni excepciones en el backend (massas.service.ts). El backend se mantiene estricto con existing.user_id === params.userId. No vamos a alterar la seguridad de producción sin auditoría previa. Lo que debés remediar es alinear Android (MassasLaunchViewModel.kt:302) eliminando la rama muerta de isAdmin y garantizando que la UI solo permita editar/eliminar al propio autor del lanzamiento (isOwner) en la fecha corriente, exactamente igual a como funciona en la Web.
- Objeción 2 (i18n y Errores - Hallazgo 2.3): NO implementar Accept-Language en el backend Fastify. El backend no debe traducir ni formatear textos para la UI. Debe responder con códigos de error semánticos y estructurados (JSON { error: string, code: string }). La responsabilidad de traducir y presentar los mensajes pertenece 100% a los clientes (Web en su i18n.json / catálogos y Android en strings.xml).

2. REMEDIACIONES A IMPLEMENTAR:
- Fase 1 (Web Refresh Token):
  * En packages/frontend/src/api/client.ts, implementar la rotación con refresh token (TTL 30 días contra /api/auth/refresh).
  * Guardar el refresh_token en localStorage al autenticar.
  * Ante una respuesta 401 por access token expirado, renovar transparentemente el access token y reintentar la petición original. Si la renovación falla, recién ahí limpiar sesión y redirigir. Paridad con Android.
- Fase 2 (Android i18n & Error Modeling):
  * Reemplazar los 71+ textos en portugués hardcodeados en las pantallas de administración (UsersScreen.kt, VehiclesScreen.kt, CategoriesScreen.kt, DriversScreen.kt, TasksScreen.kt, TimeSlotsAdminScreen.kt) por stringResource(R.string...).
  * Completar las claves faltantes en values/strings.xml y values-es/strings.xml manteniendo paridad 100%.
  * En ViewModels (MassasLaunchViewModel, StockViewModel, ReportDetailViewModel), dejar de emitir strings crudos en portugués; modelar errores o resolver textos con strings localizados.
- Fase 3 (Validación de fechas y resiliencia):
  * Backend Massas (massas.routes.ts / massas.schema.ts): validar que targetDate <= getSaoPauloDateString() (no permitir fechas futuras).
  * Frontend Web: agregar max={saoPauloToday()} en inputs de tipo date de lanzamientos.
  * Android: unificar pickers de fecha con la zona horaria America/Sao_Paulo.
  * Corregir catch silenciosos en DriverSelect.tsx, ShareAppPage.tsx y LoadingViewModel.kt.
  * Corregir magic numbers de roles en AdminDashboard.tsx.

3. VERSIONCODE, BUILD, RELEASE Y DESPLIEGUE:
- Incrementar versionCode a 509 en packages/android/app/build.gradle.kts (mantener versionName = "0.5.1"). Actualizar doc/changelog si corresponde.
- Ejecutar y validar suites de pruebas:
  * Backend: pnpm test (514+ pasando).
  * Frontend: lint / typecheck / build.
  * Android: ./gradlew testDebugUnitTest.
- Compilar APK Release firmado en Docker SDK 35 (-PversionCode=509 -PversionName=0.5.1).
- Validar firma APK Signature Scheme v2 y hash SHA-256.
- Actualizar release en GitHub: reemplazar los assets de v0.5.1 con gh release upload v0.5.1 --clobber (trindade-0.5.1.apk, app-release.apk y .sha256).
- Copiar APK idéntico verificado a /home/wilkin/Desktop/trindade-0.5.1.apk.
- REGLA ESTRICTA: PROHIBIDO adb install en el emulador de Ubuntu.
- Reconstruir imágenes de producción en Docker: docker compose build api web && docker compose up -d api web y validar health checks en puertos 3005 y 8085.
- Git & Limpieza: Trunk-based directo en main (sin ramas), commits convencionales, CERO atribución de IA, git push origin main. Limpieza de contenedores huérfanos/build.

### L2 — Scope decisions taken while implementing

- **T3 includes `AdminTimeSlotsScreen` under its real filename.** The spec names `TimeSlotsAdminScreen.kt`; the screen lives in `admin/TimeSlotsScreen.kt`. Both the filename mismatch and the screen were covered: every literal of that screen now reads from `strings.xml` (`admin_time_slots_*`), including the refused status line. `HH:MM` stays a literal because it is a format hint, not a sentence.
- **S3's ViewModel list is read as closed.** `MassasLaunchViewModel`, `MassasHistoryViewModel`, `StockViewModel` and `ReportDetailViewModel` now carry `UiText` (resource id plus arguments) and the screens resolve it with `stringResource`. The administration ViewModels (`UsersViewModel`, `VehiclesViewModel`, `CategoriesViewModel`, `DriversViewModel`, `TasksViewModel`, `TimeSlotsViewModel`, `AuditViewModel`) still hold their Portuguese `UNREACHABLE`/`REFUSED` constants; their screens were localized, their messages were not. This is a known gap, reported as such, not silently declared done.
- **S5 needs a shared helper.** `lib/date.ts` holds `saoPauloToday()` (São Paulo calendar day from the browser clock) so the four date inputs and their tests agree on one definition; `lib/date.test.ts` pins the UTC-3 edge (02:30Z is still the previous day).
- **S8 gets a named constant.** `lib/roles.ts` exports `ROLE_ID` so the admin screen compares `ROLE_ID.admin` and `ROLE_ID.worker` instead of bare `1` and `2`.
- **S7, Web.** `DriverSelect` and `ShareAppPage` keep the failure in state and render it with a retry action; the QR placeholder no longer doubles as the failure state.
- **S7, Android.** The vehicle read is the failure worth naming: without it a company driver cannot be added and the form simply refuses. The read becomes reportable (`vehiclesFail` in the fake), the state reports it, and the screen gains a retry that re-reads the day.

### L3 — Verification evidence (2026-10-23)

- **Backend** — `pnpm test` in `packages/backend`: 520 tests, 0 failures, plus the new `photo-cache.test.ts` cleanup.
- **Frontend** — `npm test` in `packages/frontend`: 146 tests, 0 failures; `tsc --noEmit` clean; `vite build` succeeded.
- **Android** — one pinned-container invocation in SDK 35: `:app:testDebugUnitTest :app:assembleRelease -PversionCode=509 -PversionName=0.5.1 -PapiBaseUrl=https://trindademasas.duckdns.org/ -PrequireSigned=true` → `BUILD SUCCESSFUL`, 969 unit tests, 0 failures.
- **APK** — `app-release.apk` (no unsigned sibling), `10,879,792` bytes, `sha256 1545739f45625f711ef6b4fbe05faa7fbdfb204d11f37d9c588b6a49ffb58fd5`; `apksigner verify -v` → `Verifies`, v2 `true`, v1 and v3 `false`; certificate SHA-256 `ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`; `aapt2` → `versionCode='509' versionName='0.5.1'`; Desktop copy identical.
- **GitHub** — assets clobbered on `v0.5.1`; the three assets report `sha256:1545739f…` (GitHub digest), so the updater cannot hand out the 508 binary under `app-release.apk`.
- **Production** — `docker compose build api web && docker compose up -d api web`: both containers `healthy`; `/api/health` → `{"status":"ok"}`; web 200 on 8085 and the served bundle contains `auth_refresh_token`, so the new build is live.
- **RED evidence** — T1: `a 401 whose message names no token still renews once` failed before the heuristic was removed and passed after. S7 Android: the rule was proven mechanically from the diff (old `message = if (schedules == null) UNREACHABLE else null` leaves the vehicle failure silent, and the new test asserts a message) after two attempts were blocked by test-compile errors, not by the assertion; the GREEN is the 969-test run above.
