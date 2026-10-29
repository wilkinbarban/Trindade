# Atajo unificado «Aero32 Bridge» para la integración Aero32

Unifica los dos atajos de iOS (`Aero32 Estoque`, `Aero32 Prueba`) en un único puente HTTP ciego que
resuelve Entrada, Saída, Consulta de Movimentos y Test de Conexión. Documento de diseño de origen:
`odd/tasks/reporte-arquitectura-aero32-trindade.md`.

## Specs

S1 — «Definir `AERO_DEFAULT_BRIDGE_SHORTCUT_NAME = 'Aero32 Bridge'` y actualizar los defaults en
`DEFAULT_AERO_SETTINGS`.»

S2 — «Implementar `buildSaidaItemPayload` (prefijos `PED_*`, escalado `PRO_QTD` x100, código a 6 dígitos con
ceros, usuario en mayúsculas).»

S3 — «Implementar `buildMovimentoItemPayload` (`CODFOR: null`, `NOMUSU` en mayúsculas, `CODPRO` a 6 dígitos
o `null`, `DTINI`, `DTFIM`, `ENTSAI`).»

S4 — «Implementar `buildAeroBridgeEnvelope`, `buildAeroBridgeTriggerUrl` y `aeroBridgeCallbackPrefix`.»

S5 — «Extender `parseAeroCallback` para soportar `aero_action` (`'entrada'`, `'saida'`, `'movimentos'`,
`'test'`) con `aero_data` JSON, manteniendo retrocompatibilidad total con `aero_sync` y `aero_test`.» +
«Implementar `aeroBridgeShortcutRecipe`.»

S6 — «Diálogo del Atajo (`AeroShortcutDialog.tsx`): Presentar la receta del atajo único `Aero32 Bridge`
(4 acciones) con botones de copiado para las claves del diccionario (`url`, `auth`, `body`, `callback`).» +
«Locales (`pt-BR.json` y `es.json`): Actualizar textos de la receta unificada.»

S7 — «Panel de Configuración (`AeroIntegrationPanel.tsx`): Al probar la conexión (`handleTest`) con Safari
HTTPS bloqueado, disparar el atajo unificado `Aero32 Bridge` hacia `/usuarios/`.» + «Página de Lanzamiento
(`MassasLaunchPage.tsx`): Actualizar `triggerAeroSync` para despachar el sobre del bridge y procesar el
retorno bridge en el `useEffect`.»

S8 — «Ejecutar suites completas (frontend y backend).» + «Commit atómico en `main` con Conventional Commits
sin `Co-Authored-By` ni atribución de IA.» + «Reconstruir contenedores con `docker compose up -d --build` y
verificar que queden sanos.»

S9 — Decisión de origen: «La decisión tomada es tener UN SOLO ATAJO en el iPhone llamado `Aero32 Bridge`
(4 acciones nativas en iOS Shortcuts) que resuelva Lançamento (Entrada), Baixa (Saída), Consulta de
Movimentos y Test de Conexión.» El atajo no conoce el negocio: recibe `{ url, method, headers, body,
callback }` y devuelve la respuesta cruda del ERP a la página.

S10 — (por L2) Opción B confirmada: «La cabecera Authorization viaja DINÁMICA en el sobre `{ url, auth,
body, callback }` (receta de 8 acciones en iOS Shortcuts).» Razón: «transparencia total; si cambia la
contraseña en la web, el iPhone no tiene que editar nunca el atajo.» El `body` viaja «serializado como
string JSON plano en el sobre». (Prevalece sobre el «4 acciones» que dicen S6 y S9: contando cada lectura
de diccionario, la receta real tiene 8.)

S11 — (por L2) «Eliminar definitivamente el campo "Código do Usuário no ERP" (`userCode`) de
`AeroIntegrationPanel.tsx`.» El panel queda «ÚNICAMENTE con los 3 campos requeridos: URL, Usuario y
Contraseña». «`ENT_USU` viaja con el username en mayúsculas (WILKIN), por lo que `userCode` es un residuo
obsoleto que confunde al operario en producción.»

S12 — (por L2) «Ajustá la documentación / ejemplo de `PRO_QTD` en `movimentoitem` con el formato humano que
descubriste.» El ERP devuelve unidades humanas (15.0), no el entero escalado ×100 del request.

No-goals de esta entrega: fases 3, 4 y 5 del reporte de arquitectura (pestañas Entrada/Baixa en la grilla,
badges de conciliación `ENT_LAN` y compensación contable al editar/eliminar). El core y el contrato del
bridge quedan listos para ellas, pero no se construye esa UI acá.

## Tasks

- T1 (S1, S2, S3, S4, S5, S9, S10, S11) — hecho, commit `47fa57d`: núcleo en `packages/frontend/src/lib/aero-integration.ts` — constante y
  defaults del bridge, migración de nombres legacy, `buildSaidaItemPayload`,
  `buildMovimentoItemPayload`, sobre/trigger/prefijo de callback, extensión de `parseAeroCallback`,
  `readAeroBridgeResult` y `aeroBridgeShortcutRecipe` con `auth` dentro del sobre, y retiro de
  `userCode` de los ajustes, con sus pruebas unitarias en `aero-integration.test.ts`. Ruta: inline
  (parent) · commit `feat(aero)`.
