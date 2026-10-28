# Integração Aero32 — Lançamento de Masas → Estoque da Empresa

Documento operativo de la integración construida. Complementa —no reemplaza— el contrato del
WebService (`docs/registro-integracion-aero-webservice.md`) y las notas de la API
(`docs/integracion-aero32.md`).

## 1. Alcance

La integración es **exclusiva del usuario `wilkin` con rol `Administrador`**. Para cualquier otro usuario
no existe: el panel no se renderiza, el switch no aparece y el backend rechaza la bitácora con `403`
porque la autoridad es la base de datos, nunca el cuerpo del pedido ni el estado del navegador.

El alcance funcional abarca el ciclo completo de inventario reflejado en la app nativa Android (`base.apk`):
1. **Lanzamiento de Masas / Producción (Entradas)**: `POST /entradasitem` (activo en fase piloto).
2. **Baja de Stock / Consumo (Salidas)**: `POST /saidasitem` (especificado e incorporable con mismo flujo).
3. **Auditoría de Movimientos por Usuario**: `POST /movimentoitem` (consulta cruzada de asientos ERP vs Trindade).

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
   │        (el Atajo recibe el JSON del pedido como texto, tal cual)
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

El panel pide **tres datos obligatorios y nada más**, que es lo que un operario puede saber de memoria en la
fábrica. Con esos tres la sincronización ya se puede activar:

| Campo | Qué es | Validación |
| --- | --- | --- |
| URL do WebService | Base del WebService, ej. `http://192.168.1.50:8000/api` | Debe empezar con `http://` o `https://`; se normaliza (sin barra final duplicada) |
| Usuário da empresa | Usuario del WebService | No vacío |
| Senha da empresa | Contraseña del WebService | No vacía; se muestra enmascarada |
| Código do usuário no ERP | Id numérico del operario (`ENT_USU`) | **Opcional**; si queda vacío se usa `1` |

Todo lo demás lo resuelve el sistema, porque no es una decisión del operario:

| Dato | Cómo se obtiene |
| --- | --- |
| Código de usuario (`ENT_USU`) | Si se cargó a mano, ese; si no, el `CODIGO` que devuelva *Testar Conexão*; si tampoco, `1` |
| Cantidad (`PRO_QTD`) | Siempre en **unidades**: `cajas × unidades por caja`. No existe lanzamiento por cajas |
| Serie del asiento (`ENT_SER`) | Fija en `APL`, la que trae quemada el binario Delphi de `base.apk` |

El código **nunca bloquea** nada. Es un dato que solo el ERP conoce, y el atajo de prueba devuelve
`success` —un booleano—, no el `CODIGO`: leerlo exigiría otra acción en el teléfono. Por eso, en el
despliegue real de la fábrica, el código queda vacío y el lanzamiento sale como usuario `1`. Si el
operario lo conoce, lo carga en el campo opcional y los asientos quedan a su nombre.

Los ajustes viven en el `localStorage` de **ese** iPhone, no en el servidor. Dos consecuencias: hay que
configurar una vez por teléfono, y borrar los datos de Safari equivale a borrar la configuración. El
botón **Limpar** hace lo mismo de forma explícita, con confirmación.

### Testar Conexão

Hace `POST {URL}/usuarios/` con Basic Auth y traduce el resultado a una frase útil. El método importa: la
ruta de la fábrica solo acepta `POST` y un `GET` devuelve `405 Supported methods: POST`.

El botón tiene **dos caminos**, y elige solo:

| Camino | Cuándo | Qué hace |
| --- | --- | --- |
| Navegador | La página y el WebService comparten esquema (desarrollo local, o un WebService HTTPS) | El `fetch` responde en el acto y devuelve la frase |
| Atajo | La página es HTTPS y el WebService HTTP | Abre el atajo **`Aero32 Prueba`**, que hace el `POST` fuera de Safari y vuelve con el veredicto |

