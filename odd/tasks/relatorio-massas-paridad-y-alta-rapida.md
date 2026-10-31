# Relatório de massas: contrato, balance y alta rápida de operarios

Cierra tres frentes pedidos por Wilkin: el contrato de `/massas-launch/report` que dejaba el card de Android vacío, la
barra de balance y el histórico unificado de massas en la Web, y la creación rápida de operarios al compartir la app.

## Specs

- **S1** — `GET /api/massas-launch/report` responde cada operario con las dos formas del mismo dato:
  `name` y `displayName`, `erpOnly` y `onlyErp`, más `operatorKey` (clave estable del operario) y
  `entLans` (los `ENT_LAN` que ese operario aportó).
- **S2** — `balance` del mismo endpoint responde `producedBoxes`, `producedUnits`, `registeredBoxes`,
  `registeredUnits`, `differenceUnits`, `reconciledSeats` y `pendingSeats`. Los dos últimos son la
  cantidad de asientos del ERP conciliados y pendientes del día.
- **S3** — El contrato OpenAPI declara esa forma y el cliente Android la deserializa: con el JSON real del
  backend, `MassasReportDayView` se construye y el card muestra los operarios y la diferencia.
- **S4** — La tarjeta de massas del relatório en la Web muestra la misma barra de balance que «Produção do
  dia»: `Produzido`, `No ERP` y `Diferença` con el color del signo (verde en cero, ámbar negativo, celeste
  positivo). Y `ReportsPage` pasa a la tarjeta la fecha del reporte, no una lectura nueva del reloj.
- **S5** — Con el switch «3. Incluir Credenciais na Mensagem» activo, la pantalla de compartir ofrece dos
  modos excluyentes: **Criar Novo Operário** (por defecto) y **Selecionar Existente** (el dropdown actual).
- **S6** — En el modo de creación: `Nome de exibição` editable; `Nome de usuário / Login` autocompletado en
  minúsculas a partir del nombre y editable; rol fijo `Trabalhador` (`role_id: 2`), sin control para
  cambiarlo; contraseña generada igual al usuario más la secuencia numérica corrida hasta 15 caracteres
  (`pedro` → `pedro1234567890`).
- **S7** — Al presionar Compartilhar / Copiar en el modo de creación se ejecuta
  `POST /api/admin/users` con `{ username, display_name, password, role_id: 2 }`. Si el usuario ya existe
  se informa el error; si se crea, el mensaje sale con sus credenciales y el enlace oficial y se abre
  WhatsApp o se copia, igual que hoy.
- **S8** — La pantalla de compartir de Android tiene la misma paridad: los dos modos, los mismos campos, la
  misma contraseña generada y la misma llamada de creación.
- **S9** — `MassasHistoryPage` carga también el espejo del ERP del día consultado y pasa `unifiedRows` a
  `MassasCollectiveTable` en modo lectura, de modo que un lanzamiento pendiente se ve con su badge
  «Pendente ERP» y la barra de balance marca la diferencia real del día.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2 | inline · backend: `massas-report.service.ts`, `massas.schema.ts`, tests del servicio | `feat(massas): answer the report day with both operator shapes` |
| T2 | S3 | inline · contrato OpenAPI regenerado y test Android de deserialización con el JSON real | `test(android): parse the report day the backend actually sends` |
| T3 | S4 | inline · `MassasReportSection.tsx`, `ReportsPage.tsx`, i18n, tests | `feat(web): show the massas balance on the report card` |
| T4 | S5, S6, S7 | inline · `ShareAppPage.tsx`, función pura de contraseña, i18n, test | `feat(web): create a worker while sharing the app` |
| T5 | S8 | inline · `ShareAppViewModel.kt`, `ShareAppScreen.kt`, strings, test | `feat(android): create a worker while sharing the app` |
| T6 | S9 | inline · `MassasHistoryPage.tsx`, test | `feat(web): reconcile the massas history with the ERP mirror` |
| T7 | — | inline · suites, Docker, APK 0.6.3 / 6003 y despliegue | `chore(android): ship the 0.6.3 APK` |

## Log

### L1 — Pedido del usuario (verbatim)

