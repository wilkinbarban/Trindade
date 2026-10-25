# Android design remediation v513: header actions, admin dialogs, profile

Directive source: `DIRECTIVA_REMEDIACION_ANDROID_V513.md` (read-only input, not committed). State: **closed**.

## Specs

S1 (§1). In `LoadingScreen.kt`, `MassasLaunchScreen.kt` and `StockCalculatorScreen.kt` the row holding
`Histórico` and `Ajuda` is `Modifier.fillMaxWidth()` with `horizontalArrangement = Arrangement.SpaceBetween`
and `verticalAlignment = Alignment.CenterVertically`: history at the start corner, help at the end corner.
Where `showBack` is true, `Voltar` stays at the start and the row still reads cleanly.

S2 (§2). `ModuleHistoryButton` lives in `com.trindade.app.ui.components` and has the same footprint as
`ModuleHelpButton`: `height = 36.dp`, `shape = RoundedCornerShape(8.dp)`,
`contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)`,
`MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)`, a 16.dp icon, a 6.dp spacer and
the label. History: `R.drawable.ic_history`, tint `MaterialTheme.colorScheme.primary`, text
`R.string.report_history`. Help keeps `R.drawable.ic_help`, tint `Color(0xFFD97706)`, text
`R.string.help_button_label`. Both are used in every operational module that has those actions.

S3 (§3). The module description/tagline no longer shares a row with the header buttons: it is drawn below
that row, in `MaterialTheme.colorScheme.onSurfaceVariant` with `bodySmall`/`bodyMedium` and the screen's own
spacing.

S4 (§4). `MassasHistoryScreen.kt` has no `Atualizar` button; the history reloads on its own. Its top row is
`NavigationActionButton(onClick = onBack, text = report_back)` at the start and `ModuleHelpButton` at the end
(`Arrangement.SpaceBetween`), with `R.string.massas_history_title` and the days counter under/next to the back
action, clean hierarchy. `StockHistoryScreen.kt` keeps an upper header with balanced distribution, no
redundant controls and clear navigation. The Relatórios and Horários histories keep their structure.

S5 (§5). The `admin_refresh` button is gone from `AuditScreen.kt`, `CategoriesScreen.kt`, `TasksScreen.kt`,
`TimeSlotsScreen.kt`, `UsersScreen.kt`, `VehiclesScreen.kt`. Every create/edit/delete in `Users`, `Vehicles`,
`Categories`, `TimeSlots`, `Tasks` and `Drivers` first shows an `AlertDialog` describing the operation with
`Confirmar` (primary) and `Cancelar` (secondary), and then a result `AlertDialog` whose title carries ✅ or ❌
with `Sucesso`/`Erro`, a message such as `Usuário criado com sucesso` or
`Não foi possível excluir o veículo: …`, and a single `Fechar` button that closes the dialog and refreshes
the state. Both dialogs are reusable components (`AdminConfirmDialog`, `AdminResultDialog`).

S6 (§6). `res/drawable/ic_profile.xml` is a clean vector: a centred round head and a soft shoulder arc in a
24dp viewport, consistent with the other `ic_*.xml` files.

