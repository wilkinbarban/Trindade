# ODD: Calculadora de Assaí, Calculador de Estoque y corrección del Dashboard

## Header

Enfoque: **primero la Web** (con backend y migraciones), validada en producción antes de portar a Android
(Nota 1). Ciclo abierto por el usuario con lenguaje directo de arquitectura. El Tercer módulo queda
**bloqueado** por un dato faltante (ver Sí bloqueado, al final), y las especificaciones 1 y 2 se ejecutan
ahora.

## Specs

### S1 — Dashboard: «Cronograma activo» abre el día del lote, o invita a crearlo

Cita literal del usuario: «Causa identificada: Al hacer clic en "Ver cronograma", usaba
summary.activeScheduleDate (fecha de carga del camión, e.g. mañana) en vez de
summary.activeScheduleBatchDate (fecha en la que el lote fue creado y registrado en loading_schedules).
Por ello cargaba una fecha sin registros y mostraba "Ninguna carga programada para este día".»

Comportamiento requerido, cita literal:

- «Si summary.activeScheduleCount > 0 y summary.activeScheduleBatchDate != null: Mostrar enlace
  "{t("dashboard.viewSchedule")}" apuntando a `/loading/edit?date=${summary.activeScheduleBatchDate}`.»
- «Si summary.activeScheduleCount === 0 (o la ventana de 15h concluyó sin nuevo cronograma creado):
  Mostrar enlace "{t("dashboard.createSchedule")}" ("Criar cronograma" / "Crear cronograma") apuntando a
  `/loading` para iniciar la creación del cronograma de hoy.»
- Claves i18n nuevas: `dashboard.createSchedule` = `Criar cronograma` (pt-BR) / `Crear cronograma` (es).
- «Actualizar tests unitarios de DashboardPage.»

### S2 — Calculadora de Assaí (Web)

Cita literal del usuario: «Archivo: packages/frontend/src/pages/AssaiCalculatorPage.tsx (ruta
/assai-calculator en App.tsx). Acceso: Nueva tarjeta en DashboardPage y enlace en navegación.»

Catálogo de 9 productos y unidades por caja, cita literal:

| Producto | Unidades por caja |
| --- | --- |
| Nhoque 1kg | 15 |
| Quadrada | 14 |
| Disco 200g | 80 |
| Disco 400g | 50 |
| Disco 500g | 40 |
| Lasanha | 45 |
| Rolo 500g | 30 |
| Rolo 1kg | 24 |
| Rolo 2kg | 10 |

Lógica, cita literal: «El usuario ingresa la cantidad de unidades deseadas para cada producto. Calcula
cajas = unidades / unidades_por_caja. Validación estricta: siempre la cuenta debe ser exacta (unidades %
unidades_por_caja === 0). Si no es exacta, mostrar error "Cantidad errada de unidades" / "Quantidade
incorreta de unidades" en ese producto y deshabilitar exportación. Resumen con total general de cajas.»

Exportación WhatsApp, cita literal: «Botón para generar vista y compartir por WhatsApp. Selector de
motorista (de /api/loading/drivers con teléfono) o número libre. Solo incluir productos con cantidad > 0.
Utilizar el enlace canónico `https://api.whatsapp.com/send?phone=...&text=...` (o shareOrCopy).»

### S3 — Calculador de Estoque (backend)  **[BLOQUEADO: falta el catálogo]**

Cita literal: «Migración schema v5 (SCHEMA_VERSION = 5): tabla `stock_counts` (id, count_date TEXT
[YYYY-MM-DD], category TEXT, product_code TEXT, product_name TEXT, units_per_box INTEGER, boxes_total
INTEGER DEFAULT 0, units_total INTEGER DEFAULT 0, entries_log TEXT, created_at TEXT, updated_at TEXT,
user_id INTEGER, UNIQUE(count_date, product_code)).»

