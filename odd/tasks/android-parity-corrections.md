# Cinco correcciones de paridad en Android, relevadas del uso real

Cinco correcciones que Wilkin relevó usando la aplicación. Cuatro son de paridad con la web y una es un
defecto de layout. No agregan funciones nuevas: alinean lo que ya existe, y una de ellas corrige un texto que
se ve mal en WhatsApp.

## Specs

S1. «Al compartir el texto del reporte por WhatsApp, los ítems desmarcados con el caracter ⬜ no se visualizan
bien en todos los clientes de WhatsApp (quedan invisibles o vacíos). Revisar la generación del texto de
exportación (en export.service.ts y el exportador de reportes) para asegurar que tanto los ítems marcados como
los pendientes tengan íconos claros, visibles y compatibles en WhatsApp (por ejemplo ✅ para cumplidos y ❌ o
◻️ / símbolo visible para pendientes).»

S1b. **(corrección del usuario, y es la que manda)** «El verdadero bug en el texto de WhatsApp de Relatórios NO
era el glifo del icono, sino que se están incluyendo las tareas que el usuario NO seleccionó.» Wilkin marcó dos
tareas y el mensaje exportó todas las demás sin marcar. «La regla correcta: en el texto exportado a WhatsApp
solo se deben incluir las tareas que fueron efectivamente seleccionadas/realizadas (checked = 1, y en
check_assai/normal solo si tienen selected_products). Las tareas no seleccionadas/no realizadas NO deben
enviarse en el mensaje.»
bien en todos los clientes de WhatsApp (quedan invisibles o vacíos). Revisar la generación del texto de
exportación (en export.service.ts y el exportador de reportes) para asegurar que tanto los ítems marcados como
los pendientes tengan íconos claros, visibles y compatibles en WhatsApp (por ejemplo ✅ para cumplidos y ❌ o
◻️ / símbolo visible para pendientes).»

S2. «En DashboardScreen.kt, las tarjetas de Calculador de Estoque, Histórico de Estoque y Calculadora de Assaí
quedaron al fondo. Deben ubicarse inmediatamente después del módulo de Cronograma Activo, respetando
exactamente el orden de la web.»

S3. «En la web, la tarjeta de Cronograma Activo tiene el conteo, la fecha y además las acciones: el enlace para
"Ver cronograma" y el botón para "Compartilhar cronograma" por WhatsApp. En Android actualmente solo muestra
número y fecha. Llevarlo a paridad completa con la web agregando estas acciones.»

S4. «En el formulario de Relatório (ReportGeneratorScreen, ReportEditScreen y ReportForm), falta el campo de
texto de "Observações" / "Observaciones". En la web existe este campo que envía el atributo notes en
CreateReportRequest y UpdateReportRequest. Agregarlo con su label y placeholder en paridad con la web.»

S5. «En la pantalla que sigue tras salvar el reporte (ReportDetailScreen.kt), los botones de acción se pisan o
desbordan. Tomá captura en el emulador, verificá el layout y corregilo con espaciado limpio (Arrangement.
spacedBy / Spacers) para que no se superpongan.»

## Tasks

| ID | Tarea | Specs | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| U0 | Reconocimiento: ubicar cada punto antes de tocar nada | S1-S5 | hecha | ver el Log, L1 |
| U1 | El icono de los ítems pendientes en el texto que se comparte por WhatsApp | S1 | pendiente | |
| U1b | **La corrección real del punto 1**: solo se exportan las tareas marcadas, y una categoría sin nada que contar no imprime encabezado | S1b | hecha | TDD: ROJO «Unchecked item must not be exported» → VERDE; `export.service.ts` filtra los ítems y difiere el encabezado al contenido; 14/14 y el módulo de reportes 74/74 |
| U2 | El orden de las tarjetas del Dashboard: las tres calculadoras tras el cronograma | S2 | pendiente | |
| U3 | Paridad del cronograma activo: enlace «Ver cronograma» y compartir por WhatsApp | S3 | pendiente | |
| U4 | El campo de Observações en el formulario de reporte | S4 | pendiente | |
| U5 | Los botones superpuestos de la pantalla de detalle, con captura previa | S5 | pendiente | |
| U6 | Suites, commits por unidad, APK de release y asset en la release v0.5.0 | S1-S5 | pendiente | |

## Log

L1 (verbatim, 2026-10-19): la directiva completa del usuario, transcripta en las Specs S1 a S5, con el pedido
de planificar en el `todo`, verificar en el emulador con capturas, correr las suites, commits limpios en
`main`, compilar el APK de release y reemplazar el asset en la release v0.5.0 para mandarlo por Telegram.

L2 (orquestador): **reconocimiento antes de editar, con la ubicación exacta de cada punto**. El icono que se
ve mal sale de `reports/export.service.ts`, en dos lugares (líneas 157 y 160), y su prueba lo exige en
`export.service.test.ts:139`; el texto de WhatsApp lo genera **solo el backend**, así que el arreglo sirve para
la web y para la app a la vez, y hay un tercer uso del mismo caracter en `ReportViewPage.tsx:256` que **no** se
toca porque es el icono en pantalla del navegador, donde se ve bien. El orden de las tarjetas del Dashboard
tiene las tres calculadoras en las líneas 193, 205 y 217, después de los contadores de 175, 178 y 181, mientras
que el cronograma está en la 154: el arreglo es mover las tres, no reordenar todo. Las acciones del cronograma
que la web ya tiene viven en `DashboardPage.tsx:170-187`. El campo de observaciones **ya existe** en
`ReportEditViewModel` (estado, cambio y envío, con la misma expresión que la web, `notes.trim() || null`), así
que lo que falta es que el **generador** lo tenga y que el formulario lo **renderice**. Y los botones del
detalle están entre las líneas 157 y 231 de `ReportDetailScreen.kt`, donde conviven una `Row` con
`SpaceBetween` y botones sueltos después: el candidato a la superposición, a confirmar con una captura antes de
tocar nada.


L3 (orquestador, U1b): **el diagnóstico inicial estaba equivocado y el usuario lo corrigió, con razón**. La
primera vuelta atacó el glifo -- cambiando el cuadrado de presentación de texto por el de presentación emoji
-- y el bug real era el **filtrado**: el reporte guarda una fila por tarea con `checked = 0` para las que
nadie marcó, y el export recorría **todas**, así que el mensaje mezclaba lo hecho con lo no hecho y sus
números no coincidían con el reporte que el operador acababa de guardar. El arreglo tiene dos niveles: los
ítems (una tarea simple entra con `checked = 1`, y una de productos con sus productos seleccionados) y el
**encabezado de categoría**, que se imprimía para toda categoría con alguna fila y después del filtro habría
dejado secciones vacías: ahora se junta el contenido y la categoría se escribe sólo si tiene algo que decir.
De paso, la rama que dibujaba el pendiente quedó sin uso y se retiró, así que el cambio de glifo de U1 quedó
superado por completo. Y apareció un efecto secundario que vale la pena: **dos pruebas de idioma usaban como
muestra la tarea sin marcar**, o sea que estaban afirmando el defecto en nombre del idioma; su sujeto es el
idioma, así que la muestra pasó a las tareas marcadas.
