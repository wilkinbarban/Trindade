# Paridad de tarjetas en el Dashboard de Android y usuario invitado de solo lectura

Dos trabajos que llegaron en la misma directiva y que **no** son del mismo tamaño ni del mismo riesgo: la
paridad de tarjetas es una unidad acotada de interfaz, y el usuario invitado es una unidad de **seguridad** que
toca backend, web y Android. Se ejecutan por separado, y la segunda no se empieza a medias.

## Specs

S1. «En Web (packages/frontend/src/pages/DashboardPage.tsx), el Dashboard cuenta con 3 tarjetas de acceso
directo que actualmente faltan en Android: Calculador de Estoque (/stock-calculator, destino Tab.STOCK_COUNTER);
Histórico de Contagem (/stock-history, destino Tab.STOCK_HISTORY); Calculadora de Assaí (/assai-calculator,
destino Tab.ASSAI).»

S2. «En Android (packages/android/app/src/main/java/com/trindade/app/dashboard/DashboardScreen.kt y
MainActivity.kt): Implementá las 3 tarjetas en DashboardScreen.kt con la misma estética, íconos y textos que las
tarjetas existentes. Cableá los callbacks onOpenStock, onOpenStockHistory, onOpenAssai en DashboardRoute y
MainActivity.kt hacia los tabs ya existentes (Tab.STOCK_COUNTER, Tab.STOCK_HISTORY, Tab.ASSAI).»

S3. «Usuario: "invitado", contraseña: "invitado" (hash bcrypt), activo (is_active = 1). Sembralo en la base de
datos de manera idempotente (en el seeder o inicialización de backend).»

S4. «El usuario invitado debe tener visibilidad idéntica al rol Administrador (puede entrar a todas las
secciones, dashboards, reportes, módulos).»

S5. «Solo Lectura (Mutaciones Bloqueadas): En Backend: crear un guard/middleware que intercepte cualquier
mutación (POST, PUT, PATCH, DELETE, excluyendo endpoints de auth login/logout/refresh) para el usuario
"invitado" y retorne HTTP 403 con mensaje explicativo: "É necessário cadastrar um usuário na página de login
para realizar ações". En Frontend Web y Android: al intentar ejecutar cualquier acción de mutación (o
globalmente al interceptar el 403 / feedback), mostrar un diálogo, modal o toast informativo claro indicando
que se debe cadastrar un usuario en la página de login.»

S6. «Invisibilidad e Inmutabilidad: el usuario "invitado" NO debe aparecer en ninguna lista de usuarios en las
aplicaciones (filtrar en las consultas backend de usuarios WHERE username != 'invitado'). En Backend, proteger
explícitamente al usuario "invitado" para que no pueda ser editado, actualizado, cambiado de contraseña ni
eliminado vía API.»

S7. «Verificá que todos los tests de Backend, Frontend Web y Android pasen en verde. Realizá commits
convencionales claros y prolijos en main sin trailers de IA ni Co-Authored-By.»

## Tasks

| ID | Tarea | Specs | Estado |
| --- | --- | --- | --- |
| T1 | Las tres tarjetas en `DashboardScreen.kt` con la estética de `DashboardCard` y sus callbacks `onOpenStock`, `onOpenStockHistory`, `onOpenAssai` | S1, S2 | pendiente |
| T2 | Cableado en `DashboardRoute` y en el `when (tab)` de `MainActivity.kt` hacia `Tab.STOCK_COUNTER`, `Tab.STOCK_HISTORY` y `Tab.ASSAI` (que **ya existen**), más sus textos si hacen falta | S2 | pendiente |
| T3 | Semilla idempotente del invitado (bcrypt, `is_active = 1`) con el patrón de `provision-admin.ts` | S3 | pendiente |
| T4 | La guarda de mutaciones: **lista de permitidos** (solo auth: login/logout/refresh) y 403 con el mensaje literal para el invitado, como hook **global** —no por ruta— para que un endpoint nuevo no quede sin cubrir | S5 | pendiente |
| T5 | Invisibilidad: filtro `username != 'invitado'` en **todas** las consultas que listan usuarios (`admin.service.ts` líneas 537, 567 y 645; `reports.lifecycle.service.ts:216`; revisar `auth.service.ts:68`) | S6 | pendiente |
| T6 | Inmutabilidad: el invitado no se puede editar, ni cambiar de contraseña, ni borrar por API | S6 | pendiente |
| T7 | Aviso en la web y en Android cuando llega el 403 (o antes de intentar): una superficie clara que diga que hay que registrarse | S5 | pendiente |
| T8 | Visibilidad del invitado idéntica al Administrador: comprobar `RolePolicy` y los menús de la web | S4 | pendiente |
| T9 | Suite completa (backend, frontend y Android) en verde y commits convencionales | S7 | pendiente |

## Log

L1 (verbatim, 2026-10-16): la directiva completa del usuario, transcripta en las Specs S1 a S7.

L2 (orquestador): **observación de seguridad dicha antes de empezar**. La contraseña del invitado está
publicada en la propia directiva, así que **cualquiera que la conozca puede entrar a producción** y —con S4—
**leer todo lo que lee un Administrador**: reportes, cronogramas, panel de administración y sus listados. La
guarda de S5 lo deja **solo lectura**, que es el control que lo contiene; pero la exposición de lectura es una
consecuencia de diseño y queda dicha. De ahí dos decisiones de implementación: (1) la guarda se escribe como
**lista de permitidos central** (solo las rutas de auth) y no como comprobación por ruta, porque una lista
negra por ruta deja sin cubrir cualquier endpoint que se agregue mañana; y (2) la protección de inmutabilidad
de S6 también es del servidor, no de la interfaz, por la misma razón.
