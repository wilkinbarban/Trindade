# Integración Lançamento de Masas (Web) ↔ WebService de stock Aero32

Espejo operativo Trindade → ERP de la empresa para el usuario `wilkin`: ajustes privados en el iPhone,
test de conexión auditado, atajo de iOS para vencer el bloqueo de Mixed Content del navegador, y bitácora
en archivo para inspección por SSH durante la primera prueba en la fábrica.

## Specs

S1 — Feature flag estricto, solo `wilkin` (admin). Texto del pedido: «Esta integración es EXCLUSIVA para el
usuario "wilkin" (admin).» y «Para cualquier otro operario o usuario, toda la UI, botones y lógica de
sincronización con la empresa deben ser COMPLETAMENTE INVISIBLES y deshabilitados.» La regla tiene dos
frentes: (a) el servidor rechaza cualquier otra identidad en el endpoint de auditoría (la autoridad es la
base, nunca el cliente); (b) el cliente no renderiza ni ejecuta nada de la integración si
`username !== 'wilkin'` o el rol no es `Administrador`.

S2 — Sección colapsable «Configuração Integração Empresa» dentro de Lançamento de Masas, visible solo para
`wilkin`. La UI muestra **tres campos y nada más**: «1. URL del WebService (`http://IP:PUERTO/api`) 2.
Usuario 3. Contraseña», guardados «privado y seguro en localStorage del navegador del iPhone». El lanzamiento
es «SIEMPRE por unidades individuales (`UN`)»: «No existe lanzamiento por cajas (`CX`)», así que el selector
de unidad desaparece de la UI y la cantidad que viaja al ERP es siempre `cajas × unidades por caja`. La serie
`ENT_SER` es fija «("APL")» porque así está en el binario Delphi de `base.apk`: el operario no configura serie
ni unidad, y ambas viven como constantes del módulo, no como ajustes.

S3 — Botón «Testar Conexão»: «intenta validar contra /api/usuarios/ y reporta el resultado al backend para
auditoría.» El resultado exitoso guarda `CODIGO` como `ENT_USU`. Cuando la página corre en HTTPS y el
WebService en HTTP plano, la petición es imposible por diseño del navegador y el panel lo dice antes de
intentar, en vez de mostrar un error de red genérico; el intento igual se audita.

S4 — Botón «Instalar Atajo en iPhone»: «permite descargar / abrir la plantilla del Atajo de iOS configurado
para disparar a /api/entradasitem.» La plantilla es una receta exacta (acciones, método, cabeceras, cuerpo,
URL de retorno) copiable desde el panel más el enlace profundo a la app Atajos. Un archivo `.shortcut`
firmado no se puede generar desde el servidor, y una plantilla a medio firmar dejaría a Wilkin con un atajo
roto en la fábrica; la receta se arma en un minuto con copiar y pegar y es verificable a ojo.

S5 — Formulario de lanzamiento: switch «Sincronizar com ERP da Empresa», «activo por defecto para wilkin si
hay credenciales configuradas». Al guardar la masa, además de persistir en la base de Trindade, «emite el
trigger hacia el Atajo de iOS (o x-callback-url) y registra el evento en el log del backend.» El lanzamiento
en Trindade nunca se pierde por un fallo del ERP: primero persiste, después sincroniza.

S6 — Endpoint interno `POST /api/integration/aero/log` «(restringido a admin wilkin)». Cuerpo validado y
estricto; la identidad que se escribe en el log sale del token verificado contra la base, nunca del cuerpo.

S7 — Servicio de logging a `logs/aero-integration.log` con «timestamp, tipo de evento (test_connection,
launch_sync, read_history), usuario (wilkin), URL destino, status HTTP, payload y respuesta/error del
servidor.» Una línea JSON por evento, append-only, con rotación por tamaño para que el archivo no crezca sin
límite. Detalle que el pedido no nombra y se aplica por seguridad: la cabecera `Authorization` (que lleva las
credenciales en base64) no se escribe nunca, y payload/respuesta se truncan.

