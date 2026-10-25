# Android remediation v514: profile header, temperature sign, time slot mask, read-only history

Directive source: `DIRECTIVA_REMEDIACION_ANDROID_V514.md` (read-only input, not committed). State: **done** — released as `versionCode 514` on GitHub Release `v0.5.2` and merged on `main`.

## Specs

S1 (§1 Perfil). "Añadir el título `stringResource(R.string.profile_title)` ("Perfil") junto al botón
`NavigationActionButton(onClick = onBack, text = stringResource(R.string.report_back))` en la parte superior,
alineado con el patrón de cabeceras secundarias del resto de la aplicación."

S2 (§1 Perfil). "Cuando `state.update` sea `ProfileViewModel.UpdateStatus.UpToDate`, mostrar únicamente el
mensaje de estado pacífico ("Você já está na versão mais recente") y el botón outlined para volver a
verificar (`OutlinedButton(onClick = onCheckForUpdates)`). **NO** mostrar el botón primario relleno
`AdminPrimaryButton` ("Baixar nova versão") cuando la app ya está actualizada. El botón primario "Baixar nova
versão" (`R.string.profile_download_update`) o "Atualizar Agora" SOLO debe mostrarse cuando efectivamente haya
una actualización disponible (`ProfileViewModel.UpdateStatus.Available`), o cuando esté en estado
`DownloadState.Completed` / `Error`."

S3 (§2 Relatórios). "El signo `-` solo puede existir al principio (índice 0). Si el operador escribe `-` al
final o en cualquier posición intermedia (ej: `4.5-` o `4-5`), el signo debe moverse automáticamente al frente
(`-4.5`), o sanearse de modo que nunca quede trailing. No permitir más de un signo `-`. No permitir más de un
separador decimal (`.` o `,`). Solo permitir caracteres numéricos `[0-9]`, `,`, `.`, y `-` inicial."

S4 (§2 Relatórios). "Asegurar que `toggledSign(value)` respete la misma regla: si empieza con `-`, se quita;
si no empieza con `-`, se limpia cualquier `-` y se antepone al inicio: `"-${value.replace("-", "")}"`."

S5 (§3 Horário de Carga). "Configurar `KeyboardOptions(keyboardType = KeyboardType.Number)`. Aplicar
auto-formateo o máscara para `HH:mm` a medida que se ingresan los dígitos (hasta 4 dígitos numéricos,
insertando `:` automáticamente entre hora y minuto: ej. "14" -> "14:", "1430" -> "14:30"). Incluir un
botón/ícono de reloj en el campo (`trailingIcon`) para abrir un selector de hora nativo ... Al seleccionar la
hora en el selector, poblar el campo con el formato normalizado `HH:mm`. Mantener la validación existente en
`TimeSlotsAdminViewModel` (`isValidTimeSlot`)."

S6 (§4 Selector de fecha). "Establecer `maxDate = System.currentTimeMillis()` (hoy). Establecer
`minDate = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)` (30 días en el pasado). Permitir al
usuario tocar el campo o el botón para abrir el calendario. Mantener los chips/botones horizontales de días
registrados (`state.days`) para selección rápida con 1 tap."

S7 (§4 Solo lectura). "Eliminar completamente los campos de entrada de calculadora (campos de texto para
editar cantidades de cajas, botones "Adicionar" y "Zerar"). Mostrar la información del día seleccionado como
**"Resumo colectivo do dia"** (`StockCollectiveSummary` / lista de productos contados con cajas, unidades,
un/cx y totales colectivos), con idéntico diseño y estructura visual que el resumen del módulo padre
(`StockCalculatorScreen`). Mostrar el total general del día (total de cajas y unidades). Mantener los botones
de compartir por WhatsApp y Email para ese resumen histórico."

S8 (§5 Material 3). "Usar tokens de `MaterialTheme.colorScheme` (`surfaceContainer`, `surfaceContainerHigh`,
`secondaryContainer`, `outlineVariant`). Cero colores hexadecimales `Color(0x...)` fuera de `Theme.kt`. Touch
target mínimo de 48.dp para todos los elementos clickeables."

