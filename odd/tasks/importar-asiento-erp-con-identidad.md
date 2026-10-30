# Import ERP seats with their own identity (`ENT_LAN` and operator)

The import button creates a launch owned by whoever is logged in, and reconciliation then refuses it because
the seat belongs to another operator. The seat never settles, the button stays, and each click adds a launch.

## Specs

S1. Backend — schema: add `ent_lan INTEGER DEFAULT NULL` to `massa_launches` as schema revision 9, with its
migration path and the fresh-install baseline updated together.

S2. Backend — create: `POST /api/massas-launch` accepts `ent_lan` and `operator_name` optionally. An
`operator_name` resolves against `users` by `username` or `display_name` (`'VICTOR'` -> `victor`,
`'GUSTAVO H'` -> `gustavo`). When it does not resolve, the launch stays with the authenticated user and keeps
its `ent_lan`.

S3. Backend — read: `ent_lan` is exposed by `massasLaunchDayView`, through `RawLaunchRow` and
`MassaIndividualLaunch`.

S4. Backend — reconciliation: `reconcileMassasDay` reconciles one-to-one and directly when `launch.ent_lan`
equals `seat.ent_lan`.

S5. Production data: the four orphan launches created today by the failed imports (`ids 90, 91, 92, 93` in the
Docker database) are removed.

S6. Web: `handleImportErpSeat` in `MassasLaunchPage.tsx` sends `ent_lan: request.entLan` and
`operator_name: row.operatorName`; `reconcileAeroMovements` in `aero-integration.ts` reconciles directly and
with priority when `launch.ent_lan` equals `movement.entLan`.

S7. Android: `MassasLaunchViewModel.kt` sends `entLan` and `operatorName` in the import payload; `AeroLogic.kt`
and `MassasApi` include `entLan` and reconcile directly by `entLan` when present.

S8. Delivery: run the backend, web and Android tests; rebuild the `trindade-api` and `trindade-web` containers;
rebuild the signed release APK; everything straight to `main`, no satellite branches and no `Co-Authored-By`.

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1 | backend: revision 9 migration, baseline, migration tests | done (pending commit) |
| T2 | S2, S3 | backend: operator resolution, create with `ent_lan`, day view, contract tests | done |
| T3 | S4 | backend: direct `ENT_LAN` pairing in `reconcileMassasDay` | done |
| T4 | S6 | web: import payload and priority reconciliation | pending |
| T5 | S7 | Android: payload, contract and priority reconciliation | pending |
| T6 | S5 | production: migrate, snapshot, remove ids 90-93, verify | pending |
| T7 | S8 | delivery: full suites, containers, signed APK, evidence | pending |

## Log