Endpoints, cita literal: «GET /api/stock?date=YYYY-MM-DD (devuelve el conteo del día; cada día nuevo inicia
en cero, devuelve productos con totales). POST /api/stock/add-entry { date, product_code, boxes_added }
(suma corrida acumulativa: agrega cajas, recalcula unidades, añade al log de contenedores y persiste).
POST /api/stock/reset-product { date, product_code } (resetea el producto a 0). GET /api/stock/history
(fechas con conteos realizados para consulta histórica).»

**Falta el dato**: «Catálogo de 25 productos (15 Terceirizados + 10 Massas) con sus códigos y capacidades
provistos en el requerimiento.» No están en este mensaje, ni en el repositorio, ni en los documentos del
checkout principal, ni en la memoria. **No se inventan**: son 25 filas que entrarían a una base de
producción y a una pantalla que el operador usa para contar mercadería real.

### S4 — Calculador de Estoque (frontend)  **[BLOQUEADO: depende de S3]**

Cita literal: «Archivo: packages/frontend/src/pages/StockCalculatorPage.tsx (ruta /stock-calculator en
App.tsx). Acceso en DashboardPage y menú de navegación. Selector de fecha para consultar historial de días
anteriores o el día actual. Dos pestañas/secciones: Terceirizados (15 productos) y Massas (10 productos).
Por cada producto: código, nombre, cajas contadas acumuladas, total de unidades calculadas, input para
agregar cajas (+ Agregar) acumulando de forma corrida, y botón Reset por producto. Exportación WhatsApp:
vista formateada con emojis y estilo tabla (solo productos con unidades > 0), envío a número elegido.
Exportación Correo Electrónico: texto plano formateado en tabla con productos y totales contados,
preconfigurado para `wilkin.barban@gmail.com` (vía mailto: con fallback a portapapeles).»

### S5 — Invariantes del ciclo

Cita literal: «Trabajar sobre main en /home/wilkin/proyectos/Trindade-worktrees/main-consolidation. TDD
estricto en cada paso (RED -> GREEN). Commits <= 400 líneas brutas, Conventional Commits sin autoría IA,
push a main en origin. Reconstrucción de imágenes Docker (trindade-web-1 y trindade-api-1), migración de
base de datos viva a v5 con respaldo preventivo, y hot-swap en producción sin downtime. Android queda
reservado para la siguiente fase tras validación en producción (Nota 1). Asentar la entrada en el Ledger
L211.»

## Tasks

| ID | Qué | Specs | Ruta | Commit |
| --- | --- | --- | --- | --- |
| T01 | El enlace del cronograma activo abre el día del lote, o invita a crearlo | S1 | inline | por cerrar |
| T02 | Calculadora de Assaí: regla pura, página, ruta, tarjeta y navegación | S2 | inline | por cerrar |
| T03 | Calculador de Estoque: migración v5 + endpoints + página | S3, S4 | inline | **bloqueada** |
| T04 | Despliegue web (sin migración: S1 y S2 son de frontend) y asiento L211 | S5 | inline | por cerrar |

Tamaño: **grande** (tres módulos, backend y migración incluidos, varias sesiones, entregables separados).
T01 y T02 son unidades chicas dentro del ciclo; T03 se registra y espera el catálogo.

## Log

**L1** (pedido original, literal): «Directiva de Arquitectura para la nueva release: Módulos "Calculadora
de Assaí", "Calculador de Estoque" y Corrección en Dashboard. Enfoque: Implementar y desplegar primero en
la aplicación Web (con backend y migraciones), validando completamente en producción antes de portar a
Android (Nota 1).» — sigue el detalle de los tres módulos, la lógica, los catálogos y los invariantes del
ciclo, transcrito en las secciones de arriba.

**L2** (bloqueo declarado): el catálogo de 25 productos de S3 no llegó con el requerimiento ni existe en el
repositorio, el checkout principal o la memoria. Se pide al usuario por lo que es: un dato que no se puede
deducir. Mientras tanto se ejecutan S1 y S2, que están completas.
