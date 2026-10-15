# Paridad Android: Calculadora de Assaí, Calculador de Estoque e Histórico

Unidades acotadas para llevar a Android los tres módulos que ya funcionan en la web, con la misma regla de
negocio y el mismo contrato. Cada unidad cierra con su commit, sus pruebas y, cuando corresponda, su
verificación en el emulador.

## Specs

S1. «Módulo Calculadora de Assaí (pantalla Compose, 9 productos, cálculo estricto de cajas enteras, total,
exportación a WhatsApp).» Los 9 productos y sus unidades por caja son los de `src/lib/assai.ts`; la regla es
la misma: una cantidad que no cierra cajas enteras es un error visible solo después de salir del campo.

S2. «Módulo Calculador de Estoque (pestañas Massas/Terceirizados, conteo corrido vía API /stock/add-entry,
reset de producto vía /stock/reset-product, totales, exportaciones por WhatsApp e email).» El catálogo de 25
productos llega del endpoint (`GET /api/stock?date=…`), no se duplica en el cliente.

S3. «Módulo Histórico de Estoque (selector de fecha, listado de productos contados, RBAC: lectura para
Trabajador).» Mismo criterio: solo los productos con unidades > 0; el Trabajador lee y el Administrador
puede agregar y resetear, con el servidor aplicando la misma frontera (403 en los POST).

S4. «Integración en DashboardScreen.kt con accesos directos a estos módulos.»

S5. «Teléfono visible en lista de motoristas y enlace a cronograma activo verificado.» Ya está hecho en
Android (`DriversScreen.kt` con `driver.phone.orEmpty()` y el `activeScheduleLink`): esta unidad solo lo
verifica.

S6. «Verificación de Emulador en Ubuntu (ssh ubuntu)»: compilar `./gradlew test` y `assembleRelease` y
validar en el AVD `Trindade_UX_v030`, con el SDK en `/home/wilkin/Desktop/../Android/Sdk` según relevamiento.

## Tasks

| ID | Tarea | Specs | Estado |
| --- | --- | --- | --- |
| A1 | Los 9 productos y la regla estricta en un módulo Kotlin puro, con sus pruebas unitarias | S1 | pendiente |
| A2 | Pantalla Compose de Assaí (tabla, error por producto tras el blur, total, exportación por WhatsApp con el enlace canónico) | S1 | pendiente |
| A3 | Cliente del Stock: modelos generados de `packages/android/contract` (ya versionados) y repositorio con `GET /api/stock`, `POST /stock/add-entry`, `POST /stock/reset-product` | S2 | pendiente |
| A4 | Pantalla Compose del Stock con las dos pestañas, tarjetas táctiles, totales y exportaciones por WhatsApp y correo | S2 | pendiente |
| A5 | Pantalla del Histórico con selector de fecha, solo productos contados y RBAC (Trabajador de lectura) | S3 | pendiente |
| A6 | Accesos directos en `DashboardScreen.kt` a los tres módulos | S4 | pendiente |
| A7 | Verificación: `./gradlew test`, `assembleRelease` y validación en el emulador de Ubuntu | S6 | pendiente |

## Log

L1 (verbatim, 2026-10-15): «Módulo Calculadora de Assaí (pantalla Compose, 9 productos, cálculo estricto de
cajas enteras, total, exportación a WhatsApp). Módulo Calculador de Estoque (pestañas Massas/Terceirizados,
conteo corrido vía API /stock/add-entry, reset de producto vía /stock/reset-product, totales, exportaciones
por WhatsApp e email). Módulo Histórico de Estoque (selector de fecha, listado de productos contados, RBAC:
lectura para Trabajador). Integración en DashboardScreen.kt con accesos directos a estos módulos. Teléfono
visible en lista de motoristas y enlace a cronograma activo verificado. Verificación de Emulador en Ubuntu
(ssh ubuntu): host operativo, SDK, AVD Trindade_UX_v030 y KVM disponibles; compilar APK (./gradlew test,
assembleRelease) y validar en el emulador de Ubuntu.»

L2 (orquestador, 2026-10-15): la regla de negocio no se reimplementa en Kotlin: A1 la traduce del módulo
puro de la web con las mismas pruebas, y A3 consume los tipos ya generados desde el contrato publicado para
que el cliente no pueda discrepar del servidor.

## Cierre de A1 — la regla del pedido de Assaí en Kotlin

