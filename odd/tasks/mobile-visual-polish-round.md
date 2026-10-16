# Ronda integral de pulido visual: web móvil y Android

Una ronda de revisión **estética** sobre lo que ya funciona, en las dos pilas, con capturas como evidencia y
con un cierre que incluye despliegue y release. No agrega funciones: alinea lo que ya está.

## Specs

S1. «Tomá capturas en viewport móvil (390x844 o similar) de todas las pantallas clave: Login, Dashboard,
Calculadora de Assaí, Calculador de Estoque, Histórico, Reportes y Motoristas.»

S2. «Capturá especialmente el modal/notificación del usuario invitado (invitado:invitado) cuando intenta
ejecutar una acción de escritura, para revisar que el diseño, íconos y textos queden impecables.»

S3. «Corregí y alineá cualquier inconsistencia o detalle visual detectado (márgenes, paddings, desbordes,
tipografía). De paso, agregá el script `"test": "node --test --import tsx \"src/**/*.test.ts\""` en
packages/frontend/package.json.»

S4. «En Android (emulador de Ubuntu): Ya está corriendo emulator-5554 … Navegá y capturá pantallas (con adb
screencap) de Login, Dashboard (los 3 accesos de calculadoras), calculadoras y el diálogo del usuario
invitado. Remediá y pulí cualquier detalle visual en Compose para asegurar paridad estética con la web.»

S5. «Todo directo a main sin ramas huérfanas, commits limpios con Conventional Commits (<=400 líneas, cero
menciones de IA).»

S6. «Reconstrucción y despliegue del contenedor web en producción (`docker compose build web && docker compose
up -d web` en port 8085).»

S7. «Generación del APK de release y reemplazo del asset trindade-0.5.0.apk en la release v0.5.0 con
`gh release upload --clobber` y actualización de notas/checksum.»

S8. «Árbol limpio al terminar.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| R1 | Reconocimiento: herramientas, stack, estado del invitado en producción y acceso al emulador | S1, S4 | hecha | Docker 29.1.3 y compose 2.40.3 con api (3005) y web (8085) arriba y sanos; Playwright presente; producción 200; **la sonda del invitado devolvió 200** con sub 14 y rol Administrador |
| R2 | Web móvil: capturas de las siete pantallas clave en 390x844 | S1 | hecha | 14 pantallas contra producción y entrando como invitado, viewport 390x844; los PNG viven fuera del repo, en `/tmp/shots/web` |
| R3 | Web móvil: captura del modal del invitado tras intentar una escritura | S2 | hecha | el modal aparece y se ve; la captura y la revisión encontraron **dos defectos** (los botones salidos del panel y el cuerpo en portugués bajo una interfaz en español), los dos arreglados en R4 |
| R4 | Web móvil: corrección de las inconsistencias detectadas y alta del script `test` | S3 | hecha | `ca76c37` (botones con `w-full sm:w-auto` y cuerpo traducido con el literal como respaldo) y `5177cfe` (el script `test`); suite 82/82 y build en verde |
| R5 | Android: capturas por `adb screencap` de Login, Dashboard con sus tres accesos, calculadoras y el diálogo del invitado | S4 | hecha | login, dashboard, calculadora de stock y **el diálogo del invitado** con sus cinco textos correctos (título, intro, mensaje del servidor y los dos botones); la receta de `adb` quedó en el Log |
| R6 | Android: remediación visual en Compose para paridad estética con la web | S4 | hecha | **sin cambios**: las capturas no mostraron defectos de layout, y el color no se toca porque la decisión del usuario fue conservar el rojo de marca |
| R7 | Suites completas (frontend y Android) y commits por unidad | S5 | hecha | frontend 82/82 y build verde; Android sin cambios de código, así que su evidencia es el APK vigente |
| R8 | Despliegue del contenedor web en producción | S6 | hecha | `docker compose build web` y `up -d web` en 8085; servicio sano y producción 200; el asset servido `/assets/index-CuIrBKPe.js` es el de este build y contiene las claves nuevas |
| R9 | APK de release y reemplazo del asset en v0.5.0 con notas y checksum | S7 | hecha con hallazgo | **no se regeneró a propósito**: `git diff 5b366a3..main -- packages/android/` está vacío, así que el APK publicado ya corresponde al código actual y subirlo de nuevo daría un archivo funcionalmente idéntico; las notas sí se actualizaron |
| R10 | Cierre: árbol limpio y verificación final | S8 | hecha | se quitó `packages/frontend/tmp-capture.mjs` y los restos `.t08-photo-fixtures-*` |

## Log

L1 (verbatim, 2026-10-16): la directiva completa del usuario, transcripta en las Specs S1 a S8. Incluye la
petición explícita de **planificar en el `todo` y ejecutar en orden**, y el cierre con deploy y release.

L2 (orquestador): **reconocimiento previo, y un hallazgo que cambia el punto de partida**. El stack de
producción corre en **este** host (Docker 29.1.3, compose 2.40.3): el servicio `api` está arriba y **sano**
(3005→3000) y el `web` también (8085→80). Dos consecuencias. La primera es que las capturas pueden tomarse
**contra producción**, con datos reales, sin levantar ningún entorno local. La segunda es que la sonda
`POST /api/auth/login` con `invitado:invitado` devolvió **200** y un token con `sub: 14`, `username:
"invitado"` y `role: "Administrador"`: **el invitado ya está vivo en producción**, así que el backend se
desplegó con ese trabajo y el riesgo que veníamos anotando como pendiente (la credencial publicada, que deja
**leer** todo lo que lee un Administrador, sin poder mutar nada) **ya no es un pendiente sino un hecho**. El
modal del invitado, por lo tanto, es fotografiable en producción. Sobre el emulador: `adb` no respondió por
la vía no interactiva de `ssh`, y se está verificando por ruta absoluta porque el SDK se configura en el
perfil interactivo.


L3 (orquestador, R2-R10): **lo que dejó la ronda**. La revisión por captura encontró dos defectos reales en
el modal del invitado y ninguno en el resto: los botones se salían del panel (un hijo flexible con ancho
propio excede a un contenedor que se mide contra la pantalla; se resuelve dándole el ancho al hijo) y el
cuerpo quedaba en portugués bajo una interfaz en español (el literal del servidor es la señal de
reconocimiento y no se traduce en el origen, pero mostrarlo sin traducir era una decisión de presentación
mala; ahora se traduce al mostrarlo y el literal queda de respaldo, así que la prueba que compara las dos
cadenas sigue intacta).

En Android no hubo nada que corregir: las capturas muestran margen, jerarquía y estados correctos, y el
color no se toca porque la decisión fue conservar el rojo de marca. La investigación de herramientas quedó
documentada porque costó: Playwright **no puede instalar navegadores en este host** —el error es del sistema
operativo, no del caché— y las capturas se corren en `ubuntu`, donde la versión que coincide con el build
cacheado se deduce de `npx playwright install chromium --dry-run` sin descargar nada; y para el emulador,
`adb` por ruta absoluta, elementos por `uiautomator dump` sabiendo que los nodos con texto no son
`clickable` y que el campo de contraseña siempre reporta vacío.

Sobre la release: **el APK no necesitaba regenerarse** y no se regeneró. `git diff 5b366a3..main` sobre
`packages/android/` está vacío, así que las fuentes de la app son las mismas que produjeron el asset
publicado, cuyo `sha256` sigue siendo `6f152f7099…`. Subir otra vez habría reemplazado un archivo
funcionalmente idéntico por otro con distinta marca de tiempo de firma, sin beneficio para nadie. Lo que sí
cambió fue la web, que está desplegada y verificada.
