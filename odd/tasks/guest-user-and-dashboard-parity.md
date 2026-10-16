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

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Las tres tarjetas en `DashboardScreen.kt` con la estética de `DashboardCard` y sus callbacks `onOpenStock`, `onOpenStockHistory`, `onOpenAssai` | S1, S2 | hecha | `7b0d969` (63 líneas con T2) |
| T2 | Cableado en `DashboardRoute` y en el `when (tab)` de `MainActivity.kt` hacia `Tab.STOCK_COUNTER`, `Tab.STOCK_HISTORY` y `Tab.ASSAI` (que **ya existen**), más sus textos si hacen falta | S2 | hecha | `7b0d969`; suite Android 834/0/0 (job-163) |
| T3 | Semilla idempotente del invitado (bcrypt, `is_active = 1`) con el patrón de `provision-admin.ts` | S3 | hecha | `ed2d076` (108 líneas, ROJO 0/3 → VERDE 3/3); regresión de la suite completa en `c2b74e6` |
| T4 | La guarda de mutaciones: **lista de permitidos** (solo auth: login/logout/refresh) y 403 con el mensaje literal para el invitado, como hook **global** —no por ruta— para que un endpoint nuevo no quede sin cubrir | S5 | hecha | `4441b16` (91 líneas, ROJO: la mutación pasaba → VERDE 2/2); backend 490/490/0 |
| T5 | Invisibilidad: filtro `username != 'invitado'` en **todas** las consultas que listan usuarios (`admin.service.ts` líneas 537, 567 y 645; `reports.lifecycle.service.ts:216`; revisar `auth.service.ts:68`) | S6 | hecha | `6e5c5d7` (con T6, 129 líneas): cinco sitios reales y dos que **no** llevan filtro a propósito |
| T6 | Inmutabilidad: el invitado no se puede editar, ni cambiar de contraseña, ni borrar por API | S6 | hecha | `6e5c5d7`; ROJO: el Administrador **podía** desactivarlo (200) y borrarlo (200) → VERDE 403 |
| T7 | Aviso en la web y en Android cuando llega el 403 (o antes de intentar): una superficie clara que diga que hay que registrarse | S5 | web hecha, Android en verificación | `42a0c12` (225 líneas; frontend 79/79 y typecheck limpio) |
| T8 | Visibilidad del invitado idéntica al Administrador: comprobar `RolePolicy` y los menús de la web | S4 | confirmada | el invitado es `role_id = 1` / `Administrador` con `is_active = 1` (visto en el rojo de T6) |
| T9 | Suite completa (backend, frontend y Android) en verde y commits convencionales | S7 | pendiente | backend 490/490/0 y frontend 79/79 hechos; falta la corrida de Android con el aviso ya cableado |

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

L3 (orquestador, T3): **`seed.sql` no servía para esto**. Esa semilla corre sólo cuando la base es nueva o
quedó a medias (`openDatabase`), así que un invitado puesto ahí habría existido en una base recién creada y
**nunca** en la que ya está sirviendo en producción. La semilla vive en el arranque, es idempotente, y su
prueba cubre los dos casos: dos arranques dejan **una** fila, y una base que **ya existía** —el caso de
producción— también recibe al invitado. Ese segundo caso es el que habría cazado el error.

L4 (orquestador, T3): **la suite completa encontró lo que la corrida dirigida no podía**. Sembrar en
`openDatabase` cambió el mundo de todas las pruebas de base, y cuatro fallaron: dos afirmaban
`COUNT(*) FROM users == 0` (una llamada, literalmente, «initializes references without fixed users») y dos
armaban la base con `schema.sql` pero **sin** `seed.sql`. Se corrigieron sin aflojar nada: las dos primeras
afirman ahora que no hay ningún usuario fijo **salvo el invitado** *y* que el invitado está; y las otras dos
dejaron de usar un fixture irreal (una instalación existente **siempre** tiene sus datos de referencia), con
sus aserciones intactas. La semilla **no** se aflojó: una instalación sin el rol Administrador es una
instalación rota y debe fallar ruidosamente, porque un invitado ausente en silencio es un requisito
incumplido en silencio.

L5 (orquestador, T4): **dos hallazgos estructurales**, los dos anteriores a este trabajo y los dos capaces de
hacer que las pruebas no prueben nada: la app de pruebas nunca pasa por `openDatabase` (así que el invitado
no existía en el mundo de las pruebas de rutas) y tampoco usa `registerApiRoutes` —registra cada módulo a
mano—, de modo que **producción y pruebas se construyen por dos caminos** y todo lo que agregue el bootstrap
hay que agregarlo en ambos. Del segundo hallazgo salió un tercero durante T5: la copia de la consulta de
opciones de usuario en el helper tenía que llevar el mismo filtro, o las pruebas seguirían viendo un invitado
que producción esconde.

L6 (orquestador, T4): **desviación consciente del vehículo y no de la intención**. La tarea pide un hook
global; un hook global de Fastify corre **antes** de los `preHandler` de cada ruta, y ahí todavía no existe
`request.user`. La guarda vive dentro del `preHandler` de autenticación, que es el punto por el que pasa
toda ruta protegida, así que la intención —que un endpoint nuevo quede cubierto por omisión— se cumple
igual. Además decide con el `username` que viene de la **base** y no con el del token, y acepta como
permitidos los métodos de lectura además de las rutas de auth, porque si no el invitado no podría ni mirar.

L7 (orquestador, T5): **la decisión que evitó codificar el error**. La suite de T4 falló con `3 !== 2` y
`4 !== 3`: el invitado estaba siendo **contado**. Lo tentador era subir las expectativas a 3 y 4, que habría
declarado al invitado **visible**, justo lo contrario de S6. El arreglo fue el filtro, y esos dos tests
volvieron a verde **sin que se les tocara una línea**. Quedó escrito también qué sitios **no** llevan filtro
y por qué: la consulta del login (el invitado tiene que poder entrar) y el escaneo de gemelos por mayúsculas
(un «Invitado» real **debe** colisionar con él).

L8 (orquestador, T5/T6): la suite encontró una tercera consecuencia que no era un conteo: el invitado, sembrado
sobre la tabla vacía del helper, se quedaba con el **`id = 1`** que los fixtures de reportes daban por
sentado, y la regla «sólo el creador edita» compara por `user_id`; de ahí los `403 «Registro somente leitura»`.
Se arregló sembrándolo **después** de los fixtures (en producción nada depende del id 1: la comparación es
contra el `sub` del token). Y el rojo de T6 mostró lo que de verdad hacía falta: el Administrador **podía**
desactivar (200) y **borrar** (200) al invitado, o sea que la cuenta de solo lectura era completamente
mutable; borrarlo era lo peor de los dos, porque volvería a aparecer en el arranque siguiente.

L9 (orquestador, T7): el aviso de la web y el de Android reconocen el rechazo con una **función pura**, y las
dos pruebas que más valen son las que **comparan la cadena del cliente con la del servidor** leyendo la
constante del backend. Dos copias de un texto que tienen que coincidir es exactamente lo que driftea en
silencio, y el día que se separen el aviso deja de aparecer sin que nada lo delate. Y cuando una de esas
pruebas no puede correr —el host de compilación sólo tiene el paquete android, no el monorepo— se reporta
**omitida** en vez de pasar: una protección que no corre no puede parecer una que sí corre.
