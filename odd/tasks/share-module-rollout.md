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

S6. «Credenciales exclusivas para Administrador y en Solo Lectura:
   - La sección 3 (Credenciales) SOLO debe renderizarse si currentUser?.role === 'Administrador'. Si el rol es Trabalhador, la sección NO se muestra ni se adjuntan credenciales en su mensaje.
   - Para el Administrador: al elegir un usuario de la lista, los campos de nombre y usuario deben mostrarse en modo SOLO LECTURA (readOnly), sin edición arbitraria. La contraseña se mantiene fija en solo lectura ('123456789').»

S7. «Envío del APK físico (el archivo mismo, no el link de GitHub):
   - Backend: Crear endpoint GET /api/app/download-apk que sirva la descarga directa del APK binario de la release con Content-Disposition: attachment; filename="trindade.apk" y Content-Type: application/vnd.android.package-archive. Actualizar contratos OpenAPI y tests.
   - Frontend: La URL en el mensaje y fallback apuntará a /api/app/download-apk (dominio propio, nunca GitHub). Para Android, implementar la acción de compartir archivo: descargar el blob y usar navigator.share({ files: [apkFile], text: message }) si navigator.canShare soporta archivos, enviando el APK directamente por WhatsApp. Si el navegador no soporta compartir archivos, descargar el APK al equipo y ofrecer abrir WhatsApp con aviso de adjuntar el archivo.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Backend endpoint GET /api/app/latest-release con caché en memoria (15 min), fallback resiliente, OpenAPI y tests unitarios | S1, S5 | hecha | `574b0d9` (354 líneas); tests unitarios 4/4; backend 494/494 verde; openapi y contratos actualizados |
| T2 | Componente ShareAppPage.tsx mobile-first con selector de plataforma, selector de usuario, live preview, waMeLink, shareOrCopy y código QR | S1, S2, S3, S4, S5 | hecha | `82dadb2` (deps qrcode), `48c6738` (share-app lib + tests 9/9), `6b1ccc7` (ShareAppPage.tsx 393 líneas); typecheck y suite frontend 100/100 verdes |
| T3 | Integración en AppShell.tsx, registro de ruta /compartir en App.tsx, traducciones i18n (pt-BR y es) y build de producción | S1, S3, S4, S5 | hecha | `ae55e60` (67 líneas); typecheck limpio, 494 tests backend + 100 tests frontend verdes, build de producción Vite exitoso (`dist/assets/ShareAppPage-*.js`) |
| T4 | Backend: Endpoint GET /api/app/download-apk para descarga directa de APK binario con headers de attachment, OpenAPI 3.1 y tests unitarios | S7 | hecha | `cc98f46` (198 líneas); headers Content-Type y Content-Disposition; openapi.json sincronizado; tests y route coverage verdes |
| T5 | Frontend: Credenciales exclusivas y readOnly para Administrador, URL de descarga a /api/app/download-apk, y compartir archivo APK con navigator.share/fallback | S6, S7 | hecha | `f3018d1` (300 líneas); credenciales ocultas para Trabalhador, solo lectura para Admin (password fija 123456789); descarga y compartir de archivo APK; tests 100/100 verdes |

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

L2 (verbatim, 2026-10-20): «Objetivo: Mejorar el módulo Compartir con 2 requerimientos obligatorios bajo ODD atómico:
1. Credenciales exclusivas para Administrador y en Solo Lectura:
   - La sección 3 (Credenciales) SOLO debe renderizarse si currentUser?.role === 'Administrador'. Si el rol es Trabalhador, la sección NO se muestra ni se adjuntan credenciales en su mensaje.
   - Para el Administrador: al elegir un usuario de la lista, los campos de nombre y usuario deben mostrarse en modo SOLO LECTURA (readOnly), sin edición arbitraria. La contraseña se mantiene fija en solo lectura ('123456789').
2. Envío del APK físico (el archivo mismo, no el link de GitHub):
   - Backend: Crear endpoint GET /api/app/download-apk que sirva la descarga directa del APK binario de la release con Content-Disposition: attachment; filename="trindade.apk" y Content-Type: application/vnd.android.package-archive. Actualizar contratos OpenAPI y tests.
   - Frontend: La URL en el mensaje y fallback apuntará a /api/app/download-apk (dominio propio, nunca GitHub). Para Android, implementar la acción de compartir archivo: descargar el blob y usar navigator.share({ files: [apkFile], text: message }) si navigator.canShare soporta archivos, enviando el APK directamente por WhatsApp. Si el navegador no soporta compartir archivos, descargar el APK al equipo y ofrecer abrir WhatsApp con aviso de adjuntar el archivo.
3. Actualizá odd/tasks/share-module-rollout.md con T4 y T5, ejecutá los commits atómicos en main (<= 400 líneas, cero Co-Authored-By), ejecutá los tests/typecheck, hacé git push origin main, y reconstruí/desplegá en producción con docker compose build y up -d. Avanzá continuo.»