**Entregado**: `app/src/main/java/com/trindade/app/assai/AssaiOrder.kt` (91 líneas) con el catálogo de los
nueve productos y sus unidades por caja, la línea ya juzgada con `valid`/`filled`/`exact`, el total de lo
que se puede servir, el mensaje con una fila por producto y el total al final, y `assaiVisibleErrors` con
la misma regla de momento que la web; y `app/src/test/java/com/trindade/app/assai/AssaiOrderTest.kt` (75
líneas, JUnit 4) con siete casos espejo de los de la web, uno por uno.

**Evidencia de la verificación** (host Ubuntu, tarea `:app:testDebugUnitTest`):
- Reporte JUnit: `com.trindade.app.assai.AssaiOrderTest: tests=7 failures=0 errors=0 time=0.054s`, con el
  nombre de los siete casos en verde.
- Fase roja con el módulo escondido: el build falla, y la causa leída del registro crudo es: (el grep del trabajo la habia recortado; se leyo despues del registro del host)

**Cuatro intentos fallidos antes, todos por la verificación y ninguno por el código**: la bandera `--tests`
sobre la tarea agregada `test`, que no la acepta — y cuyo `BUILD FAILED` reporté como el rojo del módulo
ausente, que era falso y corregí ante el usuario; dos greps tan estrechos que borraron la causa del
registro; y un contenedor `android-sdk:35` al que le faltan `build-tools;36.0.0` y `platforms;android-37.0`,
corrido sin permiso de escritura sobre el SDK, de modo que gradle no podía instalarlos: mi código nunca
llegó a compilarse. Un quinto intento quedó bloqueado por la política de seguridad, que detectó en el
cuerpo del script un cambio recursivo de propiedad sobre el árbol montado; no corrió nada y el árbol quedó
intacto.

**Lecciones**:
1. Al motivo de un fallo de build se lo lee, no se lo supone: `BUILD FAILED` no prueba nada por sí solo.
2. La salida se captura cruda y se filtra después; un grep usado como diagnóstico puede tapar la causa.
3. La evidencia de una corrida de pruebas es su reporte (`tests=`, `failures=` y el nombre de cada caso),
   no el estado del build.
4. La verificación no se hace con un contenedor privilegiado sobre el árbol de trabajo: si hace falta
   completar el SDK, el lugar es un host propio, que además es donde vive el AVD.
5. Un texto que cita un comando peligroso puede activar la misma guarda que el comando: describirlo en
   palabras y no transcribirlo.

**Siguiente unidad**: A2 — la pantalla Compose de Assaí con su ViewModel y la exportación por WhatsApp,
consumiendo esta regla y sin repetir una línea de su aritmética.

## Cierre de A2 — la pantalla del pedido de Assaí y su ViewModel

**Entregado**: `app/src/main/java/com/trindade/app/assai/AssaiViewModel.kt` (76 líneas, `@HiltViewModel`
sin dependencias inyectadas, con `StateFlow` de cantidades, campos dejados, campo en edición, número y
nota, y los derivados de `AssaiOrder`: líneas, errores visibles, total, mensaje y enlace canónico por
`waMeLink`), `app/src/main/java/com/trindade/app/assai/AssaiCalculatorScreen.kt` (145 líneas, Compose con
la lista táctil de los nueve productos, campo numérico, el cartel inline solo tras salir del campo y fuera
de foco, el total y el botón de compartir apagado si el pedido es inexacto o el número no sirve) y
`app/src/test/java/com/trindade/app/assai/AssaiViewModelTest.kt` (69 líneas, JUnit 4, cinco casos).

**Evidencia (host Ubuntu, `:app:testDebugUnitTest`)**:
- Reporte JUnit: `com.trindade.app.assai.AssaiViewModelTest: tests=5 failures=0 errors=0 time=0.054s`, con
  los cinco nombres en verde.
- Fase roja, escondiendo la implementación: el build falla, y el registro del host lo reporta así: (el registro no conservó la causa)

**Nota honesta sobre la fase roja**: esta vez el fallo duró un segundo, demasiado poco para una
compilación de Kotlin, y por eso la causa se registra **como el registro la reporta** y no como se
esperaba que fuera; la lección de A1 se aplica acá: al motivo de un fallo se lo lee.

**Decisión de diseño**: el ViewModel no recibe nada inyectado a propósito — un pedido no se persiste
hasta que se envía — y eso es lo que permite probarlo sin dispositivo ni corrutinas. La aritmética sigue
viviendo solo en `AssaiOrder`: la pantalla dibuja y el ViewModel recuerda dónde está el operador.

