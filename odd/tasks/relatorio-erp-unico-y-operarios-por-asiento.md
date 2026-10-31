# Relatório com uma só verdade, alta explícita de operarios e operario creado desde el asiento

Sale de la auditoría de la jornada de planta del 2026-10-30 sobre `trindade.db` y `aero-integration.log`. Cierra tres
frentes que hoy comparten un mismo defecto de fondo: el reporte y la importación deciden la autoría mezclando dos
sistemas, y la pantalla que crea operarios no deja crear uno.

Estado: **especificación a revisión**. No se escribió código todavía (S0).

## Specs

### Frente 1 — el Relatório deja de mirar `massa_launches`

- **S1** — El módulo Relatório «NO se vincula de ninguna manera con el módulo Lançamento de Massas ni con la tabla
  massa_launches»: `massasReportDayView` (`packages/backend/src/modules/massas/massas-report.service.ts:147`) deja de
  llamar a `massasLaunchDayView` y de importar `massa_launches`; su única fuente es `aero_movements` con
  `movement_type = 'E'`.
- **S2** — «Massas do dia» lee «exclusivamente los movimientos de entrada de productos del catálogo de masas en
  aero_movements»: entradas (`movement_type = 'E'`) cuyo `product_code` está en el catálogo `MASSAS`, sin excepción y
  sin restar lo que un lanzamiento local crea ya conciliado.
- **S3** — «Recebimento» lee «las entradas de insumos/materias primas en aero_movements»: entradas cuyo código no está
  en el catálogo de masas, que es lo que hoy ya hace `supplyReceipts`
  (`packages/backend/src/modules/reports/receipts.service.ts:72`, `:107`).
- **S4** — Con el reporte del 2026-10-30, el texto de WhatsApp no menciona ningún lanzamiento local huérfano —«los
  2.800 rolos de Jaime»— y sí muestra los asientos del ERP con su cifra real: `JONAS` con `6940 un` y el asiento de
  `VIVIANE` con su total del día, «sin duplicaciones ni números inventados».
- **S5** — En Relatório, Web y Android muestran «un solo apartado/tarjeta: «Movimentações do ERP (Massas e
  Recebimento)»» que contiene los dos bloques; ya no son dos tarjetas separadas
  (`packages/frontend/src/pages/ReportsPage.tsx:257` para massas y `:341` para a categoría de Recebimento).
- **S6** — Ese apartado tiene «un solo botón: [ 🔄 Sincronizar com ERP ] en la cabecera, que ejecuta la lectura del ERP
  vía bridge/atajo y refresca ambos bloques a la vez». La lectura es la que ya existe: `POST
  /api/integration/aero/movements` con el día consultado, y ambos bloques se repintan con la respuesta.
- **S7** — Android ofrece la misma tarjeta unificada y el mismo botón único en el detalle del reporte, con paridad de
  textos en `pt` y `es`.
- **S8** — «El texto generado para compartir por WhatsApp se nutre al 100% de esta verdad del ERP»: el mensaje de
  massas y el de recebimento salen del mismo snapshot ERP que pinta la tarjeta, sin leer nada local.

### Frente 2 — alta explícita en «Compartilhar Trindade»

- **S9** — En el modo «Criar Novo Operário» hay «un botón explícito y visible [ + Cadastrar Operário ] directamente
  debajo de los campos de credenciales», en Web (`packages/frontend/src/pages/ShareAppPage.tsx`) y en Android
  (`packages/android/app/src/main/java/com/trindade/app/share/ShareAppScreen.kt`).
- **S10** — Los campos son «Nome de Exibição, Usuário de Login (sugerido), Função fija e invariable Trabalhador
  (role_id: 2), y Senha Temporária (mínimo estricto de 15 caracteres: <usuario>1234567890...)». La derivación sigue en
  `packages/frontend/src/lib/share-app.ts:42` y `:54` y en `ShareModels.kt`, con los mismos ejemplos.
- **S11** — Al tocar el botón se ejecuta `POST /api/admin/users` con `{ username, display_name, password, role_id: 2 }`,
  «muestra feedback visual verde de éxito en pantalla», y «el operario queda registrado y habilitado de inmediato para
  loguearse y operar en Web y Android».
- **S12** — «Los botones de WhatsApp y Copiar reconocen que ya fue creado y solo envían la invitación sin reintentar el
  alta»: ninguno de los dos crea la cuenta por su cuenta. Si el operario todavía no fue cadastrado, el envío no ocurre
  y la pantalla pide el alta explícita.
