# Módulo Lançamento de Massas en Android Nativo y Release v0.5.1

Implementación en la aplicación Android nativa (`packages/android`) del nuevo módulo "Lanzamiento de Masas" (`MassasLaunch`) con total paridad respecto a la Web, incluyendo las 5 mejoras desarrolladas en Web, su inclusión en el Dashboard en la 3ra posición, instalación en emulador Ubuntu por SSH, y generación de la Release oficial v0.5.1 firmada.

## Specs

S1. Capa de Datos y API:
  - En `packages/android/app/src/main/java/com/trindade/app/network/MassasApi.kt`:
    - Endpoints Retrofit:
      - `GET /api/massas-launch?date={date}`
      - `POST /api/massas-launch`
      - `PUT /api/massas-launch/{id}`
      - `DELETE /api/massas-launch/{id}`
      - `GET /api/massas-launch/history`
    - Data models serializables con `kotlinx.serialization`.
  - Registrar `MassasApi` en `NetworkModule.kt`.
  - `MassasRepository.kt`: Operaciones de red seguras con manejo de cancelación y errores.
  - `MassasLogic.kt`: Funciones puras con tests unitarios:
    - Formato de mensaje WhatsApp: `🍝 *Lançamento de Massas — DD/MM/AAAA*`, solo productos con unidades > 0 (`• Producto: X un`), pie de página con nombres de operarios únicos `*Lançado por:* Operador1, Operador2` (SIN `*Total Geral:`).
    - Intervalo de polling pasivo: 15 segundos (`POLL_INTERVAL_MS = 15000L`).
    - Guard de polling: `shouldPollMassas(isActive, isEditing, isSubmitting)`.
  - Tests unitarios en `MassasLogicTest.kt`.

S2. Pantallas e Interfaz Jetpack Compose:
  - `MassasLaunchScreen.kt` & `MassasLaunchViewModel.kt`:
    - Bloque 1 (Cálculo): Lista de los 10 productos de masas, multiplicadores exactos de cajas a unidades, inputs numéricos por caja con cálculo de unidades en vivo, botón "Lançar" (sin botón Resetear).
    - Bloque 2 (Resumo Coletivo do Dia): Optimizado para pantallas móviles (tarjetas con totales acumulados de cajas y unidades). Detalle de lanzamientos individuales (hora, operario, cantidad). Botones Editar y Excluir habilitados ÚNICAMENTE para los lanzamientos del propio usuario autenticado (`launch.user_id == currentUserId` o administrador).
    - Bloque 3 (Compartir WhatsApp): Input de teléfono con máscara `(11) 9XXXX-XXXX` (`PhoneInput`), botón de envío directo a WhatsApp (`waMeLink`) y botón para copiar informe.
    - Botón de Ayuda Modal (`MassasHelpDialog`): Ícono de ayuda en el encabezado con el resumen en 3 pasos del módulo (paridad con `ModuleHelpModal` de Web).
    - Polling pasivo cada 15s silencioso en `MassasLaunchViewModel` mientras la pantalla está activa, con recarga silenciosa en segundo plano sin interrumpir inputs ni scroll.
  - `MassasHistoryScreen.kt` & `MassasHistoryViewModel.kt`:
    - Pantalla de Historial para consultar días anteriores con selector de fecha, lista de días con conteo y totales.

S3. Integración en Dashboard y Navegación:
  - `DashboardScreen.kt`: Ubicar la tarjeta "Lançamento de Masas" exactamente en la 3ra posición del Dashboard (después de "Cronograma Ativo" y antes de "Calculador de Estoque"), con ícono 🍝 y accesos directos al módulo y al historial.
  - `RolePolicy.kt`: Registrar `EntryPoint.MASSAS_LAUNCH` y `EntryPoint.MASSAS_HISTORY` dentro de `SHARED_ENTRY_POINTS` (accesible para Administrador y Trabajador).
  - `MainActivity.kt`: Registrar `Tab.MASSAS_LAUNCH`, agregar destino en el drawer y enrutamiento a `MassasLaunchRoute` y `MassasHistoryRoute`.