El segundo camino no es una preferencia, es la única salida: en el despliegue real Safari rechaza el
destino HTTP antes de que salga un paquete, así que probar «desde el navegador» es probar una cosa
distinta de la que va a hacer el lanzamiento.

Resultados del camino «navegador»:

| Resultado | Significado |
| --- | --- |
| `Conectado como … (código n)` | Credenciales válidas; guarda `CODIGO` como `ENT_USU` |
| `O WebService respondeu 401: …` | El servidor contestó y rechazó el login |
| `A rede respondeu, mas o WebService não libera CORS` | La IP es correcta; falta la cabecera CORS (el Atajo no la necesita) |
| `O Safari bloqueia HTTP a partir de uma página HTTPS` | Bloqueo detectado; con este build ya no aparece: el botón pasa al atajo |
| `Não foi possível alcançar o WebService` | Red, IP o puerto equivocados |
| `O WebService não respondeu em 8 segundos` | Host alcanzable, sin respuesta |

Resultados del camino «atajo», que es el que se ve en la fábrica:

| Veredicto | Significado |
| --- | --- |
| `success = true` | La empresa respondió y aceptó las credenciales: conexión correcta |
| `success = false` | El endpoint contestó y rechazó el login (usuario o contraseña) |
| Sin veredicto | El operario volvió sin ejecutar el atajo, o el atajo se cortó antes de leer `success` |

El atajo devuelve el campo `success` **tal como lo leyó**, y la página acepta las formas en que iOS lo
puede escribir (`true`, `1`, `ok`; y del otro lado `false`, `0`, `error`). Un valor que no reconoce no se
interpreta: la tarjeta dice que no hubo veredicto en vez de inventar un éxito.

Cada intento —exitoso o no— se registra en la bitácora del backend, en dos líneas: la de `requested`
antes de que el teléfono salga, y la de `result` cuando vuelve. Saber que el pedido nunca salió del
teléfono es la mitad del diagnóstico, y una línea `requested` sin `result` es exactamente eso.

## 5. Receta del Atajo (Apple Shortcuts)

Un archivo `.shortcut` tiene que estar firmado por el dispositivo que lo instala, e iOS solo acepta
enlaces oficiales de iCloud: generarlo en el servidor produce un archivo que la web no puede entregar.
Por eso el panel entrega los valores copiables y el enlace profundo a la app.

Son **dos atajos** y se arman una sola vez:

| Atajo | Para qué | Acciones |
| --- | --- | --- |
| **`Aero32 Estoque`** | Envía cada lanzamiento al ERP | 2 |
| **`Aero32 Prueba`** | Contesta *Testar Conexão* | 3 |

Los nombres deben coincidir exactamente: son la clave con la que la página los invoca (se pueden cambiar
en el panel, campo «nome do atajo»).

### `Aero32 Estoque` — el lanzamiento

Valores fijos, que el panel muestra con su botón de copiar y no cambian entre lanzamientos:

| Valor | Dónde se usa |
| --- | --- |
| URL del POST — `{wsUrl}/entradasitem` | campo **URL** de la acción 1 |
| Cabecera `Authorization: Basic …` | **Encabezados** de la acción 1 |
| URL de retorno — `{origen}/massas-launch?aero_sync=ok` | campo **URL** de la acción 2 |

1. Abrir **Atajos** → `+` → nombrarlo `Aero32 Estoque`.
2. Añadir **Obtener contenido de URL**: pegar la URL del POST, método `POST`, en **Encabezados**
   `Authorization` con el valor copiado y `Content-Type` = `application/json`, y en **Cuerpo de la
   petición** elegir `Texto` e insertar la variable **Entrada del atajo**.
3. Añadir **Abrir URLs** y pegar la URL de retorno.

La página envía como texto el cuerpo exacto del `POST /entradasitem`: el JSON del pedido, sin sobre. El
Atajo no calcula ni interpreta nada — entrega ese texto como cuerpo y vuelve a la app. Antes el texto era
un sobre `{ url, authorization, body }` que obligaba a tres lecturas de diccionario más el armado de la
respuesta: siete pasos en un teléfono, que es la razón por la que el atajo nunca llegó a existir.

