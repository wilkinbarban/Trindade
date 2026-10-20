# Mejoras Massas Web: Mobile, Historial, Formato WhatsApp y Ayuda Contextual Global

Implementación de optimizaciones mobile en tabla colectiva de masas, endpoint y página de historial de lanzamientos, actualización del mensaje de WhatsApp, y botón de ayuda contextual universal en todos los módulos de Trindade Web bajo disciplina ODD y TDD estricto.

## Specs
S1: Optimización Mobile de la tabla "Resumo Coletivo do Dia":
  - En `packages/frontend/src/pages/massas/MassasCollectiveTable.tsx`:
    - Remediación visual para celulares (< 640px / sm): la tabla debe adaptarse fluidamente sin desbordarse ni romper el ancho de pantalla.
    - Implementar vista responsiva (tarjetas compactas o filas apiladas en móvil con nombre del producto, unidades/caja en tono secundario, y cantidades de cajas y unidades destacadas a la derecha). En escritorio (sm/md en adelante) conservar la tabla tabular.
    - Asegurar que la lista de lanzamientos por operario y los botones de edición/borrado se vean nítidos y no se encimen en pantallas de celular.
S2: Módulo de Historial de Lançamento de Massas (Backend):
  - Endpoint `GET /api/massas-launch/history`: devuelve los días que tuvieron lanzamientos agrupados por fecha (`date`, `productsCount`, `totalBoxes`, `totalUnits`, `operatorsCount`), ordenados de más reciente a más antiguo.
  - Tests en `packages/backend/src/modules/massas/massas.routes.test.ts` y `massas.service.test.ts`, schemas Zod y registro en OpenAPI (`route-coverage.test.ts`, `openapi.json`).
S3: Módulo de Historial de Lançamento de Massas (Frontend):
  - Página `packages/frontend/src/pages/MassasHistoryPage.tsx` (ruta `/massas-history` en `App.tsx`).
  - Selector de fecha y listado rápido de días pasados con lanzamientos, mostrando el resumen colectivo de ese día, el desglose de operarios y el exportador a WhatsApp.
  - Enlace cruzado en `MassasLaunchPage.tsx` para navegar entre "Hoje" y "Ver Histórico".
  - Registrar `/massas-history` en el menú lateral (`AppShell.tsx`) y `DashboardPage.tsx`.
S4: Actualización del formato del mensaje de WhatsApp:
  - En `packages/frontend/src/lib/massas.ts` (y `massas.test.ts`):
    - Eliminar la línea `*Total Geral: ...*`.
    - Agregar al final la lista de nombres únicos de los operarios que lanzaron masas ese día (ej: `*Lançado por:* Operador1, Operador2`).
    - Si no hay lanzamientos, mantener el mensaje de aviso correspondiente (`Nenhum lançamento registrado até agora.`).
S5: Botón de Ayuda Contextual en TODOS los Módulos Web:
  - Crear componente reutilizable `packages/frontend/src/components/ui/ModuleHelpModal.tsx`:
    - Botón pequeño y discreto con icono `HelpCircle` y texto "Ajuda" / "Ayuda".
    - Al tocarlo, abre un diálogo/modal accesible, limpio y completamente optimizado para celulares (`max-h-[85vh]`, scroll vertical, botón de cierre táctil cómodo).
    - Explicación clara de qué hace el módulo, qué representa cada sección y cómo se opera paso a paso.
  - Integrar este botón de ayuda con su contenido contextual específico en cada módulo:
    1. Lançamento de Massas (`/massas-launch` y `/massas-history`)
    2. Calculadora de Estoque (`/stock-calculator`)
    3. Histórico de Estoque (`/stock-history`)
    4. Calculadora Assaí (`/assai-calculator`)
    5. Programação de Carga (`/loading` y `/loading/history`)
    6. Relatórios Diários (`/reports` y `/reports/history`)
    7. Compartir Aplicación (`/compartir`)
    8. Dashboard (`/dashboard`)
  - Textos localizados en `pt.json` y `es.json`.
S6: Despliegue en Producción, Git y Limpieza:
  - Commits atómicos en `main` (`<= 400` líneas, sin ramas, sin `Co-Authored-By`).
  - Tests de backend y frontend en verde.
  - Reconstruir imágenes de producción Docker (`trindade-web` y `trindade-api`) y verificar estado healthy y HTTP 200 en `https://trindademasas.duckdns.org`.
  - Dejar repositorio limpio y todo pusheado a `origin/main`.

## Tasks
- T1 (S4): Actualización del formato del mensaje de WhatsApp en `lib/massas.ts` y tests unitarios en `massas.test.ts`. Commit: `a382dcd`
- T2 (S1): Optimización mobile-first de `MassasCollectiveTable.tsx` (vista responsiva celular/escritorio y acciones limpias). Commit: `4d9a345`
- T3 (S2): Backend endpoint `GET /api/massas-launch/history`, schemas Zod, servicio, contrato OpenAPI y tests de integración. Commit: `974029c`
- T4 (S3): Frontend `MassasHistoryPage.tsx`, enlaces cruzados Hoje/Historial, registro en `App.tsx`, `AppShell.tsx` y `DashboardPage.tsx`. Commit: `5a59fcd`
- T5 (S5): Componente de ayuda contextual `ModuleHelpModal.tsx`, traducciones en `pt-BR.json` / `es.json` e integración en los 8 módulos. Commits: `13bed3c`, `b9c5d92`, `5117212`
- T6 (S6): Verificación de tests (frontend y backend), build Docker, despliegue en producción, comprobación healthy/HTTP 200 y push a `origin/main`. Commit db test fix: `5f3ad9a`

## Log
- L1: Solicitud del usuario para optimizar mobile en tabla colectiva de masas, crear historial de masas en backend y frontend con enlace cruzado, actualizar formato WhatsApp (eliminar total general y agregar lista de operadores), crear botón de ayuda contextual universal para los 8 módulos web, y desplegar en producción con commits atómicos en main.
- L2: Verificación completa:
  - Backend tests: 510 tests pasados en 79 suites (100% verde).
  - Frontend tests: 112 tests pasados en 24 suites (100% verde).
  - Docker build y deploy: `trindade-api` y `trindade-web` recreados y healthy.
  - Verificación en vivo: `curl -I https://trindademasas.duckdns.org` -> HTTP 200; `/api/app/latest-release` -> HTTP 200.
