# Massas header cleanup y release v0.5.1 (code 506)

Eliminar el badge "Modo Leitura" de la cabecera de `MassasLaunchScreen.kt`, dejar la composición limpia y publicar el APK Release con `versionCode=506`.

## Specs

S1: Eliminar por completo el badge `massas_read_only_badge` y el `Row` secundario que lo envolvía. La cabecera queda exactamente así:

```kotlin
if (state.isReadOnly && onBackToHistory != null) {
    NavigationActionButton(
        onClick = onBackToHistory,
        text = stringResource(R.string.report_back),
    )
}
Column(modifier = Modifier.weight(1f, fill = false)) {
    Text(
        text = stringResource(R.string.massas_title),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = if (state.isReadOnly) {
            stringResource(R.string.massas_production_date, state.date)
        } else {
            stringResource(R.string.massas_tagline)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

S2: Bump de versionCode a 506: `val effectiveVersionCode = declaredVersionCode ?: 506` en `packages/android/app/build.gradle.kts`, `-PversionCode=506` en la compilación Release, y `CHANGELOG.md` + `docs/release-android.md` reflejando `506`.

S3: `./gradlew testDebugUnitTest --no-daemon` en Docker SDK 35 pasa al 100%.

S4: APK Release oficial firmado con el keystore de producción; `aapt2` reporta `versionCode='506'` y `versionName='0.5.1'`; `apksigner` verifica APK Signature Scheme v2.

S5: Assets reemplazados en GitHub Release `v0.5.1` con `gh release upload v0.5.1 ... --clobber` (APK y sha256).

S6: APK copiado a `/home/wilkin/Desktop/trindade-0.5.1.apk`.

S7: REGLA DE ORO: NO INSTALAR EL APK EN EL EMULADOR VÍA ADB.

S8: Commit convencional directo en `main` sin ninguna mención a IA (`Co-Authored-By` estrictamente prohibido), y push a `origin main`.

S9: Purga de Docker y reporte de `df -h`.

## Tasks

- [x] T1 → S1 — Cabecera limpia sin badge en `MassasLaunchScreen.kt` y retiro de la cadena huérfana. Commit `ed60fc7` — `fix(massas): remove read-only badge from launch header`
- [x] T2 → S2 — Bump a 506 en `build.gradle.kts`, `CHANGELOG.md` y `docs/release-android.md`. Commit `9fb5cac` — `chore(release): bump android versionCode to 506`
- [x] T3 → S3 — Suite `testDebugUnitTest` en Docker SDK 35: `tests=965 failures=0 errors=0 skipped=0`, `BUILD SUCCESSFUL in 16m 54s` (job-67)
- [x] T4 → S4 — `assembleRelease` firmado con `-PversionCode=506`; `aapt2` reporta `versionCode='506'` / `versionName='0.5.1'`; `apksigner` `Verifies` con v2 (`CN=Trindade Massas Operacoes`, SHA-256 `ee0e404b...c431f6`). Tamaño `10,814,260` bytes, `sha256 0d0912a9...c3a670b`
- [x] T5 → S5, S6, S7 — Assets `--clobber` en GitHub Release `v0.5.1` (`app-release.apk`, `trindade-0.5.1.apk`, `.sha256`) con digest verificado; copia en `/home/wilkin/Desktop/trindade-0.5.1.apk` (mismo sha256); **ningún `adb` ejecutado en todo el ciclo**
- [x] T6 → S8 — `ed60fc7` + `9fb5cac` en `main`, sin `Co-Authored-By`, push a `origin main` (`6ae4e37..9fb5cac`)
- [x] T7 → S9 — `docker container prune` + `docker builder prune` (0B recuperables: los contenedores se autodestruyen con `--rm`) y `df -h`: `/` 64G/96G (67%), 32G disponibles

## Log

L1 (usuario, verbatim):

> ### 1. Requerimiento: Eliminar el texto/badge de "Modo solo lectura"
> El usuario determinó que el badge "Modo Leitura" / "Modo Lectura" no es relevante y debe eliminarse definitivamente para dejar el diseño totalmente limpio y despejado.
>
> ### 2. Modificación en `MassasLaunchScreen.kt`
> En `packages/android/app/src/main/java/com/trindade/app/massas/MassasLaunchScreen.kt`:
> - Eliminar por completo el badge `massas_read_only_badge` y el `Row` secundario que lo envolvía.
> - Dejar la cabecera limpia y directa: [código de S1]
>
> ### 3. Bump de VersionCode a 506
> 1. En `packages/android/app/build.gradle.kts`:
>    Actualizar la línea de fallback:
>    `val effectiveVersionCode = declaredVersionCode ?: 506`
> 2. En la compilación Release, pasar `-PversionCode=506`.
> 3. Actualizar `CHANGELOG.md` y `docs/release-android.md` reflejando el versionCode 506.
>
> ### 4. Ciclo de Verificación y Compilación
> 1. Ejecutar las pruebas unitarias en Docker SDK 35:
>    `./gradlew testDebugUnitTest --no-daemon`
>    Asegurar que pasen al 100%.
> 2. Compilar el APK Release oficial con el keystore de producción: [comando docker run con `-PversionCode=506`]
> 3. Verificar badging con `aapt2` (`versionCode='506'`, `versionName='0.5.1'`) y firma digital v2 con `apksigner`.
>
> ### 5. Publicación, Entrega y Limpieza
> 1. Reemplazar los assets en GitHub Release `v0.5.1` con `gh release upload v0.5.1 ... --clobber` (APK, sha256).
> 2. Copiar el APK firmado a `/home/wilkin/Desktop/trindade-0.5.1.apk`.
> 3. **REGLA DE ORO**: NO INSTALAR EL APK EN EL EMULADOR VÍA ADB.
> 4. Commit convencional directo en `main` sin ninguna mención a IA (`Co-Authored-By` estrictamente prohibido), y push a `origin main`.
> 5. Ejecutar purga de Docker y reportar `df -h`.

L2 (orquestador, evidencia de exploración): `massas_read_only_badge` sólo se consume en `MassasLaunchScreen.kt:146`; `LocaleStringsParityTest` compara conjuntos de claves sin conteos fijos, por lo que retirar la cadena de `values/strings.xml` y `values-es/strings.xml` en el mismo cambio preserva la paridad pt/es.

L3 (orquestador, evidencia de T1+T2): `MassasLaunchScreen.kt` ya no referencia `R.string.massas_read_only_badge` ni `labelSmall`; la cabecera quedó idéntica a S1 (botón `report_back`, `titleLarge` bold con elipse, subtítulo único con elipse). La cadena se retiró de `values/strings.xml` y `values-es/strings.xml`. `background` y `RoundedCornerShape` siguen importados porque se usan en otras tarjetas del mismo archivo (líneas ~283 y ~555), así que no hay imports huérfanos. Fallback de `versionCode` en `506`, y `CHANGELOG.md` / `docs/release-android.md` actualizados.

L4 (orquestador, verificación estructural de S2): `git diff --stat` reporta 6 archivos, 15 inserciones y 37 deleciones; no quedan referencias a `massas_read_only_badge` en `src/`.

L5 (orquestador, evidencia de S4): `apksigner verify --verbose` → `Verifies`, `v2 scheme: true`, un único firmante `CN=Trindade Massas Operacoes`. `aapt2 dump badging` → `versionCode='506' versionName='0.5.1'`. `aapt2 dump resources | grep -c massas_read_only_badge` → `0`: la cadena eliminada tampoco viaja en el binario.

L6 (orquestador, incidente menor): el primer `gh release upload` falló con `fatal: not a git repository` porque se ejecutó tras `cd /tmp`; se repitió desde la raíz del repositorio y completó con `--clobber`. Ninguna otra consecuencia.

L7 (orquestador, cierre): assets del release verificados por `gh release view --json assets` — `app-release.apk` y `trindade-0.5.1.apk` con `sha256:0d0912a9...c3a670b`, igual al APK local y al de Desktop. Tarea cerrada sin pasos pendientes.
