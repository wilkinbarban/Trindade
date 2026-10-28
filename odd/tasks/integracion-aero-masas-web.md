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

S4 — (reescrita por L12) Botón «Instalar Atajo en iPhone»: la plantilla del Atajo que dispara a
`/api/entradasitem` se arma con **dos acciones nativas**, no siete. El deep link transporta como texto el
payload crudo —el cuerpo exacto del `POST`, sin sobre— y el atajo lo entrega tal cual; la URL del endpoint y
la cabecera `Authorization` son constantes de la fábrica y viven dentro del atajo, copiadas una vez desde el
panel. El panel muestra tres valores con botón propio de copiar (URL del POST, cabecera `Authorization`, URL
de retorno), cada uno dentro del paso que lo usa. Sigue prohibido generar un archivo `.shortcut` en el
servidor: tiene que estar firmado por el dispositivo que lo instala e iOS solo acepta enlaces de iCloud.

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

S11 — (reescrita por L10, supersede la fila asimétrica de L5) El campo de fecha es un trigger propio con el
`<input type="date">` nativo transparente encima, en las cuatro pantallas y en todo ancho. Pedido textual:
«Implementar un trigger visual compacto con el input nativo transparente superpuesto»; «Un contenedor
relativo con apariencia de botón/input de 44px de alto» que «Muestra el texto formateado corto en formato
estándar: DD/MM/AAAA (ej: "28/10/2026" o "28 out 2026") con un ícono `Calendar` de lucide-react» y «Ancho
compacto natural (~135px - 145px)»; dentro, el input con «`absolute inset-0 h-full w-full opacity-0
cursor-pointer`» conservando «`id`, `value`, `min`, `max`, `onChange`, `data-testid`, etc.»; «Al tocar la caja,
el tap lo recibe el input transparente y Safari abre el wheel/calendario nativo de iOS», y «Al elegir fecha,
el texto visible se actualiza inmediatamente a "DD/MM/AAAA"». Motivo: «El `<input type="date">` nativo de
Safari renderiza el texto largo localizado ("28 de out. de 2026") con un ancho intrínseco de ~250px en el
shadow DOM de WebKit. Aunque el contenedor tenga `min-w-0 flex-1`, el elemento nativo de Safari no se encoge y
desborda hacia la derecha, haciendo que el botón que viene después se dibuje encima del borde derecho de la
fecha.» Queda prohibido volver a apoyar el ancho del campo en el control nativo.

S13 — Jerarquía sin duplicado: «En Lançamento de Massas hay un duplicado visual evidente: la tarjeta superior
dice "Lançamento de Massas" en H1, y la tarjeta inmediatamente inferior (MassasLaunchGrid) repite "Lançamento
de Massas" en H2. Remediá esa redundancia para que la jerarquía sea limpia.»

S14 — Densidad en el teléfono: «Ajustá el padding en mobile a p-4 sm:p-6 y el espaciado vertical entre
secciones a space-y-4 sm:space-y-6 para evitar tarjetas infladas con aire vacío.»

S15 — El CI de GitHub Actions vuelve a quedar completamente verde: el job `android` falla por drift de
contratos («Contract types check failed: packages/android/contract does not match
packages/contracts/openapi.json»). Faltan `AeroLogError.kt`, `AeroLogRequest.kt` y `AeroLogResponse.kt` en
`packages/android/contract/src/main/kotlin/com/trindade/app/contract/models/`; se regeneran con
`docker run --rm --user "$(id -u):$(id -g)" -v "/home/wilkin/proyectos/Trindade:/work" -w /work
ghcr.io/cirruslabs/android-sdk:35 bash scripts/generate-android-contract.sh` y se suman al commit de la
remediación móvil.

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
- T9 (S11, S13, S14) — reabierta por L10: `DateField` compartido (caja propia de 44px con `DD/MM/AAAA` e
  ícono `Calendar`, input nativo `absolute inset-0 opacity-0` encima) en las cuatro pantallas, chip de
  navegación `shrink-0` al lado, `MassasLaunchGrid` sin el H2 repetido ni la línea de fecha en ISO y densidad
  `p-4 sm:p-6` + `space-y-4 sm:space-y-6`. Ruta: inline · commit `fix(mobile)`. Superseded: el apilado de
  `e9bb880` (L3) y la fila asimétrica de `2500ad2` (L5), que dejaba el ancho del campo en manos de WebKit.
