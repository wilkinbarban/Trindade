# El formulario de Relatório no se envía con una lectura de temperatura en blanco

Defecto de la app Android al guardar el Relatório: el servidor responde 400 «Falta colocar as temperaturas no
relatório.» porque el cliente omite las lecturas vacías y no comprueba antes de enviar. La web sí lo comprueba
(`hasMissingTemp` en `ReportsPage.tsx` y `ReportEditPage.tsx`, con el texto `reports.temperaturesRequired`), así
que la remediación es la paridad: la regla que el servidor exige —una lectura por cada ranura declarada de cada
tarea de temperatura (Task 5 «Câmara Principal»: 2; Tasks 40, 41 y 42: 1 cada una)— se comprueba en el cliente
antes de mandar el cuerpo, y el operador ve qué falta.

## Specs

S1. «Planificar en el TODO la remediacion con paridad Web.»

S2. «Validar en ReportGeneratorViewModel (y ReportEditViewModel si aplica) que todas las temperaturas
obligatorias esten completas antes del envio y mostrar feedback claro.»

S3. «Asegurar persistencia de estado en Compose para que el scroll no pierda datos.»

S4. «Actualizar y correr tests unitarios de Android asegurando 100% verde.»

S5. «Commit directo a main con Conventional Commits y sin autoria IA.»

S2a. **(la causa raíz, tal como la fijó el usuario)** «en backend (reports.command.service.ts),
validateTemperatures exige 5 lecturas de temperatura exactas (Task 5 Camara Prin. exige 2 lecturas, Tasks 40, 41
y 42 exigen 1 lectura cada una). Si alguna esta vacia, ReportPayload.kt la omite y backend responde 400 'Falta
colocar as temperaturas no relatorio.'. En Android (ReportGeneratorViewModel.kt), canSubmit no valida que todas
las lecturas obligatorias esten completas antes de enviar (a diferencia de la Web donde hasMissingTemp bloquea
y muestra reports.temperaturesRequired).»

S2b. **El texto que se muestra** (paridad literal con el catálogo de la web, `packages/frontend/src/i18n/locales/pt-BR.json`):
`"temperaturesRequired": "Falta colocar as temperaturas no relatório."`

S6. «En ReportsRepository.kt y ReportGeneratorViewModel.kt: cuando el backend responde 400 (ej. reporte duplicado
para el turno), parsear el cuerpo del error ('error'/'message') y mostrar el mensaje real del servidor ('Ja
existe um relatorio para este turno hoje.'). No asumir que todo 400 es por temperaturas (la validacion de
temperaturas ya la hace el cliente).»

S7. «En ReportForm.kt: permitir valores negativos (ej. -2,4 para camaras frias). KeyboardType.Decimal oculta el
'-' en Android; permitir el signo menos y decimales con coma/punto.»

S8. «En LoadingScreen.kt: los botones de envio directo a fleteros (state.fleterosWithPhone()) se superponen sin
espacio. Envolverlos en FlowRow con Arrangement.spacedBy(8.dp) horizontal y vertical para que no se pisen.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Regla compartida de completitud en `ReportPayload.kt` (`TemperatureSlot` + `missingTemperatureReadings`), una sola casa para las dos superficies | S2, S2a | hecha | `8507fe3` |
| T2 | Generador: `canSubmit` exige el juego completo, mensaje `report_temperatures_required` y los campos que faltan marcados | S2, S2b | hecha | `8507fe3` |
| T3 | Edición: la misma regla en `ReportEditViewModel` y el mismo mensaje en la pantalla (sólo cuando el reporte es editable) | S2, S2b | hecha | `8507fe3` |
| T4 | Persistencia de estado en Compose: claves estables en la lista del generador y prueba de que una lectura escrita sobrevive al scroll | S3 | hecha | `8507fe3` (claves) · `495173e` (prueba) |
| T5 | Tests unitarios: helper del payload, los dos ViewModel y las dos pantallas; corregir los que el cambio de regla mueve | S4 | hecha | `778527b` · `495173e` · `62ae325` |
| T6 | Carril Android completo (`make ci-android`) en verde | S4 | hecha | «Android lane passed.»: 90 clases, 867 tests, 0 fallas, 0 errores, 0 omitidos |
| T7 | Commit directo a `main`, Conventional Commits, sin autoría IA; registrar el commit acá | S5 | hecha | `8507fe3` · `8a2cc35` · `778527b` · `495173e` · `62ae325` · el commit de este documento |
| T8 | Mensaje real del servidor en un rechazo: el sobre del cuerpo viaja hasta los dos ViewModel y cada uno muestra su frase en vez de suponer temperaturas | S6 | hecha | `8507fe3` · `778527b` |
| T9 | Temperaturas negativas: el signo alcanzable en el campo y `-2,4` llegando al cuerpo como −2,4 | S7 | hecha | `8507fe3` · `778527b` · `495173e` |
| T10 | Los envíos directos a fleteros en `FlowRow`, con separación horizontal y vertical | S8 | hecha | `8a2cc35` · `62ae325` |