- **S13** — Un login ya tomado, un rechazo del servidor y un servidor inalcanzable se informan cada uno con su propio
  mensaje, y el operario no queda creado a medias.

### Frente 3 — el asiento del ERP crea a su operario antes de importar

- **S14** — El backend «elimina definitivamente el fallback»: en `packages/backend/src/modules/massas/massas.service.ts:175`
  desaparece `resolveOperatorUserId(db, params.operatorName) ?? params.userId`. «Si un asiento no tiene usuario
  vinculado en Trindade, el backend rechaza la importación en lugar de inventar un dueño», también para un
  administrador (lo que hoy permite `canImportSeat`, `massas-import-policy.ts:25`).
- **S15** — El rechazo llega con un `errorCode` propio y su sentencia en portugués, y deja `massa_launches` intacto:
  ningún lanzamiento nuevo, ningún `updated_at` tocado.
- **S16** — En la tabla unificada / espejo ERP, «si existe: muestra el botón normal [ Importar ]»; «si NO existe (ej.
  JEFFERSON o JOAO H): para el Administrador, se muestra el botón inline [ + Criar Operário: JEFFERSON ]». El nombre
  mostrado es el del asiento, en mayúsculas como lo escribió el ERP.
- **S17** — Un clic en ese botón «crea automáticamente el usuario con rol Trabalhador (role_id: 2), display 'Jefferson',
  login 'jefferson' y contraseña determinista 'jefferson1234567890' (>= 15 caracteres)».
- **S18** — «La tabla se refresca al instante, reconoce al nuevo operario y el botón se transforma en [ Importar ] con la
  autoría real asignada a su id»: el lanzamiento importado a continuación queda con `user_id` del operario creado, no
  del importador.
- **S19** — La misma regla y el mismo botón existen en Web y en Android, y un trabajador (no administrador) no ve el
  botón de alta: ve el motivo por el que ese asiento no es suyo.

### Transversal

- **S20** — El contrato OpenAPI (`packages/contracts/openapi.json`) y el cliente Kotlin reflejan los campos y el
  `errorCode` nuevos, regenerados por los scripts del repo y verificados por `scripts/verify-openapi-artifact.sh`.
- **S21** — Ninguna fila existente de `massa_launches` ni de `aero_movements` se borra o reescribe sin una decisión
  explícita registrada en el Log (ver Q2).

## Decisiones abiertas antes de implementar

Estas cuatro no las puedo resolver yo: cambian producto o tocan datos ya guardados.

- **Q1 — qué pasa con la barra de balance del Relatório.** Hoy compara «producido» (local + ERP no conciliado) contra
  «registrado» (ERP). Si el reporte es ERP-only, el lado local desaparece. Opciones: **(a)** la barra muestra sólo el
  total del ERP y el desglose por operario; **(b)** la barra se retira del Relatório y queda en el módulo Lançamento,
  donde sí hay dos fuentes; **(c)** la barra compara entradas ERP contra asientos conciliados del día. Recomiendo
  **(b)**: el reporte no debe insinuar una diferencia que ya no puede explicar.
- **Q2 — los dos lanzamientos ya guardados bajo Wilkin.** Los asientos `#35947` (JOAO H, 270 un) y `#35952` (JEFFERSON,
  135 un) ya están en `massa_launches` con `user_id = 1`. Opciones: **(a)** dejarlos como están y sólo bloquear los
  futuros; **(b)** corregirlos con un script idempotente y auditable que crea el operario y reasigna el `user_id`,
  previa copia de la base. Recomiendo **(b)** después de T9, como tarea separada y con tu OK explícito por escrito,
  porque reescribe datos de producción.
- **Q3 — nombres que el ERP escribe distinto.** El asiento dice `JEFFERSON` y `JOAO H`. Al derivar: display `Jefferson`,
  login `jefferson`; para `JOAO H`, display `Joao H` y login `joao.h`. ¿Aceptás `joao.h`, o preferís `joaoh`? La regla
  actual (`deriveUsername`) produce el punto.
- **Q4 — operario inactivo.** `resolveOperatorUserId` sólo considera `is_active = 1`
  (`massas-operator.ts:93`). Un asiento de alguien dado de baja cuenta hoy como «no existe» y ofrecería crear un
  duplicado con el mismo login. Propongo que la UI y el backend distingan «no existe» de «existe pero inactivo», y que
  el segundo caso no ofrezca alta sino que pida reactivar. Confirmame si lo incluimos en este frente.

