# Estandarización de Máscara Telefónica en Trindade Web

Estandarizar el comportamiento y la máscara de entrada telefónica en todos los campos de la aplicación web donde se ingresa un número de WhatsApp para enviar reportes (`AssaiCalculatorPage`, `StockCalculatorPage`, `StockHistoryPage`), replicando el comportamiento de `ShareAppPage`.

## Specs

S1. «Centralizar o exportar limpiamente `formatPhoneMask` y `toWhatsAppNumber` en `packages/frontend/src/lib/phone.ts` (manteniendo retrocompatibilidad con `lib/share-app.ts`).»

S2. «En cada uno de los 3 inputs (`AssaiCalculatorPage`, `StockCalculatorPage`, `StockHistoryPage`):
   - Configurar `type="tel"`, `inputMode="tel"`, `autoComplete="tel"`.
   - Placeholder: `(11) 9XXXX-XXXX` (o clave i18n correspondiente).
   - Formatear en vivo en `onChange`: `onChange={(e) => setFreeNumber(formatPhoneMask(e.target.value))}`.
   - En el envío a WhatsApp (`waMeLink`): usar `toWhatsAppNumber(targetNumber)` para asegurar que números con DDD brasileño (10 u 11 dígitos) viajen con el prefijo internacional `55` hacia la API de WhatsApp sin romper la URL.»

S3. «Tests & Calidad:
   - Verificar y actualizar suites de tests (`npm run test --workspace=packages/frontend` y `npm run typecheck`).
   - Commits directos en `main`, atómicos, <= 400 líneas, cero Co-Authored-By.
   - `git push origin main`.
   - Reconstruir y desplegar imagen web: `docker compose build web && docker compose up -d web`.
   - Verificar salud con curl.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Centralizar formatPhoneMask y toWhatsAppNumber en lib/phone.ts con reexport en lib/share-app.ts y tests unitarios | S1, S3 | pendiente | - |
| T2 | Aplicar máscara, atributos tel y toWhatsAppNumber en AssaiCalculatorPage, StockCalculatorPage y StockHistoryPage | S2, S3 | pendiente | - |
| T3 | Verificación completa (typecheck, tests), git push y despliegue del servicio web en Docker con comprobación curl | S3 | pendiente | - |

## Log

L1 (verbatim, 2026-10-20): «OBJETIVO:
Estandarizar el comportamiento y la máscara de entrada telefónica en TODOS los campos de la aplicación web donde se ingresa un número de WhatsApp para enviar reportes, replicando exactamente el comportamiento logrado en el Módulo Compartir (Opción 2).

CAMPOS IDENTIFICADOS:
1. `packages/frontend/src/pages/AssaiCalculatorPage.tsx` -> `data-testid="assai-free-number"`
2. `packages/frontend/src/pages/StockCalculatorPage.tsx` -> `data-testid="stock-free-number"`
3. `packages/frontend/src/pages/StockHistoryPage.tsx` -> `data-testid="stock-history-number"`

DETALLES TÉCNICOS:
1. Centralizar o exportar limpiamente `formatPhoneMask` y `toWhatsAppNumber` en `packages/frontend/src/lib/phone.ts` (manteniendo retrocompatibilidad con `lib/share-app.ts`).
2. En cada uno de los 3 inputs (`AssaiCalculatorPage`, `StockCalculatorPage`, `StockHistoryPage`):
   - Configurar `type="tel"`, `inputMode="tel"`, `autoComplete="tel"`.
   - Placeholder: `(11) 9XXXX-XXXX` (o clave i18n correspondiente).
   - Formatear en vivo en `onChange`: `onChange={(e) => setFreeNumber(formatPhoneMask(e.target.value))}`.
   - En el envío a WhatsApp (`waMeLink`): usar `toWhatsAppNumber(targetNumber)` para asegurar que números con DDD brasileño (10 u 11 dígitos) viajen con el prefijo internacional `55` hacia la API de WhatsApp sin romper la URL.
3. Tests & Calidad:
   - Verificar y actualizar suites de tests (`npm run test --workspace=packages/frontend` y `npm run typecheck`).
4. Git & Producción:
   - Commits directos en `main`, atómicos, <= 400 líneas, cero Co-Authored-By.
   - `git push origin main`.
   - Reconstruir y desplegar imagen web: `docker compose build web && docker compose up -d web`.
   - Verificar salud con curl.

Procedé con la disciplina ODD habitual.»