### `Aero32 Prueba` — la conexión

No recibe nada: el endpoint, la cabecera y la vuelta viven dentro del atajo. Son tres acciones.

| Valor | Dónde se usa |
| --- | --- |
| URL del POST — `{wsUrl}/usuarios/` | campo **URL** de la acción 1 |
| Cabecera `Authorization: Basic …` | **Encabezados** de la acción 1 (el mismo valor del otro atajo) |
| URL de retorno — `{origen}/massas-launch?aero_test=` | campo **URL** de la acción 3, con la variable al final |

1. Abrir **Atajos** → `+` → nombrarlo `Aero32 Prueba`.
2. **Obtener contenido de URL**: la URL de arriba, método `POST`, en **Encabezados** `Authorization` y
   `Content-Type: application/json`, y **sin cuerpo**.
3. **Obtener valor del diccionario**: sobre la respuesta, con la clave `success`.
4. **Abrir URLs** y pegar la URL de retorno, con la variable `success` **al final**. Queda así:
   `…/massas-launch?aero_test=` + variable.

La página lee ese valor y lo escribe tal cual en la bitácora (`aero_test=true`), así que el log de la
fábrica muestra la respuesta del ERP y no una interpretación de ella.

## 6. Contrato con el WebService

Cabeceras en todas las llamadas:

```
Authorization: Basic base64(usuario:senha)
Accept: application/json
Content-Type: application/json
```

`GET /usuarios/` → `{ "success": true, "data": { "CODIGO": 12, "SENHA": "…", "NOME": "WILKIN" } }`.
`CODIGO` es el `ENT_USU` que espera el ERP.

### 6.1 Entrada de Producción (`POST /entradasitem`)

```json
{
  "PRO_COD": "005000",
  "PRO_DES": "MASSA ROLO 1KG",
  "PRO_QTD": 2000,
  "PRO_CST": "000",
  "ENT_DTA": "2026-02-10",
  "ENT_USU": "WILKIN",
  "ENT_SER": "APL",
  "ENT_DTANOT": "2026-02-10",
  "ENT_DTMOVI": "2026-02-10",
  "ENT_OBS": "Lancamento de massas via Trindade",
  "LOTES": []
}
```

Detalles del contrato ajustados con la fábrica:
- `PRO_COD`: 6 dígitos con ceros a la izquierda (`formatAeroProductCode`), idéntico al binario Delphi `base.apk`.
- `PRO_QTD`: entero escalado × 100 (`formatAeroQuantity`) para que Firebird no lo grabe como decimal `0.20`.
- `PRO_CST`: situación tributaria (`000` - Tributada integralmente de fabricación propia) requerida por el trigger `ENTRADADET_BIU_SPED_VERIFICA`.
- `ENT_USU`: código numérico o nombre de usuario formateado siempre en mayúsculas (`.toUpperCase()`) tal como lo hace `base.apk`.

### 6.2 Baja de Stock / Consumo (`POST /saidasitem`)

Mismo mecanismo operativo que `/entradasitem`, pero decrementa stock en Firebird para bajas por desecho, mermas o insumos:

```json
{
  "PRO_COD": "005000",
  "PRO_DES": "MASSA ROLO 1KG",
  "PRO_QTD": 2000,
  "PED_DTA": "2026-02-10",
  "PED_USU": "WILKIN",
  "PED_SER": "APL",
  "PED_DTANOT": "2026-02-10",
  "PED_OBS": "Saida de massas via Trindade",
  "LOTES": []
}
```

Diferencias con `/entradasitem`:
- Campos de cabecera con prefijo `PED_*` (`PED_DTA`, `PED_USU`, `PED_SER`, `PED_DTANOT`, `PED_OBS`).
- No envía `PED_DTMOVI`.
- Mantiene `PRO_COD` a 6 dígitos, `PRO_QTD` escalado × 100 y `PED_USU` en mayúsculas.

