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
