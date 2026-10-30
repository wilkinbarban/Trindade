# Acceso a Aero32 según el dispositivo

El ERP de la fábrica se alcanza sólo desde la red de planta, y no todos los dispositivos pueden hacerlo igual. La web deja de ofrecer controles que en ese dispositivo no pueden funcionar y deja de cargarse donde ya existe una app nativa que sí funciona.

## Specs

**S1.** En Desktop/PC (Windows, Linux, macOS) todo lo relativo a Aero32 ERP se muestra en modo lectura: el panel de integración en Admin y las acciones de despacho en Lanzamiento de Masas.

**S2.** En Desktop/PC se muestra un banner explicativo amigable indicando que los dispositivos soportados son iPhone con Atajos y la app Android, y que para ese sistema operativo (nombrando Windows, Linux o macOS) aún no se desarrolló la conexión directa con Aero32 ERP.

**S3.** El resto de módulos de la web (Carga, Assai, Estoque, etc.) funciona con normalidad en Desktop/PC.

**S4.** Abrir la web desde Android no carga la web: descarga automáticamente el APK oficial alojado en `/api/app/download-apk` (módulo Compartir), con pantalla clara de descarga y botón de reintento.

**S5.** iPhone/iOS mantiene el comportamiento actual al 100% con los atajos de Siri/iCloud y controles activos.

## Decisions

**D1. Escape explícito para Android.** La descarga automática no puede ser una cárcel: un administrador con teléfono Android necesita el panel, y la app nativa todavía no lo tiene completo. La pantalla de descarga ofrece "Continuar no navegador", que guarda la elección y no vuelve a interceptar. Sin esa salida, S4 convertiría un teléfono en un dispositivo sin acceso al panel.

**D2. La detección es del sistema operativo, no del navegador.** Un iPhone con Chrome sigue siendo iOS y conserva sus atajos; una tablet Android con Samsung Internet es Android. Se decide por user agent, que es lo único que la web conoce antes de cargar.

**D3. Modo lectura significa deshabilitado, no oculto.** Los controles de Aero32 se muestran deshabilitados con el banner al lado, en vez de desaparecer: un operario de escritorio que no ve el botón de despacho cree que la función no existe, y el banner explica por qué está ahí y qué dispositivo sí la tiene.

## Tasks

- **T1** — `src/lib/device-detection.ts`: `detectDevice()` con las cuatro familias (ios, android, desktop, unknown) y el nombre legible del sistema. Test unitario.
- **T2** — `src/lib/device-detection.ts`: `aeroSupportFor(device)` → `supported | readOnly | nativeApp`, con el motivo. Test unitario.
- **T3** — `AndroidAppGate` en `App.tsx`: intercepta Android antes del router y muestra la pantalla de descarga con botón de reintento y escape persistido. Test unitario de la decisión pura.
- **T4** — `AeroDeviceBanner`: banner de escritorio que nombra el sistema operativo. Test unitario de copy y condición.
- **T5** — Panel de integración en Admin: controles en modo lectura en escritorio, con el banner. Test de cableado.
- **T6** — Lanzamiento de Masas: despacho, sincronización y espejo en modo lectura en escritorio, con el banner. Test de cableado.
- **T7** — Strings pt-BR y es, con paridad verificada por el test existente.

## Log

**L1.** (pedido literal del usuario)

```
Che, Wilkin nos acaba de pedir una mejora clave en paralelo para la aplicacion web (packages/frontend) sobre el acceso multidispositivo a Aero32 ERP:

1. Deteccion de dispositivo:
- Desktop/PC (Windows, Linux, macOS): Mostrar en modo lectura todo lo relativo a Aero32 ERP (panel de integracion en Admin y acciones de despacho en Lanzamiento de Masas). Mostrar un banner explicativo amigable indicando que dispositivos estan soportados (iPhone con Atajos y app Android) y que para ese sistema operativo (nombrando Windows, Linux o macOS) aun no se desarrollo la conexion directa con Aero32 ERP. El resto de modulos de la web (Carga, Assai, Estoque, etc.) funciona con normalidad.
- Android: Al detectar que la web se abre desde Android, en vez de cargar la web, descargar automaticamente el APK oficial alojado en /api/app/download-apk (modulo Compartir) con pantalla clara de descarga y boton de reintento, para que usen la app nativa con soporte completo.
- iPhone/iOS: Mantiene el comportamiento actual al 100% con los atajos de Siri/iCloud y controles activos.

Sumalo como nuevas specs/tareas en el plan ODD (odd/tasks/) con tests unitarios bajo TDD para device-detection y el banner. Continua con el flujo actual de Android y cuando puedas encara esta tanda web. Todo a main sin ramas.
```

- **L2** (evidencia del avance, 2026-10-30):
  - **T1/T2** — `src/lib/device-detection.ts` con las cuatro familias y el nombre del sistema; 10 casos sobre
    user agents reales, incluido el iPad que se anuncia como `Macintosh`.
  - **T3** — `src/lib/android-gate.ts` (`androidGateDecision`, `apkDownloadUrl` desde `BASE_URL`, elección
    persistida) y `AndroidAppGate` montado en `App.tsx` por encima del router, dentro de `DeviceProvider`.
  - **T4** — `AeroDeviceBanner` con `role="status"`, 44 px y el sistema nombrado; claves `aeroDevice.*` en pt y es.
  - **T5/T6** — el panel de Admin y la pantalla de Lanzamiento de Masas consultan `canControlAero`, muestran el
    banner arriba y deshabilitan probar/guardar/atajo/sync/despacho/importar sin ocultarlos; `MassasUnifiedTable`
    y `MassasCollectiveTable` reciben `deviceLimited`. Test de cableado en `aero-device-wiring.test.ts`.
  - **T7** — paridad i18n verificada por la suite existente (`i18n-parity.test.ts`), 399 casos en verde.