## Fuera de alcance

- El módulo «Lançamento de Massas» sigue leyendo `massa_launches` y sigue mostrando la conciliación contra el espejo: la
  reconciliación no desaparece del producto, se muda de pantalla.
- `MassasHistoryPage` y el histórico Android conservan su lectura actual; no se tocan en este frente.
- No se borra ni se migra ninguna tabla, y no se publica tag ni release de GitHub.

## Evidencia recogida

De una copia read-only de la base del contenedor (`/tmp/audit-trindade.db`, mtime 2026-10-30 17:54):

- `users` no tiene a `jefferson` ni a `joao`: los dos asientos auditados no podían resolver dueño. Confirma la causa
  raíz del Frente 3.
- `aero_movements` del 2026-10-30: `JONAS` 3 movimientos, `6940.0` un; `VIVIANE` 1 movimiento, `3300.0` un. La cifra de
  Jonas coincide exactamente con la auditoría; la de Viviane es la de las 17:54 y creció con el día.
- `massa_launches` del 2026-10-30: `jaime` 1 lanzamiento de `280` cajas (`2.800` rolos) y `wilkin` 1 de `2` cajas,
  ninguno con `ent_lan`. Es el huérfano que hoy entra al reporte y que S4 saca.
- Los asientos `#35947` y `#35952` no están en esa copia: se sincronizaron después. Sus cifras y su atribución quedan
  como las aportó la auditoría del usuario y como criterio de aceptación de T10.

## Tasks

| ID | Specs | Ruta | Riesgo | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S2, S3 | `massas-report.service.ts` ERP-only; catálogo de masas vs insumos decidido en el servidor; tests del servicio con el día auditado | contrato (respuesta del endpoint) | |
| T2 | S4, S8 | texto de WhatsApp nutrido del mismo snapshot ERP; tests del mensaje con Jonas/Viviane y sin Jaime | contrato (texto compartido) | |
| T3 | S5, S6 | Web: una tarjeta `Movimentações do ERP (Massas e Recebimento)` + botón único de sync en `ReportsPage.tsx`/`ReceiptsSection.tsx`/`MassasReportSection.tsx` | 3 (contrato de UI existente) | |
| T4 | S7 | Android: misma tarjeta y mismo botón en el detalle del reporte, strings `pt`/`es` | — | |
| T5 | S9, S10, S11, S13 | Web: botón `+ Cadastrar Operário` y feedback verde; el alta deja de estar atada a los handlers | — | |
| T6 | S12 | Web: WhatsApp/Copiar exigen el alta y no la reintentan | — | |
| T7 | S9–S13 | Android: botón, feedback y las dos acciones sin alta implícita | — | |
| T8 | S14, S15, S20 | backend sin fallback, `errorCode` nuevo, OpenAPI y contrato Kotlin regenerados | 3 (changing a contract others consume) | |
| T9 | S16, S17, S19 | Web: botón inline `+ Criar Operário: <NOMBRE>` para administrador y refresco de la tabla | — | |
| T10 | S16–S19 | Android: mismo botón inline, mismo refresco, paridad de textos | — | |
| T11 | S21 / Q2 | corrección de los dos lanzamientos auditados (sólo con decisión escrita) | 1 (reescribe datos) | |
| T12 | S5–S8 | suites completas, verificación independiente de los frentes con riesgo, Docker y APK | 5 (delivery) | |

Ruta: todas inline salvo que, al implementar, T3/T5/T9 y T4/T7/T10 se confirmen como unidades independientes de varias
minutos cada una; en ese caso van tres writers en background con superficies disjuntas (Web, Android, backend).

## Log

### L1 — Pedido del usuario (verbatim)

