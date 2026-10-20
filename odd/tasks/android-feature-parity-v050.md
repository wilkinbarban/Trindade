# Paridad Total y Release v0.5.0 en Android Nativo

Llevar a la aplicación Android nativa (`packages/android`) las mejoras de paridad con Web: selector de día en Calculadora Assaí, estandarización de inputs WhatsApp con máscara `(11) 9XXXX-XXXX`, módulo nativo Compartir (`ShareAppScreen`), y compilación de APK release firmado con actualización en GitHub Release v0.5.0.

## Specs

S1. «FASE 1: Días de la semana en Calculadora Assaí (`packages/android`)
- En `AssaiOrder.kt`: actualizar `assaiMessage(lines: List<AssaiLine>, day: String? = null): String` para soportar encabezado `🛒 Pedido Assaí <Día>` (o estándar si es nulo).
- En `AssaiViewModel.kt`: agregar estado `selectedDay: String?` y callback `onDaySelect`.
- En `AssaiCalculatorScreen.kt`: agregar selector visual de día (Terça-feira, Quinta-feira, Sexta-feira, Sábado) con toggle/deselección.
- Tests unitarios en `AssaiOrderTest.kt`.»

S2. «FASE 2: Estandarización de inputs de WhatsApp en Android
- En `PhoneInput.kt`: agregar funciones puras `formatPhoneMask(raw: String): String` y `toWhatsAppNumber(phone: String): String`.
- En `AssaiCalculatorScreen.kt`, `StockCalculatorScreen.kt` y `StockHistoryScreen.kt`:
  - `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)`.
  - Placeholder `(11) 9XXXX-XXXX`.
  - Aplicar `formatPhoneMask` en `onValueChange`.
  - En la apertura de WhatsApp (`waMeLink` / Intent), asegurar que el número se normalice con `toWhatsAppNumber`.
- Tests unitarios en `PhoneInputTest.kt` y `WhatsAppLinkTest.kt`.»

S3. «FASE 3: Módulo Compartir en Android (`ShareAppScreen`)
- Crear `ShareAppScreen.kt` y `ShareAppViewModel.kt` en `packages/android/app/src/main/java/com/trindade/app/share/`:
  - Opciones de plataforma: Android (APK), iPhone (Web), Ambos.
  - Selector de usuarios desde `UsersRepository`.
  - Teléfono destino con máscara.
  - Credenciales solo para Administrador: usuario solo lectura, contraseña editable con valor inicial `123456789`.
  - Disparador de WhatsApp (`Intent(Intent.ACTION_VIEW, Uri.parse(link))`).
  - Integrar en `MainActivity.kt` (nuevo Tab o EntryPoint respetando `RolePolicy`).
- Tests en compose / unitarios correspondientes.»

S4. «FASE 4: Compilación Release, sustitución en Release v0.5.0 y Cierre
- Compilar el APK release firmado utilizando el contenedor de Docker `ghcr.io/cirruslabs/android-sdk:35` montando `/home/wilkin/.android-keystores:/keystores:ro` con las credenciales oficiales (`trindade-release.jks`, alias `trindade`, pass `SenhaTrindade123`).
- Subir y reemplazar el APK oficial en GitHub Release v0.5.0:
  `gh release upload v0.5.0 <ruta-del-apk> --clobber --repo wilkinbarban/Trindade`
- Documentar las novedades en la release y en `docs/`.
- Commits atómicos en `main` (<= 400 líneas, cero Co-Authored-By), `git push origin main`.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Fase 1: Días de la semana en AssaiOrder, AssaiViewModel, AssaiCalculatorScreen y tests | S1 | completada | 5556511 (99 insertions, tests passing) |
| T2 | Fase 2: Estandarización de inputs de teléfono WhatsApp en PhoneInput, Assai, Stock y tests | S2 | completada | 7cca21c (94 insertions, tests passing) |
| T3 | Fase 3: Módulo Compartir nativo en Android (ShareAppScreen, ViewModel, integración MainActivity y tests) | S3 | pendiente | - |
| T4 | Fase 4: Compilación Release firmada, upload a Release v0.5.0, documentación y verificación final | S4 | pendiente | - |

