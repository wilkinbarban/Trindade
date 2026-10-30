# Lanzamiento por lote: un solo viaje al atajo para todos los productos

Hoy la cola drena un producto por viaje: la página dispara el atajo, el atajo vuelve, la página guarda el
resultado y dispara el siguiente. Lanzar tres productos son tres saltos Safari→Atajos→Safari, y el operario
ve la pantalla parpadear tres veces sin saber si el segundo producto entró. El lote manda los tres cuerpos en
el mismo sobre, deja que el atajo repita el `POST` por WiFi, y vuelve una sola vez con los tres resultados.

## Specs

- **S1**: «Lograr que el lanzamiento de múltiples productos (ej. 3 productos) viaje en un ÚNICO sobre al
  atajo de iOS, de modo que el iPhone solo salte UNA vez a la app Atajos.»
- **S2**: «El atajo iterará localmente por WiFi los POST a /entradasitem y volverá a Safari una sola vez con
  el resultado consolidado.»
- **S3**: «Diseña la estructura del sobre batch (retrocompatible con lanzamientos individuales) y cómo debe
  estructurarse el payload JSON para que el atajo de iOS lo pueda iterar con la acción nativa 'Repetir con
  cada elemento'.»
- **S4**: «Diseña cómo debe recibir Trindade la vuelta (aero_action) con los resultados de cada item del lote
  o el veredicto consolidado para la bitácora y el modal.»
- **S5**: Un producto sigue funcionando igual que hoy, y un iPhone con el atajo viejo (sin la rama de lote)
  sigue pudiendo lanzar: la web no puede quedar esperando un sobre que ese teléfono no sabe leer.
- **S6**: El atajo no juzga. Transporta el sobre y devuelve lo que el ERP contestó, igual que hoy: la
  interpretación del veredicto vive en la web, donde se testea.
- **S7**: Viewport de 375 px: cero desborde horizontal y todo control táctil con 44 px de alto mínimo.
- **S8**: i18n en `pt-BR` y `es`, sin claves huérfanas.
- **S9**: Trunk-based sobre `main`, commits atómicos de hasta ~400 líneas, Conventional Commits, sin
  `Co-Authored-By` ni atribución a IA.
- **S10**: Al cerrar: `docker compose up -d --build` y HTTP 200 en producción.
- **S11**: «Cuando es 1 solo producto, debe seguir funcionando tal cual hoy sin problemas ni fricción.» Un
  producto viaja con `aero_action=entrada` y sin `bodies`, por el camino que ya está probado en producción.
- **S12**: «Adoptamos la opción (a) para la receta (leer headers -> Authorization) y tope de 8 items.»
- **S13**: «Agregar botón directo de instalación arriba de todo con el link
  https://www.icloud.com/shortcuts/3216695a428f42539fec7abbd5419f34» — abre en pestaña nueva con
  `rel="noopener noreferrer"`, táctil de 44 px mínimo, y que cualquier teléfono nuevo de la planta se instale
  en segundos sin armar las acciones a mano.
- **S14**: «Clarificar y reescribir la receta manual paso a paso con los 20 pasos exactos y claros», dividida en
  bloques (Desempaquetar, Lote, Individual, Retorno a Safari).
- **S15**: «Destacando que en el paso 16 se renombra la variable a `body_item` para no confundirla con
  `bodies`.»

## Diseño

### El sobre (retrocompatible)

El sobre de hoy no cambia de forma; se le agrega **una** clave opcional:

```json
{
  "url": "http://192.168.1.50:8000/api/entradasitem",
  "method": "POST",
  "headers": { "Authorization": "Basic …", "Content-Type": "application/json", "Accept": "application/json" },
  "body": "{\"PRO_COD\":\"001192\",…}",
  "bodies": [
    "{\"PRO_COD\":\"001192\",…}",
    "{\"PRO_COD\":\"000084\",…}",
    "{\"PRO_COD\":\"001045\",…}"
  ],
  "callback": "https://trindademasas.duckdns.org/massas-launch?aero_action=entrada_lote&aero_data="
}
```

- **`bodies` es una lista de textos, no de diccionarios.** Es la decisión central del diseño: la acción
  «Repetir con cada elemento» entrega el elemento tal cual, y el cuerpo del `POST` necesita un texto. Si los
  elementos fueran diccionarios, el atajo tendría que volver a serializar cada uno en el teléfono, que es
  justo lo que el `body` como texto evita desde el primer día. La web serializa; el atajo transporta.