- T2 (S6) — hecho, commit `0058e66`: receta única de 8 acciones en `AeroShortcutDialog.tsx` con copiado de las claves del diccionario,
  y textos en `pt-BR.json` / `es.json`. Ruta: inline · commit `feat(aero)`.
- T3 (S7, S11) — hecho, commit `fc31515`: el panel pierde el campo del código y dispara el bridge hacia `/usuarios/`; la página de
  lanzamiento lo despacha para `entrada` con `ENT_USU` en mayúsculas y procesa el retorno; prueba de
  cableado por fuente. Ruta: inline · commit `feat(aero)`.
- T4 (S8, S12) — en curso: suites completas, typecheck, documentación (incluido el ejemplo de `PRO_QTD`), commit,
  push, rebuild de contenedores y salud. Ruta: inline · evidencia en el Log.

## Log

L1 — pedido del usuario, literal:

  Hola! Wilkin nos encomendó implementar la unificación del atajo de iOS para la integración con Aero32
  WebService bajo ODD.

  Tenés el documento de diseño y especificación listo en odd/tasks/reporte-arquitectura-aero32-trindade.md.
  La decisión tomada es tener UN SOLO ATAJO en el iPhone llamado 'Aero32 Bridge' (4 acciones nativas en iOS
  Shortcuts) que resuelva Lançamento (Entrada), Baixa (Saída), Consulta de Movimentos y Test de Conexión.

  Por favor, procedé con el desarrollo bajo TDD estricto y ODD:
  1. Core (packages/frontend/src/lib/aero-integration.ts):
     - Definir AERO_DEFAULT_BRIDGE_SHORTCUT_NAME = 'Aero32 Bridge' y actualizar los defaults en
       DEFAULT_AERO_SETTINGS.
     - Implementar buildSaidaItemPayload (prefijos PED_*, escalado PRO_QTD x100, código a 6 dígitos con
       ceros, usuario en mayúsculas).
     - Implementar buildMovimentoItemPayload (CODFOR: null, NOMUSU en mayúsculas, CODPRO a 6 dígitos o
       null, DTINI, DTFIM, ENTSAI).
     - Implementar buildAeroBridgeEnvelope, buildAeroBridgeTriggerUrl y aeroBridgeCallbackPrefix.
     - Extender parseAeroCallback para soportar aero_action ('entrada', 'saida', 'movimentos', 'test') con
       aero_data JSON, manteniendo retrocompatibilidad total con aero_sync y aero_test.
     - Implementar aeroBridgeShortcutRecipe.
  2. Diálogo del Atajo (packages/frontend/src/pages/massas/AeroShortcutDialog.tsx):
     - Presentar la receta del atajo único 'Aero32 Bridge' (4 acciones) con botones de copiado para las
       claves del diccionario (url, auth, body, callback).
  3. Panel de Configuración (packages/frontend/src/pages/massas/AeroIntegrationPanel.tsx):
     - Al probar la conexión (handleTest) con Safari HTTPS bloqueado, disparar el atajo unificado Aero32
       Bridge hacia /usuarios/.
  4. Página de Lanzamiento (packages/frontend/src/pages/MassasLaunchPage.tsx):
     - Actualizar triggerAeroSync para despachar el sobre del bridge y procesar el retorno bridge en el
       useEffect.
  5. Locales (pt-BR.json y es.json):
     - Actualizar textos de la receta unificada.
  6. Verificación y despliegue:
     - Escribir pruebas unitarias en aero-integration.test.ts (TDD).
     - Ejecutar suites completas (frontend y backend).
     - Commit atómico en main con Conventional Commits sin Co-Authored-By ni atribución de IA.
     - Reconstruir contenedores con docker compose up -d --build y verificar que queden sanos.

L2 — directivas del usuario, literal:

  Colega, directivas claras y definitivas de Wilkin para avanzar con T1–T4:

  1. Credenciales en el sobre (Opción B confirmada):
     - La cabecera Authorization viaja DINÁMICA en el sobre { url, auth, body, callback } (receta de 8 acciones en iOS Shortcuts).
     - Razón de Wilkin: transparencia total; si cambia la contraseña en la web, el iPhone no tiene que editar nunca el atajo.
     - El payload body viaja serializado como string JSON plano en el sobre, tal como detectaste.

  2. Limpieza del Panel de Configuración (Urgente):
     - Eliminar definitivamente el campo "Código do Usuário no ERP" (userCode) de AeroIntegrationPanel.tsx.
     - El panel debe quedar ÚNICAMENTE con los 3 campos requeridos: URL, Usuario y Contraseña.
     - Como confirmamos con libso.so, ENT_USU viaja con el username en mayúsculas (WILKIN), por lo que userCode es un residuo obsoleto que confunde al operario en producción.

  3. Alcance:
     - Mantener el alcance acotado a T1, T2, T3 y T4 (Core, Diálogo de receta unificada, Panel y Lanzamiento, Tests + Rebuild). Fases 3–5 quedan para después.
     - Ajustá la documentación / ejemplo de PRO_QTD en movimentoitem con el formato humano que descubriste.

  Procedé con T1 inmediatamente bajo TDD estricto y ODD. Te superviso cada 5 minutos.