S8 — Vista móvil (iPhone / Safari) con «touch targets cómodos, márgenes responsivos y diseño limpio Material
3»: objetivos táctiles de 44 px, campos de 16 px de tipografía para que iOS no haga zoom al enfocar, panel a
ancho completo debajo de `sm`, y el mismo lenguaje visual (tarjetas, bordes grises, ámbar de acción) que el
resto del módulo.

S9 — Contratos del WebService tal como están documentados en `docs/registro-integracion-aero-webservice.md`:
autenticación `Authorization: Basic base64(usuario:senha)` con `Accept`/`Content-Type: application/json`;
`/usuarios/` devuelve `{ success, data: { CODIGO, SENHA, NOME } }` y `CODIGO` es el `ENT_USU` del ERP;
`POST /entradasitem` recibe `{ PRO_COD, PRO_DES, PRO_QTD, ENT_DTA, ENT_USU, ENT_SER, ENT_DTANOT, ENT_DTMOVI,
ENT_OBS, LOTES: [] }` con fechas `YYYY-MM-DD` y `LOTES` vacío; `PRO_COD` se envía tal cual sale de
`stock.catalog.ts` porque «Los códigos de producto (PRO_COD) en stock.catalog.ts (5000 = MASSA ROLO 1KG, 1045
= CASEIRA, etc.) son 100% idénticos a los del ERP.»

S10 — Protocolo y entrega: «Desglosá primero todo el plan en odd/tasks/integracion-aero-masas-web.md siguiendo
ODD y TDD.»; «todos los tests queden en verde»; «commits atómicos en main, sin ramas, sin Co-Authored-By»;
«reconstruí la imagen Docker de producción de la Web (docker compose up -d --build web), validá el
healthcheck HTTP 200 en el dominio público y dejalo 100% publicado y listo.» El backend también cambia, así
que la imagen `api` se reconstruye en el mismo despliegue.

S11 — Fecha y botón nunca se solapan en un iPhone. Pedido: «En móvil (`< sm`), cambiar a apilamiento vertical
(`flex flex-col gap-2 w-full sm:flex-row sm:items-center sm:w-auto`)» en las cuatro pantallas que hoy usan
`grid grid-cols-2 items-end gap-2 w-full sm:flex ...`: `MassasLaunchPage` (fecha vs «Ver Histórico»),
`StockCalculatorPage` (fecha vs «Ir ao Histórico»), `MassasHistoryPage` (fecha vs «Voltar para Hoje») y
`StockHistoryPage` (fecha vs «Voltar ao contador»). En móvil cada control ocupa el 100% de su fila y el botón
queda debajo con 44px de alto; desde `sm` vuelven lado a lado en la misma fila. La causa citada: «En Safari
iOS, el input de fecha nativo formatea `28 de out. de 2026`, cuyo ancho intrínseco excede los 160px que le da
la grilla en un iPhone de 375-390px».

S12 — Cierre: «Ejecutar la suite de tests para asegurar 0 fallos»; «Commit atómico directo a `main` sin
Co-Authored-By y push a `origin main`»; «Reconstruir la imagen de producción en Docker (`docker compose build
web && docker compose up -d web`)»; «Validar HTTP 200 en `https://trindademasas.duckdns.org`».

## Tasks

- T1 (S6, S7, S9): servicio de bitácora, contrato zod y ruta `POST /api/integration/aero/log` con guarda de
  identidad; registro en `routes.ts` y en el app de tests; path en `openapi.ts` y `openapi.json` regenerado;
  volumen de logs en `docker-compose.yml`. Ruta: worker en background (backend) · commit `feat(integration)`.
