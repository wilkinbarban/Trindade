# Relatório — Recebimento desde el ERP (insumos del día)

Hoy el Relatório pide tildar a mano «Recebimento de farinha / cajas / bobinas» sin cantidad ni asiento, mientras
el mismo dato ya llegó del ERP y quedó guardado en `aero_movements`. Este hito reemplaza ese checklist ciego por
la lista real de entradas de insumos del día y la lleva al texto de WhatsApp.

## Specs

- **S1**: «Los movimientos que no sean masas pero sean entradas de insumos (ENTSAI == 'E') se clasifican y
  guardan en Trindade como insumos del dia.»
- **S2**: «En la creacion/edicion del Relatorio diario, la seccion Recebimento deja de ser un checklist
  estatico y muestra dinamicamente la lista real de insumos que entraron por ERP (nombre, cantidad, asiento
  ENT_LAN y usuario).»
- **S3**: «En la misma pantalla de Relatorio, un boton 'Sincronizar Insumos do ERP' permite disparar el atajo
  si entraron insumos a ultimo momento para consolidar la lista sin duplicar (idempotencia por ENT_LAN).»
- **S4**: «El reporte diario para WhatsApp formatea la seccion de Recebimento con el detalle del ERP (o 'Nenhum
  insumo recebido no dia').»
- **S5**: «Investiga en modo lectura el modulo de reportes (backend y frontend: report_items, CategorySection.tsx,
  ReportsPage.tsx, ReportEditPage.tsx, exportador de WhatsApp y la tabla aero_movements). Analiza la viabilidad,
  edge cases y estructura de datos necesaria. Luego redacta el borrador del artefacto ODD … No implementes
  codigo todavia.»
- **S6**: Ninguna escritura nueva de insumos: la lista se **deriva** de `aero_movements`; no se copia a
  `report_items` ni a una tabla propia, para que un mismo asiento no viva en dos lugares que puedan divergir.
- **S7**: La sección es informativa y **no bloquea** el guardado del reporte: las temperaturas siguen siendo la
  única validación obligatoria. Un día sin snapshot se muestra como tal, nunca como «no hubo insumos».
- **S8**: Toda la pantalla nueva respeta el viewport de 375 px: cero desborde horizontal y acciones de 44 px.
- **S9**: Paridad de traducciones `pt-BR`/`es` sin claves huérfanas y sin placeholders rotos.

## Decisiones propuestas (a validar antes de codificar)

- **D1 — Fuente única: `aero_movements`.** El espejo ya guarda *todos* los asientos del día, no sólo masas: en
  producción, el 2026-10-29, `movement_type='E'` incluye `001774 CAIXA DE PAPELAO PEQUENA MISTA` (asiento 35911,
  Viviane, 3.606 un) junto a nueve asientos de masa. No hace falta una ingesta nueva ni una tabla de insumos.
- **D2 — Clasificación en el backend, una sola vez.** `GET /api/reports/receipts?date=YYYY-MM-DD` devuelve los
  insumos ya clasificados. El filtro no se reimplementa en el SPA: el export de WhatsApp y la pantalla tienen que
  contestar lo mismo, y dos copias de la regla divergen sin que nadie lo note.
- **D3 — «Insumo» = entrada (`E`) que no es una masa producida.** Se excluyen los 10 códigos de `MASSAS`
  (`modules/stock/stock.catalog.ts`) normalizando ceros a la izquierda (`000084` ≡ `84`), igual que
  `importCodeKey` en `aero-mirror.ts`. Los `TERCEIRIZADOS` del mismo catálogo **sí** son recebimento (batata,
  canudo, pizza, queijo): las tareas fijas 44–49, 52 y 55–56 ya los nombraban.
- **D4 — Reemplazo, no convivencia.** La categoría 4 (`Recebimento`) deja de pintarse como checklist y deja de
  leer `report_items` en el export; se emite la sección derivada. Se identifica por una constante
  `ERP_RECEIPT_CATEGORY_ID = 4` documentada, coherente con los ids fijos de `reference-data.ts`, sin migración.
- **D5 — Día calendario, no turno.** La sección usa `reports.report_date` completo, igual que la sección de
  masas (`massasLaunchDayView`). Un turno noche que cruza medianoche no parte los insumos del día en dos.
- **D6 — Refresh on-demand = el mismo atajo, con retorno al reporte.** El servidor no alcanza el ERP: sólo el
  iPhone. El botón dispara el sobre ya existente (`aero_action=movimentos`) y agrega `aero_return=/reports/<id>`
  al callback; `MassasLaunchPage` guarda el snapshot y redirige ahí. Sin cambios en el atajo instalado.

## Decisiones validadas (respuestas del arquitecto)

- **Q1 — Sí, una fila por renglón.** `movimentoitem` repite `ENT_LAN` cuando el documento trae varios productos, y
  la PK `(movement_date, ent_lan)` colapsaba los renglones. Se aprobó la **revisión 8**: `aero_movements` se
  reindexa por `(movement_date, ent_lan, product_code)`.
- **Q2 — Conservar el histórico.** Los reportes anteriores al corte siguen mostrando sus tildes; el espejo manda
  desde la fecha del hito (`2026-10-29`), no hacia atrás.
- **Q3 — Tres estados distintos.** «Nenhum insumo recebido no dia» (día consultado y vacío) se separa de
  «Pendente de sincronização ERP» (día sin snapshot).
- **Q4 — Lista plana ascendente.** `• <desc>: <qty> un (Lançamento #<ent_lan> · <usuario>)`, sin agrupar por rubro.

## Estructura de datos

Sin tablas nuevas, salvo que Q1 se confirme:

```
GET /api/reports/receipts?date=2026-10-29
200 {
  date: '2026-10-29',
  migrated: true,                 // false si la revisión de aero_movements no corrió
  syncedAt: '2026-10-29T18:04:11.000Z' | null,
  receipts: [
    { entLan: 35911, productCode: '001774', productDesc: 'CAIXA DE PAPELAO PEQUENA MISTA',
      quantity: 3606, operatorName: 'VIVIANE', entryDate: '2026-10-29' }
  ]
}
503 { code: 'AERO_MOVEMENTS_NOT_MIGRATED' }      // mismo patrón que /integration/aero/movements
```

- Orden por `ent_lan` ascendente. Cantidad 0 se incluye (es lo que el ERP dijo).
- `syncedAt` = `MAX(synced_at)` del día, para que la pantalla pueda decir cuán vieja es la foto.
- Si Q1 se confirma: revisión 8 de migración que recrea `aero_movements` con
  `PRIMARY KEY (movement_date, ent_lan, product_code)` y copia las filas existentes.

## Tasks

- **T1** (S1, Q1) — **hecha** (`004225e`): revisión 8 de `aero_movements` con clave `(movement_date, ent_lan,
  product_code)`, copia verificada por conteo dentro de una transacción `immediate`, `saveAeroMovements` con el
  `ON CONFLICT` de tres columnas y `ORDER BY ent_lan, id`. Tests de migración 7→8 (una fila preservada, dos
  renglones del mismo asiento conviven, duplicado exacto rechazado) y de ruta.
- **T2** (S1, S6) — **hecha** (`87d06b2`): `receipts.service.ts` con `supplyReceipts` (entrada `E` que no es masa,
  ceros normalizados) y `dayReceipts` (`migrated`, `syncedAt` = sello más nuevo del día). 10 tests.
- **T3** (S2) — **hecha** (`87d06b2`, `a29bd6a`): `GET /api/reports/receipts?date=YYYY-MM-DD` (200/400/503),
  `ReportReceiptsResponseSchema` estricto y entrada en `contracts/openapi.ts` + `EXPECTED_PATHS` del test de
  contrato. `openapi.json` regenerado.
- **T4** (S4, D4) — **hecha** (`51e134d`): la categoría 4 del export sale del espejo, no de los tildes. Formato
  aprobado en Q4; estados `_Nenhum insumo recebido no dia._` y `_Pendente de sincronização ERP._`. Fallback a los
  tildes sólo para fechas anteriores a `ERP_RECEIPT_SINCE` (`2026-10-29`).
- **T5** (S2) — **hecha** (commit de frontend): `lib/receipts.ts` puro (`usesErpReceipts`,
  `readReportReceiptsDay` que rechaza cuerpos a medias) con tests, `ReceiptsSection.tsx` montado en
  `ReportsPage`, `ReportEditPage` y `ReportViewPage` en lugar de `CategorySection` para la categoría 4.
  `ReportItemDetail` ganó `category_id`, así que la vista identifica la sección por identidad y no por nombre.
- **T6** (S3, D6) — **parcial**: la sección enlaza a Lançamento de Masas, donde el atajo ya sincroniza el día
  completo, y se refresca con «Atualizar». **No** dispara el atajo desde el reporte: el `aero_return` de D6 vive
  en el callback de iOS y hay que probarlo en el iPhone de Wilkin antes de darlo por bueno.
- **T7** (S9) — **hecha**: 11 claves nuevas en `pt-BR.json` y `es.json` (`reports.receipts*`).
- **T8** (S8) — **hecha**: E2E `report-receipts.spec.ts` a 375 px (sin checkboxes de cat. 4, lista del espejo,
  masa excluida, botón de 44 px, cero desborde). Suites completas: backend 565, frontend 294, Playwright 63.
- **T9** (S5) — **hecha**: este documento y el reporte de viabilidad.

## Riesgos

- **Item 1** si Q1 confirma la migración: recrear `aero_movements` con datos existentes exige copia verificada y
  conteo antes/después; se prueba sobre una copia de la base, nunca sobre producción.
- **Item 3** en T4: el texto de WhatsApp es un contrato que el grupo de la fábrica ya consume; el cambio es
  pedido, pero se documenta en `docs/` para que no sorprenda.
- **Item 6** si T5 se entrega sin los tests de la lib pura y de la sección.

## Log

- **L1** — «Ahora vamos a encarar el siguiente hito arquitectonico que definimos con Wilkin: conectar los insumos
  recibidos en el ERP directamente con el modulo Relatorio. … Hoy en la consulta de movimientos
  /api/movimentoitem, Delphi/Firebird devuelve todos los asientos del dia. Cuando llega un insumo (como vimos hoy
  en produccion con el asiento #35911 de Viviane con 3.606 un de CAIXA DE PAPELAO PEQUENA MISTA, harinas,
  bobinas, etc. con ENTSAI == 'E'), Trindade los descarta porque no estan en MASSAS_CATALOG. Al mismo tiempo, en
  el modulo Relatorio, la categoria 4 (Recebimento) tiene checkboxes fijos y ciegos … que el operario tiene que
  tildar a mano sin cantidades ni numero de asiento. … 1. Ingesta: Los movimientos que no sean masas pero sean
  entradas de insumos (ENTSAI == 'E') se clasifican y guardan en Trindade como insumos del dia. 2. Relatorio
  dinamico: … 3. Refresh on-demand: … sin duplicar (idempotencia por ENT_LAN). 4. WhatsApp Export: … Tu tarea en
  esta primera etapa: Investiga en modo lectura … Luego redacta el borrador del artefacto ODD en
  odd/tasks/relatorio-recebimento-erp.md con las especificaciones y tareas. No implementes codigo todavia …
  para que lo validemos juntos antes de codificar.»
- **Evidencia observada (2026-10-29, base de producción, sólo lectura)**: `aero_movements` tiene 10 filas para
  el día, todas `E`, `ENT_LAN` 35907–35916. La única entrada no-masa es la 35911 (`001774`, 3.606 un, VIVIANE);
  las otras nueve son masas con códigos de seis dígitos (`000084`, `005000`, `000022`, `001047`, `000060`).
  `findMassaProduct` compara sin normalizar ceros, así que la clasificación del backend necesita su propia
  normalización.
- **L2 — Decisiones del arquitecto (resumen del intercambio, no cita literal)**: aprobó revisión 8 con clave de
  tres columnas (Q1), conservar los tildes históricos para fechas sin snapshot (Q2), distinguir día vacío de día
  sin sincronizar (Q3) y lista plana ascendente con el formato `• <desc>: <qty> un (Lançamento #<ent_lan> ·
  <usuario>)` (Q4). Con eso T1 dejó de estar bloqueada.
- **L3 — Desvío en T6 (decisión del orquestador, sujeta a revisión)**: el reporte no dispara el atajo de iOS.
  D6 proponía un `aero_return` en el callback, pero ese flujo sólo se puede validar en el iPhone del operario y
  el hito ya tenía un camino completo (Lançamento de Masas → sincronizar → el reporte lee el espejo). Se enlaza a
  esa pantalla y se deja el disparo directo como pendiente explícito para la próxima prueba en fábrica.
- **L4 — Evidencia (2026-10-29)**: `004225e` (rev 8), `87d06b2` (servicio + ruta), `a29bd6a` (contrato),
  `51e134d` (export). Backend `npm test`: 565/565. Frontend `npm test`: 294/294. Playwright: 63/63. `tsc
  --noEmit` limpio en ambos paquetes.