S9 (§6 Entrega). "Actualizar `versionCode` a **514** (`versionName = 0.5.2`)". "Los tests deben pasar al 100%
(incluyendo `M3TokenHygieneTest`)". Capturas en `emulator-5554` de Perfil, Relatório (temperatura), Admin
Horarios y Histórico de Contagem. Release firmado verificado y assets de `v0.5.2` reemplazados. "Commits
atómicos convencionales directamente en `main` sin ramas intermedias. NUNCA incluir `Co-Authored-By` ni
menciones a IA en commits o pull requests."

## Tasks

| ID | Specs | Route | Commit |
| -- | ----- | ----- | ------ |
| T1 | S1, S2 | `ProfileScreen.kt`: title beside Voltar; primary download action only on `Available` / `Completed` / `Error` | c797fa8, 3bcbb24 |
| T2 | S3, S4 | Pure `reports/TemperatureInput.kt` (`sanitizeTemperatureInput`, `toggledSign`) + `ReportForm.kt` wiring + unit tests | 76b0a06 |
| T3 | S5 | Pure `admin/TimeSlotMask.kt` + `TimeSlotsAdminScreen.kt` numeric keyboard, mask and clock picker + tests | e489278 |
| T4 | S6 | `StockHistoryScreen.kt` date picker bounded to the last 30 days | 18dd931 |
| T5 | S7 | `StockHistoryScreen.kt` read-only: calculator gone, `StockCollectiveSummary` + totals, sharing kept; dead view-model write path and its tests removed | 18dd931 |
| T6 | S8 | Token and 48dp audit of the touched screens; `M3TokenHygieneTest` stays green | 76b0a06, e489278, 18dd931 |
| T7 | S9 | versionCode 514, CHANGELOG, full suite in the SDK 35 container | 0d0dbb8 |
| T8 | S9 | Signed release, `apksigner` check, emulator captures, release asset replacement, push to `main` | this record |

## Log

L1: "Leé y ejecutá la directiva completa en DIRECTIVA_REMEDIACION_ANDROID_V514.md. Incrementá versionCode a
514, aplicá las mejoras (Perfil con título y sin botón Baixar versión cuando está al día, temperatura con "-"
solo al inicio, horario con TimePicker y máscara HH:mm, Histórico de Contagem con date picker hasta 30 días y
solo lectura de Resumo colectivo). Usá la Skill android-material3-design-system. Verificá en el emulador de
Ubuntu, corré tests (100% verde), compilá release firmado, publicá en GitHub Releases v0.5.2 sustituyendo los
assets, y hacé push directo a main sin ramas ni atribución a IA."

L2: The suite first ran the four new classes alone (50 tests) and the temperature test caught a defect in my
own `toggledSign`: stripping only the leading sign left an inner `-` in place (`-4-5` came back as `4-5`). The
function now removes every `-` before applying the sign it read, and `4-5` → `-45`, `-4-5` → `45` are pinned.
The whole suite then ran in the container: 112 classes, 1029 tests, 0 failures, 0 skipped.

L3: The release lane built `versionCode=514` with the real keystore and the production base URL: `apksigner`
Verifies under APK Signature Scheme v2 (v1 and v3 absent, one signer), `aapt2` reports 514 / 0.5.2, no unsigned
APK beside it. The first published build (sha256 `35127b8c…430a`) was clobbered over the 513 assets and
installed on the emulator from the published download, which is the ordering `UpdateDetector` forces: the app
blocks every screen while the installed binary differs from the published one.

L4: The emulator run on the published build showed each rule on device: `Voltar` + `Perfil` in the header;
`Você já está na versão mais recente.` with only the outlined `Verificar atualizações`; `4.5-` settling as
`-4.5` and `±` giving `4.5`; `1430` becoming `14:30` with the clock opening the platform picker seeded at
`14:30`; the history drawing `Resumo Coletivo do Dia` with `2284 caixas · 31357 unidades` and no calculator;
and the calendar refusing 1–23 September (before the 30-day floor at 24 September) and 25–31 October (after
today).

L5: The captures showed one leftover the specs did not name: with the app up to date the install hint ("Na
primeira vez o telefone pergunta…") stayed under the version card with no button to explain. Since S2 asks for
"únicamente el mensaje de estado pacífico y el botón outlined", the hint now follows the download action it
describes (`3bcbb24`), the profile test asserts its absence when up to date, and the release was rebuilt
(sha256 `f855dcff…3f6e`), re-clobbered and re-installed on the emulator from the published download, which
matches the published `.sha256` this time.