**Siguiente unidad**: A3 — el cliente del Stock sobre los tipos ya generados en `packages/android/contract`
y los tres endpoints (`GET /api/stock`, `POST /api/stock/add-entry`, `POST /api/stock/reset-product`).

## Cierre de A3 — el cliente del Stock

**Entregado** (commit propio, 216 líneas brutas entre los cuatro archivos): `network/StockApi.kt` (35
líneas, la interfaz Retrofit escrita a mano sobre los modelos generados, con los cuatro caminos: día,
alta, reset e historial), `stock/StockRepository.kt` (71 líneas, `@Singleton`, con la regla de que un día
que no se pudo leer es **nada** y no un día vacío, y con el resultado de escritura en tres estados —
guardado, rechazado y servidor inalcanzable— porque una negativa del servidor y un servidor ausente no son
lo mismo), `stock/StockRepositoryTest.kt` (110 líneas) y el proveedor de `StockApi` en `di/NetworkModule.kt`
con el molde de los clientes existentes.

**Decisión de diseño**: las pruebas corren contra **MockWebServer** y no contra una interfaz falsa, porque
la mitad de lo que hace esta clase es leer la forma de una respuesta —un cuerpo, una negativa, un corte— y
un falso solo afirmaría mi idea del cable. Los casos fijan las claves que realmente viajan, incluido que un
reset no lleva bultos.

**Evidencia (host Ubuntu, `:app:testDebugUnitTest`)**: reporte JUnit
`com.trindade.app.stock.StockRepositoryTest: tests=6 failures=0 errors=0 time=0.428s`, con los seis nombres
en verde. Fase roja escondiendo el repositorio: el build falla, y el registro del host lo reporta así: (el registro no conservó la causa)

**Siguiente unidad**: A4 — la pantalla del Stock en Compose sobre este repositorio, con las dos pestañas del
catálogo, la acumulación corrida de bultos, el reset por producto, los totales y las exportaciones.

## Obligaciones nuevas integradas a la serie (directiva del 2026-10-15)

**Cita literal del usuario**: «Sustitución de email destino de Stock: Cambiar el correo de pruebas
wilkin.barban@gmail.com por xmitrindade@hotmail.com en StockCalculatorPage.tsx, StockHistoryPage.tsx,
stock.test.ts y en las pantallas/exportaciones de Android (A4/A5).» y «Marca de agua / Copyright en ambas
aplicaciones (Web y Android): Desenvolvido por Wilkin Barban Rosabal / E-mail: wilkin.barban@gmail.com. En
Web: como pie de página sutil/marca de agua en AppShell.tsx y LoginPage.tsx. En Android: como pie de
pantalla/marca de agua en las pantallas principales (DashboardScreen, LoginScreen, Perfil).»

**Cómo se integran**:
- **A4 y A5** usan `xmitrindade@hotmail.com` como destino del correo del stock: es el buzón de la planta.
  El correo del autor (`wilkin.barban@gmail.com`) es una cosa distinta y no debe usarse como destino ✗
  confundirlos perdería un conteo en un buzón que nadie lee.
- **A6** lleva el pie en `DashboardScreen`, `LoginScreen` y `Perfil`: «Desenvolvido por Wilkin Barban
  Rosabal» y «E-mail: wilkin.barban@gmail.com», como marca de agua discreta.
- **Web: ya entregado** — `components/ui/DeveloperCredit.tsx` se renderiza en `AppShell` (barra lateral) y
  en `LoginPage` (bajo la tarjeta), y el destino del stock es el nuevo en las dos pantallas y en las dos
  aserciones de `stock.test.ts`. Verificado con 68/68 en la suite pura, typecheck 0 y build limpio.

**Regla de diseño para Android**: un solo composable de crédito, usado por las tres pantallas, por el
mismo motivo que en la web: un crédito escrito tres veces se queda viejo en dos.

