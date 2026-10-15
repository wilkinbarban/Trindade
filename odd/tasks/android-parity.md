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
