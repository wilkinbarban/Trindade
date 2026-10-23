# Read-only history and compact headers (directiva 2026-10-23)

Remediación visual y funcional de Stock Calculator, Stock History, Massas Launch y Massas History:
históricos de solo lectura con rango de fechas pasado, encabezados que no aplastan el título en
390 px, y helpers de fecha para "ayer" y "hace N días".

## Specs

S1. Calculador de Estoque, botones de días contados: "Vista celular (< sm): mostrar solo 3 botones.
Vista normal / desktop (>= sm): mostrar 5 botones." Implementación pedida: `history.slice(0, 5)` con
`idx >= 3 ? 'hidden sm:inline-flex' : 'inline-flex'`.

S2. Helpers en `packages/frontend/src/lib/date.ts`: exportar `saoPauloDaysAgo(daysAgo: number, now?: Date): string`
y `saoPauloYesterday(now?: Date): string`, con "aritmética segura sobre YYYY-MM-DD con Date.UTC sin
desfases de zona horaria", más sus tests unitarios.

S3. Histórico de Estoque, encabezado: `flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3`;
título "Histórico de Contagem" + `ModuleHelpModal` en `flex items-center gap-2 flex-wrap`; subtítulo
abajo; botón "Voltar ao contador" `w-full sm:w-auto shrink-0`.

S4. Histórico de Estoque, fechas: "Eliminar completamente los botones de días contados (days.slice). La
fecha se selecciona únicamente en el input de fecha." Restricción: "solo días pasados hasta 30 días, sin
permitir hoy. `max={saoPauloYesterday()}`, `min={saoPauloDaysAgo(30)}`. Estado inicial: `date = saoPauloYesterday()`."

S5. Histórico de Estoque, función: "solo lectura, sin calculadora, incluir Resumo Coletivo do Dia".
Eliminar inputs de cantidad, botones Adicionar/Resetar, `sendEntry`, `resetProduct` y columna
`addBoxes`; renderizar el resumen colectivo (tabla `hidden sm:block`, tarjetas `block sm:hidden` de dos
filas, estado vacío `t('stock.collectiveEmpty')`, "Totales del día al pie") y mantener el bloque de
compartir (WhatsApp / Email).

S6. Histórico de Massas: quitar el contenedor del icono `<History />`; encabezado
`flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3` con título + ayuda en fila, subtítulo
abajo y "Voltar para Hoje" limpio. Quitar los botones "DIAS COM LANÇAMENTOS REGISTRADOS" y los badges
"Modo Leitura" y "Hoje"; rango `max={saoPauloYesterday()}`, `min={saoPauloDaysAgo(30)}`, inicial ayer.

S7. Lançamento de Massas: quitar el emoji 🍝 del encabezado; mismo layout apilado
`flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3`, título + ayuda en fila, subtítulo
abajo y acciones ("Ver Histórico" + date picker) a la derecha con layout fluido.

S8. Entrega: `npm --prefix packages/frontend test` 100 % verde; `docker compose build web && docker compose up -d web`;
HTTP 200 en `/stock-calculator`, `/stock-history`, `/massas-launch`, `/massas-history`; commits
convencionales directo a `main`, sin `Co-Authored-By` ni atribución a IA.

## Tasks

| ID | Specs | Route | Commit |
|----|-------|-------|--------|
| T1 | S2 | inline | `feat(dates): add yesterday and N days ago on the plant's clock` |
| T2 | S5 | inline | `refactor(stock): extract the day's collective summary into its own component` |
| T3 | S1 | inline | `fix(stock): show three counted days on a phone and five on a desk` |
| T4 | S3, S4, S5 | inline | `feat(stock): make the counter history a read-only reading of the day` |
| T5 | S6 | inline | `feat(massas): read the launch history only in the last thirty past days` |
| T6 | S7 | inline | `fix(massas): drop the header icon that squeezed the title on a phone` |
| T7 | S8 | inline | gates and delivery |

## Log

L1 — directiva del usuario, literal (llegó duplicada en el mismo mensaje; se registra una vez):

  Directiva técnica de remediación visual y funcional (Stock Calculator, Stock History, Massas Launch,
  Massas History): He completado la auditoría con screenshots en vistas desktop (1280px) y móvil
  (390px). Todos los hallazgos reportados por el usuario fueron confirmados visualmente y en código.
  Debes ejecutar las siguientes remediaciones:

  1. Calculador de Estoque (packages/frontend/src/pages/StockCalculatorPage.tsx): en los botones de días
     contados (<sm: 3 botones; >=sm: 5 botones, con `slice` y `hidden sm:inline-flex`).
  2. Helpers `saoPauloDaysAgo(daysAgo, now?)` y `saoPauloYesterday(now?)` en `lib/date.ts` con `Date.UTC`
     y tests.
  3. Histórico de Estoque: header apilado, sin chips de días, rango pasado de 30 días, fecha inicial ayer,
     solo lectura sin calculadora, con Resumo Coletivo do Dia (tabla/cards/empty/totales) y share.
  4. Histórico de Massas: quitar icono `<History/>`, header apilado, sin botones de días ni badges
     "Modo Leitura"/"Hoje", rango pasado de 30 días sin hoy.
  5. Lançamento de Massas: quitar 🍝 del header, header apilado con acciones a la derecha.
  6. Verificación: tests de frontend, rebuild de la imagen web, HTTP 200 en las cuatro rutas, commits
     convencionales a `main` sin atribución.

L2 — dos consecuencias que la directiva no nombra y que se aplican por lógica, no por gusto:

  1. Con `max={saoPauloYesterday()}` en el histórico de massas, `readOnly={date !== saoPauloToday()}` de
     `MassasCollectiveTable` pasa a ser siempre verdadero. Editar/borrar launches desde el histórico deja
     de ser alcanzable, así que el estado y los handlers de edición se retiran de `MassasHistoryPage` y las
     props de edición de la tabla pasan a opcionales para que un llamador de solo lectura no invente manejadores.
  2. `stock.emptyHistory` repetía el texto de `stock.collectiveEmpty` y queda sin uso al eliminar la vista
     de calculadora del histórico; se retira de ambos locales. Igual `massas.today`, `massas.readOnly` y
     `massas.pastDaysWithLaunches` tras quitar los badges y la lista de días.