- T2 (S1, S2, S3, S4, S9): librería pura del cliente (`aero-integration.ts`): ajustes en localStorage, flag de
  identidad, normalización de URL, Basic Auth UTF-8, payload de `/entradasitem`, URL del atajo, parseo del
  retorno y clasificación del bloqueo HTTPS→HTTP, con sus tests unitarios. Ruta: worker en background
  (frontend lib) · commit `feat(aero)`.
- T3 (S2, S3, S4, S8): panel «Configuração Integração Empresa» colapsable, con test de conexión, receta del
  atajo y ajustes de operación; textos en `pt-BR.json` y `es.json`. Ruta: inline (parent) · commit `feat(aero)`.
- T4 (S1, S5, S8): cableado en `MassasLaunchPage` — switch de sincronización, disparo del atajo, bitácora del
  pedido y confirmación del resultado (retorno del atajo + confirmación manual cuando falla). Ruta: inline ·
  commit `feat(massas)`.
- T5 (S10): documentación de la integración construida y del procedimiento de prueba en fábrica, incluido el
  comando de inspección del log por SSH. Ruta: inline · commit `docs(aero)`.
- T6 (S10): typecheck, suites completas, build de imágenes `api` y `web`, contenedores sanos y HTTP 200 en
  `https://trindademasas.duckdns.org`. Ruta: inline · evidencia en el Log.
- T7 (S8, S10): pase de diseño móvil con la skill `impeccable` (auditoría + pulido en modo Operate) sobre el
  camino de lanzamiento en iPhone: piso táctil de 44px en las primitivas, título del panel, diálogo del atajo
  con un solo scroll y acciones alcanzables, densidad de la grilla y copy de ambos locales. Ruta: inline ·
  commit `fix(mobile)`.
- T8 (S2, S3, S9): panel reducido a URL, usuario y contraseña; unidad fija `UN` y serie fija `APL` como
  constantes del módulo; payload siempre en unidades; trazabilidad en la bitácora cuando falta el `ENT_USU`
  capturado por «Probar conexión». Tests de la librería reescritos al contrato nuevo. Ruta: inline ·
  commit `refactor(aero)`.
- T9 (S11): fecha y botón apilados en móvil en las cuatro pantallas, con el piso táctil intacto. Ruta:
  inline · commit `fix(mobile)`.
- T10 (S12): suites completas, commit atómico en `main`, push a `origin`, rebuild de la imagen `web` y HTTP
  200 en producción. Ruta: inline · evidencia en el Log.

## Log

