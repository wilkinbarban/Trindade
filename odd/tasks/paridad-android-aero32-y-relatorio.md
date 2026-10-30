# Paridad Android con la integración Aero32, la conciliación de masas y el relatorio

Lleva a `packages/android` lo que ya funciona en la web: llamadas nativas al WebService Delphi, la tabla
unificada de los tres estados con su balance, la sincronización del día, el card de masas en los reportes y la
sección nueva del panel admin. Sin atajos de iOS y sin salir de la app.
Estado: **diseño, pendiente de aprobación de Wilkin**. Versión objetivo `0.6.0` / `versionCode 6000`, sin
publicar release.

## Specs

- **S1**: «En Android no hay puentes externos. Las llamadas van directo vía OkHttp/Retrofit contra el
  WebService de Delphi (http://192.168.25.250:1453/api).»
- **S2**: «Android 9+ prohíbe cleartext HTTP por defecto. Hay que configurar network_security_config.xml para
  permitir tráfico HTTP a 192.168.25.250 en la build de release.»
- **S3**: «Validación de red: antes de llamadas al ERP o durante el test, detectar si el dispositivo está en la
  Wi-Fi de la fábrica o si la IP responde (timeout corto de 2-3s), informando prolijamente si está "Sem
  conexão com a rede da fábrica".»
- **S4**: «Guardado local prioritario en Trindade (POST /api/massas-launch) y despacho inmediato al ERP (POST
  /entradasitem con Basic Auth y huella ENT_OBS: "Trindade #ID").»
- **S5**: «Si responde OK: 🟢 Conciliado. Si falla o no hay Wi-Fi: queda guardado en Trindade como 🟡 Pendente
  ERP.»
- **S6**: «Tabla unificada en Compose con los 3 estados: 🟢 Conciliado; 🟡 Pendente ERP con acciones rápidas
  [Enviar ao ERP] (reintento nativo) y [Já na Tablet] (ack persistido en DataStore); 🔵 Só no ERP (asientos de
  la tablet física) con acción [Importar] de cajas completas.»
- **S7**: «Barra superior de Balance: Total Produzido vs Registrado no ERP y Diferencia.»
- **S8**: «Filtro universal por trabajador (aplica a Trindade y a ERP simultáneamente).»
- **S9**: «Sincronización en cabecera junto a la fecha con timestamp de último sync (consulta /movimentoitem y
  guarda snapshot en PUT /api/integration/aero/movements).»
- **S10**: «Card permanente de Masas en ReportGeneratorScreen y ReportDetailScreen consumiendo el endpoint ya
  existente GET /api/massas-launch/report (producción consolidada de planta deduplicando conciliados).»
- **S11**: «Exportación fija y obligatoria en el texto de WhatsApp (sin checkbox opcional) con el diseño
  estricto (*BOLD*, 👤 Operário, 📦 Resumo Coletivo).»
- **S12**: «Pestaña "Integração ERP / Aero32" en AdminPanelScreen, fija, sin acordeón/colapso y sin botón
  "+ Novo".»
- **S13**: «Permisos híbridos: wsUrl fijo por defecto en http://192.168.25.250:1453/api, bloqueado con candado
  para todos excepto el usuario "wilkin".»
- **S14**: «Credenciales personales (usuario y contraseña) editables por cualquier operario en su dispositivo y
  guardadas en DataStore seguro.»
- **S15**: «Botón nativo "Testar Conexão" con diagnóstico rápido (Wi-Fi, IP, autenticación en /usuarios) y
  diálogo con resultado.»
- **S16**: «Bumpear versión a 0.6.0 (versionCode 6000) en packages/android/app/build.gradle.kts.»
- **S17**: «RESTRICCIÓN EXPLÍCITA DE WILKIN: NO publicar release en GitHub todavía. Wilkin probará primero el
  APK físicamente en la empresa. Se compilará el APK firmado oficial (trindade-release.jks), se copiará a
  Desktop/ y backups/, y se verificará en el emulador de Ubuntu por SSH.»
- **S18** (de la evidencia, bloquea S10): el contrato publicado tiene que declarar `GET
  /api/massas-launch/report`. Hoy `packages/contracts/openapi.json` no lo trae y
  `scripts/verify-openapi-artifact.sh` falla con «does not match the schemas it is generated from», así que
  `ContractCoverageTest` rechazaría la interfaz que S10 necesita. El artefacto se regenera, no se edita a mano.
- **S19**: «La app Android debe reportar todos sus eventos de integración (despachos de masas,
  sincronizaciones, test de conexión y errores) al mismo log del proyecto a través de POST
  /api/integration/aero/log.» En el campo `note` (máximo 256 caracteres) Android va prefijado con
  `client=android · via=native`, mientras la web sigue con `via=bridge`. El evento se emite en las dos fases
  que ya conoce el contrato: `phase: 'requested'` antes de salir y `phase: 'result'` con el desenlace.
- **S20**: «En las cabeceras HTTP de Retrofit/OkHttp, enviar `User-Agent: Trindade-Android/0.6.0`.» El valor
  sale de la versión compilada, no de un literal, para que el log identifique exactamente el APK que lo
  escribió.
- **S21**: «Ajuste de permisos en el backend: actualmente POST /api/integration/aero/log tiene ownerGuard
  restringido exclusivamente al usuario "wilkin". Dado que cualquier operario (rol Trabalhador o
  Administrador) puede lanzar masas desde su teléfono y necesitamos auditar los errores y éxitos de todos en
  planta, relajá el guard de ese endpoint a cualquier usuario autenticado (fastify.authenticate), de modo que
  estampe el verdadero username y role de quien despacha.» El dueño de la integración sigue siendo el único
  que edita la configuración del ERP (S13); lo que se abre es la escritura del rastro de auditoría.

- **S22** (regla de negocio, 2026-10-30, verbatim): «Cuando un lanzamiento este "Conciliado" con el ERP (es
  decir, ya fue enviado e impactado en la BD de Delphi), se DEBEN ELIMINAR los botones de edicion y
  eliminacion de ese item. No se permite editarlo ni borrarlo porque ya fue asentado en el ERP y romperian
  la consistencia.» Aplica a las **dos** aplicaciones: Web (`MassasCollectiveTable.tsx`) y Android
  (`MassasLaunchScreen.kt`). La regla manda sobre el rol: un administrador tampoco edita ni borra un
  lanzamiento conciliado.
- **S23** (bugfix, 2026-10-30, verbatim): «En AeroIntegrationPanel.tsx (linea 173), callbackUrl llama a
  aeroBridgeCallbackFor("test") SIN pasarle el parametro path. Por defecto eso construye el retorno hacia
  /massas-launch?aero_action=test. Cuando el atajo de iOS termina, Safari aterriza en /massas-launch (donde
  el panel ya no existe, porque se mudo a Admin /admin), MassasLaunchPage consume el callback pero no
  renderiza ningun modal de test, y en Admin nunca se entero.» Remediación: el callback vuelve a
  `/admin?tab=integration`, el panel lee el callback, limpia el query con `replaceState` y levanta
  `AeroResultDialog` auditando con `postAeroAuditEvent`.

## Lo que ya existe (y no hay que reinventar)

| Pieza | Dónde | Estado frente al pedido |
| --- | --- | --- |
| Contrato Delphi: `POST /entradasitem`, `/movimentoitem?_method=PATCH`, `/usuarios` | `packages/frontend/src/lib/aero-integration.ts:439,570,287` | Payload, lectura y clave de conciliación ya escritos y probados en TS: `PRO_QTD` escalado ×100, `ENT_SER 'APL'`, `PRO_CST '000'`, `ENT_OBS` con `trindadeObservation()`, `reconcileAeroMovements()` por fecha + código sin ceros + cantidad + operario. **Base directa de S1, S4, S5, S6.** |
| `aeroImportPlan` / `aeroMirrorSeats` | `packages/frontend/src/lib/aero-mirror.ts:155,191` | Entrada + producto del catálogo + cajas enteras. **S6 (Importar) se cumple con esta regla.** |
| `GET /api/massas-launch/report` | `packages/backend/src/modules/massas/massas.routes.ts:38` | Devuelve `operators`, `products`, totales, `reconciledEntLans` y **`balance` (produced/registered/difference)**: S7 y S10 no necesitan ninguna ruta nueva ni recuento en el cliente. |
| `GET|POST /api/integration/aero/movements` | `aero.routes.ts:82,114` | Espejo del día ya migrado, con validación estricta. **S9 sólo necesita el cliente.** |
| Sección de masas del export | `packages/backend/src/modules/reports/export.service.ts:279` | Fija y sin flag; `?includeMassas=false` está probado como ignorado (`reports.routes.test.ts:671`). **S11 en el cliente es borrar el control muerto**, no construirlo. |
| `MassasApi`, `MassasRepository`, `MassasLaunchViewModel`, `MassasLaunchScreen` | `packages/android/app/src/main/java/com/trindade/app/massas/` | Módulo completo de lanzamiento contra Trindade, con polling 15 s, filtro `selectedOperatorId` y modo lectura. Es donde entran S4–S9. |
| `KeystoreTokenStore` | `app/src/main/java/com/trindade/app/auth/KeystoreTokenStore.kt` | AES/GCM con clave del Android Keystore. **No hay DataStore en el proyecto**: ver D1. |
| `AdminPanelScreen` + `AdminSection` | `app/src/main/java/com/trindade/app/admin/AdminPanelScreen.kt:57,183` | El panel es una lista de cards que abren secciones, no un tab-row: S12 entra como `AdminSection.INTEGRATION`, no como pestaña. **No existe ningún botón "+ Novo" en este panel**; la ausencia se prueba, no se implementa. |
| `ContractCoverageTest`, `LocaleStringsParityTest`, `M3TokenHygieneTest` | `app/src/test/java/com/trindade/app/` | Cada interfaz nueva debe existir en `openapi.json`; cada string nuevo en `values/` y `values-es/` (676 claves hoy); ningún color literal fuera de `ui/theme/Theme.kt`, así que los badges 🟢🟡🔵 salen de `colorScheme`. |
| `network_security_config.xml` de debug | `app/src/debug/res/xml/network_security_config.xml` | Existe **sólo en debug**, con un comentario que prohíbe llevarlo a release. S2 pide lo contrario y acotado a un host. |
| Release: guardas, keystore, emulador | `app/build.gradle.kts:370`, `docs/release-android.md:199,230,302` | `assembleRelease` exige `-PapiBaseUrl`, `-PversionName` y `-PversionCode`; los defaults `0.5.3`/`5003` sólo afectan a debug. Ver D4. |

## Decisiones abiertas

- **D1 — «DataStore seguro» (S14, y el ack de S6).** El proyecto no tiene `androidx.datastore`: las
  credenciales de sesión viven en `KeystoreTokenStore` (SharedPreferences + AES/GCM con clave no exportable del
  Keystore) y esa elección está documentada en su cabecera como deliberada. Propuesta: `AeroCredentialsStore` y
  `TabletAckStore` con el mismo patrón, cero dependencias nuevas. La alternativa es agregar
  `androidx.datastore:datastore-preferences` (+ cifrado propio, porque DataStore no cifra). Necesito que Wilkin
  elija: **mismo patrón (recomendado)** o **DataStore literal**.
- **D2 — Detección de la Wi-Fi de la fábrica sin pedir ubicación.** Leer el SSID exige permiso de ubicación
  desde API 27. Propuesta: comprobar que hay una red Wi-Fi conectada (`ConnectivityManager`, permiso
  `ACCESS_NETWORK_STATE`) y que `192.168.25.250:1453` acepta un TCP connect en 2,5 s. Es la señal que el
  operario necesita y no amplía permisos de la app.
- **D3 — Cleartext en release (S2).** Abre una excepción que hoy no existe en el APK publicado. Va acotada al
  host exacto (sin `base-config` y sin comodines), queda como **item 2 de riesgo** y su test lee el XML.
- **D4 — Dónde vive la versión (S16).** `build.gradle.kts` no contiene la versión de release: la recibe por
  `-P`/entorno y falla si falta. Propuesta: mover los defaults de debug a `0.6.0`/`6000` y compilar el APK
  firmado con `-PversionName=0.6.0 -PversionCode=6000`, además de la fila nueva en `docs/release-android.md`.

## Plan TDD

Cada tarea cierra con sus tests, sus claves pt/es y la documentación que toque. Ninguna publica nada.

| ID | Spec | Ruta | Trabajo | Commit |
| --- | --- | --- | --- | --- |
| T1 | S18 | inline | RED: `scripts/verify-openapi-artifact.sh` falla hoy (observado). GREEN: `npm run contracts:generate --workspace=packages/backend`, regenerar tipos con `scripts/generate-android-contract.sh`; el script y `check-android-contract-types.sh` pasan. | `chore(contract): publish the massas report operation` |
| T2 | S1, S4, S5, S9 | inline | Cliente `AeroApi` (Retrofit + OkHttp propio, sin `AuthInterceptor`, Basic dinámico, connect/read 3 s) y la lógica pura portada: `buildEntradaItemPayload` (incluye `ENT_OBS: "Trindade #ID"`), `buildMovimentoItemPayload`, `readAeroTestResult`, `readAeroMovements`, `reconcileAeroMovements`. RED: MockWebServer con payload exacto, header Basic, `{success,data:[…]}`, `success:false`, seat ilegible. | `feat(android): speak to the Aero32 WebService from the device` |
| T3 | S2 | inline | `src/main/res/xml/network_security_config.xml` acotado a `192.168.25.250` + `android:networkSecurityConfig` en el manifest + `ACCESS_NETWORK_STATE`. RED: test que parsea el XML (dominio exacto, sin `base-config` cleartext) y verifica que el manifest lo declara y que el de debug sigue sin fusionarse. | `feat(android): allow cleartext to the plant host in release builds` |
| T4 | S3, S15 | inline | `AeroReachability` (Wi-Fi conectada + TCP connect 2,5 s) y `describeProbe()` con los casos: sin red, host mudo, 401, 405, éxito. Texto «Sem conexão com a rede da fábrica» en pt/es. RED: tests puros del diagnóstico y del mapeo a mensaje. | `feat(android): diagnose the plant network before asking the ERP` |
| T5 | S13, S14 | inline | `AeroSettingsStore` (wsUrl por defecto + candado salvo `wilkin`, usuario/senha de cada operario) según D1. RED: default, normalización de la URL, `canEditUrl` por usuario, ida y vuelta del store, credenciales nunca en claro en el archivo. | `feat(android): keep the Aero32 settings on the device` |
| T6 | S5, S6, S7, S8 | worker | Lógica pura `MassasUnified.kt`: tres estados, filtro universal, `acks`, `importable`, `massasBalance`. RED: los casos del pedido — conciliado, pendiente, sólo ERP, caja incompleta sin botón, filtro que toca ambos lados, balance con diferencia negativa y cero. GREEN: port de `massas-unified.ts` sin tocar UI. | `feat(android): reconcile the plant day in one table model` |
| T7 | S4, S5, S6 | worker | `MassasLaunchViewModel`: guardado local primero y despacho inmediato al ERP; éxito → conciliado, fallo o sin red → pendiente; acciones `enviar`, `já na tablet`, `importar`. RED: orden de las llamadas, estado tras cada respuesta, reintento idempotente, import de cajas completas. | `feat(android): launch to Trindade and dispatch to the ERP at once` |
| T8 | S6, S7, S8 | inline | UI Compose: tabla unificada con badges de `colorScheme`, acciones por fila, barra de balance y filtro universal. Criterio del repo: `M3TokenHygieneTest`, 48dp de toque, sin desborde a 360dp. | `feat(android): one massas table with its three states` |
| T9 | S9 | inline | Cabecera: acción de sincronización junto a la fecha, consulta nativa de `/movimentoitem`, `POST /api/integration/aero/movements` y hora del último sync. RED: snapshot armado con los nombres del backend, `syncedAt` persistido por día, fallo de red sin borrar el anterior. | `feat(android): sync the ERP day from the massas header` |
| T10 | S10 | worker | `MassasReportApi` + card permanente en `ReportGeneratorScreen` y `ReportDetailScreen`. RED: el card lee `operators`/`products`/`balance`, deduplica conciliados (un launch conciliado cuenta una vez) y muestra vacío sin inventar totales. | `feat(android): show the massas card on both report screens` |
| T11 | S11 | inline | Quitar el switch «incluir massas» y el parámetro `includeMassas` del cliente: el texto ya es fijo en el backend. RED: el detalle no ofrece el control y el export se pide sin flag; la clave huérfana sale de pt y es. | `fix(android): export the massas section with no switch` |
| T12 | S12, S13, S14, S15 | worker | `AdminSection.INTEGRATION` con card fija (sin colapso), candado de `wsUrl`, credenciales personales, «Testar Conexão» con su diálogo. RED: sección visible para ambos roles, candado sólo se abre con `wilkin`, ausencia de cualquier control de colapso y de «+ Novo», test de conexión con los tres desenlaces. | `feat(android): add the ERP integration section to the admin panel` |
| T13 | S16 | inline | Defaults de debug a `0.6.0`/`6000` (D4) y fila nueva en `docs/release-android.md`. RED: test que lee `BuildConfig` y afirma versión y código. | `chore(android): target version 0.6.0` |
| T14 | S17 | inline | `assembleRelease` firmado con el keystore real (`-PrequireSigned=true`), verificación con `apksigner`/`aapt2`, copia del mismo archivo a `~/Desktop/trindade-0.6.0.apk` y a `~/backups/`, `adb install -r` en el emulador de Ubuntu por SSH. **Sin tag y sin GitHub Release.** | `chore(android): build and verify the signed 0.6.0 APK` |
| T15 | S21 | inline | `POST /api/integration/aero/log` pasa de `ownerGuard` a `fastify.authenticate`; se retira el guard, su constante y el `403` del contrato. RED: los dos tests que hoy esperan `403` (admin que no es wilkin y trabalhador) pasan a exigir `200` y la línea en disco con el `user`/`role` reales de cada uno. | `feat(integration): let every operator report to the audit log` |
| T16 | S19, S20 | worker | `AeroLogClient` + `AppUserAgentInterceptor` (`Trindade-Android/<versionName>`): `note` prefijado con `client=android · via=native` y las dos fases del despacho, el test de conexión y la sincronización. RED: el prefijo sobrevive al recorte de 256, el `User-Agent` sale en el request y un fallo del log nunca rompe el despacho. | `feat(android): report every integration event to the shared log` |

Orden de ejecución: **T15 primero** (es el backend que la app necesita para reportar) y **T16 junto a T2**, para
que el cliente nativo nazca ya instrumentado.

## Riesgos

- **Item 1 — escribe en el ERP de la empresa.** El despacho nativo crea asientos en un sistema que no se puede
  deshacer desde la app. Mitigación: payload idéntico al que ya usa la web, `ENT_OBS` con el id del launch,
  y el registro local siempre ocurre primero.
- **Item 2 — S2 abre cleartext.** Va acotado a un host y cubierto por test; sin él, el APK de release no puede
  hablar con la fábrica.
- **Item 5 — dependencia nueva sólo si D1 elige DataStore.**
- **Item 6 — el WebService no tiene entorno de prueba:** el primer contacto real será el de Wilkin en la
  empresa. Por eso T2–T4 se prueban contra MockWebServer y T14 incluye el emulador.

## Log

- **L1** (pedido original, verbatim):

  > Hola. Es hora de llevar a la aplicación Android (packages/android) la paridad completa con las mejoras que
  > desarrollamos en la web para la integración con Aero32, conciliación de masas, relatorio integral y panel de
  > administración.
  >
  > Wilkin y yo acabamos de definir la arquitectura y los casos de uso para Android. La premisa clave es que en
  > Android NO usamos atajos de iOS: la experiencia debe ser 100% nativa, fluida y transparente, ejecutando las
  > consultas directas sin salir de la aplicación.
  >
  > Te comparto los lineamientos centrales para que los explores en el código y armemos el artefacto ODD:
  >
  > 1. Transporte y Red Nativa (Delphi Aero32):
  > - En Android no hay puentes externos. Las llamadas van directo vía OkHttp/Retrofit contra el WebService de
  >   Delphi (http://192.168.25.250:1453/api).
  > - Importante: Android 9+ prohíbe cleartext HTTP por defecto. Hay que configurar network_security_config.xml
  >   para permitir tráfico HTTP a 192.168.25.250 en la build de release.
  > - Validación de red: antes de llamadas al ERP o durante el test, detectar si el dispositivo está en la Wi-Fi
  >   de la fábrica o si la IP responde (timeout corto de 2-3s), informando prolijamente si está "Sem conexão com
  >   a rede da fábrica".
  >
  > 2. Módulo Lançamento de Masas:
  > - Guardado local prioritario en Trindade (POST /api/massas-launch) y despacho inmediato al ERP (POST
  >   /entradasitem con Basic Auth y huella ENT_OBS: "Trindade #ID").
  > - Si responde OK: 🟢 Conciliado. Si falla o no hay Wi-Fi: queda guardado en Trindade como 🟡 Pendente ERP.
  > - Tabla unificada en Compose con los 3 estados:
  >   * 🟢 Conciliado
  >   * 🟡 Pendente ERP con acciones rápidas [Enviar ao ERP] (reintento nativo) y [Já na Tablet] (ack persistido
  >     en DataStore)
  >   * 🔵 Só no ERP (asientos de la tablet física) con acción [Importar] de cajas completas
  > - Barra superior de Balance: Total Produzido vs Registrado no ERP y Diferencia.
  > - Filtro universal por trabajador (aplica a Trindade y a ERP simultáneamente).
  > - Sincronización en cabecera junto a la fecha con timestamp de último sync (consulta /movimentoitem y guarda
  >   snapshot en PUT /api/integration/aero/movements).
  >
  > 3. Módulo Relatório:
  > - Card permanente de Masas en ReportGeneratorScreen y ReportDetailScreen consumiendo el endpoint ya existente
  >   GET /api/massas-launch/report (producción consolidada de planta deduplicando conciliados).
  > - Exportación fija y obligatoria en el texto de WhatsApp (sin checkbox opcional) con el diseño estricto
  >   (*BOLD*, 👤 Operário, 📦 Resumo Coletivo).
  >
  > 4. Módulo Painel Admin:
  > - Pestaña "Integração ERP / Aero32" en AdminPanelScreen, fija, sin acordeón/colapso y sin botón "+ Novo".
  > - Permisos híbridos: wsUrl fijo por defecto en http://192.168.25.250:1453/api, bloqueado con candado para
  >   todos excepto el usuario "wilkin".
  > - Credenciales personales (usuario y contraseña) editables por cualquier operario en su dispositivo y
  >   guardadas en DataStore seguro.
  > - Botón nativo "Testar Conexão" con diagnóstico rápido (Wi-Fi, IP, autenticación en /usuarios) y diálogo con
  >   resultado.
  >
  > 5. Preparación Release v0.6.0 y Restricción:
  > - Bumpear versión a 0.6.0 (versionCode 6000) en packages/android/app/build.gradle.kts.
  > - RESTRICCIÓN EXPLÍCITA DE WILKIN: NO publicar release en GitHub todavía. Wilkin probará primero el APK
  >   físicamente en la empresa. Se compilará el APK firmado oficial (trindade-release.jks), se copiará a
  >   Desktop/ y backups/, y se verificará en el emulador de Ubuntu por SSH.
  >
  > Por favor, explorá la arquitectura actual de packages/android y packages/android/contract, evaluá los
  > componentes existentes, y generá el artefacto formal ODD en odd/tasks/paridad-android-aero32-y-relatorio.md
  > con el inventario, specs S1..Sn y plan TDD de tareas T1..Tn.

- **L3** (requerimiento nuevo de Wilkin, verbatim, 2026-10-30): «Requerimiento nuevo fundamental de Wilkin
  sobre el .log compartido: La app Android debe reportar todos sus eventos de integración (despachos de masas,
  sincronizaciones, test de conexión y errores) al mismo log del proyecto a través de POST
  /api/integration/aero/log. Para que podamos identificar de qué aplicación proviene cada mensaje en
  aero-integration.log: 1. En el campo "note" (hasta 256 chars), Android debe prefijar/incluir claramente
  "client=android · via=native" (mientras web usa "via=bridge"). 2. En las cabeceras HTTP de
  Retrofit/OkHttp, enviar "User-Agent: Trindade-Android/0.6.0". 3. Ajuste de permisos en el backend:
  actualmente POST /api/integration/aero/log tiene ownerGuard restringido exclusivamente al usuario
  "wilkin". Dado que cualquier operario (rol Trabalhador o Administrador) puede lanzar masas desde su
  teléfono y necesitamos auditar los errores y éxitos de todos en planta, relajá el guard de ese endpoint a
  cualquier usuario autenticado (fastify.authenticate), de modo que estampe el verdadero username y role de
  quien despacha.» Decisiones D1 y D4 aprobadas en el mismo mensaje; S17 confirmado una vez más.
- **L2** (evidencia de la exploración, 2026-10-30):
  - `scripts/verify-openapi-artifact.sh` **falla en `main`**: falta `/api/massas-launch/report` y el schema
    `MassasReportDayView` en `packages/contracts/openapi.json` (1 path y 1 schema; nada más difiere). Es
    preexistente, reproducible, y bloquea S10 porque `ContractCoverageTest` exige el path en el contrato.
  - `MassasLaunchScreen` ya tiene filtro por operario, polling de 15 s y modo lectura; no tiene ninguna llamada
    al ERP ni tabla unificada. Todo S1–S9 es nuevo del lado de red/estado.
  - `ReportDetailScreen:228` todavía dibuja el switch «incluir massas» y `ReportDetailViewModel:149` lo manda
    como query: el backend lo ignora desde que la sección pasó a ser fija. Es un control muerto hoy.
  - `AdminPanelScreen` no es un tab-row como el de la web: es una lista de cards que abre secciones. S12 entra
    como sección nueva y «sin botón + Novo» se verifica como ausencia, porque ese botón no existe en este panel.
  - El panel web y el Android comparten la web de `KeystoreTokenStore`, no DataStore: no hay `androidx.datastore`
    en el catálogo de dependencias. Ver D1.
  - `assembleRelease` falla sin `-PversionName`/`-PversionCode` (guarda en `build.gradle.kts:370`); los defaults
    `0.5.3`/`5003` sólo alcanzan a debug. Ver D4.

- **L4** (evidencia del avance, 2026-10-30). Cerrado y verificado en el árbol:
  - **T15** — `POST /api/integration/aero/log` consagrado a `fastify.authenticate`; los dos casos que exigían
    `403` ahora exigen `200` y la línea en disco. Contrato regenerado, `verify-openapi-artifact.sh` en verde.
  - **T1** — tipos Kotlin regenerados (158 archivos, incluidos `AeroMovement`, `AeroMovementsSavedResponse`).
  - **T2** — `AeroLogic.kt` (payloads, lecturas, conciliación), `AeroErpApi.kt` y `AeroErpClient.kt` con
    `AeroLogicTest`/`AeroErpClientTest` sobre MockWebServer.
  - **T3** — `src/main/res/xml/network_security_config.xml` con un solo dominio y sin `base-config`; el de
    debug conserva el host de planta porque el archivo se reemplaza, no se fusiona. `AeroNetworkSecurityConfigTest`
    y la aserción de `scripts/ci-android.sh` cubren las dos mitades.
  - **T4** — `AeroReachability` (Wi-Fi real, sin SSID ni permiso de ubicación; TCP 2,5 s) y `aeroHostPort`.
  - **T5** — `KeystoreCipher` con alias propio, `AeroSettingsStore` y `TabletAckStore`; el codec JSON quedó puro
    y probado, el cifrado se ejercita en el emulador porque `AndroidKeyStore` no existe en la JVM.
  - **T6** — `MassasUnified.kt` portado regla por regla, con `MassasUnifiedTest` cubriendo los tres estados, el
    filtro universal, el ack, la caja incompleta y el balance.

- **L5** (directiva de Wilkin, 2026-10-30, verbatim):
  > «omiti por completo el paso del emulador en la PC Ubuntu por SSH porque Wilkin no tiene acceso a esa maquina
  > ahora. En cuanto pasen los tests, compila directamente el APK Release firmado oficial (trindade-release.jks)
  > con la version 0.6.0, copialo a Desktop/trindade-0.6.0.apk y a backups/, reconstrui el contenedor Docker de
  > produccion de la web (docker compose up -d --build web) para que queden activos los cambios de main, y
  > recorda NO publicar release en GitHub.»

  Consecuencias sobre las specs, sin reescribir lo ya acordado:
  - **S5 se recorta**: el keystore cifrado y su round-trip se verifican por lectura de código y por
    `AeroSettingsCodecTest` sobre el codec puro. `AndroidKeyStore` no existe en la JVM, así que el round-trip
    real queda para el dispositivo de Wilkin, que es donde el APK va a correr.
  - **T14 pierde el emulador**: no hay `adb install` remoto, ni captura de la tabla unificada en el emulador, ni
    verificación de upgrade in-place. En su lugar: APK firmado con `~/.android-keystores/trindade-release.jks`,
    `apksigner verify` y `aapt2 dump badging` sobre el artefacto, copia a `~/Desktop/trindade-0.6.0.apk` y a
    `~/backups/`, y la reconstrucción del contenedor web de producción. La prueba en el dispositivo la hace
    Wilkin y su resultado entra como evidencia nueva cuando la reporte.
  - **Sin release en GitHub**: el tag `v0.6.0` no se crea y el workflow `android-release.yml` no corre. El APK
    es un artefacto local, y así se reporta.

- **L6** (directiva de Wilkin, 2026-10-30): la regla de S22 y la remediación de S23 entran en el mismo cierre
  que el resto de la feature. Ambas tocadas en las dos apps y con tests unitarios en las dos suites, sin
  release en GitHub (S17 sigue vigente).

- **L7** (evidencia, 2026-10-30). Cerrado y verificado:
  - **S23** — `aeroInternalRoute` acepta un query propio (`?tab=integration`) y `aeroBridgeCallbackPrefix` lo
    deja antes de `aero_action`; `AdminDashboard` abre la pestaña pedida por la URL; el panel lee
    `parseAeroCallback(window.location.search)`, limpia el query y dibuja el veredicto con la misma ruta de
    estado que ya usaba el almacenamiento. `aero-integration.test.ts` y `aero-bridge-wiring.test.ts` en verde.
  - **S22 Web** — `reconciledLaunchIds(rows)` en `massas-unified.ts`, cubierto en `massas-unified.test.ts`;
    `MassasCollectiveTable` no dibuja editar/eliminar para un lanzamiento conciliado y muestra la insignia
    «Conciliado» en su lugar. Paridad de locales en `pt-BR.json`/`es.json`.
  - **Fallas de la suite Android corregidas antes de S22 Android**: el padding del código de producto a seis
    dígitos (los tests esperaban cuatro), el `Json` del test de reportes sin el serializador contextual de
    `BigDecimal`, el matcher del test de ack (qué lanzamiento idéntico se concilia es del matcher, no de la
    regla) y el panel de Admin componiendo el card de integración fuera de un host de Hilt.

- **L8** (decisión de implementación, 2026-10-30). La regla de S22 no se queda en la pantalla: el backend
  **rechaza** el `PUT` y el `DELETE` de un lanzamiento conciliado con `409 MASSAS_RECONCILED`, también para
  un administrador. El guard vive en `massas.service.ts` (`assertLaunchNotReconciled`) y compara el día
  completo con `reconcileMassasDay`, porque la conciliación es una decisión sobre pares: la misma fila está
  conciliada o no según qué asientos llegaron. Sin el guard, la regla sería una convención de la interfaz que
  cualquier `curl` podría saltear, y la promesa de consistencia con Delphi quedaría sin respaldo. La web
  traduce ese `409` con `massas.reconciledLocked` en vez de repetir la frase en inglés; Android ya no ofrece
  las acciones y trata el `409` como error de la operación.