## Log

L1 (verbatim, directiva del usuario): «Tenemos un bug en la app Android al guardar el Relatorio: da error de
temperaturas. Causa raiz confirmada: en backend (reports.command.service.ts), validateTemperatures exige 5
lecturas de temperatura exactas (Task 5 Camara Prin. exige 2 lecturas, Tasks 40, 41 y 42 exigen 1 lectura cada
una). Si alguna esta vacia, ReportPayload.kt la omite y backend responde 400 'Falta colocar as temperaturas no
relatorio.'. En Android (ReportGeneratorViewModel.kt), canSubmit no valida que todas las lecturas obligatorias
esten completas antes de enviar (a diferencia de la Web donde hasMissingTemp bloquea y muestra
reports.temperaturesRequired). Tareas: 1) Planificar en el TODO la remediacion con paridad Web. 2) Validar en
ReportGeneratorViewModel (y ReportEditViewModel si aplica) que todas las temperaturas obligatorias esten
completas antes del envio y mostrar feedback claro. 3) Asegurar persistencia de estado en Compose para que el
scroll no pierda datos. 4) Actualizar y correr tests unitarios de Android asegurando 100% verde. 5) Commit
directo a main con Conventional Commits y sin autoria IA. Procede con la planificacion y ejecucion.»

L2 (relevamiento, 3 lecturas acotadas): la regla vive en el servidor —
`validateTemperatures` (`packages/backend/src/modules/reports/reports.command.service.ts:33`) arma el conjunto
`name_pt:readingIndex` de todas las tareas activas de categorías de tipo `temperature` y exige que el cuerpo lo
traiga completo; el catálogo de referencia (`packages/backend/src/db/reference-data.ts`) tiene exactamente las
cuatro tareas y las cinco lecturas que el usuario nombró. En Android, `ReportPayload.kt` deja fuera del cuerpo
cada lectura en blanco y los dos ViewModel (`ReportGeneratorViewModel.kt:89`, `ReportEditViewModel.kt:78`)
definen `canSubmit` sin mirar las temperaturas. La web sí lo comprueba antes del `POST`/`PATCH`
(`packages/frontend/src/pages/ReportsPage.tsx:178`, `ReportEditPage.tsx:303`).

L3 (decisión, T3): la regla se aplica también a `ReportEditViewModel` porque el `PATCH` valida el mismo cuerpo
con la misma función (`reports.command.service.ts:140`) y porque el formulario reemplaza el conjunto de
lecturas en vez de fusionarlo: un cuerpo con una lectura en blanco ya se rechaza hoy con el mismo 400, sólo que
sin decir cuál falta.

L4 (decisión, T1): «completa» significa **cada lectura declarada parsea a un número**, no «el campo no está
vacío». El texto que la web sólo mira en blanco (`String(value).trim() === ''`) y luego convierte con
`Number(...)`, que devuelve `NaN` para un texto cualquiera. En Android el parser que manda es `toReading`
(coma decimal incluida) y es el mismo que arma el cuerpo, así que un campo con `abc` se bloquea en el cliente en
vez de producir el mismo 400 con el mensaje apuntando a otra cosa.