- **`body` sigue viajando** con el primer producto. Un atajo sin rama de lote lo ignora a `bodies` y hace su
  `POST` de siempre: el lote degrada a un viaje por producto (el comportamiento actual) en vez de fallar.
- **`url`, `method`, `headers` y `callback` son los mismos valores para todo el lote.** Todos los productos
  van al mismo endpoint, con la misma credencial y vuelven a la misma página: mandarlos por item sería
  repetir tres veces lo que no cambia.
- **Tope de 8 cuerpos por sobre** (`AERO_BATCH_MAX`). El sobre viaja dentro de un deep link
  `shortcuts://x-callback-url/run-shortcut?…&text=<sobre codificado>`; ocho cuerpos de ~300 bytes son ~3 KB
  codificados, cómodos para iOS; un día de veinte productos serían ~8 KB y el enlace empieza a ser una
  apuesta. Un lote mayor se parte en varios viajes: la cola ya es la lista de lo que falta.

### El umbral: uno es uno, dos es un lote

La regla es el número de productos, no la pantalla ni el azar:

| Productos | Sobre | `aero_action` |
| --- | --- | --- |
| 1 | el de hoy, sin `bodies` | `entrada` |
| 2 a 8 | `bodies` + `body` del primero | `entrada_lote` |
| 9 o más | varios viajes de hasta 8 | el que corresponda a cada tanda |

Un lote de nueve son dos viajes: ocho en el primero y uno en el segundo, y ese segundo vuelve a ser un
producto suelto. Un sobrante de uno no se disfraza de lote: si el operario lanzó nueve, el iPhone salta dos
veces porque hay dos sobres, y la segunda vez no carga con una rama que no necesita.

El umbral vive en una función pura (`buildAeroLaunchTrips`), no en un `if` dentro de la página: es la regla
que Wilkin pidió explícitamente, y una regla así se testea.

### Las acciones del atajo (20, contando las dos ramas)

El atajo sigue siendo **uno solo** y se sigue llamando igual. La rama nueva sólo se activa cuando el sobre
trae `bodies`; la rama de un producto es la de hoy. Wilkin validó veinte acciones reales contra su enlace de
iCloud, así que la receta del diálogo se numera igual y se agrupa en cuatro bloques para que se pueda seguir
con el pulgar.

**A. Desempaquetar (1–5)** — leer el sobre una sola vez.

| # | Acción | Qué lleva |
| --- | --- | --- |
| 1 | Obtener diccionario de la entrada | — |
| 2 | Obtener valor del diccionario | `url` |
| 3 | Obtener valor del diccionario | `headers` |
| 4 | Obtener valor del diccionario sobre (3) | `Authorization` |
| 5 | Obtener valor del diccionario | `callback` |

**B. Lote (6–14)** — sólo cuando el sobre trae `bodies`.

| # | Acción | Qué lleva |
| --- | --- | --- |
| 6 | **Si** el diccionario tiene valor para `bodies` | condición |
| 7 | Establecer variable `respostas` | lista vacía: iOS no arrastra el valor de un uso anterior |
| 8 | **Repetir con cada elemento** en (`bodies`) | abre el ciclo |
| 9 | ⤷ Obtener contenido de URL | `url`, `POST`, `Authorization` = (4), `Content-Type`/`Accept`, cuerpo **Texto** = Elemento de repetición |
| 10 | ⤷ Añadir a variable `respostas` | resultado de (9) |
| 11 | Fin de repetición | — |
| 12 | Combinar texto `respostas` con separador `,` | — |
| 13 | Texto: `[` + (12) + `]` | — |
| 14 | Codificar URL sobre (13) | — |

**C. Individual (15–18)** — la rama de un solo producto.

| # | Acción | Qué lleva |
| --- | --- | --- |
| 15 | **Si no** | rama de un solo producto |
| 16 | Obtener valor del diccionario y **renombrar la variable a `body_item`** | cuerpo del `POST`; el nombre no puede ser `body` porque iOS lo confunde con `bodies` y el cuerpo sale vacío |
| 17 | ⤷ Obtener contenido de URL | cuerpo **Texto** = `body_item` |
| 18 | Codificar URL sobre (17) | — |