- T10 (S12, S15): suites completas, contrato Android regenerado (los tres modelos `AeroLog*`), commit
  atómico en `main`, push a `origin`, rebuild de la imagen `web` y HTTP 200 en producción. Ruta: inline ·
  evidencia en el Log.
- T11 (S4, S8): atajo de dos acciones. El deep link entrega el payload crudo y `aeroShortcutRecipe` expone
  los tres valores fijos; el sobre `{ url, authorization, body }` y su API (`aeroLaunchEnvelope`,
  `buildAeroShortcutEnvelope`) se retiran. Diálogo reescrito con los valores dentro de cada paso, copy en
  ambos locales, documentación y verificación del portapapeles a 375 y 390px. Ruta: inline ·
  commit `feat(aero)`.

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

L5 — pedido del usuario, literal:

  Buenas. Wilkin subió 4 fotos nuevas tomadas directamente desde su iPhone en Safari (en ~/adjuntos/photo_AQAD*.jpg) de los 4 módulos: Lançamento de Massas, Calculador de Estoque, Histórico de Massas e Histórico de Contagem.

  Mirá el diagnóstico visual de por qué "no acaba de encajar en el diseño":
  1. Al pasar de grid-cols-2 a flex-col con w-full, convertiste el selector de fecha y el botón en dos bloques gigantes apilados de 326px cada uno. En la pantalla del iPhone se comen el 40% del alto útil antes de que el operario vea un solo producto o registro.
  2. ¿Por qué se montaba antes? Porque grid-cols-2 forzaba un 50/50 estricto (~150px por celda) y el input date nativo de Safari mide ~185px. La solución no era apilar dos rectángulos gigantescos a ancho completo, sino una fila flex asimétrica:
     - Contenedor en mobile: flex items-center gap-2 w-full sm:w-auto
     - Input date: flexible (flex-1 min-w-0), h-10, con label sr-only en mobile.
     - Botón de navegación (Histórico / Voltar): shrink-0 inline-flex items-center gap-1.5 h-10 px-3 rounded-lg border text-xs font-semibold whitespace-nowrap. En mobile texto conciso ("Histórico" con ícono History, "Hoje" / "Contador" con ArrowLeft).
     - En 375px y 390px, ~190px de fecha + 8px gap + ~115px de botón suman ~315-325px: entran perfectamente en UNA SOLA fila limpia y elegante, sin desborde ni solapamiento.
  3. En Lançamento de Massas hay un duplicado visual evidente: la tarjeta superior dice "Lançamento de Massas" en H1, y la tarjeta inmediatamente inferior (MassasLaunchGrid) repite "Lançamento de Massas" en H2. Remediá esa redundancia para que la jerarquía sea limpia.
  4. Ajustá el padding en mobile a p-4 sm:p-6 y el espaciado vertical entre secciones a space-y-4 sm:space-y-6 para evitar tarjetas infladas con aire vacío.

  Aplicá esta remediación en los 4 módulos (MassasLaunchPage, StockCalculatorPage, MassasHistoryPage, StockHistoryPage y MassasLaunchGrid).
  Reglas de entrega:
  - Guiate por la skill impecable.
  - Corré la suite completa (195 frontend, 532 backend) y validá que a 375px y 390px no haya solape ni desborde.
  - Commit directo a main con conventional commits (nada de Co-Authored-By ni ramas) y push a origin.
  - Reconstruí la imagen web de Docker (docker compose build web && docker compose up -d web) y verificá HTTP 200 en producción.

  Avisame cuando quede listo.

L6 — pedido del usuario, literal:

  Atención: Wilkin me pidió revisar los CI de GitHub que quedaron en rojo. Revisé el último run (40675665423) y encontré la causa exacta:
  El job "verify" pasó 100% verde, pero falló el job "android" por drift de contratos:
  "Contract types check failed: packages/android/contract does not match packages/contracts/openapi.json"
  Faltan AeroLogError.kt, AeroLogRequest.kt y AeroLogResponse.kt en packages/android/contract/src/main/kotlin/com/trindade/app/contract/models/.

  Regeneralo corriendo:
  docker run --rm --user "$(id -u):$(id -g)" -v "/home/wilkin/proyectos/Trindade:/work" -w /work ghcr.io/cirruslabs/android-sdk:35 bash scripts/generate-android-contract.sh

  Sumá esos modelos regenerados al commit de la remediación móvil para que el pipeline de GitHub Actions vuelva a quedar completamente verde.

