# Conciliación unificada de Masas, relatorio integral y Panel Admin

Artefacto de diseño del salto que Wilkin definió con el orquestador: una sola producción de planta, un
relatorio que la cuenta completa, y la integración ERP donde se configura y no donde se produce.
Estado: **implementación en curso** con las decisiones del cierre ya tomadas por Wilkin y registradas en D1–D8.
T1, T2 y T3 cerrados con commits sobre `main`; T4–T9 pendientes.

## Specs

- **S1**: «El reporte del día (en pantalla y exportación de WhatsApp) debe reflejar la producción REAL COMPLETA
  de la fábrica: lo lanzado en Trindade (massa_launches) MÁS lo que se lanzó directo a Delphi desde la tablet
  Android o PC (aero_movements).»
- **S2**: «Sin duplicados: lo conciliado se cuenta una vez, lo que vino de la tablet se suma al operario
  correspondiente.»
- **S3**: «Tarjeta fija MassasReportSection en ReportsPage y ReportViewPage (análoga a ReceiptsSection).»
- **S4**: «En export.service.ts el bloque de masas queda fijo y permanente (eliminamos el toggle
  includeMassas)».
- **S5**: «Cuidando con rigor el formato exacto de WhatsApp con emojis (*BOLD*, 👤 Operário, 📦 Resumo
  Coletivo).»
- **S6**: «Adiós a la división en dos pestañas (Trindade vs ERP): una sola tabla unificada de producción.»
- **S7**: «Cada fila muestra su estado real: Conciliado (impactado en ambos lados). Pendente ERP (en Trindade,
  aún no enviado a Delphi) con acciones rápidas [Enviar al ERP] y [Ya cargado en Tablet]. Só no ERP (nació en
  la tablet de la empresa) con [Importar] (para cajas enteras).»
- **S8**: «Filtro por trabajador universal que filtre simultáneamente todo (Trindade + Tablet/ERP).»
- **S9**: «Botón destacado [Sincronizar com ERP] en la cabecera con hora del último sync.»
- **S10**: «Huella de origen en Delphi: ENT_OBS envía 'Trindade #ID' para correlación certera.»
- **S11**: «Pestaña 'Integração ERP / Aero32'» en el Panel Admin; «Sacamos la configuración técnica de la
  pantalla de producción de Masas para no saturar al operario.»
- **S12**: «wsUrl: fijo por defecto en 'http://192.168.25.250:1453/api', read-only para todos los usuarios
  excepto 'wilkin' (el único admin autorizado a cambiarlo).»
- **S13**: «Usuario y Senha: son personales de cada operario y se guardan en el localStorage del iPhone.
  Cualquier trabajador puede entrar a editarlos.»
- **S14**: «Botón de instalar atajo iCloud (1 toque) y test de conexión habilitados para todos los
  trabajadores.»
- **S15**: «Barra de balance superior: Total Producido vs Total Registrado en ERP.»
- **S16**: «Vista móvil iPhone: viewport 375px, targets táctiles >=44px, cero desbordes y modales limpios.»

## Lo que ya existe (y no hay que reinventar)

