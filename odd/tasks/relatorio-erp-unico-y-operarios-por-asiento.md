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
  aero_movements»: entradas (`movement_type = 'E'`) cuyo `product_code` está en el catálogo `MASSAS`, que son
  exactamente diez códigos —`1045`, `39`, `60`, `84`, `91`, `1192`, `5000`, `1047`, `22`, `1616`— comparados sin los
  ceros de relleno del ERP (`000084` es `84`). Sin excepción y sin restar lo que un lanzamiento local crea ya
  conciliado.
- **S3** — «Recebimento de Insumos» lee **toda** entrada de `aero_movements` cuyo código no esté en esos diez, y la
  regla es taxativa: `001800` brotinho, `001591` unit fa, `000138` pizza, `000787` canudo y `001040` farinha son
  insumo/recebimento, no masa, aunque estén en el catálogo de tercerizados
  (`packages/backend/src/modules/reports/receipts.service.ts:72`, `:107`).
- **S4** — Con el reporte del 2026-10-30, el texto de WhatsApp no menciona ningún lanzamiento local huérfano —«los
  2.800 rolos de Jaime»— y muestra a `JONAS` con `6940 un` y a `VIVIANE` con su total del día **bajo «Recebimento de
  Insumos» y no bajo «Massas do dia»**, «sin duplicaciones ni números inventados». El módulo Lançamento de Massas no
  muestra ni acepta ninguno de esos códigos: `launchMassas` ya rechaza todo `product_code` fuera del catálogo
  (`massas.service.ts:165`) y la tabla unificada filtra por el mismo catálogo.
- **S5** — En Relatório, Web y Android muestran «un solo apartado/tarjeta: «Movimentações do ERP (Massas e
  Recebimento)»» que contiene los dos bloques; ya no son dos tarjetas separadas
  (`packages/frontend/src/pages/ReportsPage.tsx:257` para massas y `:341` para a categoría de Recebimento). La tarjeta
  muestra **sólo el total producido del ERP y el desglose por operario**: la barra comparativa local (Producido /
  No ERP / Diferencia) sale del Relatório y queda en el módulo Lançamento de Massas (Q1).
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
  ningún lanzamiento nuevo, ningún `updated_at` tocado. Un operario que existe con `is_active = 0` no cuenta como
  vinculado: el rechazo dice que está inactivo y pide reactivarlo, y la UI no ofrece crear un duplicado que rompa por
  el `UNIQUE` del login (Q4).
- **S16** — En la tabla unificada / espejo ERP, «si existe: muestra el botón normal [ Importar ]»; «si NO existe (ej.
  JEFFERSON o JOAO H): para el Administrador, se muestra el botón inline [ + Criar Operário: JEFFERSON ]». El nombre
  mostrado es el del asiento, en mayúsculas como lo escribió el ERP.
- **S17** — Un clic en ese botón «crea automáticamente el usuario con rol Trabalhador (role_id: 2), display 'Jefferson',
  login 'jefferson' y contraseña determinista 'jefferson1234567890' (>= 15 caracteres)». El login se deriva **sin
  puntos**: `JOAO H` da `joaoh` y su contraseña `joaoh1234567890` (Q3), y la misma regla rige el login sugerido del
  Frente 2.
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
- **S22** — Las dos secciones de la tarjeta unificada se reparten el día sin solaparse: un asiento está en `Massas do
  dia` o en `Recebimento de Insumos`, nunca en las dos, y el total de la tarjeta es la suma de ambas.

## Decisiones resueltas (L2)

Las cuatro quedaron respondidas por el usuario (L2) antes de implementar.

- **Q1 — barra de balance del Relatório: se retira.** El Relatório es 100 % ERP: muestra el total producido del ERP y el
  desglose limpio por operario. La barra comparativa pertenece al módulo Lançamento de Massas, donde sí hay dos fuentes.
- **Q2 — los dos lanzamientos bajo Wilkin: se corrigen en T11.** Script idempotente, previa copia de `trindade.db`, que
  crea `joaoh` y `jefferson` y reasigna el `user_id` de los asientos `#35947` y `#35952`.