> Che, estuvimos analizando en detalle con Wilkin lo ocurrido hoy en planta y auditamos a fondo la base de datos real
> (trindade.db) y los logs de aero-integration.log. Detectamos con total precisión los tres frentes que necesitamos
> encarar con máxima prioridad y rigor arquitectónico en Web y Android.
>
> Queremos que redactes el artefacto ODD completo antes de tirar una sola línea de código, para que lo revisemos y te dé
> luz verde. Te paso el desglose técnico y las decisiones de diseño consensuadas:
>
> ---
>
> ### Frente 1: Módulo Relatório — Desacople absoluto de massa_launches y unificación con ERP
> 1. Causa raíz: massas-report.service.ts venía haciendo una conciliación híbrida entre massa_launches local y
> aero_movements. Esto metió en el reporte de WhatsApp lanzamientos locales huérfanos que nunca fueron al ERP (como los
> 2.800 rolos de Jaime) y omitió o desdibujó asientos reales del ERP (Jonas con 6.940 un, Viviane con 9.053 un).
> 2. Decisión arquitectónica: El módulo Relatório NO se vincula de ninguna manera con el módulo Lançamento de Massas ni
> con la tabla massa_launches. La única fuente de la verdad para el reporte es la consulta que devuelve la BD de la
> empresa (aero_movements con movement_type = 'E').
>    - 'Massas do dia': lee exclusivamente los movimientos de entrada de productos del catálogo de masas en
> aero_movements.
>    - 'Recebimento': lee las entradas de insumos/materias primas en aero_movements.
> 3. UI unificada (Web y Android):
>    - En la pantalla de Relatório unificamos en un solo apartado/tarjeta: «Movimentações do ERP (Massas e
> Recebimento)».
>    - Un solo botón: [ 🔄 Sincronizar com ERP ] en la cabecera, que ejecuta la lectura del ERP vía bridge/atajo y
> refresca ambos bloques a la vez.
>    - El texto generado para compartir por WhatsApp se nutre al 100% de esta verdad del ERP, reflejando fielmente lo
> asentado en la empresa sin duplicaciones ni números inventados.
>
> ---
>
> ### Frente 2: 'Compartilhar Trindade' — Botón explícito de alta de operario
> 1. Causa raíz: En ShareAppPage.tsx y ShareAppScreen.kt, al seleccionar 'Criar Novo Operário', existen los campos de
> texto pero no hay ningún botón de acción para crearlo. Quedó atado de forma oculta a compartir por WhatsApp o copiar
> mensaje.
> 2. Decisión de diseño: Incorporar un botón explícito y visible [ + Cadastrar Operário ] directamente debajo de los
> campos de credenciales.
>    - Campos: Nome de Exibição, Usuário de Login (sugerido), Função fija e invariable Trabalhador (role_id: 2), y Senha
> Temporária (mínimo estricto de 15 caracteres: <usuario>1234567890...).
>    - Al tocar el botón: ejecuta POST /api/admin/users, muestra feedback visual verde de éxito en pantalla, y el
> operario queda registrado y habilitado de inmediato para loguearse y operar en Web y Android.
>    - Los botones de WhatsApp y Copiar reconocen que ya fue creado y solo envían la invitación sin reintentar el alta.
>
> ---
>
> ### Frente 3: 'Lançamento de Massas' — Alta en 1 clic desde el asiento ERP antes de importar
> 1. Causa raíz: Al auditar los logs de hoy, vimos que el asiento #35947 de JOAO H (270 un) y el #35952 de JEFFERSON
> (135 un) quedaron guardados en massa_launches bajo Wilkin (user_id = 1). La causa fue el fallback en massas.service.ts:
>
>    ownerId = resolveOperatorUserId(db, params.operatorName) ?? params.userId
>
>    Como JOAO H y JEFFERSON no existen en la tabla users de Trindade, dio null y le asignó las cajas al usuario
> autenticado (Wilkin). Esto cambió los nombres y mezcló operarios.
> 2. Decisión de diseño:
>    - Backend: Eliminar definitivamente el fallback. Si un asiento no tiene usuario vinculado en Trindade, el backend
> rechaza la importación en lugar de inventar un dueño.
>    - Frontend y Android (tabla unificada / espejo ERP): Evaluamos si el operario del asiento existe en Trindade:
>      * Si existe: muestra el botón normal [ Importar ].
>      * Si NO existe (ej. JEFFERSON o JOAO H): para el Administrador, se muestra el botón inline [ + Criar Operário:
> JEFFERSON ].
>      * Al hacer 1 clic en [ + Criar Operário ]:
>        1. Crea automáticamente el usuario con rol Trabalhador (role_id: 2), display 'Jefferson', login 'jefferson' y
> contraseña determinista 'jefferson1234567890' (>= 15 caracteres).
>        2. La tabla se refresca al instante, reconoce al nuevo operario y el botón se transforma en [ Importar ] con la
> autoría real asignada a su id.
>
> ---
>
> Por favor, armá el artefacto ODD completo con sus especificaciones (S) y tareas (T) bien detalladas para ambas
> aplicaciones en odd/tasks/, guardalo en Engram y avísame cuando esté listo para que lo revise antes de empezar a
> implementar.