L7 — desvío declarado sobre la altura del chip: L5 pide `h-10` (40px) y el piso táctil acordado en T7 es 44px
en las primitivas (`max-sm:min-h-[44px]`). Se conserva 44px en móvil (`h-11`) y se usa `sm:h-10` desde `sm`,
donde hay mouse. La queja de fondo —dos bloques gigantes apilados— se resuelve igual: medido, la fila ocupa
44px y no 96px, así que el alto recuperado es el mismo que pedía el punto 1. La etiqueta visible de la fecha
se mantiene en las dos pantallas de stock desde `sm` (`sr-only` en móvil, como pide L5) y se oculta siempre en
las dos de masas, donde ya era `sm:sr-only`.

L8 — el servidor local de e2e (`packages/backend/src/e2e-server.ts`) no monta las rutas de masas, así que la
grilla nunca renderiza ahí y la prueba visual del punto 3 no podía verse. Se interceptó `**/api/massas-launch**`
en Playwright con un día de ejemplo (login real contra la API local) y se midió el DOM resultante. No se tocó
código de producto para la prueba.

### Evidencia — remediación móvil, ronda 2 (commit `2500ad2`)

RED observado antes de tocar: las cuatro pantallas mostraban dos bloques a ancho completo (44px + 8px + 44px
= 96px de alto) y la cabecera de `MassasLaunchGrid` repetía el título de la página en un H2. Capturas del
iPhone de Wilkin: `photo_AQADPg1rGzNYEUd-.jpg` (Histórico de Contagem), `photo_AQADPA1rGzNYEUd-.jpg`
(Calculador de Estoque).

GREEN, medido con Playwright/Chromium a 375x844 y 390x844 (8 combinaciones = 4 rutas x 2 anchos), con login
real contra la API local:

  375 /massas-launch     fecha 192x44 | chip 109x44 "Histórico" | gap 8 | misma fila | cabecera↓270px
  375 /massas-history    fecha 222x44 | chip  79x44 "Hoje"      | gap 8 | misma fila | cabecera↓298px
  375 /stock-calculator  fecha 194x44 | chip 109x44 "Histórico" | gap 8 | misma fila | cabecera↓248px
  375 /stock-history     fecha 193x44 | chip 110x44 "Contador"  | gap 8 | misma fila | cabecera↓248px
  390 (las cuatro)       fecha 207-237px, mismos chips, misma fila, cabeceras 248-298px

En las ocho: `overlap: false`, `scrollWidth === clientWidth` (0px de desborde), campo y chip de 44px de alto
(piso táctil de T7; `sm:h-10` desde `sm`), y la fecha nunca por debajo de 192px, es decir por encima de los
185px intrínsecos de Safari. El chip queda entre 79px y 110px, dentro del presupuesto de ~315-325px que fija
L5.

Jerarquía, medido en el DOM de `/massas-launch` a 375px: `H1: Lançamento de Massas`, y de ahí en adelante
`H2: Lançamentos Individuais`, `H2: Resumo Coletivo do Dia`, `H2: Compartilhar por WhatsApp`. El H2 duplicado
desapareció y con él la línea de contexto en ISO («unidades calculadas · Data: 2026-10-28»), que repetía la
fecha recién elegida en el campo de arriba: el vivo de cajas ahora se renderiza solo cuando
`totalLaunchBoxes > 0`. La prueba visual se hizo interceptando `**/api/massas-launch**` en Playwright con un
día de ejemplo, porque el servidor local de e2e no monta las rutas de masas (L8); no se tocó código para eso.

Suites: frontend 195/195, backend 532/532, `tsc --noEmit` sin errores. Detector mecánico de `impeccable`
(`detect --json`) sobre las cinco pantallas tocadas: `[]`, sin hallazgos.

