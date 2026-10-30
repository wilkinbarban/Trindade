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