> Che, estuvimos revisando con Wilkin los últimos detalles y tenemos tres puntos claros para encarar en esta tanda:
>
> 1. Remediación de 'Massas do dia' en el módulo Relatório (Web y Android):
> - En Android descubrimos la causa raíz exacta de por qué no se ve nada: hay un desacople entre el payload que devuelve
>   massasReportDayView en el backend y el contrato OpenAPI/Kotlin (MassasReportDayView). El modelo de Android espera
>   displayName, operatorKey, entLans, onlyErp y en balance reconciledSeats y pendingSeats. Como el backend hoy envía name,
>   erpOnly y omite esos campos en balance, Kotlinx.serialization tira excepción y el repositorio devuelve null
>   silenciosamente, dejando el card vacío. Alineá massas-report.service.ts y el endpoint /api/massas-launch/report para que
>   devuelva ambas variantes compatibles (displayName y name, onlyErp y erpOnly, operatorKey, entLans, y en balance
>   producedUnits, registeredUnits, differenceUnits, reconciledSeats, pendingSeats, producedBoxes, registeredBoxes). Con eso
>   Android deserializa limpio y renderiza el card.
> - En la Web (MassasReportSection.tsx), sumale la barra de balance (Produzido, Registrado ERP y Diferença con el color
>   correspondiente) idéntica a la que tenemos en Produção do dia, para que el supervisor vea el balance contable real
>   (-1931 un). En ReportsPage.tsx asegurate de pasarle la fecha correspondiente al reporte.
>
> 2. Creación rápida de operarios en 'Compartilhar Trindade' (Web ShareAppPage.tsx y Android ShareAppScreen.kt +
> ShareAppViewModel.kt):
> - En la sección 3 'Incluir Credenciais na Mensagem' (gobernada por el switch), sumá la creación rápida de operarios con
>   rol fijo Trabalhador (role_id: 2).
> - Dejá dos modos excluyentes cuando el switch esté activo:
>   a) 'Criar Novo Operário' (por defecto): campo Nome de exibição; campo Nome de usuário / Login (autocompletado en
>   minúsculas al escribir el nombre, editable); Rol fijo Trabalhador; contraseña generada automáticamente con mínimo 15
>   caracteres: base del usuario + secuencia numérica corrida '1234567890123...' (ej. para 'pedro' de 5 letras, agrega 10
>   dígitos -> 'pedro1234567890'); al presionar Compartilhar / Copiar ejecutar POST /api/admin/users con
>   { username, display_name, password, role_id: 2 }; si el usuario ya existe informar el error, si se crea armar el mensaje
>   con sus credenciales y el enlace oficial y abrir WhatsApp / copiar.
>   b) 'Selecionar Existente': el dropdown actual de usuarios registrados.
> - Paridad idéntica en Web y Android.
>
> 3. Histórico de Masas en la Web con la misma fidelidad que Android:
> - En MassasHistoryPage.tsx de la Web, hoy solo se leen los lanzamientos locales y no se pasa unifiedRows. Cargá también el
>   espejo del ERP para la fecha consultada y pasale unifiedRows a MassasCollectiveTable en modo lectura (readOnly={true}),
>   para que el lanzamiento pendiente de Jaime (2800 rolos) figure con su badge 'Pendente ERP' y la barra de balance marque
>   la diferencia (-1931 un), idéntico a Android.
>
> Metodología habitual: trunk-based estricto directo a main, sin ramas, sin Co-Authored-By ni menciones de IA, bajo ODD/TDD.
> Al finalizar las suites, redesplegá los contenedores Docker y armá el APK Release oficial firmado bumpeando a 0.6.3
> (code 6003).

### L2 — Evidencia de T1 a T6 (2026-10-30)

- **T1** (`1b416c6`): `massas-report.service.ts` emite las dos formas del operario y los dos pares de conteos del
  asiento. `npm test` en backend: 650 pruebas verdes.
- **T2** (`62b2f99`): `packages/contracts/openapi.json` regenerado y `ReportDayContractTest` deserializa el body
  real del backend con el mismo `Json` que arma la app. Verificado en el contenedor SDK 35 (job-87).
- **T3** (`12dc445`): `MassasBalanceBar` compartida por `MassasUnifiedTable` y `MassasReportSection`; `ReportsPage`
  pasa la fecha del reporte memoizada.
- **T4** (`482cbe7`): `deriveUsername` y `quickWorkerPassword` puras en `share-worker.ts`, modos en
  `ShareAppPage.tsx`, creación antes de WhatsApp/copiar.
- **T6** (`76a45a8`): `MassasHistoryPage` carga `/integration/aero/movements?date=` y pasa `unifiedRows` a
  `MassasCollectiveTable` con `readOnly`.

### L3 — Decisión de T5 (2026-10-30)

La creación en Android va por `AdminApi.createUser` con `role_id` como número y el rol fijo en `2`; la contraseña y
el login se derivan con las mismas funciones puras que la Web, con los mismos ejemplos fijados en
`SharePasswordTest`, para que las dos puntas no puedan entregar credenciales distintas para el mismo nombre. El
modo `EXISTING` conserva el dropdown y el campo de contraseña editable de siempre.

### L4 — Suites de T7 (2026-10-30)

- Backend: 620 pruebas, 620 verdes. Una corrida intermedia falló una prueba y no se reprodujo en las dos corridas
  completas posteriores con el log entero guardado (`/tmp/backend-suite-3.log`).
- Frontend: 451 pruebas, 451 verdes (incluye `share screen quick worker creation`).
- Android (contenedor SDK 35, `:app:testDebugUnitTest`): 202 pruebas verdes, incluidas `SharePasswordTest`,
  `ShareAppViewModelTest` y `ReportDayContractTest`. `ReportDetailScreenTest` necesitó los dos conteos de cajas que
  el contrato ahora exige.