| Pieza | Dónde | Estado frente al pedido |
| --- | --- | --- |
| `massa_launches` + `massasLaunchDayView` | `massas.service.ts:61` | Sólo Trindade. Suma cajas y unidades por producto, con `display_name` por launch. |
| `aero_movements` + `listAeroMovements` | `aero.movements.service.ts:91` | Espejo del día por `ENT_LAN` + producto, con `operator_name` normalizado a mayúsculas. Ya existe y está migrado. |
| `reconcileAeroMovements` | `aero-integration.ts:750` | Concilia por fecha + código sin ceros + cantidad + operario. Devuelve `reconciled[]`, `matchedLaunchIds[]`, `unmatchedLaunchIds[]`. **Base directa de S2.** |
| `aeroImportPlan` | `aero-mirror.ts:172` | Ya exige entrada (`E`), producto del catálogo y **cajas enteras**. **S7 se cumple con esto.** |
| `aeroMirrorSeats` | `aero-mirror.ts:155` | Filtra el día del ERP al catálogo de masas. Deja afuera insumos que van a Recebimento. |
| Sección de masas del export | `export.service.ts:279` | Ya tiene el formato por operario, `Resumo Coletivo` y los emojis. Está detrás de `includeMassas`, que por defecto es `false`. |
| `buildAeroEntryPayload` | `aero-integration.ts:419` | Ya arma `ENT_OBS`; hoy vale `'Lancamento de massas via Trindade'` por defecto. El `body` del sobre viaja tal cual al WebService, así que **S10 no requiere tocar el atajo certificado**. |
| `AeroIntegrationPanel` | `massas/AeroIntegrationPanel.tsx` | Configuración + test de conexión + atajo iCloud, montado en `MassasLaunchPage:946`. Es lo que S11 mueve. |
| `ReceiptsSection` | `components/reports/ReceiptsSection.tsx` | Patrón exacto de la tarjeta que S3 pide: se monta en `ReportsPage:321`, `ReportEditPage:450` y `ReportViewPage:301`. |
| `AeroShortcutDialog` | `massas/AeroShortcutDialog.tsx` | Enlace iCloud certificado y receta de 20 acciones. S14 lo reutiliza sin cambios. |

Lo que **no** existe hoy: ninguna lectura que una las dos fuentes; la vista ERP es una tarjeta separada
(`ErpMirrorCard`) con su propio sync; la configuración vive en producción; `wsUrl` arranca vacío y lo edita
cualquiera; `ENT_OBS` no lleva identidad de Trindade; el export exige un toggle.

## Diseño por frente

### Frente 1 · Relatório integral

Una sola función pura decide qué produjo la planta, y todo lo demás la lee:

```
massasReportDayView(db, date) -> { date, operators[], products[], totalBoxes, totalUnits, balance }
```

Regla de conteo, en una frase: **cada caja se cuenta una vez, del lado que la originó**. Un launch de
Trindade conciliado con un seat del ERP es una caja, no dos; un seat sin launch conciliado es una caja del
operario que lo cargó en la tablet.

1. Se leen launches y seats del día (las dos consultas que ya existen).
2. `reconcileAeroMovements(seats, launches)` marca qué seats ya están y qué launches ya viajaron.
3. Se agrupa por operario: los launches por `display_name`/`username`; los seats **no conciliados** por
   `operator_name` del ERP.
4. Se suman por producto para `Resumo Coletivo`.

El export pierde el parámetro `includeMassas` en `generateWhatsAppText` y en la ruta `/export`
(`reports.routes.ts:290`), y el bloque se emite siempre que haya producción. `ExportPreview` pierde el
checkbox. El formato no se toca: es el que ya está en `export.service.ts:284-326`.

### Frente 2 · Vista unificada de lanzamiento

`ErpMirrorCard` y la tabla actual dejan de convivir como dos vistas y pasan a ser **una** lista:

```
massasUnifiedRows(dayView, seats) -> MassasRow[]
MassasRow = { key, operator, productCode, productName, boxes, units,
              state: 'reconciled' | 'pendingErp' | 'erpOnly',
              launchId?, entLan?, importPlan? }
```

- `reconciled`: launch con seat. Sin acciones; es información.
- `pendingErp`: launch sin seat. Acciones **[Enviar al ERP]** (encola un sobre como hoy) y
  **[Ya cargado en Tablet]** (marca la fila para no volver a ofrecerla ese día).
- `erpOnly`: seat sin launch. Acción **[Importar]** sólo si `aeroImportPlan` devuelve cajas enteras; si no,
  la fila se muestra sin botón y con el motivo.

El filtro por trabajador (S8) se aplica sobre `MassasRow.operator`, así que cubre las dos fuentes por
construcción, incluidos operarios de tablet que no son usuarios de Trindade. Eso vuelve a abrir la pregunta
de identidad de D6.

El sync (S9) deja de estar dentro de la tarjeta ERP y pasa a la cabecera de la página, con
`data-testid="massas-sync-erp"` y la hora de `snapshot.syncedAt` (ya se calcula en `ErpMirrorCard:83`).

