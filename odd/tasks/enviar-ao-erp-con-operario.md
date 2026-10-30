# Enviar ao ERP: el pendiente viaja con su operario

Dos aplicaciones que dicen «Enviar ao ERP» y ninguna que mande lo que el operario cree. En la Web el botón
de la fila naranja consulta el histórico del ERP; en Android el despacho firma con el usuario configurado en
el teléfono. El lanzamiento pendiente de Jaime (2800 unidades, 280 cajas de 5000) es la prueba: sigue
pendiente después de tocar el botón, y cuando salga tiene que salir a nombre de JAIME.

## Specs

- **S1.** «En MassasUnifiedTable.tsx el botón de la fila naranja pendingErp dice 'Enviar ao ERP' y llama a
  onClick={onSync}... Pero handleSyncFromErp dispara el atajo de iOS para consultar el histórico de
  movimientos (movimentoitem, read_history), ¡NO envía nada a /entradasitem! Para enviar los lanzamientos
  pendientes hacia el ERP tiene que armar la cola con ese producto/cantidad/operador y llamar a
  triggerAeroSync, que es la que construye los sobres de entradasitem (launch_sync).»
- **S2.** «En AeroDispatcher.kt construye el payload con userCode = settings.userCode (el usuario configurado
  globalmente en el teléfono, ej. Wilkin) en vez de pasarle el operario del lanzamiento (row.operatorName /
  Jaime). En el ERP Delphi, ENT_USU debe viajar con el nombre del operario en mayúsculas (JAIME).»
- **S3.** «Además, si la red de la fábrica no responde o estamos fuera del Wi-Fi LAN, debe informar con
  claridad 'Sem conexão com a rede da fábrica'.»
- **S4.** «El lanzamiento id 89 de Jaime (280 cajas de 5000 = 2800 unidades) está en massa_launches para hoy
  2026-10-30. Al arreglar el botón 'Enviar ao ERP' en la fila de la Web y Android, el despacho hacia el ERP
  debe salir con el operario JAIME.»

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S4 | web: the pending row's button queues its own launch and trips `entradasitem` | pending |
| T2 | S2, S4 | Android: `AeroDispatchRequest` carries the launch's operator and the payload signs with it | pending |
| T3 | S3 | Android: an unreachable plant says «Sem conexão com a rede da fábrica» | pending |
| T4 | S1-S4 | delivery: suites, containers, signed APK, docs | pending |

## Log

**L1 (the request, verbatim).**

> Che, encontramos la causa exacta del problema que reportó Wilkin con el lanzamiento pendiente de Jaime
> (2800 unidades / 280 cajas de producto 5000) y por qué el botón 'Enviar ao ERP' no lo está enviando:
>
> 1. Causa Raíz en la Web:
> En MassasUnifiedTable.tsx el botón de la fila naranja pendingErp dice 'Enviar ao ERP' y llama a
> onClick={onSync}. En MassasCollectiveTable.tsx se le pasa onSync={onSync}, y en MassasLaunchPage.tsx:1069
> ese prop está cableado a onSync={handleSyncFromErp}. Pero handleSyncFromErp dispara el atajo de iOS para
> consultar el histórico de movimientos (movimentoitem, read_history), ¡NO envía nada a /entradasitem! Para
> enviar los lanzamientos pendientes hacia el ERP tiene que armar la cola con ese producto/cantidad/operador
> y llamar a triggerAeroSync, que es la que construye los sobres de entradasitem (launch_sync).
>
> 2. Causa Raíz en Android:
> En MassasLaunchViewModel.kt, sendPendingLaunch(row) llama a aeroDispatcher.dispatch(settings, request),
> pero en AeroDispatcher.kt construye el payload con userCode = settings.userCode (el usuario configurado
> globalmente en el teléfono, ej. Wilkin) en vez de pasarle el operario del lanzamiento (row.operatorName /
> Jaime). En el ERP Delphi, ENT_USU debe viajar con el nombre del operario en mayúsculas (JAIME). Además, si
> la red de la fábrica no responde o estamos fuera del Wi-Fi LAN, debe informar con claridad 'Sem conexão com
> a rede da fábrica'.
>
> 3. En la base de datos:
> El lanzamiento id 89 de Jaime (280 cajas de 5000 = 2800 unidades) está en massa_launches para hoy
> 2026-10-30. Al arreglar el botón 'Enviar ao ERP' en la fila de la Web y Android, el despacho hacia el ERP
> debe salir con el operario JAIME.
>
> Hagamos esta remediación en ambas aplicaciones con TDD, todo directo a main, sin ramas y sin Co-Authored-By.
