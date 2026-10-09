# Directiva: Frente Nativo Android en Ubuntu (Fase F3 / U3)

## Estado Verificado del Entorno Remoto (`ssh ubuntu`)
- **Host**: `ubuntu` (`10.50.0.3`, usuario `wilkin`). Conexión SSH operativa.
- **Herramientas**: OpenJDK 21.0.12.1 en PATH, Android SDK en `/home/wilkin/Android/Sdk`, ADB en `/home/wilkin/Android/Sdk/platform-tools/adb`.
- **Emulador Activo**: `emulator-5554` (AVD `Trindade_UX_v030`, Android 14 API 34, `sdk_gphone64_x86_64`) corriendo de forma continua y saludable (RAM disponible: 2.7 GiB, Swap libre: 1.6 GiB).
- **App Instalada**: `com.trindade.app` versionCode 301, versionName 0.3.0.
- **APK Candidato Staged**: `/tmp/trindade-upgrade-goZ49W/app-release.apk` (10.420.212 bytes, SHA256 `e24353311fd08243ea402e80648da7d570277d5f83c4a5b9feb714481210e221`).
- **Harness Preexistente**: `/home/wilkin/ux_runtime_f3/run_talkback_r2_slice.py` y artefactos XML/PNG en Ubuntu.

## Directiva de Ejecución
1. **Prioridad Inmediata**: Concluir el cierre de la unidad actual de admisión (`9c2b2-admission`: resultado de `mv08h8we`, verificación independiente, RDD/ACK y commit local en `main`).
2. **Activación Frente Nativo Android**:
   - Ajustar el harness de validación en Ubuntu para apuntar a `-s emulator-5554` (en lugar de `5556`).
   - Ejecutar la batería de pruebas de accesibilidad TalkBack: verificación de foco en ventana Trindade, dos gestos de avance, etiquetas, campos vacíos y botón submit bloqueado.
   - Ejecutar pruebas visuales de diseño: escala de fuentes (1.0 vs 1.3), densidad 420, insets de teclado/IME y scroll.
   - Preservar volcados UI (XML) y capturas PNG en el directorio de evidencias para acreditar la aceptación visual.
3. **Reglas de Orquestación**:
   - **Trunk-Based**: Todo trabajo consolidado va directo a `main`; no dejar ramas ni worktrees residuales al cerrar.
   - **Límite de Diff**: Máximo $\le 400$ líneas brutas ALL por unidad/corte.
   - **Sin Atribución AI ni Push**: Commits estrictamente locales; sin `Co-Authored-By` ni trailers de IA.
   - **Higiene de Disco**: Limpieza segura de artefactos temporales al cerrar el ciclo.