`ENT_OBS` (S10): `buildAeroEntryPayload` ya acepta `observation`. El cambio es pasar `Trindade #<id>` en el
flujo real. **Riesgo detectado:** hoy el POST local (`/massas-launch`) no captura los ids que devuelve y el
sobre se arma con `requested` sin id (`MassasLaunchPage.tsx:264-272`). Para tener `#ID` hay que leer los
launches creados y correlacionarlos con el pedido antes de armar el sobre, o devolverlos desde el backend.
El atajo no cambia: ignora claves extra y ya manda `body` completo.

### Frente 3 · Panel Admin

Pestaña nueva `integration` en `AdminDashboard`, con tres bloques:

- **Servidor**: `wsUrl` con el valor por defecto `http://192.168.25.250:1453/api`. Editable sólo si
  `username === 'wilkin'`; para el resto se muestra como texto con candado.
- **Mis credenciales**: usuario y senha del operario, editables por cualquiera, persistidos en
  `localStorage` con las funciones que ya existen (`loadAeroSettings`/`saveAeroSettings`).
- **Probar y instalar**: `probeAeroConnection` y `AeroShortcutDialog`, disponibles para todos.

`MassasLaunchPage` deja de montar `AeroIntegrationPanel`. La pestaña se alimenta del mismo `AeroSettings`
que hoy vive en `aero-integration.ts:86`, con `DEFAULT_AERO_SETTINGS.wsUrl` pasando de `''` al valor fijo.

### Frente 4 · Balance y UX

Barra superior con dos números y su diferencia:

- **Total Produzido**: unidades de `massasReportDayView` (la cifra conciliada, sin doble conteo).
- **Total Registrado en ERP**: suma de entradas `E` del snapshot del día.
- **Diferencia**: `registrado − producido`. Negativa = producción que el ERP no vio; positiva = el ERP tiene
  algo que Trindade no.

La barra no es un contador de progreso: es un diagnóstico. Por eso muestra la diferencia con signo y no un
porcentaje.

375 px, 44 px y cero desbordes ya son la norma del módulo y de `aero-shortcut-dialog.spec.ts`; el E2E nuevo
hereda esas aserciones.

## Decisiones

- **D1 — Una caja, un origen.** El relatorio cuenta el launch de Trindade y descarta el seat conciliado; el
  seat sólo suma cuando no tiene launch. Es la única forma de que «producción real completa» no dependa de
  cuántas veces se sincronizó el teléfono.
- **D2 — `ENT_OBS` primero, heurística después.** Cuando el seat trae `Trindade #ID`, la conciliación es por
  id y no por forma. Sin huella (seats viejos o cargados en la tablet) se mantiene
  `reconcileAeroMovements`, que ya compara operario además de fecha, código y cantidad.
- **D3 — El id se captura antes de armar el sobre.** Consecuencia de S10: el flujo de confirmación tiene que
  conocer el id que devolvió el POST local. Si el backend no los devuelve hoy, se cambia ahí y no se adivina
  en el teléfono.
- **D4 — `wsUrl` restringido en la UI no es un control de seguridad.** El valor vive en `localStorage` del
  iPhone y cualquiera con devtools lo cambia. La restricción a `wilkin` es higiene de configuración, no un
  límite de confianza. Si Wilkin quiere garantía, la URL se sirve desde el backend y el teléfono no la
  escribe.
- **D5 — El estado de una fila no se guarda.** `reconciled`/`pendingErp`/`erpOnly` se derivan del día y del
  snapshot en cada lectura. Guardarlo sería una tercera fuente de verdad que se desincroniza.
- **D6 — El operario de la tablet puede no ser usuario de Trindade.** `ENT_USU` es texto libre del ERP. Se
  muestra como chip propio y se suma a su nombre; asociarlo a una cuenta requiere una decisión de Wilkin.
- **D7 — `Ya cargado en Tablet` es del día y del dispositivo.** Clave `aero_tablet_acked_YYYY-MM-DD` en
  `localStorage` con los ids marcados. No crea un launch ni escribe en el ERP; es un aviso que sobrevive
  recargas y muere con la jornada. Otro teléfono vuelve a ofrecer la fila, que es lo correcto: la marca es de
  quien la cargó.