S4. Release v0.5.1, Firma del APK y Distribución:
  - Actualizar versión en `packages/android/app/build.gradle.kts`:
    - `effectiveVersionName = declaredVersionName ?: "0.5.1"`
    - `effectiveVersionCode = declaredVersionCode ?: 502`
  - Actualizar `FALLBACK_RELEASE.version = "v0.5.1"` en `packages/backend/src/modules/app/app.service.ts`.
  - Compilar el APK release firmado utilizando el contenedor Docker `ghcr.io/cirruslabs/android-sdk:35` montando `/home/wilkin/.android-keystores` con el keystore oficial `trindade-release.jks` (`SenhaTrindade123`, alias `trindade`).
  - Copiar el APK firmado a `/home/wilkin/Desktop/trindade-0.5.1.apk` y `/home/wilkin/backups/trindade-0.5.1.apk`.
  - Instalación en emulador Ubuntu por SSH:
    - `scp packages/android/app/build/outputs/apk/release/app-release.apk ubuntu:/tmp/trindade-0.5.1.apk`
    - Verificar/levantar emulador en `ssh ubuntu` (`AVD Trindade_UX_v030`).
    - Instalar APK: `adb install -r /tmp/trindade-0.5.1.apk`.
    - Confirmar instalación con `pm list packages | grep com.trindade.app`.
  - Crear el Release oficial en GitHub:
    `gh release create v0.5.1 packages/android/app/build/outputs/apk/release/app-release.apk --title "v0.5.1: Módulo Lançamento de Massas e Paridade Android" --notes "..." --repo wilkinbarban/Trindade`

S5. Documentación y Disciplina Git:
  - Actualizar `CHANGELOG.md`, `README.md`, `docs/release-android.md` y `docs/calculadoras.md`.
  - Todo directo en `main`, sin ramas intermedias, commits atómicos (<= 400 líneas), cero `Co-Authored-By`, y push a `origin/main`.

## Tasks
- [x] S1: Capa de Datos y API Retrofit (MassasApi.kt, NetworkModule.kt, MassasRepository.kt, MassasLogic.kt y tests unitarios). Commit: d0ad75b
- [x] S2: Componente Reutilizable Compose `ModuleHelpDialog.kt` (HelpSection, Material 3, amber icon, scrollable, botón "Entendido") con soporte de cadenas en `strings.xml`. Commit: 6fb9676
- [x] S3: Pantallas Jetpack Compose Masas (`MassasLaunchScreen`, `MassasLaunchViewModel`, `MassasHistoryScreen`, `MassasHistoryViewModel`) con integración de ayuda, polling pasivo 15s, inputs, resumen móvil, WhatsApp y RBAC. Commits: 953bbd2, 3aef701, 8c347f8, 1c58f81
- [x] S4: Integración del botón de ayuda en pantallas principales: Dashboard, Estoque, Assaí, Carga, Relatórios, Share. Commit: a4707d0
- [x] S5: Integración en Dashboard (3ra posición), RolePolicy y MainActivity (drawer/rutas). Commit: d8403da
- [x] S6: Bump v0.5.1 en Gradle y Backend, APK release firmado en Docker, copia local en Desktop/backups, instalación en emulador SSH Ubuntu (Trindade_UX_v030) y GitHub Release v0.5.1. Commit: 1fb12d5
- [x] S7: Documentación (CHANGELOG, README, docs/), verificación de suites y push final a origin/main. Commit: d99c98c

## Log
- L1: Solicitud del usuario para implementar en Android nativo el módulo Lanzamiento de Masas con total paridad respecto a Web, polling pasivo 15s, modal de ayuda, histórico, 3ra posición en Dashboard, release oficial v0.5.1 firmada e instalación en emulador Ubuntu por SSH.
- L2: Implementación de MassasApi, NetworkModule, MassasRepository, MassasLogic y suite de tests MassasLogicTest (6/6 en verde).
- L3: Implementación de ModuleHelpDialog.kt, HelpSection, ModuleHelpButton, ic_help.xml, strings.xml y ModuleHelpTest (3/3 en verde).
- L4: Implementación de MassasLaunchViewModel, MassasHistoryViewModel, MassasLaunchScreen, MassasHistoryScreen, RolePolicy y suites unitarias (100% verde).
- L5: Integración de ModuleHelpButton en Dashboard, Assaí, Estoque, Carga, Relatórios y ShareApp.
- L6: Posicionamiento fijo de Lançamento de Massas en la 3ª posición de DashboardScreen y rutas en MainActivity.
- L7: Alineación de icono en frontend web MassasLaunchPage.tsx (emoji 🍝) y redespliegue de contenedor web.
- L8: Compilación de APK de Release firmado con Docker (`:app:assembleRelease`), keystore `trindade-release.jks` v2 scheme. SHA-256: 1030bc3fb3deffdb84911f7da6a1c17a7098f83c5208f8a6b530853c040ab400.
- L9: Copia de seguridad en `/home/wilkin/Desktop/trindade-0.5.1.apk` y `/home/wilkin/backups/trindade-0.5.1.apk`.
- L10: Transferencia vía SCP e instalación exitosa en emulador remoto Ubuntu (`Trindade_UX_v030`, `adb install -r`).
- L11: Publicación de GitHub Release oficial `v0.5.1` con asset firmado `trindade-0.5.1.apk`.
- L12: Actualización de CHANGELOG.md, README.md, docs/release-android.md y docs/calculadoras.md.