- **Q3 — derivación de login: sin puntos.** `JOAO H` → `joaoh` y `joaoh1234567890`; la misma regla rige el login
  sugerido del Frente 2 (`Maria Silva` → `mariasilva`).
- **Q4 — operario inactivo: se informa, no se duplica.** `is_active = 0` no cuenta como vinculado; el backend y la UI
  dicen que está inactivo y piden reactivarlo, sin ofrecer un alta que rompa por el `UNIQUE` del login.

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
| T1 | S1, S2, S3 | `massas-report.service.ts` ERP-only; catálogo de masas vs insumos decidido en el servidor; tests del servicio con el día auditado | contrato (respuesta del endpoint) | `4f97dda` |
| T2 | S4, S8 | texto de WhatsApp nutrido del mismo snapshot ERP; tests del mensaje con Jonas/Viviane y sin Jaime | contrato (texto compartido) | `4f97dda` |
| T3 | S5, S6 | Web: una tarjeta `Movimentações do ERP (Massas e Recebimento)` + botón único de sync en `ReportsPage.tsx`/`ReceiptsSection.tsx`/`MassasReportSection.tsx` | 3 (contrato de UI existente) | `d134b4c` |
| T4 | S7 | Android: misma tarjeta y mismo botón en el detalle del reporte, strings `pt`/`es` | — | `e6c2352` |
| T5 | S9, S10, S11, S13 | Web: botón `+ Cadastrar Operário` y feedback verde; el alta deja de estar atada a los handlers | — | `d134b4c` |
| T6 | S12 | Web: WhatsApp/Copiar exigen el alta y no la reintentan | — | `d134b4c` |
| T7 | S9–S13 | Android: botón, feedback y las dos acciones sin alta implícita | — | `e6c2352` |
| T8 | S14, S15, S20 | backend sin fallback, `errorCode` nuevo para asiento sin dueño y para operario inactivo, OpenAPI y contrato Kotlin regenerados | 3 (changing a contract others consume) | `4ab41bf` |
| T9 | S16, S17, S19 | Web: botón inline `+ Criar Operário: <NOMBRE>` para administrador, aviso de reactivación y refresco de la tabla | — | `d134b4c`, S19 en `feat(erp): a seat knows why it has no owner` |
| T10 | S16–S19 | Android: mismo botón inline, mismo refresco, paridad de textos | — | `e6c2352` |
| T11 | S21, Q2 | script idempotente que crea `joaoh` y `jefferson` y reasigna los asientos `#35947` / `#35952`, con copia previa de `trindade.db` | 1 (reescribe datos) | `dbea09d` |
| T12 | S5–S8 | suites completas, verificación independiente de los frentes con riesgo, Docker y APK | 5 (delivery) | `5271d0c`, `a95708b` |

Ruta: todas inline salvo que, al implementar, T3/T5/T9 y T4/T7/T10 se confirmen como unidades independientes de varias
minutos cada una; en ese caso van tres writers en background con superficies disjuntas (Web, Android, backend).

## Contrato que las pantallas consumen (implementado en T8)

`GET /api/massas-launch/report?date=YYYY-MM-DD` responde el día **sólo** desde `aero_movements`:
`operators[]`, `products[]`, `totalBoxes`, `totalUnits` y un `balance` que compara el día contra sí mismo
(`produced == registered`, `differenceUnits: 0`), por lo que la pantalla ya no dibuja la barra comparativa.

`POST /api/massas-launch` (importación) responde dos rechazos nuevos, ambos 409 y sin escribir nada:

| `code` | Cuándo | Sentencia que muestra la pantalla |
| --- | --- | --- |
| `MASSAS_OPERATOR_NOT_LINKED` | el operario del asiento no existe en Trindade | «O operador deste lançamento do ERP não está vinculado no Trindade: cadastre o operário antes de importar» |
| `MASSAS_OPERATOR_INACTIVE` | el operario existe con `is_active = 0` | «O operador deste lançamento do ERP está inativo no Trindade: reative o operário antes de importar» |

