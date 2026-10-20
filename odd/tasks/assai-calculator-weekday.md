# Selección de Día de la Semana en Calculadora Assaí

Permitir escoger un día de la semana (Martes, Jueves, Viernes, Sábado) para el reporte del Pedido Assaí en la aplicación web, reflejándolo en el encabezado del mensaje de WhatsApp.

## Specs

S1. «Permitir escoger un día de la semana para el reporte del Pedido Assaí.
   - Días disponibles (selección única de 1 día): Martes (Terça-feira), Jueves (Quinta-feira), Viernes (Sexta-feira), Sábado (Sábado).
   - El día elegido SOLO se refleja en el mensaje resultante de WhatsApp en el encabezado: "🛒 Pedido Assaí <Día>" (ejemplo: "🛒 Pedido Assaí Sexta-feira"). Si no se elige día, se mantiene "🛒 Pedido Assaí".
   - En la UI de AssaiCalculatorPage.tsx: selector claro e intuitivo (botones tipo pill / segmented control mobile-friendly) en la tarjeta de compartir / mensaje.»

S2. «Implementación técnica:
   - Modificar packages/frontend/src/lib/assai.ts: assaiMessage(lines, day?: string | null).
   - Actualizar tests unitarios en packages/frontend/src/lib/assai.test.ts para validar el encabezado con y sin día.
   - Modificar packages/frontend/src/pages/AssaiCalculatorPage.tsx integrando el selector de día.
   - Agregar claves i18n en packages/frontend/src/i18n/locales/pt-BR.json y es.json.»

S3. «ODD & Despliegue:
   - Documentar en odd/tasks/assai-calculator-weekday.md.
   - Commits atómicos en main (<= 400 líneas, cero Co-Authored-By).
   - Validar con typecheck y suite de tests completa (npm run test --workspace=packages/frontend).
   - Git push origin main.
   - Reconstruir y desplegar el contenedor de producción web con docker compose build web && docker compose up -d web y verificar con curl.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | Test-first en lib/assai.ts y assai.test.ts para soporte de assaiMessage(lines, day) | S1, S2 | hecha | `be548b4` (40 líneas); ciclo RED -> GREEN; tests unitarios 16/16 verdes |
| T2 | Selector de día mobile-first en AssaiCalculatorPage.tsx y claves i18n (pt-BR y es) | S1, S2, S3 | hecha | `9f4d3f2` (93 líneas); ASSAI_WEEKDAY_OPTIONS, radio buttons estilo pill con toggle y clear, claves en es y pt-BR, 103 tests verdes |
| T3 | Validación de tests/typecheck, git push y despliegue del servicio web en Docker | S3 | pendiente | - |

## Log

L1 (verbatim, 2026-10-20): «Objetivo: Implementar la selección de día de la semana en la Calculadora Assaí (aplicación web) bajo disciplina ODD y TDD estricto.
1. Requerimiento funcional:
   - Permitir escoger un día de la semana para el reporte del Pedido Assaí.
   - Días disponibles (selección única de 1 día): Martes (Terça-feira), Jueves (Quinta-feira), Viernes (Sexta-feira), Sábado (Sábado).
   - El día elegido SOLO se refleja en el mensaje resultante de WhatsApp en el encabezado: "🛒 Pedido Assaí <Día>" (ejemplo: "🛒 Pedido Assaí Sexta-feira"). Si no se elige día, se mantiene "🛒 Pedido Assaí".
   - En la UI de AssaiCalculatorPage.tsx: selector claro e intuitivo (botones tipo pill / segmented control mobile-friendly) en la tarjeta de compartir / mensaje.
2. Implementación técnica:
   - Modificar packages/frontend/src/lib/assai.ts: assaiMessage(lines, day?: string | null).
   - Actualizar tests unitarios en packages/frontend/src/lib/assai.test.ts para validar el encabezado con y sin día.
   - Modificar packages/frontend/src/pages/AssaiCalculatorPage.tsx integrando el selector de día.
   - Agregar claves i18n en packages/frontend/src/i18n/locales/pt-BR.json y es.json.
3. ODD & Despliegue:
   - Documentar en odd/tasks/assai-calculator-weekday.md.
   - Commits atómicos en main (<= 400 líneas, cero Co-Authored-By).
   - Validar con typecheck y suite de tests completa (npm run test --workspace=packages/frontend).
   - Git push origin main.
   - Reconstruir y desplegar el contenedor de producción web con docker compose build web && docker compose up -d web y verificar con curl.
Avanzá continuo de punta a punta.»
