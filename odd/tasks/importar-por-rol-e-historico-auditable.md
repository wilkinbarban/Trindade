# Importar sólo lo propio y un histórico que no se toca

Two business rules close the ERP loop: a worker may only import the seats that the ERP already
attributes to them (an admin may import anyone's), and the Massas history is a record nobody edits
or deletes, not even an admin.

## Specs

- **S1.** «Un trabajador sólo puede importar sus propios asientos del ERP (donde el operario resuelto
  coincida con su usuario). Los administradores sí pueden importar a voluntad asientos de cualquier
  operario.»
- **S2.** «En el backend (POST /api/massas-launch): si el usuario autenticado no es admin y el asiento
  pertenece a otro operario (ownerId !== request.user.id), rechazar con 403 Forbidden.»
- **S3.** «En frontend (Web y Android): si el usuario es trabajador, ocultar el botón «Importar» en las
  filas de otros operarios; si es admin, mostrarlo en todas.»
- **S4.** «En el Histórico de Lançamento de Massas no se puede editar ni borrar información bajo ninguna
  circunstancia, ni siquiera para administradores (rompe la lógica de auditoría de la empresa).»
- **S5.** «En Web: en MassasHistoryPage.tsx, fijá readOnly={true} en MassasIndividualTable y remové los
  handlers/botones de editar y borrar de esa pantalla.»
- **S6.** «En Android: verificá que al abrir fechas pasadas desde el historial no exista ningún camino
  que ofrezca editar o eliminar lanzamientos.»
- **S7.** «Los asientos 35937 (Victor) y 35939 (Gustavo) del día de hoy no los toques por SQL: dejalos
  en el espejo para que Wilkin y los operarios prueben la importación con identidad y con las
  restricciones de rol directamente desde la UI.»

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2 | backend: resolve the seat's owner from the mirror, refuse a worker's foreign seat with 403 | done |
| T2 | S3 | web: expose the owner on the mirror row and hide the import button from a worker | done |
| T3 | S3 | Android: same owner check on the unified row and its import action | 6455a2a |
| T4 | S4, S5 | web: the history reads, never writes | done |
| T5 | S4, S6 | Android: past days opened from the history offer no edit or delete, admin included | 6455a2a |
| T6 | all | delivery: suites, containers, signed APK, docs | d7c3f2e |

## Log

**L1 (the request, verbatim).**

> Impecable el empaquetado del APK 0.6.0 y el despliegue del backend. Ahora Wilkin nos pide dos ajustes
> clave de lógica de negocio para cerrar el circuito:
>
> 1. Restricción de roles al importar asientos:
> - Un trabajador sólo puede importar sus propios asientos del ERP (donde el operario resuelto coincida
>   con su usuario). Los administradores sí pueden importar a voluntad asientos de cualquier operario.
> - En el backend (POST /api/massas-launch): si el usuario autenticado no es admin y el asiento pertenece
>   a otro operario (ownerId !== request.user.id), rechazar con 403 Forbidden.
> - En frontend (Web y Android): si el usuario es trabajador, ocultar el botón «Importar» en las filas
>   de otros operarios; si es admin, mostrarlo en todas.
>
> 2. Histórico de Massas 100% de solo lectura:
> - En el Histórico de Lançamento de Massas no se puede editar ni borrar información bajo ninguna
>   circunstancia, ni siquiera para administradores (rompe la lógica de auditoría de la empresa).
> - En Web: en MassasHistoryPage.tsx, fijá readOnly={true} en MassasIndividualTable y remové los
>   handlers/botones de editar y borrar de esa pantalla.
> - En Android: verificá que al abrir fechas pasadas desde el historial no exista ningún camino que
>   ofrezca editar o eliminar lanzamientos.
>
> 3. Los asientos 35937 (Victor) y 35939 (Gustavo) del día de hoy no los toques por SQL: dejalos en el
> espejo para que Wilkin y los operarios prueben la importación con identidad y con las restricciones de
> rol directamente desde la UI.
>
> Hagamos este ajuste con TDD, todo directo a main y sin Co-Authored-By.

**L2. The table is called `MassasCollectiveTable`.** The request names `MassasIndividualTable`, which does
not exist in this repository. `MassasHistoryPage.tsx` renders `MassasCollectiveTable` — the collective
summary plus the per-operator breakdown — and that is the one receiving `readOnly`.

**L3. The owner is resolved from the mirror, not from the request body.** A seat's `ENT_USU` is what the
Delphi screen typed, and it is already stored in `aero_movements`. Reading the owner from there means a
crafted body cannot claim somebody else's seat, and it makes the row the frontend hides and the request
the backend refuses come from the same fact. An `ent_lan` the mirror does not know leaves the owner
unknown, and unknown belongs to the admin alone: a worker cannot prove a seat is theirs by naming it.

**L4. The freeze is a server rule, not a screen preference.** S4 says "bajo ninguna circunstancia", and a
hidden button is not a circumstance the server can check: `PUT`/`DELETE /api/massas-launch/:id` answer
`409 MASSAS_HISTORY_FROZEN` for any launch whose day is not today in São Paulo, before roles and before
reconciliation are weighed. The order matters — an administrator is refused for the same reason a worker is.
The API's own tests that exercise editing and deleting now run on today, because yesterday is exactly the
day those tests must no longer reach.

**L5. Delivered and verified against the deployed binary.** Backend 615/615, frontend 426/426, Android
`testDebugUnitTest` green, `trindade-api-1` and `trindade-web-1` rebuilt from this tree. The rules were then
asked of the running image with a copy of the production database mounted into a throwaway container:
`POST /api/massas-launch` answers `403 MASSAS_FORBIDDEN` to a worker importing 35937 (VICTOR's) and to VICTOR
importing 35939 (GUSTAVO's), and `201` to an administrator importing 35937; `PUT` and `DELETE` on a
2026-10-29 launch answer `409 MASSAS_HISTORY_FROZEN` to an administrator, that day keeps exactly its seven
launches and their boxes, and the same administrator still rewrites a fresh 2026-10-30 launch with `200`. The
signed 0.6.1 APK (versionCode 6001) is what `/api/app/download-apk` serves.