- **D8 — La regla de conciliación vive dos veces, a propósito y con los mismos casos.** El backend no puede
  importar `packages/frontend/src/lib/aero-integration.ts` y no hay paquete compartido de código
  (`packages/contracts` es sólo OpenAPI). `reconcileAeroMovements` (frontend) y la función pura del reporte
  (backend) se testean con la misma tabla de casos. Unificarlas en un paquete compartido es deuda explícita,
  no de este salto.

## Contratos de prueba

### Frente 1

- `massasReportDayView` con 2 cajas de un launch conciliado (100 un, `wilkin`) + 1 seat conciliado (100 un,
  `wilkin`) + 1 seat libre (50 un, `jefferson`) → `totalUnits === 150`, `wilkin === 100`, `jefferson === 50`.
- El seat conciliado no aparece dos veces en `operators[]`.
- Un seat de salida (`movement_type = 'S'`) no suma nunca.
- `GET /api/reports/:id/export` sin query contiene `🍝 *LANÇAMENTO DE MASSAS*`, `Por Operário:`,
  `👤 Wilkin:`, `   - CASEIRA: 100 un`, `Resumo Coletivo:` y `📦 CASEIRA: 150 un`, comparados como literales
  con emojis.
- `?includeMassas=false` **no** quita la sección: el parámetro ya no existe.
- Wiring: `ReportsPage` y `ReportViewPage` montan `MassasReportSection` con la fecha del reporte.

### Frente 2

- `massasUnifiedRows`: launch conciliado → `reconciled` (sin acciones); launch sin seat → `pendingErp` con
  las dos acciones; seat sin launch con cajas enteras → `erpOnly` con `importPlan`; seat con fracción →
  `erpOnly` sin botón y con motivo.
- El filtro por trabajador reduce las dos fuentes a la vez (un operario que sólo existe en el ERP también
  aparece y filtra).
- `buildAeroEntryPayload({ observation: 'Trindade #42' })` → `ENT_OBS === 'Trindade #42'`; sin observación
  sigue el texto actual.
- Regresión del contrato del atajo: el sobre `entrada` conserva exactamente `url`, `headers`, `body`,
  `callback` (el atajo certificado no se re-certifica).
- E2E 375 px: hay **una** tabla (no dos tabs), `[Sincronizar com ERP]` visible con la hora del último sync,
  y cada acción de fila mide ≥44 px.

### Frente 3

- `DEFAULT_AERO_SETTINGS.wsUrl === 'http://192.168.25.250:1453/api'`.
- `aeroSettingsEditable('wilkin', 'wsUrl') === true`; para cualquier otro usuario, `false`; usuario y senha
  editables siempre.
- Guardar credenciales como no-`wilkin` conserva el `wsUrl` vigente y cambia usuario/senha.
- Wiring: `AdminDashboard` tiene la pestaña `integration`; `MassasLaunchPage` ya no importa
  `AeroIntegrationPanel`.
- La pestaña muestra el botón iCloud (`aero-shortcut-icloud-link`) y el test de conexión a un operario sin
  rol de administrador.

### Frente 4

- `massasBalance`: 100 un de Trindade conciliadas + 50 un de tablet → `produced = 150`, `registered = 150`,
  `difference = 0`; con 100 de Trindade sin sincronizar → `produced = 100`, `registered = 0`,
  `difference = -100`.
- E2E 375 px: la barra de balance está arriba de la tabla, sin desborde horizontal y con targets ≥44 px.

## Decisiones cerradas con Wilkin

1. **Pestaña Integração para todos los roles, workers incluidos.** El operario entra ahí a configurar sus
   credenciales personales, probar conexión e instalar el atajo. `AdminDashboard.tsx:184` cambia: la pestaña
   `integration` no se filtra por rol.
2. **`wsUrl` con candado de UI, sin migración de backend.** Campo read-only con icono de candado para todos;
   editable sólo si el usuario logueado es `wilkin`. Higiene de UI, no control de seguridad (D4).