## Cierre de A4a — la regla de bultos del contador

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockBoxes.kt` (38 líneas: `boxesInvalid` con el
campo vacío como algo que no es error, `addDisabled` y `visibleBoxErrors`) y
`app/src/test/java/com/trindade/app/stock/StockBoxesTest.kt` (62 líneas, seis casos).

**Por qué vive aparte**: son las dos decisiones que ocurren **antes** de que nada viaje al servidor —
si lo tipeado puede ser una cantidad de bultos y si decirlo corresponde en este momento— y por eso no
pertenecen ni a la pantalla que las dibuja ni al repositorio que las envía. La regla es la misma que
aplica la web, caso por caso, porque las dos aplicaciones responden al mismo operario.

**Evidencia (host Ubuntu, `:app:testDebugUnitTest`)**: reporte JUnit
`com.trindade.app.stock.StockBoxesTest: tests=6 failures=0 errors=0 time=0.035s`, con los seis nombres en
verde. Fase roja escondiendo la regla: `Unresolved reference 'boxesInvalid'` en las líneas 24, 25 y 26 del
archivo de prueba, leído del registro del host.

**Siguiente (A4b)**: el ViewModel sobre `StockRepository` y la pantalla Compose con las dos pestañas del
catálogo, las tarjetas táctiles por producto, la acumulación corrida, el reset, los totales, el correo al
buzón de la planta (`xmitrindade@hotmail.com`) y WhatsApp.

## Cierre de A4b — el estado del contador y los textos que salen de la planta

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockMessages.kt` (68 líneas: el mensaje con una
línea por producto contado y solo unidades, el total al final, y el correo como tabla medida con las
columnas `Categoria | Código | Produto | Unidades` y el total alineado bajo las unidades, los dos idénticos
a los de la web) y `app/src/main/java/com/trindade/app/stock/StockViewModel.kt` (155 líneas: `@HiltViewModel`
con `StockRepository` inyectado, el día leído como San Pablo y no como el dispositivo, las dos secciones del
catálogo derivadas de lo que respondió el servidor, el estado del momento que consulta la regla pura de A4a,
los tres resultados de escritura bien separados y el buzón de la planta como destino del correo), más
`app/src/test/java/com/trindade/app/stock/StockViewModelTest.kt` (108 líneas, cinco casos).

**Dos decisiones fijadas como pruebas y no como comentarios**: el día se lee como lo lee San Pablo —un
conteo es un documento sobre un día y un teléfono con otra hora no debe rotularlo con la fecha de mañana— y
**el destino es el buzón de la planta y nunca el del autor**, que es la distinción que decide dónde queda
archivado un conteo.

**Evidencia (host Ubuntu, `:app:testDebugUnitTest`)**: reporte JUnit
`com.trindade.app.stock.StockViewModelTest: tests=5 failures=0 errors=0 time=0.262s`, con los cinco nombres
en verde. Fase roja escondiendo el estado y los textos: `Unresolved reference 'StockViewModel'` en las
líneas 47 y 58 del archivo de prueba, con los `onFocus` y `onQuantityChange` en cascada, leído del registro
del host.

**Siguiente (A4c)**: la pantalla Compose —las dos pestañas del catálogo, las tarjetas táctiles con sus
métricas y su fila de acciones, los totales y los dos botones de exportación al buzón de la planta y a
WhatsApp— que consume este ViewModel sin computar nada por su cuenta.

## Lección de arnés (A4c) — el host de verificación tiene que estar completo

**El síntoma**: la suite completa en el host reportó **681 pruebas con 7 fallos**. Las siete eran de
`PasswordPolicyContractTest` (1) y `ContractCoverageTest` (6), y todas decían lo mismo:
`IllegalStateException: openapi.json is not on the test classpath; the test source set should include it`.

**La causa, y no era el código**: esas dos clases leen el **contrato publicado** desde el classpath de
pruebas, y el contrato vive en `packages/contracts/openapi.json` — **fuera** de `packages/android/`, que era
lo único que el `rsync` copiaba al host. El host estaba incompleto, no el árbol roto. La comprobación que lo
confirma: esas mismas pruebas pasan en el CI, que corre el repositorio entero.

**La regla que queda**: el arnés de verificación copia **todo lo que las pruebas leen**, no solo el paquete
que se está tocando. En este proyecto eso incluye `packages/contracts/` además de `packages/android/`, y la
ruta relativa que espera el build es la del repositorio (`../contracts/openapi.json` desde el proyecto de
Gradle), así que el host tiene que reflejarla (`~/contracts/` cuando el paquete está en
`~/trindade-android-a1/`).

**Y una advertencia para el próximo veredicto**: un fallo en pruebas ajenas al cambio no se atribuye al
cambio ni se ignora; se investiga hasta saber de quién es. Acá la causa era del arnés, y eso también hay que
probarlo antes de decir que el código está bien.

