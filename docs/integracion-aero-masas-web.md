# Integração Aero32 — Lançamento de Masas → Estoque da Empresa

Documento operativo de la integración construida. Complementa —no reemplaza— el contrato del
WebService (`docs/registro-integracion-aero-webservice.md`) y las notas de la API
(`docs/integracion-aero32.md`).

## 1. Alcance

La integración es **exclusiva del usuario `wilkin` con rol `Administrador`**. Para cualquier otro usuario
no existe: el panel no se renderiza, el switch no aparece y el backend rechaza la bitácora con `403`
porque la autoridad es la base de datos, nunca el cuerpo del pedido ni el estado del navegador.

El objetivo de la primera etapa es **medir, no automatizar**. El sistema:

1. persiste el lanzamiento en Trindade como siempre;
2. emite un pedido de sincronización hacia el Atajo de iOS;
3. registra en un log del servidor qué se intentó, con qué destino y qué respondió el WebService.

Ninguna de esas etapas puede perder producción: si la empresa no contesta, el lanzamiento ya está
guardado en Trindade.

## 2. Por qué un Atajo y no un `fetch`

La Web corre en `https://trindademasas.duckdns.org`. El WebService de la fábrica escucha en HTTP plano
sobre la red interna (`http://192.168.1.50:8000/api`). Safari —WebKit— bloquea ese salto por diseño en
dos capas: *mixed content* (contenido inseguro dentro de una página segura) y *Private Network Access*
(una página pública no puede alcanzar direcciones privadas). No es un error de configuración que se
arregle con headers: es una negativa del navegador antes de emitir el paquete.

Un Atajo de iOS corre como proceso nativo, sin origen de página. Puede hablar HTTP plano contra la IP
interna cuando el teléfono está en el Wi-Fi de la fábrica. Por eso el pedido sale de la página y termina
en el Atajo.

## 3. Flujo completo

```
Operador (wilkin)
   │  carga cajas por producto y confirma el lanzamiento
   ▼
POST /api/massas-launch            ← Trindade guarda la producción (autoridad)
   │
   ▼
cola local (localStorage)          ← una entrada por producto, sobrevive a la ida y vuelta
   │
   ▼
shortcuts://x-callback-url/run-shortcut?name=…
   │        (el Atajo recibe { url, authorization, body })
   ▼
POST http://192.168.1.50:8000/api/entradasitem
   │
   ▼
Abrir URLs → https://trindademasas.duckdns.org/massas-launch?aero_sync=ok&aero_msg=…
   │
   ▼
La página lee el retorno, escribe el resultado en el log y sigue con el producto siguiente
```

`/entradasitem` acepta **un producto por request**, así que un lanzamiento de tres productos son tres
idas y vueltas. La cola en `localStorage` es lo que hace que la segunda no se pierda si iOS abre la
vuelta en otra pestaña.

## 4. Configuración (panel «Configuração Integração Empresa»)

Dentro de *Lançamento de Masas*, debajo de la grilla, hay una tarjeta colapsable. Arranca abierta solo
cuando todavía no hay nada configurado.

| Campo | Qué es | Validación |
| --- | --- | --- |
| URL do WebService | Base del WebService, ej. `http://192.168.1.50:8000/api` | Debe empezar con `http://` o `https://`; se normaliza (sin barra final duplicada) |
| Usuário da empresa | Usuario del WebService (`ENT_USU`) | No vacío |
| Senha da empresa | Contraseña del WebService | No vacía; se muestra enmascarada |
| Quantidade enviada ao ERP | `UN` (unidades) o `CX` (cajas) | Por defecto `UN` |
| Série do lançamento | `ENT_SER` | Por defecto `APL`, hasta 6 caracteres, mayúsculas |
| — | Código de usuario (`ENT_USU`) | Lo aporta *Testar Conexão* a partir de `CODIGO` |

Los ajustes viven en el `localStorage` de **ese** iPhone, no en el servidor. Dos consecuencias: hay que
configurar una vez por teléfono, y borrar los datos de Safari equivale a borrar la configuración. El
botón **Limpar** hace lo mismo de forma explícita, con confirmación.

### Testar Conexão

Hace `GET {URL}/usuarios/` con Basic Auth y traduce el resultado a una frase útil:

| Resultado | Significado |
| --- | --- |
| `Conectado como … (código n)` | Credenciales válidas; guarda `CODIGO` como `ENT_USU` |
| `O WebService respondeu 401: …` | El servidor contestó y rechazó el login |
| `A rede respondeu, mas o WebService não libera CORS` | La IP es correcta; falta la cabecera CORS (el Atajo no la necesita) |
| `O Safari bloqueia HTTP a partir de uma página HTTPS` | Se detectó el bloqueo **antes** de intentar; es el caso normal en producción |
| `Não foi possível alcançar o WebService` | Red, IP o puerto equivocados |
| `O WebService não respondeu em 8 segundos` | Host alcanzable, sin respuesta |

Cada intento —exitoso o no— se registra en la bitácora del backend. El caso «bloqueado» se registra
igualmente: saber que el pedido nunca salió del teléfono es la mitad del diagnóstico.

## 5. Receta del Atajo (Apple Shortcuts)

Un archivo `.shortcut` debe estar firmado por el dispositivo que lo instala; generarlo en el servidor
produce un archivo que falla al abrir. Por eso el panel entrega la receta exacta —copiable— y el enlace
profundo a la app. Se arma una vez, en un minuto.