**D. Retorno a Safari (19–20)** — una sola vuelta, con cualquiera de las dos ramas.

| # | Acción | Qué lleva |
| --- | --- | --- |
| 19 | Fin de la condición | cierra el `Si` de (6) |
| 20 | Abrir URLs: `callback` + resultado codificado | vuelve a Safari una vez |

La pieza que hace que esto sea JSON válido sin escapar nada: cada respuesta del ERP es un objeto JSON, y la
coma que agrega «Combinar texto» cae **entre** objetos. Las comas internas de un mensaje van dentro de
comillas, así que `[{…},{…},{…}]` queda bien formado. Sólo rompe si el ERP contesta algo que no es JSON (una
página de error del servidor, un `Could not connect to the server` del teléfono); ese caso ya está previsto y
termina en «no se pudo leer la respuesta», nunca en un lote a medias contado como completo.

**El atajo no cuenta ni decide**: devuelve el array crudo en el orden en que se envió.

### La vuelta

- Acción nueva **`entrada_lote`**, en el `callback` del sobre. No se reutiliza `entrada` porque un array y un
  objeto no significan lo mismo y la web no debería adivinarlo por la forma.
- `aero_data` trae el array de respuestas **en el orden del lote**: `data[0]` es el primer producto de
  `bodies`. La web compara su largo con los items que mandó.
- La web interpreta cada elemento con el mismo `readAeroBridgeResult` de hoy. Un elemento ilegible es un
  item fallido, no un lote ilegible.
- **Estados del lote** (`readAeroBatchResult`):

  | Estado | Cuándo | Tono | Qué se dice |
  | --- | --- | --- | --- |
  | `complete` | tantas respuestas como items, todas `success: true` | `ok` | «3 lançamentos sincronizados com o ERP» |
  | `partial` | algunas ok, otras no o sin respuesta | `warn` | «2 de 3 sincronizados — 1 falhou» + el mensaje del ERP por producto |
  | `failed` | todas fallaron | `error` | «Nenhum dos 3 lançamentos foi sincronizado» + el mensaje |
  | `unreadable` | `aero_data` no es un array | `error` | «Não foi possível ler a resposta do ERP» |
  | `legacy` | llega un objeto suelto con `entrada_lote` | — | el atajo no tiene la rama: se cae al flujo por producto de hoy |

- **Bitácora**: una línea `requested` con el lote entero (`payload` = los tres cuerpos y
  `note: "batch 3 · via=bridge"`), y una línea `result` consolidada
  (`note: "batch ok=2 failed=1"`, `error` con el primer mensaje fallido, `response` con el resumen). Dos
  líneas por lote en vez de dos por producto: el detalle por item ya está en el `payload`, y una bitácora que
  crece con el producto de la planta se vuelve ilegible justo cuando más se necesita.
- **Cola**: tras un `complete` se limpia y el modal sale una vez. Tras un `partial` la cola guarda **sólo los
  fallidos** y el modal ofrece «Reenviar falhas (1)»: un segundo viaje lo decide el operario, no la página.
  Tras un `legacy` se sigue drenando item por item como hoy, sin modal hasta el final.
- **`syncAwaiting`** pasa a contar productos, no viajes: «3 pendentes» antes de salir, «2 de 3» en el
  resultado parcial.

## Decisiones

- **D1 — Lista de textos, no de diccionarios.** Es lo que permite que «Repetir con cada elemento» alimente el
  cuerpo del `POST` sin una conversión en el teléfono, y lo que mantiene la regla de que el atajo transporta.
- **D2 — `body` y `bodies` conviven.** El sobre sigue siendo válido para un atajo de ocho acciones; la
  degradación es lenta y visible (vuelve a saltar por producto), nunca silenciosa ni rota.
- **D3 — Acción propia `entrada_lote`.** El tipo del dato no debe ser el contrato. Además deja la puerta
  abierta a una salida por lote (`saida_lote`) sin tocar lo que ya funciona.
- **D4 — Tope de 8 y varios viajes.** Un deep link no es un canal de datos: se acota en la web, no en el
  teléfono, y la cola ya sabe partirse.
- **D5 — El reintento es del operario.** Reenviar solo los fallidos en un segundo viaje automático dejaría al
  iPhone saltando otra vez sin que nadie lo haya pedido, que es el problema que este hito viene a resolver.