Contrato Android: `scripts/generate-android-contract.sh` dentro de `ghcr.io/cirruslabs/android-sdk:35`
regeneró 127 → 146 archivos Kotlin. El `git status` del directorio mostró exactamente los tres modelos que
faltaban (`AeroLogError.kt`, `AeroLogRequest.kt`, `AeroLogResponse.kt`) y ni un archivo existente modificado,
así que el drift era solo de alta. El contenedor necesita `-e HOME=/tmp`: con `--user "$(id -u)"` el `HOME`
hereda `/` y el script falla al crear `//.cache` (L9).

CI de GitHub Actions: run 40679350382 sobre `2500ad2`, `verify: success` y `android: success`. El job que
estaba rojo era `android`, por drift de contratos (L6); con los tres modelos en el árbol, el check de frescura
del contrato pasa.

Despliegue: commit `2500ad2` en `main` y pusheado a `origin/main`. `docker compose build web && docker compose
up -d web`: `trindade-api-1 healthy`, `trindade-web-1 healthy`, HTTP 200 en `https://trindademasas.duckdns.org`
y en `/api/health`. El bundle servido contiene `items-center gap-2 sm:w-auto sm:items-end` y ya no contiene
`flex-col gap-2 sm:flex-row sm:items-end`, o sea que la fila nueva es la que está en producción.

L9 — obstáculo real del contenedor: el comando que pasó el usuario (L6) falla con `mkdir: cannot create
directory '//.cache': Permission denied` porque `HOME=/` dentro de la imagen. Se corrió el mismo comando con
`-e HOME=/tmp` y el script eligió `/tmp/.cache/trindade-openapi-generator`. No se modificó el script.

### Evidencia — DateField con el input nativo encima (commit `54ab0b7`)

RED, medido con Playwright a 375x844 sobre el árbol anterior: el `<input type="date">` de Chromium con
locale pt-BR ocupaba 222px y el chip arrancaba 8px después, pero en Safari el mismo control mide ~250px
intrínsecos y se sale de su caja: en las capturas de Wilkin el chip «Histórico» se dibuja sobre el borde
derecho del campo (foto `photo_AQADQQ1rGzNYEUd-.jpg`, Calculador de Estoque). `min-w-0` no puede encogerlo
porque el ancho lo fija el shadow DOM de WebKit.

GREEN, ocho combinaciones (4 rutas x 375/390), midiendo la caja visible, el input invisible y el chip:

  375 /massas-launch     caja 125x44 "28/10/2026" | chip 109 | gap 8 | solape caja/chip: no | toque→input: sí
  375 /massas-history    caja 125x44 "27/10/2026" | chip  79 | gap 8 | no | sí
  375 /stock-calculator  caja 125x44 "28/10/2026" | chip 109 | gap 8 | no | sí
  375 /stock-history     caja 125x44 "27/10/2026" | chip 110 | gap 8 | no | sí
  390 (las cuatro)       idéntico

En las ocho: el input invisible queda recortado exactamente a la caja (`|right_input - right_caja| <= 1`), no
hay solape entre input y chip, `scrollWidth === clientWidth`, la caja mide 125x44 y tocar su centro deja
`document.activeElement` en el `<input type="date">`, que es el gesto que abre el wheel de iOS. Fila total
125 + 8 + 110 = 243px de 343 disponibles a 375px: ~100px de margen, como pedía L10.

Formato: `formatDateLabel('2026-10-28') === '28/10/2026'` y los dos tests nuevos fijan además que un valor
vacío o incompleto se devuelve intacto, nunca como un día inventado ni como `undefined`. RED observado:
`does not provide an export named 'formatDateLabel'`. GREEN: 197/197 frontend (195 previos + 2), 532/532
backend, `tsc --noEmit` limpio, detector `impeccable detect --json` sobre las cinco piezas: `[]`.

CI de GitHub Actions: run 40683453980 sobre `54ab0b7`, `verify: success` y `android: success`.

Despliegue: `54ab0b7` en `main` pusheado a `origin/main`; `docker compose build web && docker compose up -d
web` → `trindade-web-1 healthy`; HTTP 200 en `https://trindademasas.duckdns.org` y en `/api/health`. El bundle
servido contiene `absolute inset-0 h-full w-full min-w-0 cursor-pointer opacity-0` y `tabular-nums`.

