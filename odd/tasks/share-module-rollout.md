# Módulo Compartir (/compartir) en Trindade: Accesos y Credenciales

Implementación del módulo "Compartir" para distribución ágil de accesos de Trindade (Android APK, iPhone/Web o Híbrido) vía WhatsApp con credenciales opcionales, enlace directo waMeLink, QR en pantalla y copia rápida.

## Specs

S1. «Función principal: Compartir por WhatsApp los accesos a Trindade:
   - Opción Android: Enlace de descarga directa del último APK de la última release de GitHub (trindade-0.5.0.apk).
   - Opción iPhone / Web: Enlace oficial a la aplicación web (https://trindademasas.duckdns.org).
   - Opción Ambos (Híbrido): Incluye ambos accesos en el mensaje.»

S2. «Credenciales en el mensaje:
   - Selector para elegir uno de los usuarios registrados en el sistema (autocompleta display_name y username).
   - Campo de contraseña editable (pre-cargado por defecto con 123456789) y switch/toggle para incluir o no credenciales.»

S3. «Envío por WhatsApp sin Evolution API:
   - Input de celular con formato (DDD) 9XXXX-XXXX.
   - Enlace canónico de WhatsApp usando packages/frontend/src/lib/whatsapp.ts (waMeLink) para abrir chat directo.
   - Botón de copiar mensaje con shareOrCopy y código QR en pantalla para escaneo directo desde celulares.»

S4. «Diseño Mobile-First:
   - Cuidar meticulosamente la vista en celulares: layout apilable verticalmente, tarjetas responsive, botones táctiles grandes, sin desbordes horizontales.»

S5. «Plan de Entrega ODD (Commits atómicos directos en main <= 400 líneas brutas, sin ramas ni Co-Authored-By):
- Slice 1: Backend endpoint GET /api/app/latest-release con caché en memoria (15 min) y tests unitarios.
- Slice 2: Componente packages/frontend/src/pages/ShareAppPage.tsx con diseño mobile-first, selector de plataforma, selector de usuario, live preview y waMeLink.
- Slice 3: Integración en AppShell.tsx, registro de ruta en App.tsx, i18n (pt-BR y es) y build de producción.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Backend endpoint GET /api/app/latest-release con caché en memoria (15 min), fallback resiliente, OpenAPI y tests unitarios | S1, S5 | hecha | `574b0d9` (354 líneas); tests unitarios 4/4; backend 494/494 verde; openapi y contratos actualizados |
| T2 | Componente ShareAppPage.tsx mobile-first con selector de plataforma, selector de usuario, live preview, waMeLink, shareOrCopy y código QR | S1, S2, S3, S4, S5 | hecha | `82dadb2` (deps qrcode), `48c6738` (share-app lib + tests 9/9), `6b1ccc7` (ShareAppPage.tsx 393 líneas); typecheck y suite frontend 100/100 verdes |
| T3 | Integración en AppShell.tsx, registro de ruta /compartir en App.tsx, traducciones i18n (pt-BR y es) y build de producción | S1, S3, S4, S5 | hecha | `ae55e60` (67 líneas); typecheck limpio, 494 tests backend + 100 tests frontend verdes, build de producción Vite exitoso (`dist/assets/ShareAppPage-*.js`) |

## Log

L1 (verbatim, 2026-10-20): «Objetivo: Implementar el módulo "Compartir" (/compartir) en Trindade bajo disciplina ODD y TDD estricto.

Requisitos del Módulo:
1. Función principal: Compartir por WhatsApp los accesos a Trindade:
   - Opción Android: Enlace de descarga directa del último APK de la última release de GitHub (trindade-0.5.0.apk).
   - Opción iPhone / Web: Enlace oficial a la aplicación web (https://trindademasas.duckdns.org).
   - Opción Ambos (Híbrido): Incluye ambos accesos en el mensaje.
2. Credenciales en el mensaje:
   - Selector para elegir uno de los usuarios registrados en el sistema (autocompleta display_name y username).
   - Campo de contraseña editable (pre-cargado por defecto con 123456789) y switch/toggle para incluir o no credenciales.
3. Envío por WhatsApp sin Evolution API:
   - Input de celular con formato (DDD) 9XXXX-XXXX.
   - Enlace canónico de WhatsApp usando packages/frontend/src/lib/whatsapp.ts (waMeLink) para abrir chat directo.
   - Botón de copiar mensaje con shareOrCopy y código QR en pantalla para escaneo directo desde celulares.
4. Diseño Mobile-First:
   - Cuidar meticulosamente la vista en celulares: layout apilable verticalmente, tarjetas responsive, botones táctiles grandes, sin desbordes horizontales.

Plan de Entrega ODD (Commits atómicos directos en main <= 400 líneas brutas, sin ramas ni Co-Authored-By):
- Slice 1: Backend endpoint GET /api/app/latest-release con caché en memoria (15 min) y tests unitarios.
- Slice 2: Componente packages/frontend/src/pages/ShareAppPage.tsx con diseño mobile-first, selector de plataforma, selector de usuario, live preview y waMeLink.
- Slice 3: Integración en AppShell.tsx, registro de ruta en App.tsx, i18n (pt-BR y es) y build de producción.
Arranca con el artefacto ODD en odd/tasks/share-module-rollout.md y ejecuta el Slice 1.»