El alta del operario que destraba el asiento es `POST /api/admin/users` con
`{ username, password, display_name, role_id: 2 }`: el login sale de `deriveUsername(<NOMBRE DEL ASIENTO>)` y la
senha de `quickWorkerPassword(login)`, la misma pareja del Frente 2.

### El espejo dice por qué un asiento no tiene dueño (S19)

`GET /api/integration/aero/movements` añade `owner_status: 'active' | 'inactive' | 'missing'` a cada asiento.
`owner_user_id` sigue siendo `null` cuando nadie puede importar, y el estado nuevo dice si eso es porque la cuenta
está desactivada (`inactive`, se reactiva en Admin › Operários) o porque el nombre no existe en Trindade
(`missing`, se crea desde la fila). Sin ese dato, la fila de un operario dado de baja ofrecía crear un login que
el índice único rechazaría después, y escondía la acción que sí funciona.

### T11: qué encontró la corrección en la base real

La copia de `trindade.db` de producción (31/10) no tiene ningún lanzamiento importado: los 74 registros de
`massa_launches` son manuales y ninguno lleva `ent_lan`. El espejo sincronizó sólo el 29 y el 30 de octubre
(asientos `35907`–`35939`), así que los asientos `#35947` y `#35952` todavía no llegaron: `JEFFERSON` sí
aparece en seis asientos del 29/10 y `JOAO H` no aparece. No hay nada que reasignar hoy.

El script (`packages/backend/scripts/adopt-erp-seats.ts`) queda listo y probado: en cuanto la sincronización
traiga esos asientos y alguien los importe, se corrige con `--ent-lan 35947,35952` previa copia automática. Con
el flujo nuevo, esos asientos ni siquiera se pueden importar mal: la tabla unificada ofrece `+ Criar Operário`
en la fila del asiento sin dueño, y el alta real de `jefferson` sale de ahí.

## Log

### L4 — Corrección de la revisión (verbatim)

> «El botón único de sync no debe mentir cuando el dispositivo no tiene acceso a la empresa, y la fila de un
> operario inactivo no debe ofrecer un alta que el índice único va a rechazar.»

Un solo cableado (`triggerAeroMovementsSync`) dispara el puente desde el reporte y desde el recibo; si no hay
acceso, la cabecera dice `Atualizar` y refresca lo que ya leyó. El asiento de un operario dado de baja muestra
`Operador inativo: reative em Admin › Operários` en lugar del alta, y `owner_status` viaja en el espejo para que
la pantalla no tenga que adivinar.

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

### L2 — Decisiones del usuario (verbatim)

> Che, impecable la redacción del artefacto ODD en odd/tasks/relatorio-erp-unico-y-operarios-por-asiento.md. Las 21
> specs y 12 tareas cubren los 3 frentes con total precisión.
>
> Acá tenés las respuestas oficiales a las cuatro decisiones para destrabar la implementación:
>
> 1. Q1 (Barra de balance en Relatório): Aprobado retirar la barra comparativa local del Relatório. El Relatório ahora
> es 100% ERP, así que muestra únicamente el total producido del ERP y el desglose limpio por operario. La barra de
> balance comparativa pertenece al módulo Lançamento de Massas.
> 2. Q2 (Los dos lanzamientos auditados bajo Wilkin): Aprobado corregirlos en T11 con un script idempotente (previa
> copia de seguridad de trindade.db). Crea los usuarios joaoh y jefferson y reasigna el user_id correspondiente a los
> lanzamientos #35947 y #35952 para que la base quede limpia y sin distorsión.
> 3. Q3 (Derivación de login): Usá joaoh (sin puntos), minúsculas y limpio, manteniendo la contraseña determinista de >=
> 15 caracteres (joaoh1234567890).
> 4. Q4 (Operario inactivo): Aprobado. Si el operario ya existe pero está con is_active = 0, la UI y el backend deben
> indicar que está inactivo y pedir reactivación en vez de intentar crear un usuario duplicado que rompa por UNIQUE
> constraint.
>
> Tenés LUZ VERDE para arrancar con la implementación de T1 en adelante. Todo directo a main sin ramas, con commits
> atómicos convencionales y cero mención a IA. Dale gas.