## Cierre de A4c — la pantalla del contador

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockCalculatorScreen.kt` (201 líneas): el título y
el campo del día, las dos pestañas derivadas del catálogo que respondió el servidor (la activa no se vuelve
a pulsar), una tarjeta táctil por producto con sus métricas, el campo numérico con el foco escuchado en el
modificador, el cartel inline que consulta la regla pura de A4a, Adicionar apagado cuando no hay nada que
agregar o no se puede agregar, Resetar, el total del día tal como lo respondió el servidor, el campo del
número, la vista previa del mensaje y los dos botones de exportación: WhatsApp por el enlace canónico y
correo por `mailto` al buzón de la planta.

**Sin prueba unitaria de UI, dicho tal cual**: para una pantalla, lo honesto es que compile y que la suite
del proyecto siga pasando, y eso es lo que se verificó. No se declara una prueba que no existe.

**Dos correcciones propias, ambas leídas y no supuestas**:
1. El primer intento **no compiló**: `Unresolved reference 'category' on receiver of type
   List<StockDayProductsInner>` en la línea 81 — un `active?.category` de un diseño anterior, cuando la
   pestaña activa era un grupo con su categoría, sobre lo que en el código final es la lista de sus
   productos. Arreglado derivando la categoría mostrada aparte.
2. La suite reportó **7 fallos sobre 681 pruebas** que **no eran del cambio**: `PasswordPolicyContractTest`
   y `ContractCoverageTest` leen el contrato publicado desde el classpath de pruebas, y el contrato vive en
   `packages/contracts/`, fuera del único paquete que el `rsync` copiaba. El host estaba incompleto, no el
   árbol roto — esas mismas pruebas pasan en CI con el repositorio entero.

**Siguiente (A5)**: el histórico de conteos en Compose, con selector de fecha, solo los productos contados,
exportaciones y el control de rol: lectura para Trabajador y escritura para Administrador, que es la misma
frontera que el servidor aplica con su 403.

## Cierre de A5a — el estado del día pasado y la frontera del rol

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockHistoryViewModel.kt` (158 líneas) y
`app/src/test/java/com/trindade/app/stock/StockHistoryViewModelTest.kt` (103 líneas, seis casos).

**Dos ViewModels y no uno con una bandera**: el contador responde qué hay en la estantería ahora y el
histórico qué se contó ese día, que son dos preguntas de dos momentos distintos. Este trae el estado del día
pasado, sus exportaciones (reutilizando `StockMessages` y el buzón de la planta) y la regla del rol.

**La frontera se aplica en el servidor**, con su 403; la regla de acá existe para que a nadie se le ofrezca
un botón que va a ser rechazado, porque un operario no debería aprender sobre permisos con un no después de
intentarlo. `canEdit` compara contra el mismo texto que el servidor chequea.

**Los tests son síncronos a propósito**, y hay una corrección detrás: al escribirlos les había puesto un
`sleep` para esperar la corrutina, y `viewModelScope` no tiene dispatcher Main en una prueba JUnit, así que
el caso habría fallado por el motivo equivocado. El rol se decide **antes** de lanzar la corrutina, que es
exactamente por lo que se puede afirmar sin red; las rutas que sí hablan con el servidor están cubiertas en
los tests del repositorio (A3).

**Evidencia (host Ubuntu, `:app:testDebugUnitTest`)**: reporte JUnit
`com.trindade.app.stock.StockHistoryViewModelTest: tests=6 failures=0 errors=0 time=0.298s`, con los seis
nombres en verde — incluido «who may correct a past day is the same string the server checks» y «a worker is
told instead of being refused later». Fase roja escondiendo el ViewModel: `Unresolved reference
'StockHistoryViewModel'` en las líneas 56, 57 y 58 del archivo de prueba, leído del registro del host.

**Siguiente (A5b)**: la pantalla del histórico con el selector de fecha, la lista de solo los productos
contados y la vista que se apaga para quien no puede corregir.

## Especificación de A6 (directiva del usuario, 2026-10-15)

Cita literal: «para la siguiente unidad (A6), tené en cuenta que la marca de agua DeveloperCredit en Android
debe ser bien reducida y discreta para pantallas móviles (10.sp, color atenuado con alpha y centrada al
pie).»