L1: "Che, auditamos a fondo el problema que reportó Wilkin al intentar importar lanzamientos de la tableta en el módulo de Lanzamiento de Masas: ... Causa Raíz Exacta: 1. En Web (MassasLaunchPage.tsx:516) y en Android (MassasLaunchViewModel.kt:501), al hacer clic en 'Importar' sobre un asiento erpOnly: - El cliente llama a POST /api/massas-launch pasando únicamente { date, items: [{ product_code, boxes }] }. - NO envía entLan ni el operatorName del asiento ERP. 2. En el backend (massas.routes.ts y massas.service.ts): - POST /api/massas-launch asigna ciegamente user_id = request.user.sub (el usuario autenticado, en este caso Wilkin). - La tabla massa_launches no tiene columna para registrar qué asiento (ent_lan) originó el lanzamiento. 3. En la reconciliación (reconcileAeroMovements en web/Android y reconcileMassasDay en backend): - La clave de matching exige coincidencia estricta de operador: date|productCode|units|operator. - Como la tableta fue operada por 'VICTOR' (asiento 35937) o 'GUSTAVO H' (asiento 35939), y el lanzamiento importado se guardó bajo 'wilkin', reconcileAeroMovements compara WILKIN con VICTOR y rehúsa conciliar. 4. Consecuencias en producción: - El asiento ERP nunca se concilia: sigue mostrándose como erpOnly con el botón 'Importar' activo. - Wilkin, al ver que el botón sigue ahí, vuelve a hacer clic, generando lanzamientos duplicados en massa_launches (hoy se crearon los ids 90, 91, 92, 93 bajo Wilkin). - El lanzamiento importado queda marcado como pendingErp (naranja, 'Pendente ERP - Enviar ao ERP'), arriesgando reenviarlo al ERP Firebird. - En el balance superior, las unidades se duplican/triplican sumando el asiento ERP + los lanzamientos huérfanos. 5. En Android (AeroLogic.kt y MassasLaunchViewModel.kt): - Pasa EXACTAMENTE lo mismo: Android no manda entLan ni operatorName al importar, y su reconcileAeroMovements en AeroLogic.kt compara estrictamente launch.username con movement.operatorName. Plan de Remediación Integral: 1. Backend: - Agregar columna ent_lan INTEGER DEFAULT NULL a massa_launches (migración / schema rev 9). - En POST /api/massas-launch: permitir recibir opcionalmente ent_lan y operator_name. Si viene operator_name, resolverlo contra la tabla users buscando por username o display_name (ej. 'VICTOR' -> victor, 'GUSTAVO H' -> gustavo). Si no se encuentra, asignar al usuario autenticado pero conservando ent_lan. - Exponer ent_lan en massasLaunchDayView y en RawLaunchRow / MassaIndividualLaunch. - En reconcileMassasDay: si launch.ent_lan está presente y coincide con seat.ent_lan, conciliar de forma directa 1-a-1. - Limpiar en la BD de producción de docker (trindade.db) los 4 lanzamientos huérfanos creados hoy por las pruebas de importación (ids 90, 91, 92, 93). 2. Frontend Web: - En handleImportErpSeat (MassasLaunchPage.tsx), enviar ent_lan: request.entLan y operator_name: row.operatorName al llamar a POST /api/massas-launch. - En reconcileAeroMovements (aero-integration.ts): si launch.ent_lan coincide con movement.entLan, conciliar de forma prioritaria directa. 3. Android: - En MassasLaunchViewModel.kt, enviar entLan y operatorName en el payload de importación. - En AeroLogic.kt y MassasApi, incluir entLan y conciliar directamente por entLan cuando esté presente. 4. Validación y Despliegue: - Correr tests en backend, web y Android. - Reconstruir contenedores en Docker (trindade-api y trindade-web). - Recompilar APK release firmado si se modifica Android. - Recordá: todo directo a main, sin ramas satélites ni Co-Authored-By."

L2: Evidence gathered before writing (production Docker database, revision 8):
- `massa_launches` has no `ent_lan`; columns are id, launch_date, product_code, boxes, user_id, created_at, updated_at.
- Orphans: 90 (2026-10-30, product 5000, 27 boxes), 91 (product 22, 3 boxes), 92 (product 22, 3 boxes), 93 (product 5000, 27 boxes), all `user_id = 1` (`wilkin`).
- Seats of 2026-10-30: 35937 `005000` 270 units `VICTOR`; 35939 `000022` 45 units `GUSTAVO H`; 35935 `000022` 30 units `WILKIN`; 35936 (three lines) `JONAS`; 35938 `000787` `VIVIANE`.
- `27 boxes x 10 units = 270` (product 5000) and `3 boxes x 15 units = 45` (product 22), so 90/93 duplicate seat 35937 and 91/92 duplicate seat 35939.
- Users available for resolution: wilkin, nicolas, viviane, victor, invitado, jonas, guilherme, gustavo, jaime.

L3: Decision (derived, not a new requirement). `'GUSTAVO H'` is not equal to `'Gustavo'`, so an exact username or
display-name comparison cannot resolve it. Resolution therefore compares token by token with the operator's own
tokens as a prefix of the ERP name (`GUSTAVO H` -> `GUSTAVO`), and only accepts a prefix that names exactly one
user; anything ambiguous or absent falls back to the authenticated user, which S2 already allows. The seat name
stays authoritative for reconciliation through `ENT_LAN`, so a failed resolution costs attribution, never pairing.

L4: Decision (derived). The click-twice duplication is a data problem, not a UI one, so revision 9 also carries a
partial unique index on `ent_lan` (`WHERE ent_lan IS NOT NULL`) and the create answers `409 ALREADY_IMPORTED`
when the seat is already here. That makes the second import fail loudly instead of adding an orphan.
