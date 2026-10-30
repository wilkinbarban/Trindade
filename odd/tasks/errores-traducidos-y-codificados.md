# Errores de masas: capturados, codificados y en portugués

Ninguna negativa del servidor llega al operario en inglés ni como un número suelto. El backend responde
en portugués con su código semántico, la web traduce por código y mensaje, y Android viaja el código
hasta la pantalla para explicar la causa.

## Specs

- **S1 — Backend en portugués con su código.** En `massas.routes.ts`:
  - 403 `MASSAS_ERROR_CODES.FORBIDDEN` → `'Este lançamento pertence a outro operário'`.
  - 409 `MASSAS_ERROR_CODES.ALREADY_IMPORTED` → `'Este lançamento do ERP já foi importado'`.
  - 409 `MASSAS_ERROR_CODES.HISTORY_FROZEN` → `'O histórico de massas é somente leitura: dias passados não podem ser alterados'`.
  - El resto de los `apiError(...)` de masas también sale en portugués: ningún texto en inglés viaja al
    cliente desde este módulo.
- **S2 — Web traduce por mensaje y por código.** `resolveApiErrorKey` reconoce `HISTORY_FROZEN`,
  `FORBIDDEN` y `ALREADY_IMPORTED` —por mensaje y por código `MASSAS_*`— y los traduce por i18n; nunca
  deja pasar un texto en inglés al operario. `client.ts` le entrega el `code` que acompaña al `error`.
- **S3 — Android explica la causa.** `MassasWriteResult.Refused` guarda `errorCode` y `message`; el
  ViewModel muestra el mensaje traducido:
  - `MASSAS_HISTORY_FROZEN` → `'O histórico de massas é somente leitura: dias passados não podem ser alterados.'`
  - `MASSAS_FORBIDDEN` → `'Este lançamento pertence a outro operário.'`
  - `MASSAS_ALREADY_IMPORTED` → `'Este lançamento do ERP já foi importado.'`
  - Fallback para otros códigos: el texto del servidor, o el código amigable traducido.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1 | backend: mensajes en portugués en `massas.routes.ts` + tests de ruta | c47f7f2 |
| T2 | S2 | web: `resolveApiErrorKey(status, message, code?)`, claves i18n pt/es, `client.ts` pasa el código | b31bda5 |
| T3 | S3 | android: `Refused(statusCode, errorCode, message)`, parseo del cuerpo de error, traducción en el VM | pending |

## Log

### L1 — Pedido del usuario (verbatim)

> Wilkin nos pide una auditoría y remediación de cómo se manejan y muestran los errores en ambas
> aplicaciones: que todos los errores estén capturados, se muestren de forma correcta y 100% traducidos
> al idioma del usuario (portugués):
>
> 1. En el Backend (massas.routes.ts):
> Actualmente varios mensajes de error salen en inglés crudo:
> - 403: 'This ERP seat belongs to another operator' -> debe ser 'Este lançamento pertence a outro operário' (MASSAS_ERROR_CODES.FORBIDDEN).
> - 409: 'This ERP seat was already imported' -> debe ser 'Este lançamento do ERP já foi importado' (MASSAS_ERROR_CODES.ALREADY_IMPORTED).
> - 409: 'O histórico de massas é somente leitura: dias passados não podem ser alterados' (MASSAS_ERROR_CODES.HISTORY_FROZEN).
>
> 2. En la Web (api-error-normalizer.ts y MassasLaunchPage.tsx):
> Asegurate de que resolveApiErrorKey reconozca tanto los mensajes como los códigos MASSAS_ERROR_CODES
> (HISTORY_FROZEN, FORBIDDEN, ALREADY_IMPORTED) y los traduzca a través de i18n, sin que jamás se le
> escape un texto en inglés al operario.
>
> 3. En Android (MassasRepository.kt y MassasLaunchViewModel.kt):
> Actualmente MassasWriteResult.Refused solo guarda val statusCode: Int, y en la UI muestra 'O servidor
> recusou o lançamento (código 409)' o '(código 403)' sin explicar la causa real.
> Ampliá MassasWriteResult.Refused para que capture el code o message devuelto por el backend (ej.
> errorCode: String?, message: String?), y en MassasLaunchViewModel mostrá el mensaje traducido
> correspondiente en portugués:
> - MASSAS_HISTORY_FROZEN -> 'O histórico de massas é somente leitura: dias passados não podem ser alterados.'
> - MASSAS_FORBIDDEN -> 'Este lançamento pertence a outro operário.'
> - MASSAS_ALREADY_IMPORTED -> 'Este lançamento do ERP já foi importado.'
> - Fallback para otros códigos con el texto traducido del servidor o código amigable.
>
> Dejemos todos los errores prolijos, traducidos y con tests unitarios en verde directo a main.