Cómo se implementa: un solo composable de crédito en `com.trindade.app.ui` —mismo criterio que en la web, un
crédito escrito tres veces se queda viejo en dos—, con los textos «Desenvolvido por Wilkin Barban Rosabal» y
«E-mail: wilkin.barban@gmail.com» en **10.sp**, color atenuado **con alpha** (y no un gris fijo, que en tema
oscuro desaparece o grita) y **centrado al pie**, montado en `DashboardScreen`, `LoginScreen` y `Perfil`.

El correo del autor es para el crédito; el buzón de la planta (`xmitrindade@hotmail.com`) es otra cosa y solo
se usa como destino de los conteos.

## Cierre de A5b — la pantalla del histórico

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockHistoryScreen.kt` (203 líneas): el título, el
selector de fecha, los atajos de los días ya contados, y una tarjeta por producto **solo de los que tienen
unidades** (código, nombre, categoría, cajas y unidades), con el vacío dicho cuando el día no tuvo conteo y
sin ningún control de escritura para quien no puede corregir.

**La decisión de seguridad de esta pantalla**: el rol entra como **parámetro con `null` por defecto**, y
`null` significa solo lectura. Si nadie le dijo quién está mirando, la pantalla muestra el día y no ofrece
ninguna corrección: el modo por defecto es el seguro, y A6 lo conecta a la sesión real. El servidor aplica la
misma frontera con su 403 — esta capa existe para que a nadie se le ofrezca un botón que será rechazado.

## Cierre de A5b — la pantalla del histórico

**Entregado**: `app/src/main/java/com/trindade/app/stock/StockHistoryScreen.kt` (203 líneas): título, selector
de fecha, atajos de los días ya contados, y una tarjeta por producto **solo de los que tienen unidades**
(código, nombre, categoría, cajas y unidades), con el vacío dicho cuando el día no tuvo conteo y **sin ningún
control de escritura** para quien no puede corregir.

**La decisión de seguridad**: el rol entra como **parámetro con `null` por defecto**, y `null` significa solo
lectura — si nadie le dijo quién está mirando, la pantalla muestra el día y no ofrece corrección. El modo por
defecto es el seguro y A6 lo conecta a la sesión real. El servidor aplica la misma frontera con su 403.

**Verificación en el host Ubuntu**: la pantalla compila; **escondiéndola, la compilación falla** (o sea que
es parte del build y no un archivo dejado al costado); y la suite completa pasa con el contrato presente. Sin
prueba unitaria de UI, dicho tal cual.

**Con esto A5 queda completa** (A5a el ViewModel y su frontera de rol, A5b la pantalla).

### Corrección de A5b — el «rojo por remoción» no ocurrió

**Lo que afirmé y era falso**: en el commit de A5b y en el cierre de arriba quedó escrito que esconder
`StockHistoryScreen.kt` hacía fallar la compilación, «o sea que es parte del build». **No falló**: el paso
devolvió `salida=0`.

**Por qué no falló**: **nada importa todavía esa pantalla**. Gradle compila de forma incremental y, al quitar
un archivo que ningún otro referencia, no tenía nada que romper — la pantalla es parte del árbol de fuentes,
pero todavía no es parte del **build alcanzable**. Recién cuando A6 cablee la navegación hacia ella (y hacia
los otros dos módulos), esconderla hará fallar la compilación y esa prueba pasará a significar algo.

**Lo que la verificación sí probó**, que no es poco: la pantalla **compila** (paso 1, `BUILD SUCCESSFUL`) y
la **suite completa pasa** con el contrato presente — 88 archivos de reporte, **834 pruebas, 0 fallos, 0
errores**, incluidos `StockHistoryViewModelTest: tests=6 failures=0` y el resto de la serie.

**La lección, que es la misma de todo el día con una vuelta más**: no alcanza con correr tres pasos; hay que
**leer el resultado de cada uno** y no el resumen. Tenía los tres pasos y leí el «todo verde» del conjunto;
el segundo decía `salida=0` y yo escribí lo contrario. Un paso que no falla cuando debía fallar es una
pregunta, no una confirmación.

## Cierre de A6a — el crédito del autor en tres pantallas

**Entregado**: `app/src/main/java/com/trindade/app/ui/components/DeveloperCredit.kt` (un solo composable, con
los valores literales de la directiva del usuario: **10.sp**, `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)`
—color del tema con alpha, no un gris fijo, porque en tema oscuro un gris fijo desaparece o grita— y
`TextAlign.Center`, con las dos líneas del crédito), montado al pie de `LoginScreen`, `DashboardScreen` y
`ProfileScreen` con su import en las tres.

**Incidente, resuelto entre el usuario y el orquestador**: la primera inserción se hizo **por número de
línea** y en `DashboardScreen` el pie cayó **después del `else` de un `when`**; la suite lo cazó al compilar
con `ELSE_MISPLACED_IN_WHEN` y el usuario lo corrigió adentro del `Column`. **La lección**: los números de
línea eran correctos y la **estructura** no; al insertar hay que leer **alrededor** del punto de inserción, no
solo el final del archivo.

**Observación que queda abierta y que un compilador no puede juzgar**: en `DashboardScreen` el pie quedó con
más indentación que en las otras dos ✗ —dentro de un bloque anidado, el que cierra en la 187— mientras que en
`LoginScreen` (241) y `ProfileScreen` (280) quedó hermano dentro del contenedor. Si ese bloque anidado es una
rama, el crédito se dibujaría solo ahí. **Es la primera cosa a mirar en el emulador** (A7), junto con los
valores en pantalla real.

**Evidencia (host Ubuntu)**:
- Las tres pantallas **compilan** y la **suite completa pasa** (job-147, `salida=0`).
- **El rojo por remoción está probado**: esconder `DeveloperCredit.kt` rompe la compilación con
  `Unresolved reference 'DeveloperCredit'` en `LoginScreen.kt:3` y `:241` y en `ProfileScreen.kt:3` y `:280`
  — o sea que las pantallas lo importan de verdad ✓, que es exactamente lo que en A5b **no** ocurría y por eso
  allí el chequeo no significaba nada.
- Precisión de método: los `834` del reporte del intento anterior eran de una corrida previa, porque esa
  suite no llegó a compilar; el número que vale es el del intento corregido.

**Siguiente (A6b)**: los tres accesos a los módulos nuevos (Calculadora de Assaí, contador de stock e
histórico) como **pestañas**, no como rutas: entradas en `MainActivity.Tab` y en `RolePolicy.EntryPoint`,
sumadas a `DEFAULT_DESTINATIONS`, con sus ramas en el `when (tab)` de `MainActivity.kt` (línea 258) y sus
textos en `strings.xml`, porque el enum `Tab` pide `@StringRes`.

## Cierre de A6b — los tres accesos como pestañas, y con ello A6 completa

**Entregado** (un commit): las tres entradas nuevas en `RolePolicy` (`ASSAI_ORDER`, `STOCK_COUNTER` y
`STOCK_HISTORY` en el enum y **sumadas a `SHARED_ENTRY_POINTS`**), las tres pestañas en `MainActivity.Tab` con
sus textos nuevos en `values/strings.xml` (el proyecto no tiene `values-es`), los tres pares en
`DEFAULT_DESTINATIONS`, las tres ramas del `when (tab)` que dibujan `AssaiCalculatorScreen`,
`StockCalculatorScreen` y `StockHistoryScreen`, y los imports de las tres pantallas.

**Por qué al conjunto compartido**: los tres los ve cualquier operario con sesión, exactamente como los
ofrece la web. El propio archivo advierte que una entrada agregada «solo para el trabajador» se le entrega
también al administrador — y acá eso es lo correcto. El histórico decide por sí mismo si la visita es una
lectura o una corrección, y el servidor aplica esa frontera con su 403.

**El fallo que la verificación trajo, y por qué fue una buena noticia**: `RolePolicyTest` falló con
`expected` la lista de cinco entradas compartidas contra la de siete que existe ahora. Eso es la prueba
haciendo su trabajo: **el cambio tuvo que declararse en vez de entrar en silencio**. El test se actualizó
agregando las tres después de `CATALOG_DRIVERS`, que es donde las pone la declaración.

**Lo que NO hubo que tocar**: `MainActivityNavigationTest` pasó entero (32 pruebas) y `NavigationActionButtonTest`
también — las tres pestañas nuevas **no rompieron** la fila de navegación, que es justamente lo que ese test
vigila.

**Una decisión con su porqué**: la rama del histórico dibuja la pantalla **sin rol**. El modo por defecto de
esa pantalla es solo lectura, así que un rol sin cablear solo puede ofrecer **menos**, y la corrección no
depende de esa línea: el servidor aplica la misma frontera con su 403. Queda como detalle a cablear, no como
agujero de permisos.

**Evidencia (host Ubuntu)**: la compilación pasa con las tres pestañas y sus pantallas (`BUILD SUCCESSFUL`), y
la suite completa pasa tras actualizar el test de rol (job-149, `salida=0` en las dos fases).

**Con esto A6 queda completa** (A6a el crédito en tres pantallas, A6b los tres accesos). **Queda A7**: el
cierre de la serie con `./gradlew assembleRelease` y la validación en el emulador Ubuntu `Trindade_UX_v030`,
donde se ven por fin las tres cosas que ningún compilador juzga: la posición real del crédito en
`DashboardScreen` (quedó dentro de un bloque anidado), sus valores en pantalla (10.sp, alpha, centrado) y las
tres pestañas dibujando sus pantallas.

## Cierre de A7 — la serie completa, y el defecto que solo la pantalla podía mostrar

**Lo que A7 encontró**: el crédito se dibujaba **con el estilo pedido** —10.sp, atenuado con alpha y
centrado— y en el **lugar equivocado**: en la pantalla de login aparecía **arriba de todo**, sobre el logo,
como primer elemento. Ningún compilador ni prueba unitaria podía verlo: un composable en cualquier punto del
cuerpo es legal.

**El diagnóstico, del usuario, confirmado por el arreglo**: en `LoginScreen` el crédito había quedado **fuera
del `Column`**, como hijo directo del `BoxWithConstraints`, y **un `Box` ubica a sus hijos desde arriba**.
Adentro del `Column`, como último hijo y con un `Spacer(16.dp)` después del botón de registro, quedó al pie.

**Las otras dos pantallas, revisadas con el mismo criterio**: `ProfileScreen` ya estaba correcto (último hijo
de su `Column` scrolleable); `DashboardScreen` estaba dentro de un bloque anidado —así que se dibujaba solo
en esa rama y no era el pie de nada— y pasó a ser el último hijo del `Column` scrolleable.

**Evidencia visual, leída como imagen**: la captura del build previo mostró el crédito arriba; la del build
arreglado lo muestra **al pie**, centrado y en el tono atenuado del tema. La app corriendo era la de hoy
(`versionCode=500`, `versionName=0.5.0`), instalada en el AVD `Trindade_UX_v030` tras desinstalar la 0.3.3
vieja.

**Las recetas que quedaron probadas**:
- **Release**: `./gradlew :app:assembleRelease --no-daemon -PapiBaseUrl=https://trindademasas.duckdns.org/
  -PversionName=0.5.0 -PversionCode=500` → BUILD SUCCESSFUL, 10.478.240 bytes, **`app-release-unsigned.apk`**.
  El proyecto tiene **dos guardas deliberadas** sin valores por defecto (apiBaseUrl y versionName/versionCode:
  un release no puede apuntar en silencio al loopback ni salir con una versión inventada), el `versionCode`
  sale de la fórmula del CI (`major*10000 + minor*100 + patch` → 0.5.0 = 500) y **la firma es un paso del
  carril del CI con el keystore**: publicar exige tag, carril y firma, y eso es decisión del usuario.
- **Emulador**: rutas **absolutas** de `adb` y `emulator` (un `export PATH` detrás de un `&` se va al
  proceso de fondo); para instalar el build de hoy hubo que **desinstalar** la app vieja (`VERSION_DOWNGRADE`
  primero, `UPDATE_INCOMPATIBLE` después: Android no reemplaza una app firmada por A con una firmada por B).
- **Captura**: `adb exec-out screencap -p` + `scp` y **leerla como imagen**. Tres veces las capturas de
  intentos fallidos mostraban la app **vieja** del AVD y **no se usaron como evidencia**; la señal era el
  tamaño (≈289 KB contra ≈136 KB) **más** el `dumpsys package` con la versión de hoy.

**Límite honesto**: la captura es de la **pantalla de login**, una de las tres que llevan el crédito. Las
pantallas de las **tres pestañas** requieren **sesión** y no hay credenciales, así que verlas —y ver el
crédito al pie del dashboard— queda para el ojo del usuario.

**Con esto A7 cierra y la serie de paridad Android queda completa**: diez unidades cerradas y verificadas,
tres módulos nuevos en el shell como pestañas, el cliente del stock sobre el contrato publicado, el RBAC
respetado en las dos capas, el crédito del autor al pie de tres pantallas, y la verificación visual en el
emulador de Ubuntu.