3. **Sin tabla de alias.** `ENT_USU` se muestra en mayúsculas tal cual. Si coincide case-insensitive con
   `username` o `display_name` de Trindade se asocia; si no, queda como operario de planta en el filtro y en
   el reporte.
4. **`Diferencia = Registrado − Producido`.** Cero significa conciliación total; negativa avisa que hay
   producción sin impactar en Delphi. Los seats conciliados cuentan del lado ERP: el balance compara dos
   registros, no dos producciones.
5. **`Ya cargado en Tablet` vive en el dispositivo.** `localStorage` con la clave
   `aero_tablet_acked_YYYY-MM-DD` y los ids de los launches marcados. Sobrevive recargas de la jornada y no
   pide migración.

## Tasks

- **T1** (S1, S2, D1, D2, D8) — `massas/massas-report.service.ts`: `reconcileMassasDay` (pura) y
  `massasReportDayView` que une launches y seats conciliados, tolerando la tabla sin migrar. Tests puros de
  doble conteo, salidas, operarios de tablet y catálogo vacío.
  Evidencia: `abd4eda` (regla pura) y `a21bce8` (vista del día); 12/12 en verde.
- **T2** (S4, S5) — `export.service.ts`: sección de masas permanente, sin `includeMassas`; `reports.routes.ts`
  y `ExportPreview` sin el parámetro ni el checkbox; tests de literales con emojis.
  Evidencia: `511fd1a`; 68/68 backend de reportes, 7/7 E2E de export.
- **T3** (S3) — `MassasReportSection` reutilizable y montada en `ReportsPage` y `ReportViewPage`, con test de
  cableado y E2E de la tarjeta.
  Evidencia: `2a4a922`; `GET /api/massas-launch/report`, 14/14 de rutas, 353/353 frontend, 3/3 E2E.
- **T4** (S6, S7, S8, D5) — `massasUnifiedRows` en `lib/` y la tabla unificada en `MassasLaunchPage`; se
  retira `ErpMirrorCard` como vista y sus tab; tests puros de los tres estados, cajas enteras y filtro
  universal.
- **T5** (S9) — Sync en la cabecera con `data-testid="massas-sync-erp"` y hora del último sync; E2E.
- **T6** (S10, D2, D3) — `ENT_OBS = 'Trindade #ID'`: capturar ids del POST local y pasarlos a
  `buildAeroEntryPayload`; tests de `ENT_OBS` y de regresión del sobre del atajo.
- **T7** (S11, S12, S13, S14, D4) — Pestaña `integration` en `AdminDashboard`, `wsUrl` por defecto y
  read-only salvo `wilkin`, credenciales por operario, iCloud y test habilitados; retiro de
  `AeroIntegrationPanel` de `MassasLaunchPage`; tests de `aeroSettingsEditable` y de cableado.
- **T8** (S15, S16) — Barra de balance con `massasBalance` y verificación 375 px/44 px/cero desbordes en los
  tres frentes.
- **T9** — Cierre: suites frontend y backend, `tsc --noEmit`, Playwright completo, `docker compose up -d
  --build web` y HTTP 200.

## Log

