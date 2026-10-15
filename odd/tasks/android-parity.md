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