S7 (§7). `ProfileScreen.kt`: top row with `Voltar` at the start; a profile card with a circular avatar (large
user glyph or the user's initial in a tonal/primary container), the display name in
`titleLarge`/`headlineSmall` **Bold**, `@username` in `onSurfaceVariant` and a role chip
(`Administrador`/`Trabalhador`). Sections as styled cards, in this order: `Dados da Conta` (display-name field
plus `Salvar`), `Segurança` (current and new password plus `Alterar senha`), `Versão do aplicativo e
atualizações` (installed version, update-check state and the action button), and `Sair da conta` clearly
delimited at the end.

S8 (§8). `AssaiCalculatorScreen.kt` top: a `fillMaxWidth()` row with `Arrangement.SpaceBetween` carrying the
module title `Calculadora Assaí` at the start (`MaterialTheme.typography.titleMedium.copy(fontWeight =
FontWeight.Bold)`) and `ModuleHelpButton` at the end; the `assai_subtitle` is drawn underneath, integrated.

S9 (§9). `versionCode` default is **513** in `packages/android/app/build.gradle.kts` with `versionName`
`0.5.2`. The Android unit and Compose suites pass with 0 failures. Screenshots of the changed screens are
taken on Ubuntu `emulator-5554` before the final build. The signed release APK replaces
`trindade-0.5.2.apk` and `trindade-0.5.2.apk.sha256` in GitHub Release `v0.5.2` (`--clobber`), and an install
straight from the published release is checked on the emulator. Commits are conventional, direct to
`origin/main`, with no AI attribution.

S10 (§10, the `android-material3-design-system` skill rules). Every screen and dialog touched by this work
uses theme tokens only: no `Color(0xFF…)` outside `ui/theme/Theme.kt` (the `Brand*` palette is the token
source, not a magic colour), `MaterialTheme.shapes` for corners, `MaterialTheme.typography` for type. Every
interactive control keeps a touch target of at least 48dp in both directions, including the ones drawn smaller
(the header actions are a 36dp pill inside a 48dp target). Cards, dialogs and headers sit on the tonal
surfaces of the scheme (`surfaceContainer`, `surfaceContainerHigh`, `secondaryContainer`, `errorContainer`),
and `AlertDialog` keeps Material's own dialog spacing and elevation. Leaf composables stay free of business
state and side effects.

S11 (§11, whole-app scope). The same rules cover the whole of `packages/android/app/src/main/java`: Dashboard,
Relatórios, Horários/Cargas, Massas, Estoque, Assaí, Compartilhar, Login, Registro, Perfil and every Admin
screen (Usuários, Veículos, Categorias, Horários, Tarefas, Motoristas, Auditoria). No hex colour survives
outside `Theme.kt`; every `Card` uses a tonal container and the standard 12dp corner (an explicit `shape`
only when the card is a badge or a pill, which use `CircleShape`); every button and chip keeps a 48dp target.
Compilation and the full suite stay green.

**Colour role map** (the one place all four modules take their mapping from, so the app does not end up with
two greens meaning "ok"):

| Intent | Token |
| ------ | ----- |
| Success / notice banner | `secondaryContainer` background, `onSecondaryContainer` content, with a ✓ glyph |
| Error / refusal banner | `errorContainer` background, `onErrorContainer` content, `error` accent, with a ⚠ glyph |
| Informational band, description, hint | `surfaceVariant` background, `onSurfaceVariant` content, `outlineVariant` border |
| Ordinary card | `surfaceContainer` |
| Emphasised card, header band, dialog sheet | `surfaceContainerHigh` |
| Chip / badge on | `primary` background, `onPrimary` content |
| Chip / badge off | `surfaceVariant` background, `onSurfaceVariant` content |
| Manual shadow | `scrim` at the alpha it needs, or `cardElevation` |
| Border of an outlined control | `outline`, one token for the whole app |

The theme's `primaryContainer`, `tertiaryContainer` and `errorContainer` are all the same light red, so tone
alone cannot tell a success banner from a failure one: the glyph and the words carry that, and success uses
the gold `secondaryContainer` instead of a third copy of the red.


## Tasks

| ID | Specs | Route | Commit |
| -- | ----- | ----- | ------ |
| T1 | S1, S2, S3, S8 | `ModuleHistoryButton` component; header rows in Loading, Massas, Stock, Assaí; description below the row | `4b77389` |
| T2 | S4 | Massas history header without `Atualizar`; stock history header review | `71a0081` |
| T3 | S5 | `admin_refresh` removal in six screens; `AdminConfirmDialog`/`AdminResultDialog`; view-model outcome state; six screens + Drivers wired | `b958a88` |
| T4 | S6, S7 | `ic_profile.xml`; profile screen redesign in cards | `cb79d24` |
| T7 | S10, S11 | Massas module: `MassasLaunchScreen.kt`, `MassasHistoryScreen.kt` onto tokens, 12dp cards, 48dp targets | `71a0081` |
| T8 | S10, S11 | Stock and Assaí: `StockCalculatorScreen.kt`, `StockHistoryScreen.kt`, `AssaiCalculatorScreen.kt` | `f72ad25` |
| T9 | S10, S11 | Dashboard, Relatórios, Compartilhar, Horários: `DashboardScreen.kt`, `reports/*`, `share/*`, `loading/LoadingScreen.kt` | `6f6aea1` |
| T10 | S10, S11 | Auth: `LoginScreen.kt`, `RegisterScreen.kt`, `ProfileScreen.kt` (the last one also carries S7) | `6f6aea1` |
| T11 | S10, S11 | Admin: `admin/*` onto tokens, cards and 48dp targets, on top of the T3 dialogs | `a9f5d79` |
| T12 | S9, S10, S11 | Unit and Compose tests for the new behaviour; full suite | `b958a88` |
| T13 | S9 | versionCode 513, emulator screenshots, signed build, release clobber, docs and CHANGELOG | `899653a` |

## Log

L1: "Leé y ejecutá la directiva completa en DIRECTIVA_REMEDIACION_ANDROID_V513.md. Incrementá versionCode a
513, aplicá las mejoras de diseño (botones en esquinas, ModuleHistoryButton, descripciones estilizadas,
eliminación de Atualizar, diálogos de confirmación y resultado en Admin, nuevo ic_profile, cabecera de perfil,
título en Assaí), probalo en el emulador de Ubuntu con capturas, corré tests, compilá release firmado,
sustituilo en GitHub Release v0.5.2 y comiteá directo a main sin ramas."

L2: Design decisions taken while implementing.
- S1 is resolved with `ModuleHistoryButton` at the start, `Spacer(Modifier.weight(1f))` and `ModuleHelpButton` at the
  end, plus the `Voltar` action before them when the screen has a parent. Two children under `SpaceBetween` and the
  same three children under a weight spacer occupy the same pixels, so the row still reads as "history left, help
  right" and a back action does not force a second row.
- S3 moves the tagline out of the action row into its own `Text` under it, `bodySmall` + `onSurfaceVariant`; in
  read-only Massas that slot carries the production date instead of the tagline, as before.
- S5 archive of the admin dialogs: `AdminOutcome` (success flag + `UiText`) lives in the view model's state so the
  dialog survives recomposition and is testable without a screen; `AdminConfirmation` is screen-local because it is
  a pre-action with nothing to persist.
- S7 keeps the version and update card outside the "profile loaded" branch: it is the line an operator reads back
  when they ask whether an update landed, and a profile that failed to load is exactly when someone looks for it.

L3: "Se instaló la skill android-material3-design-system en .agents/skills/android-material3-design-system/.
Consultá su SKILL.md y aplicá sus lineamientos de Material 3 (tokens de tema, consistencia de controles, touch
targets >= 48dp y elevaciones tonales) al pulir el diseño de las pantallas y diálogos de Android." The skill
itself is three files of pointers to the official Material 3 docs; its operative rules are the ones the
directive wrote out in §10, and they are recorded as S10.

L4: "Directiva actualizada (sección 11): El alcance de diseño se extiende a TODA la aplicación Android. Aplicá la
skill android-material3-design-system de forma integral en todos los módulos (Dashboard, Relatórios, Horários,
Massas, Estoque, Assaí, Compartilhar, Login/Registro/Perfil y Admin completo): eliminá colores hardcodeados
reemplazándolos por tokens semánticos de MaterialTheme.colorScheme, estandarizá Card/TextFields/Buttons con
touch targets de 48dp y superficies tonales de M3 para lograr total homogeneidad visual." Recorded as S11; the
survey of the tree is what sized it: 39 hex colours outside `Theme.kt` (24 of them in `MassasLaunchScreen.kt`),
26 `Card` calls with five different corner radii, and five interactive controls pinned below 48dp.

L5: Decisions taken while applying S10/S11, all of them made to keep the two sizes Material needs separate.
- The header actions are a 36dp pill drawn by an inner `Surface` inside a 48dp box that owns the click:
  `Modifier.height(36.dp)` on a Material button shrinks the target with the visual, and `minimumInteractiveComponentSize`
  around a button paints the background at the padded size. Two nodes, two numbers, both true.
- The pill's height is a minimum (`heightIn(min = 36.dp)`), not a fixed height: at a 2.0x font scale a fixed
  36dp clips the label, which is the one thing a pill must never do.
- `ModuleHeaderAction` is private and both public buttons are thin wrappers, so history and help cannot drift.
  They carry `testTag`s (`module-history-pill`, `module-help-pill`) because the visible pill and the touch
  target are different nodes and a test has to be able to name each.
- Loading had no tagline of its own (`help_loading_desc` is a paragraph, not a line), so `loading_tagline` was
  added in both locales rather than reusing help copy in a header. Massas now shows its tagline in read-only
  days too: the date already lives in the card below, and a header that swaps kinds of sentences depending on
  the reader is a header that says nothing.
- `ModuleHelpDialog` and `ProfileScreen` were on hardcoded Tailwind greys and blues; both moved onto the
  scheme. The dialog's sheet is `surfaceContainerHigh` at elevation level 3 (6dp), its amber band is
  `secondaryContainer`, and its only action is the brand `primary` instead of the #1F2937 it had.

L6: Decisions taken while the three module passes landed (T7, T8, T9/T10) and the admin dialogs (T3).
- The 12dp card corner is now declared in `Theme.kt` as `Shapes(medium = RoundedCornerShape(12.dp))`. It was
  already Material's baseline, so this changes no pixel: it turns a default of a library into a decision of
  this project, which is what `Card(shape = RoundedCornerShape(12.dp))` was trying to say in five places.
- The help pill's amber is `HelpAmber` in the palette, not a literal in `ModuleHelpDialog`. M3 has no "aside"
  colour and the directive fixes that exact value, so it belongs with the other brand constants -- and naming
  it is what makes "a hex colour lives in Theme.kt and nowhere else" a rule a test can enforce.
  `M3TokenHygieneTest` scans `src/main/java` for `Color(0x…)` and fails outside the palette.
- `ModuleHeaderAction` draws a full-round pill, matching the `OutlinedButton` ("Voltar") it sits beside in
  every sub-screen: an 8dp rectangle next to a pill read as two button families.
- `AdminConfirmation` lost its secondary constructor and its label-based destructive heuristic. Deciding the
  red button by comparing the label against the word "Excluir" meant a caller that wrote "Excluir usuário"
  got a filled primary button on a delete; the five delete confirmations already pass `isDestructive = true`.
- The 13 administration cards were `Card()` with no colours, inheriting `surfaceContainerHighest`. They now
  take `surfaceContainer`, except the panel's section cards, which are `surfaceContainerHigh` with an
  `outlineVariant` border -- the same pair the dashboard's shortcuts use.
- Register's success title had become `"✓ " + title`, so the node stopped being the exact string the app
  declares (and a screen reader read "check mark" into the sentence). The glyph got its own node.
  `RegisterScreenTest` also expected the submit button to vanish while submitting; it now expects it
  disabled, because the worker aligned register with login, which already keeps the label beside its spinner.
  The asymmetry was the defect, not the alignment.

L7: Left out on purpose. Portuguese literals that never switch language are still in the tree
(`"Nenhum motorista disponível."`, `"Salvar fletero"`, `"Nenhum histórico de massas encontrado."`,
`"caixas"`, `"produtos lançados"`, `"un/cx"`, `"cx"`, `"un"`). They are older than this directive and fixing
them means new keys in two locales for copy nobody asked to change; they are reported, not touched.

L8: Visual verification on `emulator-5554`, over the *published* binary. The first capture run stopped on the
mandatory update dialog and that was the app being right: `UpdateDetector` compares the release tag with the
installed versionName and, on a tie, the published APK's digest with the installed one. A freshly built 513
next to a published 512 is exactly `SAME_RELEASE_PATCH`, and that state blocks every screen behind the notice.
So the order had to invert: publish first, then photograph what is published. A capture script that met the
dialog earlier would have produced thirteen pictures of a modal and a report saying the design was fine.

L9: The dialog body read "Higiene e Organização será desativado." The string was fixed in the masculine while
the subject changes per section -- *categoria* and *tarefa* are feminine, *veículo* and *usuário* are not. Both
locales moved to phrasing with no participle to agree: "ficará indisponível para uso" / "voltará a ficar
disponível" and "quedará no disponible" / "volverá a estar disponible". Found by looking, not by testing: the
five admin screen tests assert the body through `copy(R.string.…, name)`, so they passed both before and after.

L10: Delivered as nine commits on `main` (`4b77389` … `899653a`), pushed to `origin/main`. The working tree at
`899653a` is the tree the signed APK came from; the intermediate commits were ordered so that each one
compiles (shared tokens and components first, the hygiene test last), but only the final tree was built and
tested as a whole. 1010 tests, 0 failures.

L11: Reported and not changed, because it is product, not design. Profile states "Você já está na versão mais
recente" and keeps a filled primary "Baixar nova versão" right below it; and Profile is the only screen that
opens with just "Voltar", no title in the bar, because the identity card is doing that job.