- **D6 — Bitácora por lote.** El detalle vive en el `payload`; el resultado es una línea. Se puede reconstruir
  qué producto falló sin leer diez líneas por día.
- **D7 — El umbral es el número de productos, y el sobrante vuelve a ser uno.** Un producto jamás entra a la
  rama de lote: recorre el camino que ya funciona, con su `entrada` y su sobre sin `bodies`. Un lote de nueve
  se parte en ocho más uno, y el uno final viaja como producto suelto. Así el iPhone salta dos veces porque
  hay dos sobres, no por una regla escondida.
- **D8 — La receta lee `headers` → `Authorization` (opción a).** Es la que Wilkin eligió: sin claves nuevas en
  el sobre y con la receta hablando del contrato que realmente viaja. El test de la receta pasa a validar el
  sobre real, no una lista de claves paralela.

- **D9 — El enlace de iCloud es el camino principal, la receta es el respaldo.** Wilkin lo construyó y lo
  validó con nosotros; instalar en un toque no deja lugar a que un teléfono nuevo arme veinte acciones a mano
  y se equivoque en una. La receta se queda porque explica qué hace el atajo y porque un teléfono sin iCloud
  (o con el enlace caído) todavía tiene que poder hacerlo.
- **D10 — La receta se numera igual que el atajo real: 20 acciones.** Numerar de menos no es un detalle de
  estilo: un operario que cuenta diecisiete y no llega a veinte se queda con un atajo que no cierra el `Si` y
  no vuelve a Safari.
- **D11 — `body_item` no es cosmético.** iOS resuelve variables por nombre y `body` colisiona con `bodies` en
  la rama de lote: el `POST` individual salía con el cuerpo del último elemento de la repetición. El paso 16
  renombra explícitamente.

## Hallazgo del `auth` que la opción (a) resuelve

El diálogo *Como instalar o Atalho no iPhone* pide copiar la clave **`auth`** (paso 3) y explica el
`Authorization` a partir de ella, pero `buildAeroBridgeEnvelope` no produce `auth`: produce
`headers: { Authorization }`. El sobre de muestra que el mismo diálogo ofrece copiar sí trae `headers`. O sea
que el contrato documentado y el contrato que viaja no coinciden, y el test
(`aero-integration.test.ts` → `recipe.keys`) valida la receta escrita, no el sobre real. Quien siguió los
pasos al pie de la letra leyó del diccionario una clave que no existe.

Wilkin eligió **(a)**: la receta lee `headers` y de ahí saca `Authorization` (paso 3 del diálogo: obtener el
diccionario `headers`, paso 4: obtener su valor `Authorization`). El sobre no cambia de forma, no aparece
ninguna clave nueva, y el atajo igual necesita `headers` para `Content-Type`/`Accept` si algún día se
parametrizan. El test de la receta tiene que validar el sobre real (`buildAeroBridgeEnvelope`), no una lista
de claves escrita al lado: si la receta dice `headers`, el sobre tiene que traer `headers`.

## Tasks

- **T1** ✅ `7c7fe9c` (S3, S5, S6, S11, D1, D2, D4, D7) — `aero-batch.ts`: `AERO_BATCH_MAX = 8`,
  `buildAeroLaunchTrips(payloads)` (1 → `entrada` sin `bodies`; 2–8 → `entrada_lote` con `bodies` + `body` del
  primero; 9+ → tandas de 8 y el sobrante de 1 como `entrada`). Tests puros del umbral, del sobre y del tope.
- **T2** ✅ `7c7fe9c` (S3, S5, D3) — `entrada_lote` en `AeroBridgeAction`/`parseAeroCallback`; `body`/`bodies` leídos como
  textos. Tests de parseo, del objeto suelto y del array.
- **T3** ✅ `6bc93e5` (S4, D6) — `readAeroBatchResult` y `batchSyncFeedback` (los cuatro estados y los tonos) en
  `aero-feedback.ts`. Tests puros.
- **T4** ✅ `caf7d03` (S1, S2, S4) — `MassasLaunchPage`: el lanzamiento arma un sobre por lote, la cola drena por lotes y el
  callback `entrada_lote` cierra con el modal de lote; bitácora `requested`/`result` consolidadas;
  `syncAwaiting` en productos.
