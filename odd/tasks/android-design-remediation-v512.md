# Android design remediation — v0.5.2 (iconography, tabs, drawer, module headers, admin sections, stock date)

Source: `DIRECTIVA_REMEDIACION_ANDROID.md` (the operator's file, outside this repository; its operative text is quoted verbatim in S1-S8). Android package only; no backend, web or contract change.

## Specs

S1. Vector icons: create clean, lightweight XML vector resources in `packages/android/app/src/main/res/drawable/` for `ic_menu`, `ic_profile`, `ic_dashboard`, `ic_reports`, `ic_truck`, `ic_layers`, `ic_calculator`, `ic_boxes`, `ic_calendar`, `ic_archive`, `ic_file_clock`, `ic_history`, `ic_share`, `ic_admin`, `ic_audit`, `ic_tasks`, `ic_drivers` — "recursos vectoriales XML limpios y ligeros ... correspondientes a la semántica de la Web (Lucide)".

S2. Header actions: "Botón Menú: Ícono `ic_menu` con `contentDescription = stringResource(R.string.nav_menu)`. Botón Perfil: Ícono `ic_profile` con indicador de actualización si `updateAvailable`. Mantener el touch target mínimo de 48dp y la accesibilidad."

S3. Drawer: `NavigationDrawerItem` gains `icon` with `painterResource(destination.icon)` ("`24.dp`, tint acorde al tema"), in this order, verbatim: "1. Dashboard (`ic_dashboard`), 2. Relatórios / Planilhas (`ic_reports`), 3. Horários / Cronograma de Carregamento (`ic_truck`), 4. Lançamento de Massas (`ic_layers`), 5. Calculadora de Assaí (`ic_calculator`), 6. Calculador de Estoque (`ic_boxes`), 7. Histórico de Massas (`ic_calendar`), 8. Histórico de Contagem (`ic_archive`), 9. Histórico de Relatórios (`ic_file_clock`), 10. Histórico de Carregamento (`ic_history`), 11. Compartilhar App (`ic_share`), 12. Painel Admin (`ic_admin`) — visible para Administrador y Trabajador (con secciones acordes a su rol), 13. Auditoria (`ic_audit`) — visible para Administrador."

S4. Tab row: remove `Tab.MASSAS_HISTORY`, `Tab.STOCK_HISTORY`, `Tab.TASKS`, `Tab.DRIVERS`; order left to right: "1. `Tab.DASHBOARD`, 2. `Tab.REPORTS`, 3. `Tab.LOADING`, 4. `Tab.MASSAS_LAUNCH`, 5. `Tab.ASSAI`, 6. `Tab.STOCK_COUNTER`, 7. `Tab.ADMIN` (visible tanto para Administrador como Trabajador para acceder a sus tareas/motoristas permitidos), 8. `Tab.SHARE`". Histories stay reachable "desde el botón `Histórico` dentro del módulo padre o desde el Drawer".

S5. Admin panel: `AdminSection` extended with `TASKS` and `DRIVERS`; "Mostrar las tarjetas de Tarefas y Motoristas disponibles tanto para Administrador como para Trabajador (verificar `RolePolicy.CATALOG_TASKS` y `RolePolicy.CATALOG_DRIVERS`)"; CATEGORIES, VEHICLES, TIME_SLOTS, USERS, AUDIT remain `isAdmin == true`; TASKS opens `TasksRoute(sessionKey = sessionKey, onBack = { selectedSection = null })`, DRIVERS opens `DriversRoute(...)` likewise; back returns to the panel; "Garantizar que los permisos de backend y roles no se rompan ni se altere ninguna funcionalidad."

S6. Module headers: remove `Voltar` at the root of Assaí, Estoque, Carregamento and Massas ("remover el botón \"Voltar\" cuando la pantalla está en la raíz de su pestaña") and keep it "exclusivamente en subpantallas e Históricos"; delete `Text(..., style = MaterialTheme.typography.titleLarge)` that repeats the active tab name; add a clean top action row with `Histórico` "(en módulos con histórico: Massas, Estoque, Carregamento, Relatórios)" and `Ajuda` (`ModuleHelpButton`).

S7. Stock calculator: "Eliminar el campo `OutlinedTextField` de fecha y el botón `openDayPicker` en `StockCalculatorScreen`"; "La calculadora siempre debe operar sobre el día actual (`saoPauloLocalDate()` / `saoPauloToday()`)"; past days are read and edited from `StockHistoryScreen`.

S8. Verification and delivery: "Actualizar `MainActivityNavigationTest.kt` y demás tests unitarios de Compose para reflejar la nueva disposición de tabs, drawer y accesos por rol"; `./gradlew testDebugUnitTest` green; `versionCode` 512 and `versionName` "0.5.2"; `assembleDebug`, install on the Ubuntu `emulator-5554` over SSH, capture screenshots "(drawer, dashboard, stock, massas, admin)"; `assembleRelease`; update the GitHub release artifact; "Commit directo en `main` sin ramas satélites, siguiendo conventional commits y sin atribución a IA (sin `Co-Authored-By`)".

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1-T4 | S1, S2, S3, S4, S5, S6, S7 | inline | `feat(android): rebuild the shell the web already had` |
| T5 | S8 | inline | `chore(android): release 0.5.2` |

T1, T2, T3 and T4 ship as one commit (`feat(android): rebuild the shell the web already had`): the
eight-tab row, the drawer, the admin panel sections and the cleaned module headers are one wiring, and
splitting them would leave commits that do not compile. T5 carries the version bump, the changelog and
this document.

## Log

L1 (user, verbatim): see `DIRECTIVA_REMEDIACION_ANDROID.md`, incorporated by reference from the repository root.
L2: §4 enumerates eight tab-row entries including `Tab.ADMIN` before `Tab.SHARE`, while the document's diagnosis paragraph says "solo 7 módulos raíz". The operative enumeration (§4) governs: the row keeps ADMIN so a worker can reach its own tasks and drivers inside the panel.
L3: the four histories are drawer entries; three of them (massas, reports, loading) are overlays that live inside their parent tab, so the drawer target carries an optional overlay in addition to the tab.

L4: the massas module's tagline shared its row with `Histórico` and `Ajuda` and was cut at "produção d…"
on the emulator; it now takes two lines. Found by looking at the screen, not by a test.
L5: the update gate blocks every build that is not the published binary (`SAME_RELEASE_PATCH` compares
the installed digest against the release asset), so the app was walked with a debug build whose
`githubApiBaseUrl` pointed at an unresolvable host, and the released APK was checked afterwards.
L6: evidence — 975 unit tests, 0 failures; emulator `emulator-5554` (Ubuntu, AVD `Trindade_UX_v030`,
1080x2400), walked as Administrador and then as Trabajador: the drawer with thirteen entries and both
group headers, and twelve for the worker with no `Auditoria`; the admin panel with Tarefas and Motoristas
above the five administrator sections for the administrator, and those two alone for the worker, where
`Acessar` opens Tarefas; the stock counter with no date field and `Histórico`/`Ajuda` where the repeated
title used to be; massas with the whole tagline in two lines; `Voltar` only in the sub-screens (the stock
history and Tarefas).