- **L1** — «Hola! Con Wilkin acabamos de definir la arquitectura para el siguiente salto grande del sistema:
  la conciliación unificada de Masas, el reporte integral de planta y la reorganización de la integración ERP
  en el Panel Admin. Te paso el desglose de los 4 frentes acordados con Wilkin para que lo investigues en el
  código, lo contrastes con lo que ya tenemos y armemos el artefacto ODD:

  1. Módulo Relatório (Consolidación total de planta):
  - El reporte del día (en pantalla y exportación de WhatsApp) debe reflejar la producción REAL COMPLETA de
    la fábrica: lo lanzado en Trindade (massa_launches) MÁS lo que se lanzó directo a Delphi desde la tablet
    Android o PC (aero_movements).
  - Sin duplicados: lo conciliado se cuenta una vez, lo que vino de la tablet se suma al operario
    correspondiente.
  - Tarjeta fija MassasReportSection en ReportsPage y ReportViewPage (análoga a ReceiptsSection).
  - En export.service.ts el bloque de masas queda fijo y permanente (eliminamos el toggle includeMassas),
    cuidando con rigor el formato exacto de WhatsApp con emojis (*BOLD*, 👤 Operário, 📦 Resumo Coletivo).

  2. Módulo Lançamento de Masas (Vista Unificada):
  - Adiós a la división en dos pestañas (Trindade vs ERP): una sola tabla unificada de producción.
  - Cada fila muestra su estado real: Conciliado (impactado en ambos lados). Pendente ERP (en Trindade, aún
    no enviado a Delphi) con acciones rápidas [Enviar al ERP] y [Ya cargado en Tablet]. Só no ERP (nació en
    la tablet de la empresa) con [Importar] (para cajas enteras).
  - Filtro por trabajador universal que filtre simultáneamente todo (Trindade + Tablet/ERP).
  - Botón destacado [Sincronizar com ERP] en la cabecera con hora del último sync.
  - Huella de origen en Delphi: ENT_OBS envía 'Trindade #ID' para correlación certera.

  3. Módulo Painel Admin (Pestaña 'Integração ERP / Aero32'):
  - Sacamos la configuración técnica de la pantalla de producción de Masas para no saturar al operario.
  - Manejo híbrido de permisos: wsUrl: fijo por defecto en 'http://192.168.25.250:1453/api', read-only para
    todos los usuarios excepto 'wilkin' (el único admin autorizado a cambiarlo). Usuario y Senha: son
    personales de cada operario y se guardan en el localStorage del iPhone. Cualquier trabajador puede entrar
    a editarlos. Botón de instalar atajo iCloud (1 toque) y test de conexión habilitados para todos los
    trabajadores.

  4. Balance Colectivo y Cuidados UX:
  - Barra de balance superior: Total Producido vs Total Registrado en ERP.
  - Vista móvil iPhone: viewport 375px, targets táctiles >=44px, cero desbordes y modales limpios.

  Hacé una exploración en modo lectura del código actual (massas.service.ts, export.service.ts,
  MassasLaunchPage.tsx, ErpMirrorCard.tsx, AdminDashboard.tsx), compará con esta arquitectura y redactá el
  artefacto ODD en odd/tasks/conciliacion-unificada-masas-y-relatorio.md con el desglose de fases y contratos
  de prueba. Avisame apenas lo tengas para revisarlo juntos antes de tirar código.»
- **L2** — Respuestas oficiales de Wilkin a las cinco preguntas: «1. Pestaña Integração en AdminDashboard:
  Habilitada para que todos los roles (incluidos workers) puedan verla y acceder. Los operarios acceden allí
  para configurar sus credenciales personales, probar conexión e instalar el atajo de iCloud. 2. wsUrl y
  permisos: Valor por defecto en código: 'http://192.168.25.250:1453/api'. En la UI: campo read-only con
  icono de candado para todos los usuarios. Solo si el usuario logueado es 'wilkin' se habilita la edición del
  campo. Higiene de UI limpia sin necesidad de migraciones de backend. 3. Operarios del ERP que no existen en
  Trindade: Se muestran directamente con su nombre de ERP (ENT_USU en mayúsculas). Si el nombre coincide
  case-insensitive con algún usuario/display_name de Trindade se asocia; si no, se muestra tal cual como
  operario de planta en el filtro y en el reporte. Sin tablas de alias complejas. 4. Balance: Total Producido
  (cifra real conciliada sin duplicados) vs Total Registrado en ERP (entradas de masas en snapshot).
  Diferencia = Registrado - Producido. Si es 0 está 100% conciliado; si es negativa alerta que hay producción
  sin impactar en Delphi. 5. 'Ya cargado en Tablet': Se persiste en localStorage del dispositivo por fecha
  (ej. 'aero_tablet_acked_YYYY-MM-DD') asociando los IDs de launches marcados. Así sobrevive recargas de
  página durante la jornada laboral sin requerir migraciones de base de datos.» Y la autorización: «Tenés luz
  verde total. Arrancá con T1 y T2 bajo TDD estricto (RED -> GREEN), cuidando commits atómicos en main
  (<= 400 líneas, sin AI attribution).»