### L3 — Corrección del usuario sobre la separación masa / insumo (verbatim)

> Che, observación clave de Wilkin recién confirmada en código:
> Lo que lanzaron Jonas y Viviane en el ERP (códigos 001800 brotinho, 001591 unit fa, 000138 pizza, 000787 canudo,
> 001040 farinha) NO pertenece al catálogo MASSAS (que son únicamente los 10 códigos: 1045, 39, 60, 84, 91, 1192,
> 5000, 1047, 22, 1616).
> Por lo tanto, la regla de filtrado de supplyReceipts / isSupplyReceipt es taxativa:
> - Todo lo que lanzaron Jonas y Viviane es INSUMO / RECEBIMENTO.
> - En la tarjeta unificada del Relatório y en el mensaje de WhatsApp, Jonas y Viviane deben mostrarse bajo la
>   sección 'Recebimento de Insumos' y NO bajo 'Massas do dia'.
> - En el módulo 'Lançamento de Massas', no deben aparecer jamás porque ese módulo es exclusivo del catálogo MASSAS.
> Asegurate de que S2, S3, S4 y los tests de T1/T2 respeten esta separación limpia.

### L5 — Evidencia de T4, T7, T10, T12 (Android y suites)

Android cierra los tres frentes con la misma regla que la Web: una sola tarjeta `Movimentações do ERP` con el
botón de sync en la cabecera, `+ Cadastrar Operário` explícito antes de compartir y el alta inline del asiento sin
dueño para el administrador. `:app:testDebugUnitTest` corre en el contenedor fijado (1.131 pruebas, 0 fallos), y
`:app:assembleRelease -PapiBaseUrl=https://trindademasas.duckdns.org/ -PversionName=0.7.0 -PversionCode=7000`
produce `app-release-unsigned.apk` (11.136.752 bytes) en el mismo contenedor. Va sin firmar porque este entorno no
tiene el keystore: firmar y publicar es decisión del usuario, no de la tarea.

Verificación de la entrega: backend 640 pruebas en verde, frontend 468, Playwright 82 escenarios en verde sobre
un servidor limpio, `docker build -f docker/Dockerfile.web` y la imagen `trindade-api:check` construidas.

APK firmado y desplegado: `:app:assembleRelease -PrequireSigned=true` con el keystore oficial montado en `/keys`
produce `app-release.apk` (versionCode 7000, versionName 0.7.0, 11.149.040 bytes, un firmante, esquema v2,
certificado `ee0e404b…431f6`). Las mismas copias quedan en `~/Desktop`, `~/backups` y `packages/backend/apk`, y
`GET /api/app/latest-release` responde `v0.7.0` con `apkSize` 11149040 después de reconstruir `api` y `web`; el
sha256 del binario descargado coincide con el compilado (`903b37af…298d4b`).

Dry-run de adopción contra una copia de la base productiva (checkpoint de WAL incluido): el asiento 35947 lo ocupa
`JOAO H` → crearía `joaoh` y movería 1 lanzamiento; el 35952 lo ocupa `JEFFERSON` → crearía `jefferson` y movería
1 lanzamiento. El md5 del archivo no cambió entre dos corridas: el dry-run no escribe nada.

La suite E2E volvió a verde después de tres correcciones que el propio frente destapó:

- el día compartido entre specs ya no se puede fabricar en el pasado (`MASSAS_HISTORY_FROZEN`), así que los specs
  siembran el día de hoy y juzgan sus propias filas por id en vez de contar la tabla entera;
- la tarjeta del día se dibujaba una vez por categoría con temperaturas: con dos, el mismo día aparecía dos veces
  en la página, y ahora sigue a la última;
- los specs que configuran, prueban o lanzan con la integración corren con el user agent del iPhone, que es el
  dispositivo para el que el gate de dispositivos habilita esos controles.