### 6.3 Consulta de Movimientos por Usuario (`POST /movimentoitem`)

Permite recuperar el historial de asientos registrados en el ERP para contrastar contra la producción de Trindade:

```json
{
  "CODFOR": null,
  "NOMUSU": "WILKIN",
  "CODPRO": "005000",
  "DTINI": "2026-02-10",
  "DTFIM": "2026-02-10",
  "ENTSAI": "E"
}
```

- Filtro `ENTSAI`: `"E"` (entradas), `"S"` (salidas/bajas) o `null` (ambos).
- Retorna array con `ENT_LAN` (número de asiento), `PRO_COD`, `PRO_DES`, `PRO_QTD`, `ENT_DTA`, `ENT_USU` y `ENTSAI`.

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

Eventos soportados: `test_connection`, `launch_sync`, `exit_sync`, `history_query`. La identidad se toma del token verificado
contra la base, nunca del cuerpo. La cabecera `Authorization` **no se escribe nunca** (lleva las
credenciales en base64) y el payload/respuesta se truncan para que una línea siga siendo una línea.

`test_connection` usa dos líneas y una nota:

| `phase` | `note` | `error` |
| --- | --- | --- |
| `requested` | `via=shortcut reason=mixed_content` (o `via=browser`) | `null`, o `invalid_config` si faltaban datos |
| `result` | `via=shortcut aero_test=true` | `null` si fue `true`; `shortcut:error` si fue `false`; `shortcut:unknown` si no se pudo leer |
| `result` | `the operator returned without a shortcut result` | `shortcut:no_verdict` |

## 8. Procedimiento de prueba en fábrica

Antes de ir:

- [ ] `docker compose ps` con `web` y `api` sanos, y `https://trindademasas.duckdns.org` respondiendo 200.
- [ ] El iPhone tiene el Atajo `Aero32 Estoque` creado y **probado a mano** con un JSON de ejemplo.
- [ ] El iPhone está en el Wi-Fi de la fábrica (el Atajo no funciona por datos móviles: la IP es interna).

En la fábrica:

1. Abrir la Web en Safari, entrar como `wilkin`.
2. *Lançamento de Masas* → **Configuração Integração Empresa** → completar URL, usuario y contraseña.
3. **Testar Conexão**. El botón abre `Aero32 Prueba`; al volver, la tarjeta dice si la empresa respondió.
   Lo que importa es que no diga «sin veredicto»: eso significa que el atajo no llegó a leer `success`.
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

## 10. Evolución y Roadmap: Bajas de Stock y Consulta de Movimientos

A partir de la ingeniería inversa de `base.apk`, el modelo de datos de la empresa soporta dos funcionalidades clave a incorporar:

### 10.1 Módulo de Bajas / Saídas de Insumos y Masas
- **Objetivo**: Permitir dar de baja stock por mermas, desecho o consumo interno directamente desde Trindade.
- **Ruta WebService**: `POST /saidasitem` con prefijo `PED_*` (`PED_DTA`, `PED_USU`, `PED_SER: "APL"`).
- **Implementación**:
  - Reutiliza la misma cola y mecanismo de Atajo de iOS (`Aero32 Saida`).
  - Escala la cantidad x100 y formatea el código a 6 dígitos con ceros a la izquierda.

### 10.2 Vista de Movimientos del Usuario (ERP vs Trindade)
- **Objetivo**: Mostrar en la interfaz de Trindade qué se registró efectivamente en el ERP bajo el usuario logueado (`NOMUSU`).
- **Ruta WebService**: `POST /movimentoitem` enviando `{"NOMUSU": "WILKIN", "DTINI": "...", "DTFIM": "...", "ENTSAI": "E"}` (o `"S"` para bajas).
- **Implementación**:
  - Cruza el historial local de Trindade con el número de asiento Firebird (`ENT_LAN`).
  - Muestra badges en el historial: entrada confirmada en ERP con su número de asiento (`ENT_LAN: 10542`), o baja registrada (`ENTSAI: "S"`).