L10 — pedido del usuario, literal:

  Wilkin subió 4 capturas nuevas tomadas desde su iPhone en Safari (en ~/adjuntos/photo_AQADQA*.jpg a photo_AQADQw*.jpg). Mirá las capturas: en las 4 pantallas el botón derecho se monta físicamente encima del borde derecho del campo de fecha.

  Causa raíz en Safari iOS:
  El `<input type="date">` nativo de Safari renderiza el texto largo localizado ("28 de out. de 2026") con un ancho intrínseco de ~250px en el shadow DOM de WebKit. Aunque el contenedor tenga `min-w-0 flex-1`, el elemento nativo de Safari no se encoge y desborda hacia la derecha, haciendo que el botón que viene después se dibuje encima del borde derecho de la fecha. Además, en WebKit no se puede formatear el texto interno de un `<input type="date">` nativo por CSS/JS porque toma el locale del sistema operativo.

  La solución estándar e impecable para mobile web:
  Implementar un trigger visual compacto con el input nativo transparente superpuesto:
  1. Un contenedor relativo con apariencia de botón/input de 44px de alto:
     - Muestra el texto formateado corto en formato estándar: DD/MM/AAAA (ej: "28/10/2026" o "28 out 2026") con un ícono `Calendar` de lucide-react.
     - Ancho compacto natural (~135px - 145px).
  2. Dentro del contenedor, el `<input type="date">` con clases:
     `absolute inset-0 h-full w-full opacity-0 cursor-pointer`
     manteniendo sus atributos: `id`, `value`, `min`, `max`, `onChange`, `data-testid`, etc.
  3. Al tocar la caja, el tap lo recibe el input transparente y Safari abre el wheel/calendario nativo de iOS. Al elegir fecha, el texto visible se actualiza inmediatamente a "DD/MM/AAAA".
  4. Al medir solo ~135px, entra al lado del botón de histórico/vuelta (~100px) sumando ~240px: en un iPhone de 375px sobran más de 100px de margen. Cero solape, cero desborde, y el campo de fecha queda proporcionado y pequeño como pidió Wilkin.

  Aplicá este componente/patrón en los 4 módulos:
  - MassasLaunchPage
  - StockCalculatorPage
  - MassasHistoryPage
  - StockHistoryPage

  Reglas de entrega:
  - Correr suites de tests completas (195 frontend, 532 backend).
  - Verificar que a 375px y 390px no haya solapamiento alguno.
  - Commit directo a main con conventional commits (sin Co-Authored-By ni ramas) y push a origin.
  - Reconstruir imagen de producción en Docker (`docker compose build web && docker compose up -d web`) y verificar HTTP 200 en https://trindademasas.duckdns.org.

  Avisame cuando quede listo.

L11 — dos desvíos mínimos sobre las clases literales de L10, ambos declarados: (a) la caja mide 125px y no
135-145px porque el texto `DD/MM/AAAA` más el ícono y el padding dan ese ancho natural y no se fuerza relleno
artificial; (b) el input lleva además `min-w-0` y la caja `overflow-hidden`, porque un input absoluto con
`w-full` todavía puede imponer su ancho intrínseco y, sin el recorte, su caja invisible habría robado el tap
del chip vecino. El piso táctil de 44px se mantiene (`h-11`, `sm:h-10`), igual que en L7.

L12 — pedido del usuario sobre el atajo, reconstruido: el texto original llegó antes de una compactación y
no está disponible verbatim, así que se conserva su contenido sin comillas. Pedía que el atajo de iOS se
arme con **dos acciones nativas** y unos 30 segundos: (1) el deep link del disparador manda el payload como
JSON plano (`input=text&text=…`), no un sobre `{ url, authorization, body }`; (2) `AeroShortcutDialog` pasa a
una guía de dos pasos con botones de copiar para la URL del endpoint `/entradasitem`, la cabecera
`Authorization` Basic y la URL de retorno; además actualizar los locales, los tests y la documentación, y
cerrar con suites en verde, commit a `main` sin Co-Authored-By, push, rebuild de `web` y HTTP 200 en
producción.

L13 — desvíos declarados del cierre de T11: (a) el diálogo tiene tres pasos numerados, no dos, porque el
nombre del atajo es preparación en la app y no una acción que se agregue — se muestra en una tarjeta aparte,
sin número, y la lista numera exactamente las dos acciones; (b) el campo del nombre se quedó visible en vez de
pedirlo por diálogo, porque es la única pieza que el operario puede teclear mal y el payload no viaja con ella.