L5 (decisión, límite deliberado): la web además bloquea cuando **no hay ninguna** tarea de temperatura
(`hasMissingTemp || activeTempTasks.length === 0`). Acá no se replica: si el servidor no tiene tareas de
temperatura activas, `validateTemperatures` arma un conjunto vacío y acepta el cuerpo con `temperatures`
vacío, así que bloquear dejaría un formulario imposible de enviar. La regla que se implementa es la del
servidor: las ranuras declaradas, completas.

L6 (proceso): el carril de escritura delegado (`gentle-ai-worker`, unidad «superficie de edición + tests»)
falló en su primer turno con «assistant reported an error» y cero llamadas a herramientas: es un fallo de
infraestructura del subagente, no un hallazgo del producto, y el árbol quedó intacto (verificado con
`git status`). La unidad se hizo en línea, como el resto, y el fallo se reporta en el cierre.

L7 (incidente de herramienta, resuelto): el primer intento de correr el carril focalizado abortó en
`packageDebugResources` con «Unable to delete directory … app/build/intermediates/incremental/debug/
packageDebugResources»: había 1293 rutas `root:root` dentro de los directorios de build (residuo de una
corrida anterior del contenedor sin `--user`), y el proceso del carril —que corre con el uid del operador—
no puede borrarlas. Se limpiaron sólo las rutas `root:root` bajo `packages/android/app/build` y
`packages/android/build` con un contenedor `--user 0:0` (salidas de build, regenerables y fuera de git),
quedaron en cero, y el carril se relanzó con `set -o pipefail` porque el primer intento había reportado
salida 0 con el build fallado: el `tail` al final del pipe se comía el código de salida.

L8 (verbatim, ampliación de alcance del usuario, mismo ciclo): «Excelente. Mientras corre el carril, agrega
estas 3 correcciones criticas de paridad y UX al plan para resolverlas en este mismo ciclo antes de make
ci-android y el commit a main: 1) En ReportsRepository.kt y ReportGeneratorViewModel.kt: cuando el backend
responde 400 (ej. reporte duplicado para el turno), parsear el cuerpo del error ('error'/'message') y mostrar
el mensaje real del servidor ('Ja existe um relatorio para este turno hoje.'). No asumir que todo 400 es por
temperaturas (la validacion de temperaturas ya la hace el cliente). 2) En ReportForm.kt: permitir valores
negativos (ej. -2,4 para camaras frias). KeyboardType.Decimal oculta el '-' en Android; permitir el signo
menos y decimales con coma/punto. 3) En LoadingScreen.kt: los botones de envio directo a fleteros
(state.fleterosWithPhone()) se superponen sin espacio. Envolverlos en FlowRow con Arrangement.spacedBy(8.dp)
horizontal y vertical para que no se pisen. Procede incorporandolas al TODO.»

L9 (relevamiento de la ampliación):
- S6: el backend **ya traduce** el código a la frase — `reports.routes.ts:103-110` convierte
  `reportAlreadyExists` en «Já existe um relatório para este turno hoje.» (o «Ya existe un reporte para este
  turno hoy.» si el `Accept-Language` empieza con `es`) y la manda en `error` **y** en `message` del sobre
  generado `ErrorEnvelope.kt` (`{ error, details?, message? }`). El cliente no tiene que mapear códigos: lee
  el sobre. Lo que hoy hace mal el generador es doble: adivina «Relatório incompleto. Preencha todas as
  temperaturas» para **cualquier** 400, y la frase del duplicado dice «nesta data» donde la del servidor (y
  la de la web) dice «hoje». Queda decidido que la frase del 403 (ventana de edición) sigue siendo la de la
  app, porque ahí el mensaje del servidor es un código.
- S7: `KeyboardType.Decimal` mapea a `TYPE_CLASS_NUMBER|TYPE_NUMBER_FLAG_DECIMAL`, que no incluye
  `TYPE_NUMBER_FLAG_SIGNED`, y Compose no expone un tipo con signo. Decisión: se mantiene el teclado decimal y
  el signo se hace alcanzable con un botón `±` en el propio campo (prefija o quita el `-` del texto actual),
  que funciona con cualquier IME y no degrada el caso positivo. `toReading` ya acepta `-2,4`, `-2.4` y `-2`.
