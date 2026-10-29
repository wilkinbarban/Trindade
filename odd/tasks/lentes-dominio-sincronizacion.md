# Sincronización del ERP: un snapshot, dos lentes de dominio

Los dos módulos que leen el ERP —Lançamento de Masas y Relatório— comparten el mismo snapshot diario en
`aero_movements`, pero hasta hoy sólo Masas mostraba el resultado de la consulta, y lo mostraba completo: once
asientos contados como «movimentos». Este hito separa el dato del lente: cada pantalla cuenta lo suyo y el
Relatório puede pedir la consulta sin salir de su página.

## Specs

- **S1**: «El modal de resultado al sincronizar movimientos del ERP actualmente muestra '11 movimentos lidos',
  contando tanto las masas como los insumos (como la caja de Viviane). Queremos que el modal y el feedback de
  este módulo filtren exclusivamente por el catálogo de masas (usando isMassaProduct o normalización de ceros
  contra MASSAS_CATALOG) y reporte sólo las masas: ej. '10 massas sincronizadas do ERP' (o 'Nenhuma massa
  sincronizada do ERP' si no hubo masas ese día). Cero mención ni conteo de insumos en este módulo.»
- **S2**: «En ReceiptsSection, el botón 'Atualizar' / 'Sincronizar' debe disparar el MISMO atajo de iOS hacia el
  ERP de la empresa (aero_action=movimentos) que usa el módulo de masas, pero retornando a la página del reporte
  (aero_return). Al volver de la consulta, los datos se guardan en aero_movements y se muestra el modal de
  resultado (AeroResultDialog) filtrando EXCLUSIVAMENTE por insumos (entradas ENTSAI == 'E' que no son masas):
  ej. '1 insumo recebido sincronizado do ERP' (o 'Nenhum insumo recebido no dia').»
- **S3**: «Ambos módulos guardan la totalidad del snapshot en aero_movements, pero cada uno aplica su propio
  lente de dominio en su modal de resultado y en su vista.»
- **S4**: Viewport de 375 px: cero desborde horizontal y todo control táctil con 44 px de alto mínimo.
- **S5**: i18n en `pt-BR` y `es`, sin claves huérfanas.
- **S6**: Trunk-based sobre `main`, commits atómicos de hasta ~400 líneas, Conventional Commits, sin
  `Co-Authored-By` ni atribución a IA.
- **S7**: Al cerrar: `docker compose up -d --build web` y HTTP 200 en producción.

## Decisiones

- **D1 — La clasificación no se copia al cliente.** El catálogo de masas vive en `stock.catalog.ts` y ya tiene dos
  consumidores en el backend (el export y `/reports/receipts`). Duplicar los diez códigos en el SPA para contar
  asientos crearía una segunda regla que diverge en silencio. `POST /integration/aero/movements` responde el
  desglose `{ saved, massas, insumos }`, calculado por la misma función que alimenta la sección del relatório.
- **D2 — `aero_return` es la URL de callback, no un parámetro nuevo.** El atajo es un proxy ciego: abre la
  `callback` que la web le entrega. Para volver al reporte basta con que esa URL sea la del reporte; el atajo
  instalado no cambia. El `path` se valida como ruta relativa del mismo origen (sin esquema, sin `//`, sin
  query) para que una URL de retorno manipulada no pueda sacar al operario del sitio.
- **D3 — El conteo es del lente, no del día.** Un día sin masas es «Nenhuma massa sincronizada do ERP», aunque
  hayan entrado cinco insumos; un día con diez masas y una caja es «10 massas» en Masas y «1 insumo» en
  Relatório. El snapshot se guarda entero en ambos casos.
- **D4 — El modal sale después de guardar.** Si el snapshot no se pudo guardar, el operario ve el error de
  guardado y no un conteo que no está en la base.

## Tasks

- **T1** (S1, S3, D1) — `movementLenses()` en `receipts.service.ts` sobre la misma predicción que
  `supplyReceipts`, y `POST /integration/aero/movements` respondiendo `{ saved, massas, insumos }`. Tests de
  servicio y de ruta; `openapi.json` regenerado.
- **T2** (S1, S2, D2) — `movementsSyncFeedback` pasa a recibir un conteo; nuevo `receiptsSyncFeedback`; 
  `aeroBridgeCallbackPrefix`/`aeroBridgeCallbackFor` aceptan un `path` de retorno validado. Tests puros.
- **T3** (S1) — `MassasLaunchPage`: el modal de `movimentos` cuenta masas y guarda antes de mostrarlo. Claves
  nuevas en `pt-BR`/`es`.
- **T4** (S2) — `ReceiptsSection`: botón que dispara el atajo con retorno a la ruta actual, procesamiento del
  callback al volver (guardar snapshot, recargar la sección, modal con lente insumos). Claves nuevas.
- **T5** (S4, S5) — E2E a 375 px con el mismo payload de once asientos: Masas cuenta diez, Relatório cuenta uno.
- **T6** (S6, S7) — Suites completas, `tsc --noEmit`, build y despliegue con HTTP 200.

## Riesgos

- **Item 2** en D2: el retorno lo elige la página y viaja por el iPhone. Se mitiga validando que sea una ruta
  relativa del mismo origen, con test del intento `https://evil.example` y de `//evil`.
- **Item 3** descartado: la respuesta del POST sólo agrega campos; `saved` sigue igual y ningún consumidor
  existente cambia de forma.

## Log

- **L1** — «Hola! Wilkin probó el flujo en el iPhone y definimos dos remediaciones clave para dejar la UX
  impecable: 1. Filtro estricto en MassasLaunchPage: El modal de resultado al sincronizar movimientos del ERP
  actualmente muestra '11 movimentos lidos', contando tanto las masas como los insumos (como la caja de
  Viviane). Queremos que el modal y el feedback de este módulo filtren exclusivamente por el catálogo de masas
  (usando isMassaProduct o normalización de ceros contra MASSAS_CATALOG) y reporte sólo las masas: ej. '10
  massas sincronizadas do ERP' (o 'Nenhuma massa sincronizada do ERP' si no hubo masas ese día). Cero mención
  ni conteo de insumos en este módulo. 2. Botón Sincronizar en Relatório (Recebimento): En ReceiptsSection, el
  botón 'Atualizar' / 'Sincronizar' debe disparar el MISMO atajo de iOS hacia el ERP de la empresa
  (aero_action=movimentos) que usa el módulo de masas, pero retornando a la página del reporte (aero_return).
  Al volver de la consulta, los datos se guardan en aero_movements y se muestra el modal de resultado
  (AeroResultDialog) filtrando EXCLUSIVAMENTE por insumos (entradas ENTSAI == 'E' que no son masas): ej. '1
  insumo recebido sincronizado do ERP' (o 'Nenhum insumo recebido no dia'). Ambos módulos guardan la totalidad
  del snapshot en aero_movements, pero cada uno aplica su propio lente de dominio en su modal de resultado y en
  su vista. Tenés luz verde para implementarlo bajo TDD y ODD: - Trunk-based directo en main, commits atómicos
  (<= 400 líneas) con Conventional Commits y sin Co-Authored-By ni mención a IA. - Viewport móvil 375px con
  botones táctiles >= 44px. - Actualizar i18n (pt-BR / es) y tests (unitarios + Playwright E2E). - Al finalizar:
  docker compose up -d --build web y validación de HTTP 200 en producción. ¡Adelante!»

## Evidencia

- **T1** — `4ec895d` · `pdf` → `POST /integration/aero/movements` responde `{ saved: 1, massas: 0, insumos: 1 }`
  en producción con una caja sola (verificado contra `trindade-api-1`, 2026-10-29).
- **T2** — `a9b2260` · `aero-feedback.test.ts` 13/13 y `aero-integration.test.ts` cubren el retorno válido y los
  intentos `https://evil.example` y `//evil`.
- **T3** — `4f1761e` · `erp-result-dialogs.spec.ts` (once asientos de masa) espera «11 massas sincronizadas».
- **T4** — `995ef7e` · `receipts-sync-wiring.test.ts` + `aero-sync-lenses.spec.ts` (Relatório cuenta el insumo,
  lo lista y cierra el modal con 44 px).
- **T5** — `995ef7e` · `aero-sync-lenses.spec.ts`: mismo payload de 11 asientos, 10 masas en `/massas-launch` y
  1 insumo en `/reports`; desborde horizontal 0 en 375 px.
- **T6** — backend 569/569, frontend 302/302, `tsc --noEmit` limpio, Playwright 66/66.
  `docker compose up -d --build` → `trindade-api-1` y `trindade-web-1` healthy, `/api/health` 200, web 200,
  `https://trindademasas.duckdns.org/` 200. El bundle servido (`index-C9LsnGm5.js`) contiene los textos nuevos.
