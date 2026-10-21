# Actualización en Vivo (Polling Pasivo) en Lanzamiento de Masas

Implementación de live-reload / polling pasivo con guard de visibilidad y guard de interacción activa en MassasLaunchPage bajo disciplina ODD y TDD estricto.

## Specs
S1: Intervalo de Polling Pasivo:
  - Definir e implementar constante `POLL_INTERVAL_MS = 15000` (15 segundos).
S2: Guard de Visibilidad:
  - Solo disparar la consulta cuando `document.visibilityState === 'visible'`. Si la pantalla está apagada o la pestaña oculta, suspender el polling para ahorrar batería y ancho de banda.
  - Al volver la pestaña a 'visible', disparar actualización inmediata si no hay interacción activa.
S3: Refresco Silencioso:
  - Las consultas periódicas actualizan `dayView` sin activar spinner de pantalla completa (`loading` a false), sin borrar o alterar los campos del formulario de captura (`quantities`), y sin resetear el scroll de pantalla.
  - Los errores de red transitorios durante el polling pasivo se absorben silenciosamente sin mostrar alerta de error intrusiva.
S4: Guard de Interacción Activa:
  - Pausar la actualización de fondo mientras el usuario tenga un lote en edición (`editingLaunchId !== null`) o esté enviando (`submitting === true`), evitando pisar o interferir con la entrada del operador.
S5: Pruebas, Verificación y Despliegue:
  - Tests unitarios en verde para los guards y la lógica de polling (`shouldPollMassas`, `POLL_INTERVAL_MS`).
  - Verificación de tipos (`tsc -b packages/frontend`).
  - Suite completa de tests en verde.
  - Build de imágenes Docker (`api` y `web`) y verificación de salud de contenedores y HTTP 200 en `https://trindademasas.duckdns.org`.
  - Commit atómico directo en `main` (`<= 400` líneas) y push a `origin/main`.

## Tasks
- T1 (S1, S2, S4): Tests unitarios (RED) de guards de polling en `massas.test.ts` y lógica pura en `massas.ts`. Commit: `01e4a9d`
- T2 (S1, S2, S3, S4): Integración de polling pasivo y refresco silencioso en `MassasLaunchPage.tsx`. Commit: `01e4a9d`
- T3 (S5): Verificación de suites, build Docker, despliegue en producción y push git.

## Log
- L1: Solicitud del usuario para implementar actualización en vivo (live-reload / polling pasivo) en MassasLaunchPage con intervalo de 15s, guard de visibilidad, refresco silencioso y guard de interacción activa.
- L2: Verificación completa:
  - Frontend: 118 tests unitarios pasando en 25 suites (incluyendo guards de visibilidad, pausas de edición/envío e intervalo 15s).
  - Backend: 510 tests pasando en 79 suites.
  - Compilación TypeScript limpia sin errores (`tsc -b packages/frontend`).
  - Docker: Imagen `trindade-web` reconstruida y contenedores reiniciados y healthy (`trindade-api-1`, `trindade-web-1`).
  - Producción en vivo: `https://trindademasas.duckdns.org` responde HTTP 200.