- S8: `FlowRow` es estable en el BOM 2026.09 y ya se usa en `LoadingHistoryScreen.kt`, con
  `horizontalArrangement`/`verticalArrangement` en `spacedBy(8.dp)`: es el mismo patrón que pide la directiva.

L10 (decisión, S6): la frase del servidor se muestra **tal como llegó**, sin traducir ni filtrar, incluido el
`Invalid input` del validador de esquema. Es lo que pide la directiva y lo que hace la web (muestra
`err.message`), y cualquier filtro sería una segunda lista de casos que mantener. Queda registrado que
`AuthRepository.errorMessage()` sí tiene una convención distinta —reemplaza ese mismo `Invalid input` por una
frase propia, porque en su pantalla no nombra ningún campo— y que acá se elige no replicarla: en esta ruta el
cuerpo lo arma el cliente desde el contrato, así que ese 400 no es alcanzable por lo que el formulario escribe.

L11 (evidencia, S4): el carril focalizado hizo su trabajo y encontró dos errores de compilación propios antes de
llegar a los tests: (a) `TemperatureSlot` era `internal` y viajaba en un `UiState` público
(`EXPOSED_PROPERTY_TYPE`), así que pasó a `data class` público, con la razón escrita al lado; (b) el `when {}`
sin sujeto de los dos `refusalMessage` tenía `409 ->` y `400 ->` como condiciones en vez de
`statusCode == 409`/`statusCode == 400` (`CONDITION_TYPE_MISMATCH`). Ambos corregidos y el carril relanzado.

L12 (evidencia, S3/S4): el carril relanzado compiló todo y corrió **232 tests con una sola falla**: la prueba
de scroll que escribí para S3. La causa fue de la prueba y no del código: `displayed("Tarefa 14")` usaba
`performScrollTo`, que sólo alcanza un nodo **ya compuesto**, y en una `LazyColumn` el item lejano no existe
hasta que la lista lo dibuja — el mismo límite que la cabecera de ese archivo ya documentaba («a taller
fixture would need a different selection strategy»). Reescrita para mover la lista por índice
(`hasScrollAction()` sobre el nodo de la lista + `performScrollToIndex(tall.size)` y vuelta a `1`), que sí
alcanza items no compuestos y además es lo que hace real la afirmación: al llegar al final, la categoría de
temperaturas quedó desechada. Los tests nuevos de S6, S7 y S8 pasaron en esa misma corrida.

L13 (evidencia, S3): la prueba de scroll necesitó tres iteraciones más y las tres enseñaron algo exacto del
banco de pruebas: (a) el fixture alto se construía y **no se le pasaba al estado**, así que el listado tenía
cinco items y `performScrollToIndex(21)` salía en `out of bounds [0, 5)`; (b) con categorías de una fila el
contenido apenas supera la ventana y el campo quedaba 190px arriba, **composed**; (c) aun con el contenido
varias ventanas más largo, el nodo del campo sigue existiendo fuera de la ventana (`t=-280px`) porque la
semántica de Robolectric lo retiene. Conclusión: la premisa «el item se desecha» no es del requisito sino de
la implementación de la lista, así que la prueba **no** la afirma. Lo que afirma son los tres hechos que el
requisito pide: la lectura se dibuja, el listado se movió (el último ítem está en pantalla, varias ventanas
más abajo) y la lectura vuelve al volver — y la razón por la que eso prueba S3 es que `TemperatureBlock` no
guarda copia del texto: lee `readings` del formulario y escribe por `onChange`.

L14 (cierre, evidencia): el carril público completo corrió sobre el árbol congelado y pasó —`make ci-android`
→ «Android lane passed.»— con **90 clases y 867 tests unitarios en 0 fallas, 0 errores y 0 omitidos**, más el
APK debug, el APK release, las guardas de manifiesto y de URL base, y la comprobación de que los tipos del
contrato están al día. Los commits en `main`, trunk-based y sin ramas: `8507fe3` (las tres correcciones del
formulario: guard, frases y signo), `8a2cc35` (los fleteros), `778527b` (tests de estado y payload),
`495173e` (tests de pantallas), `62ae325` (test de los fleteros) y el commit de este documento, todos sin
autoría de IA ni trailers.
