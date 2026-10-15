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

**Desbloqueado**: el catálogo llegó del usuario y se transcribe acá **literal**, en el documento que es la
fuente de verdad de la implementación, porque ya se perdió una vez y no puede volver a perderse.

TERCEIRIZADOS (15 productos):

| Código | Nombre | Unidades por caja |
| --- | --- | --- |
| 1667 | BATATA EXTRA FINA | 20 |
| 1567 | BATATA PALHA 400G | 25 |
| 1566 | BATATA PALHA 80G | 25 |
| 1461 | BRINQUEDO GELADO | 100 |
| 787 | CANUDO | 30 |
| 1438 | NHOQUE 1 kg | 20 |
| 1436 | NHOQUE 400g | 42 |
| 1236 | PÃO PICANTE | 10 |
| 1237 | PÃO TRADICIONAL | 10 |
| 1595 | PÃO CALABRESA | 10 |
| 1591 | PIZZA FAMILIA 200g | 20 |
| 107 | PIZZA KIT 160g | 20 |
| 1753 | 150g - PIZZA KIT | 20 |
| 1800 | PIZZA BROTINHO 55G | 50 |
| 2108 | QUEIJO RALADO | 25 |

MASSAS (10 productos):

| Código | Nombre | Unidades por caja |
| --- | --- | --- |
| 1045 | MASSA CASEIRA | 10 |
| 39 | MASSA DISCO 200G | 10 |
| 60 | MASSA DISCO 400G | 20 |
| 84 | MASSA DISCO 500G | 20 |
| 91 | MASSA LASANHA 500G | 20 |
| 1192 | MASSA QUADRADA | 15 |
| 5000 | MASSA ROLO 1KG | 10 |
| 1047 | MASSA ROLO 2KG | 6 |
| 22 | MASSA ROLO 500G | 15 |
| 1616 | MASSA TOKO | 10 |

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
| T03 | Calculador de Estoque: migración v5 + endpoints + página | S3, S4 | inline | por cerrar |
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

**L3** (catálogo recibido): el usuario entregó los 25 productos con sus códigos, nombres y unidades por
caja —15 Terceirizados y 10 Massas— y quedaron transcriptos literalmente en S3. T03 queda **desbloqueada**:
el catálogo va a código (una constante del backend, como el de Assaí en el frontend) y **no** a la base,
porque es un dato del producto y no del conteo del día; la tabla `stock_counts` guarda lo que el operador
cuenta, no el catálogo que lo ordena.

## L4 — Cierre de T02 y especificación ampliada de T03 (verbatim)

**T02 CERRADA** el 2026-10-14: la regla del pedido (`c112c83`, `src/lib/assai.ts`, 8 pruebas, 48/48) y la pantalla `/assai-calculator` con su ruta, tarjeta, enlace de menú y claves (`724653c`, 288 líneas). CI verde (verify y android) y desplegada en producción sin downtime: el bundle servido contiene «Calculadora de Assaí», «Quantidade incorreta de unidades» y `assai-calculator`. Detalle completo en el ledger, entrada **L212**.

**Pedido literal del usuario para T03** (2026-10-14): «Arrancá de inmediato con T03 (Calculador de Estoque):
   - Migración schema v5 (tabla stock_counts con count_date, category, product_code, product_name, units_per_box, boxes_total, units_total, entries_log, created_at, updated_at, user_id). Respaldo preventivo de trindade.db antes de aplicar.
   - Endpoints API (/api/stock GET por fecha, POST add-entry suma acumulativa de contenedores, POST reset-product, GET history).
   - Frontend en /stock-calculator con selector de fecha, pestañas Terceirizados (15 productos) y Massas (10 productos) con el catálogo suministrado, botones de agregar cajas acumulativo, reset por producto, exportación WhatsApp (estilo tabla con emojis) y exportación por correo a wilkin.barban@gmail.com vía mailto:.
   - Tarjeta en Dashboard y enlace en navegación.
3. Mantené TDD estricto, commits <= 400 líneas sin mención a IA, reconstrucción de imágenes y hot-swap en producción sin downtime, asentando L213.»

**Nota de diseño para T03**: `entries_log` guarda el registro de lo agregado (la acumulación corrida es el valor de `boxes_total`, y el log permite auditar cuándo entró cada tanda); el catálogo sigue en código y la fila guarda nombre y unidades por caja del momento del conteo, para que un cambio futuro del catálogo no reescriba un conteo viejo.
