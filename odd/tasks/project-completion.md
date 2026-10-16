

## Tarea nueva: paridad de tarjetas del Dashboard de Android y usuario invitado

Directiva del usuario del 2026-10-16, con dos trabajos de tamaño y riesgo distintos: la paridad de las tres
tarjetas de acceso directo en el Dashboard de Android (unidad acotada de interfaz, y los tres `Tab` ya existen
desde A6b), y el **usuario invitado de solo lectura** (una unidad de seguridad que toca backend, web y
Android: guarda global de mutaciones, semilla idempotente, invisibilidad en los listados e inmutabilidad).

Queda gobernada por `odd/tasks/guest-user-and-dashboard-parity.md`, con las specs literales del usuario y nueve
unidades. **Observación de seguridad registrada en ese documento antes de empezar**: la contraseña del invitado
está publicada en la propia directiva, así que cualquiera que la conozca puede entrar a producción y —por S4—
leer todo lo que lee un Administrador; la guarda de solo lectura es el control que lo contiene, y por eso se
escribe como **lista de permitidos central** (solo auth) y no como comprobación por ruta.