L1 — pedido del usuario, literal:

  Hola colega, buenas noches. Tenemos un objetivo clave para dejar listo hoy: planificar, implementar y desplegar en producción la integración del módulo Lançamento de Masas de Trindade Web con el WebService de stock de la empresa (Aero32 / base.apk), preparado para que Wilkin realice su primera prueba mañana en la fábrica desde su iPhone.

  Antes de tocar código, leé con atención la auditoría y los contratos que documentamos y commiteamos en main:
  1. docs/registro-integracion-aero-webservice.md (contratos exactos de endpoints y JSON)
  2. docs/integracion-aero32.md (contexto general de Aero32 y base.apk)

  ### Puntos clave del negocio y arquitectura acordados con Wilkin:

  1. Feature Flag estricto (Solo para wilkin):
     - Esta integración es EXCLUSIVA para el usuario "wilkin" (admin).
     - Para cualquier otro operario o usuario, toda la UI, botones y lógica de sincronización con la empresa deben ser COMPLETAMENTE INVISIBLES y deshabilitados.

  2. Espejo operativo Trindade <-> Empresa:
     - Wilkin lanza y edita sus propias masas (firmadas en el ERP con su código numérico ENT_USU).
     - Los códigos de producto (PRO_COD) en stock.catalog.ts (5000 = MASSA ROLO 1KG, 1045 = CASEIRA, etc.) son 100% idénticos a los del ERP.
     - Puede ver y filtrar el historial de lanzamientos de todos los operarios del día leyendo /api/movimentoitem.

  3. El reto de iPhone (Safari / WebKit) y la solución:
     - Trindade Web corre bajo HTTPS en la nube y el WebService de la fábrica en HTTP plano en la Wi-Fi local (http://IP:PUERTO/api).
     - Safari en iOS bloquea Mixed Content y CORS contra IPs privadas locales.
     - Por eso, el envío local en el iPhone se apoya en la app nativa Atajos de iOS (Apple Shortcuts), que como proceso nativo del sistema no tiene restricciones de navegador.

  4. Qué debemos construir en el Frontend Web (packages/frontend):
     - En el módulo Lançamento de Masas, agregar una sección / drawer colapsable de "Configuração Integração Empresa" (solo visible para wilkin):
       * Campos: URL del WebService (ej. http://192.168.1.50:8000/api), Usuario de empresa, Contraseña de empresa.
       * Guardado privado y seguro en localStorage del navegador del iPhone.
       * Botón "Testar Conexão": intenta validar contra /api/usuarios/ y reporta el resultado al backend para auditoría.
       * Botón "Instalar Atajo en iPhone": permite descargar / abrir la plantilla del Atajo de iOS configurado para disparar a /api/entradasitem.
     - En el formulario de lanzamiento de masas:
       * Checkbox / Switch "Sincronizar com ERP da Empresa" (activo por defecto para wilkin si hay credenciales configuradas).
       * Al guardar la masa en Trindade, además de persistir en PostgreSQL, emite el trigger hacia el Atajo de iOS (o x-callback-url) y registra el evento en el log del backend.
     - Cuidar con obsesión la vista móvil (iPhone / Safari): touch targets cómodos, márgenes responsivos y diseño limpio Material 3.

  5. Qué debemos construir en el Backend (packages/backend):
     - Endpoint interno para registro de auditoría: POST /api/integration/aero/log (restringido a admin wilkin).
     - Servicio de logging que guarde en un archivo logs/aero-integration.log los eventos: timestamp, tipo de evento (test_connection, launch_sync, read_history), usuario (wilkin), URL destino, status HTTP, payload y respuesta/error del servidor.
     - Esto permitirá que inspeccionemos el log por SSH cuando Wilkin haga las pruebas mañana en la fábrica.

  6. Protocolo de trabajo:
     - Desglosá primero todo el plan en odd/tasks/integracion-aero-masas-web.md siguiendo ODD y TDD.
     - Ejecutá paso a paso, asegurando que todos los tests queden en verde.
     - Trunk-based estricto: commits atómicos en main, sin ramas, sin Co-Authored-By.
     - Al terminar, reconstruí la imagen Docker de producción de la Web (docker compose up -d --build web), validá el healthcheck HTTP 200 en el dominio público y dejalo 100% publicado y listo.

  Avanzá con la creación del plan ODD y la implementación. Tenés luz verde.

### Evidencia — implementación y verificación

Commits en `main` (trunk-based, sin ramas, sin Co-Authored-By):

- `66fc56f` feat(integration): record Aero32 WebService events in a rotating audit log — T1.
- `510bc2f` feat(aero): sync massas launches to the company ERP from the phone — T2, T3, T4, T5.
- `f5f2be4` fix(mobile): make the massas launch path readable and thumb-sized on iPhone — T7.

Suites: frontend 195/195, backend 532/532, `tsc --noEmit` sin errores en ambos paquetes.

L2 — salida de la auditoría móvil (Chromium con viewport emulado de 375x667, 390x844 y 414x896,
`isMobile` + `hasTouch`; no es WebKit ni un iPhone físico, y no se sintetizaron gestos táctiles):

- Antes del pulido: 14 controles por debajo de 44px (menú 40x40, «Sair» 49x36, PT 41x32, ES 43x32,
  «Ajuda» 83x30, fecha y «Ver Histórico» 40 de alto, campos de cantidad 36 de alto, compartir 36 y 38).
- Antes del pulido: el panel cortaba su propio título en «Configuração I…» y el diálogo del atajo dejaba
  «Abrir o app Atajos» y «Fechar» fuera del panel, sin scroll que llegara a ellos.
- Antes del pulido: `pt-BR` decía «Atajo», «Atajos» y «a IP»; `es` decía «Testar Conexão»,
  «Sincronizar com ERP da Empresa» y «Configuração Integração Empresa».
- Después del pulido: 0 controles <44px, 0 solapes reales, 0 desborde horizontal en los tres anchos y en
  las diez rutas barridas; el diálogo queda con un solo scroll (`ol`) y sus tres acciones visibles a 375px.
- Falsos positivos descartados midiendo: los «solapes» del menú lateral eran cajas de items desplazadas
  fuera del canvas (el `nav` termina 17px arriba del pie «Sair», con `scrolls: true`); las tablas de
  `/admin` tienen scroll propio (`min-w-[650px]`) y no desbordan la página.

Pendiente conocido, fuera de este alcance: en `/admin` las pestañas (32px) y en `/assai-calculator` los
días de la semana (34px) siguen por debajo del piso táctil; el modal de confirmación de masas repite el
título de la página dentro de la tarjeta de la grilla; la hora del historial se muestra en formato de 12
horas («05:00 AM»).

L3 — pedido del usuario, literal:

  Hola colega. Wilkin probó la aplicación web directamente desde su iPhone en Safari y nos envió 4 capturas
  exactas en `/home/wilkin/adjuntos/` (Operations 1 a 4) con dos pedidos puntuales de remediación urgente que
  debemos resolver y desplegar:

  ### 1. Simplificación de la configuración de conexión (AeroIntegrationPanel):
  - **Lanzamiento siempre por unidades:** En la fábrica el lanzamiento SIEMPRE es por unidades individuales
    (`UN`). No existe lanzamiento por cajas (`CX`). Eliminar el selector de unidades de la UI de configuración
    y dejarlo internamente fijo en `UN`.
  - **Serie de lanzamiento (`ENT_SER`):** En el binario Delphi de `base.apk` la serie es fija (`"APL"`). El
    usuario no tiene por qué saber qué es una serie contable/fiscal ni configurarla. Eliminar ese campo de la
    UI y dejarlo internamente como constante invariable (`"APL"`).
  - **Los ÚNICOS 3 campos que debe ver el usuario en la UI son:**
    1. URL del WebService (`http://IP:PUERTO/api`)
    2. Usuario
    3. Contraseña
    Cualquier otro dato necesario (como el código numérico `ENT_USU` / `CODIGO`) se captura automáticamente de
    la API al presionar "Probar conexión". Si algún dato adicional no se puede capturar, se registra la
    trazabilidad en `logs/aero-integration.log`.

  ### 2. Remediación del solapamiento de fecha y botones en iPhone (Causa raíz identificada):
  Wilkin adjuntó las capturas en `/home/wilkin/adjuntos/` donde se ve claramente el defecto en 4 páginas:
  1. `MassasLaunchPage.tsx`: Fecha vs "Ver Histórico"
  2. `StockCalculatorPage.tsx`: Fecha vs "Ir ao Histórico"
  3. `MassasHistoryPage.tsx`: Fecha vs "Voltar para Hoje"
  4. `StockHistoryPage.tsx`: Fecha vs "Voltar ao contador"

  **Causa Raíz:**
  En las 4 pantallas se usó:
  `<div className="grid grid-cols-2 items-end gap-2 w-full sm:flex sm:items-center sm:gap-2 sm:w-auto shrink-0">`
  En Safari iOS, el input de fecha nativo formatea `28 de out. de 2026`, cuyo ancho intrínseco excede los
  160px que le da la grilla en un iPhone de 375-390px. Como resultado, el botón contiguo se monta y solapa
  físicamente sobre el borde del campo de fecha.

  **Solución Requerida:**
  En móvil (`< sm`), cambiar a apilamiento vertical (`flex flex-col gap-2 w-full sm:flex-row sm:items-center
  sm:w-auto`):
  - En móvil: el selector de fecha ocupa el 100% del ancho de su fila, y el botón de histórico/volver ocupa el
    100% del ancho debajo, con altura mínima de 44px táctil.
  - En pantallas medianas/grandes (`sm:` en adelante): se mantienen lado a lado en la misma fila (`sm:flex-row
    sm:items-center sm:w-auto`).
  - Esto elimina de raíz cualquier posibilidad de solapamiento en Safari iOS.

  ### 3. Pipeline de cierre:
  - Ejecutar la suite de tests para asegurar 0 fallos.
  - Commit atómico directo a `main` sin Co-Authored-By y push a `origin main`.
  - Reconstruir la imagen de producción en Docker (`docker compose build web && docker compose up -d web`).
  - Validar HTTP 200 en `https://trindademasas.duckdns.org`.

L4 — decisión de diseño sobre la alineación en `sm` (desvío mínimo, declarado): el pedido nombra
`sm:items-center`. En `MassasLaunchPage` y `MassasHistoryPage` la etiqueta de la fecha es `sm:sr-only` y no
ocupa alto, así que `items-center` y `items-end` son idénticos; en `StockCalculatorPage` y `StockHistoryPage`
la etiqueta es visible, y con `items-center` el botón quedaría flotando ~15px por encima del campo. Se usa
`sm:items-end` en las cuatro para que el botón se apoye en el mismo borde que el campo de fecha, con una sola
cadena de clases para las cuatro pantallas. El requisito de fondo —apilado a ancho completo en móvil, fila
lado a lado desde `sm`— se cumple tal cual.

### Evidencia — remediación móvil del 2026-10-28

Commit en `main`: `e9bb880` fix(aero,mobile), pusheado a `origin/main`. Imagen `web` reconstruida y
contenedor recreado (`trindade-web-1 healthy`); HTTP 200 en `https://trindademasas.duckdns.org`. El bundle
servido ya no contiene «Quantidade enviada ao ERP» ni «Série do lançamento», trae `fieldsHint` en los dos
idiomas, el chunk de masas incluye la fila `flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-end` y
la traza `userCode missing`.

T8 — el contrato se movió a constantes: `AERO_ENTRY_SERIES = 'APL'`, `AERO_QUANTITY_UNIT = 'UN'`;
`AeroSettings` perdió `quantityUnit` y `entrySeries`, y un test fija que un teléfono con los valores viejos
guardados vuelve sin ellos (`'quantityUnit' in loaded === false`). RED observado: la suite no cargaba
(`does not provide an export named 'AERO_ENTRY_SERIES'`). GREEN: 28/28 en `aero-integration.test.ts`,
195/195 en el paquete. El panel, medido en el DOM a 375px, expone exactamente tres campos: `aero-ws-url`
(`type=url`), `aero-ws-user` (text) y `aero-ws-pass` (password).

T9 — medición con Playwright/Chromium a 375 y 390, fijando `minWidth = 185px` en el campo de fecha (ancho
real en Safari, medido sobre el PDF de la captura: la caja del campo va de x=33 a x=218 en 375px de
viewport). Sin esa restricción Chromium recorta el texto y da falso verde (0/8 solapes), que es exactamente
por qué el defecto llegó a producción. RED: 8/8 combinaciones con solape (`overlap: true`, fila
`grid/row`, campo de 185px contra celda de 150px). GREEN: 0/8, `stacked: true` en las ocho, fila
`flex/column`, y a 900px `flex/row` con los dos controles en la misma línea. Barrido de las diez rutas a
375px: `scrollWidth === clientWidth` en todas.

T10 — suites: frontend 195/195, backend 532/532, `tsc --noEmit` sin errores en el paquete frontend.