- **T5** ✅ `caf7d03` (S4, D5) — Acción «Reenviar falhas» sobre la cola parcial, con su traducción y su test de cableado.
- **T6** ✅ `f91ce29`, `9bd154c` (S7, S8, S12, D8) — Diálogo del atajo: la receta de 17 acciones con la rama de lote, `keys` leyendo
  `headers` (opción a) con el test validando el sobre real, i18n `pt-BR`/`es`, 375 px y 44 px.
- **T7** ✅ `38c963f`, `a42aa61` (S9, S10) — E2E: tres productos vuelven en un solo `aero_action=entrada_lote` con tres respuestas →
  modal «3 sincronizados» y una sola línea de resultado en la bitácora. Suites, typecheck, build y HTTP 200.
- **T8** ✅ `87d945b` (S13, S14, S15, D9, D10, D11) — `AeroShortcutDialog`: tarjeta destacada arriba con el botón de iCloud
  (`https://www.icloud.com/shortcuts/3216695a428f42539fec7abbd5419f34`, pestaña nueva, `noopener noreferrer`,
  44 px) y la receta manual reescrita en cuatro bloques con los 20 pasos, incluido el renombre a `body_item`
  del paso 16. `actionCount` a 20, URL en `aero-integration.ts`, i18n `pt-BR`/`es`. Tests unitarios del
  conteo, del enlace y de las claves copiables.
- **T9** ✅ `3a4a4f1` (S7, S9, S10) — `aero-shortcut-dialog.spec.ts`: botón de iCloud por `data-testid`, href exacto,
  pestaña nueva, 44 px, los cuatro bloques y los 20 pasos, cero desborde en 375 px. Suites, typecheck, build
  y HTTP 200.

## Riesgos

- **Item 3** en la renuncia a `aero_action=entrada` para el lote: agrega una acción a un contrato que ya
  consume el iPhone y el parser. Se mitiga manteniendo `entrada` intacta y con tests de las dos.
- **Item 1** no aplica: el lote no escribe en la base por su cuenta; los `POST` los sigue haciendo el ERP.
- **Item 6** acotado: el único camino sin test automático posible es el propio atajo (se instala a mano);
  todo lo que decide la web queda cubierto por unitarios y por el E2E que simula la vuelta.

## Log

- **L1** — «Hola! Ahora vamos a encarar la mejora del Lanzamiento por Lote (Batch) que definimos con Wilkin.

  Contexto y causa raíz: Wilkin probó lanzar 3 productos al mismo tiempo desde el iPhone y observó que la app
  hizo 3 saltos consecutivos entre Safari y Atajos (uno por cada producto). Al inspeccionar MassasLaunchPage,
  vimos que la cola (AeroQueue) drena producto por producto: cada retorno de callback vuelve a disparar
  triggerAeroSync con el siguiente item de la cola.

  Objetivo: Lograr que el lanzamiento de múltiples productos (ej. 3 productos) viaje en un ÚNICO sobre al
  atajo de iOS, de modo que el iPhone solo salte UNA vez a la app Atajos. El atajo iterará localmente por
  WiFi los POST a /entradasitem y volverá a Safari una sola vez con el resultado consolidado.

  Tu tarea en esta primera etapa (Modo Investigación y ODD): 1. Analiza aero-queue.ts, aero-integration.ts y
  el flujo de lanzamiento en MassasLaunchPage.tsx. 2. Diseña la estructura del sobre batch (retrocompatible
  con lanzamientos individuales) y cómo debe estructurarse el payload JSON para que el atajo de iOS lo pueda
  iterar con la acción nativa 'Repetir con cada elemento'. 3. Diseña cómo debe recibir Trindade la vuelta
  (aero_action) con los resultados de cada item del lote o el veredicto consolidado para la bitácora y el
  modal. 4. Redacta el borrador ODD en odd/tasks/lanzamiento-por-lote-aero.md con las especificaciones y
  tareas. No implementes código todavía: pasanos tu reporte de diseño y la estructura exacta del atajo para
  que lo validemos juntos con Wilkin antes de codificar.»
- **L2** — Lectura de `aero-queue.ts`, `aero-integration.ts` (sobre, callback, receta) y `MassasLaunchPage.tsx`
  (lanzamiento, `triggerAeroSync`, callback). Hallazgo del `auth` vs `headers` en la receta.
