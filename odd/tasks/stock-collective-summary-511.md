# Stock calculator: no product code in the message, and the day's collective summary

Feature: build 511. Two changes to the Calculador de Estoque on both platforms, plus the release.
Risk: item 3 (the WhatsApp/e-mail text is a contract the plant already reads) and item 5 (release).

## Specs

S1. The stock message the operator shares drops the product code on both platforms, in the WhatsApp
text and in the e-mail body alike. Verbatim:

> 1. En el módulo Calculador de Estoque (Stock Calculator), tanto en el mensaje enviado por WhatsApp
> como en el cuerpo de correo E-mail:
>    - Eliminar el código del producto (product.code) para una vista limpia.
>    - Paridad total en ambas aplicaciones (Web y Android).
>    - Actualizar sus respectivos tests unitarios.

S2. A section below the calculator, named exactly `Resumo Coletivo do Dia`, with the totals of every
counted product, the visual design of the massas summary (amber cards: name, un/cx, boxes and units
badges), and no operator filter. Verbatim:

> 2. En el mismo módulo (Calculador de Estoque), crear una sección debajo de la calculadora llamada
> 'Resumo Coletivo do Dia':
>    - Muestra los totales de cada producto contado (boxes y units).
>    - Mismo diseño visual que el 'Resumo Coletivo do Dia' del módulo Lançamento de Massas (tarjetas
>      ámbar con nombre, un/cx, badges de cajas y unidades), pero SIN el filtrado/selector por operador.
>    - Paridad total en Web (StockCalculatorPage.tsx) y Android (StockCalculatorScreen.kt) con soporte
>      i18n completo (pt-BR y es).

S3. Android release 511. Verbatim:

> 3. Release Android:
>    - Incrementar versionCode a 511 en packages/android/app/build.gradle.kts (versionName = "0.5.1").
>    - Actualizar CHANGELOG.md y docs/release-android.md.

S4. Gates. Verbatim:

> 4. Verificación y Gates:
>    - Backend unit tests (npm --prefix packages/backend test).
>    - Frontend unit tests (npm --prefix packages/frontend test).
>    - Android unit tests (./gradlew testDebugUnitTest).

S5. APK 511 compiled in the SDK 35 container, signature v2 verified, `trindade-0.5.1.apk.sha256`
generated, the binary copied to `/home/wilkin/Desktop/trindade-0.5.1.apk`, and the three assets of
GitHub Release `v0.5.1` replaced with `--clobber`.

S6. Production rebuilt and healthy on ports 3005 and 8085.

S7. Immutable rules: trunk-based on `main`, conventional commits with no `Co-Authored-By` and no AI
mention, and absolutely no `adb install` on the Ubuntu emulator.

## Tasks

| ID  | Spec | Route | Work unit commit |
| --- | ---- | ----- | ---------------- |
| T1  | S1   | inline | `feat(stock): drop the product code from the stock message` |
| T2  | S2   | inline | `feat(stock): show the day's collective summary below the calculator` |
| T3  | S3   | inline | `chore(release): bump the android build to 511` |
| T4  | S4   | inline | (gate, no commit of its own) |
| T5  | S5   | inline | (distribution) |
| T6  | S6   | inline | (deployment) |

## Log

L1 — the user's request, verbatim:

> DIRECTIVA DE IMPLEMENTACIÓN Y RELEASE (BUILD 511)
> [...as quoted in Specs S1-S7...]
> Comienza de inmediato ejecutando y reportando cada fase.

L2 — decision: the summary is derived from the day the API already returns. `StockDay.products`
arrives with the per-product totals the server computed (`boxes`, `units`, `unitsPerBox`), so neither
platform multiplies anything: the section filters what was counted and draws it. That keeps the two
numbers of a count agreeing with the server, which is the rule the module already follows.

L3 — plan change, recorded rather than silently applied: S1 and S2 landed as two commits as
planned, but their tests could not be split by file, because the web and Android suites keep the
message and the summary assertions in the same test files. The message hunks were staged alone for
`feat(stock): drop the product code from the stock message`, and the summary hunks followed in
`feat(stock): show the day's collective summary below the calculator`; each commit compiles on its
own.

L4 — gate evidence and one unplanned fix. The Android gate failed twice, both times on
`AdminApiPayloadTest > updateTask transports numeric is_active 1 JsonObject on wire` with a
`TestTimedOutException` on its own `@Test(timeout = 5000L)`; the class passes alone in milliseconds
and the failing run recorded 8.2 s for that test against 2.4 s for its sibling. The timeout guards a
hang, not latency, so it became a named 30 s guard in `test(android): give the wire payload test room
to answer`. 974 Android tests, 520 backend tests and 149 frontend tests then ran green, and the
release APK reports `versionCode='511'`.