Nombre del atajo: **`Aero32 Estoque`** (el nombre debe coincidir exactamente: es la clave con la que la
página lo invoca).

1. Abrir **Atajos** → `+` → nombrarlo `Aero32 Estoque`.
2. Añadir **Obtener diccionario del texto de entrada** (la página envía un JSON como texto de entrada).
3. Añadir **Obtener valor del diccionario** → clave `url`.
4. Repetir para las claves `authorization` y `body`.
5. Añadir **Obtener contenido de URL**:
   - URL: la variable `url`
   - Método: `POST`
   - Cabeceras: `Authorization` = variable `authorization`, `Content-Type` y `Accept` = `application/json`
   - Cuerpo de la petición: `Texto` = variable `body`
6. Añadir **Obtener valor del diccionario** sobre la respuesta → clave `message`.
7. Añadir **Abrir URLs** con la URL de retorno que muestra el panel, seguida del mensaje codificado con
   la acción **Codificar URL**.

La página envía el sobre `{ url, authorization, body }`: la URL completa de `/entradasitem`, la cabecera
`Basic …` ya armada en base64 y el JSON del pedido como texto. El Atajo no calcula nada; solo transporta.

## 6. Contrato con el WebService

Cabeceras en todas las llamadas:

```
Authorization: Basic base64(usuario:senha)
Accept: application/json
Content-Type: application/json
```

`GET /usuarios/` → `{ "success": true, "data": { "CODIGO": 12, "SENHA": "…", "NOME": "WILKIN" } }`.
`CODIGO` es el `ENT_USU` que espera el ERP.

`POST /entradasitem`:

```json
{
  "PRO_COD": "5000",
  "PRO_DES": "MASSA ROLO 1KG",
  "PRO_QTD": 20,
  "ENT_DTA": "2026-02-10",
  "ENT_USU": 12,
  "ENT_SER": "APL",
  "ENT_DTANOT": "2026-02-10",
  "ENT_DTMOVI": "2026-02-10",
  "ENT_OBS": "",
  "LOTES": []
}
```

Los códigos de producto (`PRO_COD`) salen tal cual de `stock.catalog.ts` (`5000` = MASSA ROLO 1KG,
`1045` = CASEIRA, …): son idénticos a los del ERP. Las fechas van en `YYYY-MM-DD` y en la zona de São
Paulo, no en UTC.

## 7. Bitácora

Ruta interna: `POST /api/integration/aero/log`, restringida al admin. El archivo se escribe en
`logs/aero-integration.log`, montado al host para poder leerlo por SSH sin entrar al contenedor:

```bash
ssh <host> 'tail -n 50 /ruta/al/proyecto/logs/aero-integration.log'
```

Una línea JSON por evento:

```json
{"timestamp":"2026-02-10T12:00:00.000Z","event":"launch_sync","phase":"requested","user":"wilkin",
 "destinationUrl":"http://192.168.1.50:8000/api/entradasitem","httpStatus":null,"payload":{…},
 "response":null,"error":null}
```

Eventos: `test_connection`, `launch_sync`, `read_history`. La identidad se toma del token verificado
contra la base, nunca del cuerpo. La cabecera `Authorization` **no se escribe nunca** (lleva las
credenciales en base64) y el payload/respuesta se truncan para que una línea siga siendo una línea.

## 8. Procedimiento de prueba en fábrica

Antes de ir:

- [ ] `docker compose ps` con `web` y `api` sanos, y `https://trindademasas.duckdns.org` respondiendo 200.
- [ ] El iPhone tiene el Atajo `Aero32 Estoque` creado y **probado a mano** con un JSON de ejemplo.
- [ ] El iPhone está en el Wi-Fi de la fábrica (el Atajo no funciona por datos móviles: la IP es interna).

En la fábrica:

1. Abrir la Web en Safari, entrar como `wilkin`.
2. *Lançamento de Masas* → **Configuração Integração Empresa** → completar URL, usuario y contraseña.
3. **Testar Conexão**. Si el WebService no libera CORS verás el aviso correspondiente: es esperable.
   Lo que importa es que el mensaje no diga «não foi possível alcançar».
4. Cargar **un** producto, una caja, y confirmar con el switch *Sincronizar com ERP da Empresa* activo.
5. iOS abre el Atajo; al volver, la tarjeta de confirmación debe desaparecer y aparecer el resultado.
6. Verificar en el ERP que la entrada existe, y en el log del servidor las dos líneas (`requested` y
   `result`).
7. Repetir con dos productos para comprobar que la cola envía el segundo después del retorno del primero.
8. Probar el camino de fallo: apagar el Wi-Fi, lanzar, y confirmar que el lanzamiento **igual** quedó
   guardado en Trindade y que la tarjeta de confirmación manual permite registrar la falla.

## 9. Límites conocidos

- El Atajo necesita el teléfono en la red de la fábrica; no hay acceso remoto al WebService.
- Sin retorno del Atajo (el operador lo cancela), la página no puede adivinar el resultado: lo pregunta.
- La configuración es por dispositivo, no por usuario del sistema.
- El test de conexión desde la página HTTPS siempre fallará por diseño; sirve para distinguir «red mal»
  de «bloqueo del navegador», no para validar credenciales en producción.