- **L3** — «Excelente trabajo con la remediación del Espelho do ERP. Ya comiteé docker/nginx.conf a main
  (0b8fd09), hacé 'git pull --rebase'.

  Wilkin aprobó el plan de lanzamiento por lote con una aclaración clave:
  - Cuando es 1 solo producto, debe seguir funcionando tal cual hoy sin problemas ni fricción.
  - Cuando son 2 o más productos, viajan juntos en un único sobre batch (bodies: [...]) hacia el atajo, con la
    acción 'entrada_lote'.
  - Adoptamos la opción (a) para la receta (leer headers -> Authorization) y tope de 8 items.

  Comiteá el artefacto ODD (odd/tasks/lanzamiento-por-lote-aero.md) y arrancá con la implementación bajo TDD
  de las tareas T1 a T7 (aero-batch, entrada_lote, feedback consolidado, MassasLaunchPage, reintento de
  fallas y actualización de la receta en el modal). Manteneme al tanto.»
- **L4** — Evidencia de la implementación (2026-10-29). Suites y verificación observadas, no inferidas:
  - Frontend `npm test`: 345/345 en verde, incluidos `aero-batch.test.ts` (umbral 1/2–8/9), `aero-queue.test.ts`
    (cola en vuelo), `aero-feedback.test.ts` (cuatro estados) y `aero-bridge-wiring.test.ts`.
  - `npx tsc --noEmit` sin errores.
  - Backend `npm test`: 569/569. El primer intento falló en `db/index.test.ts` porque `dist/` era viejo
    (`spawn` de `dist/server.js`); tras `npm run build`, verde. No toca el código de este feature.
  - Playwright completo: 69/69 sobre 375×667, incluidos `aero-batch-launch.spec.ts` (3 productos en un sobre,
    lote parcial con «Reenviar falhas (1)», producto suelto con el camino viejo) y
    `aero-shortcut-dialog.spec.ts` (17 pasos, cero desborde, copiar de 44 px).
  - `docker compose up -d --build web api`: `web` y `api` healthy; `GET /` 200 y `GET /api/health` 200 en
    `http://localhost:8085`.
- **L5** — Dos defectos encontrados por el E2E y corregidos en `3be0eed`, ambos del mismo malentendido: la
  cola con los productos en el teléfono. (1) `readAeroQueue` borraba una cola con `items` vacío e `inFlight`
  lleno, así que la vuelta no sabía qué producto había fallado y un lote parcial se cerraba como completo.
  (2) El cierre de `aero_action=entrada` drenaba `items`, vacío ahora que el producto espera en `inFlight`, y
  el operario volvía a una pantalla sin veredicto. Un rechazo de un producto suelto ahora vuelve al frente de
  la cola en lugar de perderse.
- **L6** — Colisión de specs: `erp-result-dialogs` y el nuevo `aero-batch-launch` creaban `wilkin` con
  contraseñas distintas y el segundo en correr no podía entrar al panel. Se centralizó en
  `src/__e2e__/aero-owner.ts` (`a42aa61`). El fallo era del arnés de pruebas, no del producto.
- **L7** — «Wilkin probó el atajo con nosotros y lo certificamos con su link de iCloud. Nos pidió dos mejoras en
  AeroShortcutDialog: 1) Agregar botón directo de instalación arriba de todo con el link
  https://www.icloud.com/shortcuts/3216695a428f42539fec7abbd5419f34 ('Instalar Atalho Pronto (1 toque)' /
  'Instalar Atajo Listo (1 toque)'). 2) Clarificar y reescribir la receta manual paso a paso con los 20 pasos
  exactos y claros, destacando que en el paso 16 se renombra la variable a 'body_item' para no confundirla con
  'bodies'. Actualiza aero-shortcut-dialog.spec.ts, corre suites y Playwright, comitea en main y rebuild web.»
- **L8** — Cierre de T8/T9 (2026-10-29), observado y no inferido: `packages/frontend` 349/349, `packages/backend`
  569/569, Playwright 71/71 (los dos del diálogo incluidos), `tsc --noEmit` limpio, `docker compose up -d
  --build web` y `web=200` / `api=200` en `http://localhost:8085`. Los dos defects que el RED encontró fueron
  del test, no del producto: cortar la receta por `<ol>` caía en el helper `StepBlock` (componente vacío), y
  `text=Ação 1` también matcheaba «Ação 10»; el primero se cortó por `<StepBlock>`, el segundo se ancló con
  `^Ação N —`.