### L2 — Segunda tanda (verbatim)

> Buenas che. Dos temas puntuales que estuvimos auditando con Wilkin y que tenemos que resolver prolijamente:
>
> 1. Sobre Android (T3 en curso):
> En job-78 el build de Gradle falló con:
> Unresolved reference 'StringRes' en MassasLaunchViewModel.kt línea 269.
> Falta agregar el import: import androidx.annotation.StringRes. Agregalo para dejar en verde el test y commitear T3.
>
> 2. Recebimento en Relatório (formato de exportación y UI):
> Wilkin nos pide cambiar el formato de las líneas de Recebimento: no quiere que aparezca el número de lanzamiento
> '(Lançamento #35936 · JONAS)', sino únicamente el nombre del operario '(JONAS)'.
> - En packages/backend/src/modules/reports/export.service.ts: en la sección de Recebimento, cambiar la línea a:
>   • ${receipt.productDesc}: ${receipt.quantity} un (${receipt.operatorName})
>   (y actualizar las aserciones en export.service.test.ts).
> - En packages/frontend/src/components/reports/ReceiptsSection.tsx: alinear la UI para mostrar solo
>   {receipt.operatorName} sin el número de asiento.
>
> 3. Unión de Relatório con Lanzamiento de Masas (Massas do dia):
> Al compartir el relatório por WhatsApp, la sección de Massas do dia mostraba cantidades y operarios errados
> (atribuyendo a Wilkin lanzamientos importados como Rolo 1kg de Joao H y Quadrada de Jefferson).
> - Causa raíz: en massas-report.service.ts (massasReportDayView), al iterar launches:
>   const name = (launch.display_name || launch.username || 'Operador').trim();
>   Si el lanzamiento proviene de una importación (launch.ent_lan != null), en la base de datos user_id quedó con
>   el usuario que importó (Wilkin) cuando el operario del ERP no tenía cuenta en Trindade (ej. JOAO H, JEFFERSON).
> - Solución: si launch.ent_lan != null, buscá el asiento en seats por ent_lan: si existe, el nombre del operario
>   debe salir del asiento (seat.operator_name o su display name resuelto con knownNames), marcando erpOnly: true
>   si no tiene usuario local.
> - En la BD de producción (/app/packages/backend/data/trindade.db): el lanzamiento id 88 (2 cajas de producto 22
>   = 30 unidades a nombre de Wilkin con ent_lan: null del 2026-10-30) fue una prueba manual residual previa a la
>   importación que infla las cantidades; borralo para que la producción del día concilie exacta con la fábrica.
>
> Avancemos con esto bajo TDD, commits atómicos en main, compilación del APK Release y redespliegue de Docker.

## Specs (segunda tanda)

- **S9** — Las líneas de Recebimento del relatório exportado se leen `• PRODUCTO: N un (OPERARIO)`:
  nunca `(Lançamento #35936 · JONAS)`.
- **S10** — La tarjeta de Recebimento en pantalla muestra el producto, las unidades y el nombre del
  operario; no muestra el número de asiento del ERP.
- **S11** — Un lanzamiento con `ent_lan` se atribuye al operario de su asiento en el ERP, resuelto a su
  nombre local cuando tiene cuenta (y `erpOnly: false`), o al nombre tal cual lo escribió la tableta con
  `erpOnly: true` cuando no la tiene. El usuario que importó no conserva esa producción.
- **S12** — Un lanzamiento sin `ent_lan` se sigue atribuyendo a quien lo escribió en Trindade.
- **S13** — `MassasLaunchViewModel` compila con `androidx.annotation.StringRes` importado y la suite de
  `com.trindade.app.massas.*` y `com.trindade.app.ui.*` queda en verde.

## Tasks (segunda tanda)

- **T4** Rechazos del espejo ERP (backend + normalizer + i18n) en portugués — commit `11a3149`.
- **T5** Recebimento sin número de asiento (export, UI, e2e, i18n) — commit `5e69e0e`.
- **T6** Atribución de lanzamientos importados por asiento (`massasReportDayView`) — commit `06d5c38`.
- **T7** Import `StringRes` y suite Android de massas+ui en verde — commit `55ede10`.
- **T8** Borrar el lanzamiento residual 88 de producción con respaldo previo (S11 evidencia).
- **T9** APK Release firmado y redespliegue Docker (api + web).
