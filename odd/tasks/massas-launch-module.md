# Módulo Lanzamiento de Masas (Lançamento de Massas)

Implementación del módulo "Lanzamiento de Masas" en la aplicación Web de Trindade, integración en la navegación, inclusión como la 3ª tarjeta en el Dashboard, y despliegue en producción bajo disciplina ODD y TDD estricto.

## Specs
S1: Catálogo de 10 Masas: Utilizar los 10 productos clasificados como "Massas" en `packages/backend/src/modules/stock/stock.catalog.ts` (MASSA CASEIRA 10 un/cx, MASSA DISCO 200G 10 un/cx, MASSA DISCO 400G 20 un/cx, MASSA DISCO 500G 20 un/cx, MASSA LASANHA 500G 20 un/cx, MASSA QUADRADA 15 un/cx, MASSA ROLO 1KG 10 un/cx, MASSA ROLO 2KG 6 un/cx, MASSA ROLO 500G 15 un/cx, MASSA TOKO 10 un/cx). Conteo y persistencia 100% independientes de la Calculadora de Estoque.
S2: Persistencia en Backend (SQLite): Migración de base de datos aditiva (revisión 6) con tabla `massa_launches` (`id`, `launch_date TEXT`, `product_code TEXT`, `boxes INTEGER`, `user_id INTEGER REFERENCES users(id)`, `created_at TEXT`, `updated_at TEXT`). Incrementar `SCHEMA_VERSION` a 6 con snapshot preventivo.
S3: Endpoints REST en `packages/backend`:
  - `GET /api/massas-launch?date=YYYY-MM-DD`: retorna los 10 productos con el acumulado colectivo de cajas (`SUM(boxes)`) y unidades (`totalBoxes * unitsPerBox`), más los lançamentos individuales del día con `username` y `user_id`.
  - `POST /api/massas-launch`: registra cajas lanzadas asociadas al usuario autenticado.
  - `PUT /api/massas-launch/:id`: permite editar cajas de un lanzamiento. RBAC estricto: valida que `launch.user_id === req.user.id`; si es de otro usuario retorna 403 Forbidden.
  - `DELETE /api/massas-launch/:id`: permite borrar un lanzamiento propio (valida `launch.user_id === req.user.id`).
  - Cobertura completa de tests en verde y registro en OpenAPI (`route-coverage.test.ts`).
S4: Frontend Web:
  - Ruta `/massas-launch` en `App.tsx` y enlace en el menú lateral (`AppShell.tsx`).
  - Dashboard (`DashboardPage.tsx`): Ubicar la tarjeta "Lançamento de Massas" en la 3ª posición (después de 'Relatórios Hoje' y 'Carga Ativa', antes de 'Estoque').
  - Pantalla `/massas-launch` con 3 bloques:
    - Superior: Formulario/grilla con los 10 productos para ingresar cajas y ver unidades calculadas en vivo. Botón "Lançar" para enviar. SIN botón Resetear.
    - Intermedio: Tabla colectiva del día con los 10 productos mostrando la sumatoria de todos los usuarios (cajas y unidades) y el desglose de qué usuario lanzó qué cantidad. Los lanzamientos del usuario autenticado tienen opción de editar/corregir cajas; los de otros usuarios se muestran en solo lectura.
    - Inferior: Compartir por WhatsApp con input de teléfono con máscara `(11) 9XXXX-XXXX` y prefijo 55. Vista previa del reporte mostrando únicamente unidades totales por producto y el total general.
  - Textos en `pt.json` y `es.json`. Tests de frontend en verde.
S5: Despliegue, Git y Limpieza:
  - Commits atómicos directamente en `main` (`<= 400` líneas, sin ramas, sin `Co-Authored-By`).
  - Reconstruir imágenes de producción Docker (`trindade-web` y `trindade-api`) y verificar estado healthy y HTTP 200 en `https://trindademasas.duckdns.org`.
  - Dejar repositorio limpio y todo pusheado a `origin/main`.

## Tasks
- T1 (S1, S2): Migración SQLite rev 6 (`massa_launches`), `SCHEMA_VERSION = 6`, `schema.sql` y tests de migración.
- T2 (S1, S3): Módulo backend `massas`: servicio, schemas Zod, rutas GET/POST/PUT/DELETE, RBAC, OpenAPI y tests.
- T3 (S4): Navegación Web: `/massas-launch` en `App.tsx`, enlace en `AppShell.tsx`, 3ª tarjeta en `DashboardPage.tsx`, i18n (`pt.json`, `es.json`) y biblioteca pura `lib/massas.ts` con tests.
- T4 (S4): Pantalla `MassasLaunchPage.tsx`: grilla superior con 10 masas y cálculo en vivo, tabla intermedia colectiva con desglose por usuario y edición RBAC, bloque inferior WhatsApp con máscara y preview de unidades totales.
- T5 (S5): Snapshot preventivo de base de datos en producción, ejecución de migración a rev 6, rebuild Docker Compose (`web`, `api`), verificación de salud y HTTP 200, git push a `origin/main`.

## Log
- L1: Solicitud inicial del usuario para implementar el módulo "Lanzamiento de Masas" en Trindade Web con catálogo de 10 masas independiente de stock, migración SQLite rev 6, endpoints REST con RBAC por usuario, navegación en AppShell y Dashboard (3ª tarjeta), UI en 3 bloques (grilla sin reset, tabla colectiva editable por dueño, WhatsApp con máscara y unidades totales), commits atómicos en main y despliegue Docker en producción.