## Log

L1 (verbatim, 2026-10-20): «OBJETIVO:
Llevar a la aplicación Android nativa todas las mejoras recién entregadas en Web:
1. Días de la semana en la Calculadora Assaí.
2. Estandarización de inputs de teléfono WhatsApp (teclado telefónico, máscara `(11) 9XXXX-XXXX` y normalización internacional `55`).
3. Módulo Compartir nativo en Android (plataformas, usuarios, credenciales editables para admin, teléfono y envío a WhatsApp).
4. Compilación del APK de Release firmado con el keystore oficial, reemplazo del asset en GitHub Release `v0.5.0` (`gh release upload v0.5.0 ... --clobber`) y actualización documental.

DETALLES DE IMPLEMENTACIÓN POR FASES (ODD):

FASE 1: Días de la semana en Calculadora Assaí (`packages/android`)
- En `AssaiOrder.kt`: actualizar `assaiMessage(lines: List<AssaiLine>, day: String? = null): String` para soportar encabezado `🛒 Pedido Assaí <Día>` (o estándar si es nulo).
- En `AssaiViewModel.kt`: agregar estado `selectedDay: String?` y callback `onDaySelect`.
- En `AssaiCalculatorScreen.kt`: agregar selector visual de día (Terça-feira, Quinta-feira, Sexta-feira, Sábado) con toggle/deselección.
- Tests unitarios en `AssaiOrderTest.kt`.

FASE 2: Estandarización de inputs de WhatsApp en Android
- En `PhoneInput.kt`: agregar funciones puras `formatPhoneMask(raw: String): String` y `toWhatsAppNumber(phone: String): String`.
- En `AssaiCalculatorScreen.kt`, `StockCalculatorScreen.kt` y `StockHistoryScreen.kt`:
  - `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)`.
  - Placeholder `(11) 9XXXX-XXXX`.
  - Aplicar `formatPhoneMask` en `onValueChange`.
  - En la apertura de WhatsApp (`waMeLink` / Intent), asegurar que el número se normalice con `toWhatsAppNumber`.
- Tests unitarios en `PhoneInputTest.kt` y `WhatsAppLinkTest.kt`.

FASE 3: Módulo Compartir en Android (`ShareAppScreen`)
- Crear `ShareAppScreen.kt` y `ShareAppViewModel.kt` en `packages/android/app/src/main/java/com/trindade/app/share/`:
  - Opciones de plataforma: Android (APK), iPhone (Web), Ambos.
  - Selector de usuarios desde `UsersRepository`.
  - Teléfono destino con máscara.
  - Credenciales solo para Administrador: usuario solo lectura, contraseña editable con valor inicial `123456789`.
  - Disparador de WhatsApp (`Intent(Intent.ACTION_VIEW, Uri.parse(link))`).
  - Integrar en `MainActivity.kt` (nuevo Tab o EntryPoint respetando `RolePolicy`).
- Tests en compose / unitarios correspondientes.

FASE 4: Compilación Release, sustitución en Release v0.5.0 y Cierre
- Compilar el APK release firmado utilizando el contenedor de Docker `ghcr.io/cirruslabs/android-sdk:35` montando `/home/wilkin/.android-keystores:/keystores:ro` con las credenciales oficiales (`trindade-release.jks`, alias `trindade`, pass `SenhaTrindade123`).
- Subir y reemplazar el APK oficial en GitHub Release v0.5.0:
  `gh release upload v0.5.0 <ruta-del-apk> --clobber --repo wilkinbarban/Trindade`
- Documentar las novedades en la release y en `docs/`.
- Commits atómicos en `main` (<= 400 líneas, cero Co-Authored-By), `git push origin main`.

Procedé con la disciplina ODD paso a paso.»
