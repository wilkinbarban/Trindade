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
shortcuts://x-callback-url/run-shortcut?name=Aero32 Bridge
   │        (el Atajo recibe el sobre { url, auth, body, callback } como texto)
   ▼
POST     →  la url del sobre  (http://192.168.1.50:8000/api/entradasitem)
   │
   ▼
Abrir URLs → https://trindademasas.duckdns.org/massas-launch?aero_action=entrada&aero_data=<respuesta del ERP>
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

No hay campo de código de usuario, y no es un olvido: `ENT_USU` viaja con el **usuario en mayúsculas**, que
es lo que manda el binario Delphi de `base.apk`. El `CODIGO` numérico que devuelve el ERP se muestra al
probar la conexión y queda en la bitácora, pero no se pide ni se guarda. Un teléfono que todavía tenga el
campo cargado de una versión anterior lo descarta al leer los ajustes.

Todo lo demás lo resuelve el sistema, porque no es una decisión del operario:

| Dato | Cómo se obtiene |
| --- | --- |
| Usuario del asiento (`ENT_USU`) | El usuario del panel, en mayúsculas |
| Cantidad (`PRO_QTD`) | Siempre en **unidades**: `cajas × unidades por caja`. No existe lanzamiento por cajas |
| Serie del asiento (`ENT_SER`, `PED_SER`) | Fija en `APL`, la que trae quemada el binario Delphi de `base.apk` |

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
| Atajo | La página es HTTPS y el WebService HTTP | Abre el atajo **`Aero32 Bridge`** con el sobre de `/usuarios/`, que hace el `POST` fuera de Safari y vuelve con la respuesta |

El segundo camino no es una preferencia, es la única salida: en el despliegue real Safari rechaza el
destino HTTP antes de que salga un paquete, así que probar «desde el navegador» es probar una cosa
distinta de la que va a hacer el lanzamiento.

Resultados del camino «navegador»:

| Resultado | Significado |
| --- | --- |
| `Conectado como …` | Credenciales válidas; muestra el `CODIGO` que informa el ERP |
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

**Adónde vuelve el atajo.** El panel se mudó al Panel Admin, así que la prueba apunta su callback a
`/admin?tab=integration` en vez de a la pantalla de lanzamiento. El atajo abre exactamente la URL que se le
entrega, y una que aterrizara en `/massas-launch` dejaba el veredicto en una pantalla que ya no puede
dibujarlo: el operario volvía, no veía nada y el intento quedaba sin respuesta visible. El panel lee sus
propios parámetros (`aero_action`, `aero_data`), limpia el query con `replaceState` y levanta el mismo
diálogo de resultado que usa el camino del navegador, con la línea de auditoría correspondiente.

## 5. Receta del Atajo (Apple Shortcuts)

Un archivo `.shortcut` tiene que estar firmado por el dispositivo que lo instala, e iOS solo acepta
enlaces oficiales de iCloud: generarlo en el servidor produce un archivo que la web no puede entregar.
Por eso el panel entrega los valores copiables y el enlace profundo a la app.

Es **un solo atajo**, y se arma una sola vez:

| Atajo | Para qué | Acciones |
| --- | --- | --- |
| **`Aero32 Bridge`** | Lleva y trae cualquier llamada al ERP | 8 |

El nombre debe coincidir exactamente: es la clave con la que la página lo invoca (se puede cambiar en el
panel, campo «nome do atajo»).

### `Aero32 Bridge` — el puente

El atajo no sabe de masas ni de stock. Recibe un sobre, hace el `POST` y devuelve la respuesta:

```json
{
  "url": "http://192.168.1.50:8000/api/entradasitem",
  "auth": "Basic d2lsa2luOnNlbmhh",
  "body": "{\"PRO_COD\":\"001192\",\"PRO_DES\":\"MASSA QUADRADA\",\"PRO_QTD\":1500,\"PRO_CST\":\"000\",\"ENT_DTA\":\"2026-10-28\",\"ENT_USU\":\"WILKIN\",\"ENT_SER\":\"APL\",\"ENT_OBS\":\"\"}",
  "callback": "https://trindademasas.duckdns.org/massas-launch?aero_action=entrada&aero_data="
}
```

Los cuatro valores son fijos por llamada, y el panel los muestra con su botón de copiar en el diálogo
*Como instalar o Atalho no iPhone*:

| Clave del sobre | Qué es | Dónde se usa |
| --- | --- | --- |
| `url` | El endpoint completo, credenciales aparte | campo **URL** del `POST` |
| `auth` | `Authorization: Basic …` en base64, armado en el momento | **Encabezados** → `Authorization` |
| `body` | El JSON del pedido **serializado como texto** | **Cuerpo de la petición** → `Texto` |
| `callback` | La vuelta a esta página, con la acción ya puesta | **Abrir URLs** al final |

Pasos, en el orden en que el diálogo los muestra:

0. Abrir **Atajos** → `+` y nombrarlo `Aero32 Bridge`.
1. **Obtener diccionario de la entrada** — la entrada del atajo.
2. **Obtener valor del diccionario**, clave `url`.
3. **Obtener valor del diccionario**, clave `auth`.
4. **Obtener valor del diccionario**, clave `body`.
5. **Obtener valor del diccionario**, clave `callback`.
6. **Obtener contenido de URL**: la URL es el valor `url`; método `POST`; en **Encabezados**
   `Authorization` = valor `auth`, `Content-Type` = `application/json` y `Accept` = `application/json`; en
   **Cuerpo de la petición** elegir `Texto` e insertar el valor `body`.
7. **Codificar URL** sobre el resultado de la acción 6 (la respuesta del ERP).
8. **Abrir URLs**: valor `callback` + el resultado codificado, uno a continuación del otro.

Ese es el atajo completo, y es el único. El pedido de entrada, la baja, la consulta de movimientos y la
prueba de conexión salen por ahí: lo único que cambia es el sobre que manda la página.

Dos detalles que parecen menores y no lo son:

- **El `body` va como texto, no como diccionario.** Si el sobre trajera un objeto y el teléfono lo
  insertara en el cuerpo, iOS lo convertiría en una lista de propiedades y el ERP recibiría algo que no es
  JSON. La página serializa antes de disparar el atajo, y el atajo solo transporta.
- **La credencial viaja en el sobre.** Se podría quemar en el atajo, y así era en el diseño de dos atajos.
  Viajando en el sobre, cambiar la contraseña en la web no obliga a volver a tocar el iPhone; el precio son
  dos lecturas de diccionario más, que es la diferencia entre 6 y 8 acciones.

### Cómo vuelve la respuesta

El atajo abre `callback` + la respuesta del ERP, codificada. La página separa el prefijo, decodifica y lee
el cuerpo real. La acción viaja en el parámetro `aero_action`:

| `aero_action` | Endpoint | Qué hace la página al volver |
| --- | --- | --- |
| `entrada` | `/entradasitem` | Escribe el resultado en la bitácora y sigue con el producto siguiente de la cola |
| `saida` | `/saidasitem` | Escribe el resultado; no hay cola que continuar |
| `movimentos` | `/movimentoitem` | Guarda el día en `aero_movements` y lo cuenta con el lente de la pantalla que lo pidió |
| `test` | `/usuarios/` | Cierra la prueba de conexión con lo que contestó la empresa |

Los parámetros se leen y se borran de la URL, así que un *pull-to-refresh* en la fábrica no vuelve a
procesar el mismo resultado. Un teléfono que todavía tenga los atajos anteriores sigue funcionando: la
página también entiende `aero_sync` y `aero_test`, el formato viejo.

## 6. Contrato con el WebService

Cabeceras en todas las llamadas:

```
Authorization: Basic base64(usuario:senha)
Accept: application/json
Content-Type: application/json
```

`POST /usuarios/` → `{ "success": true, "data": { "CODIGO": 12, "SENHA": "…", "NOME": "WILKIN" } }`.
`CODIGO` es un dato informativo: lo que viaja en `ENT_USU` es el nombre en mayúsculas, como en el binario
Delphi. La ruta solo acepta `POST`; un `GET` devuelve `405 Supported methods: POST`.

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
- `ENT_USU`: el nombre de usuario formateado siempre en mayúsculas (`.toUpperCase()`), tal como lo hace `base.apk`.

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
- **`PRO_QTD` vuelve en unidades humanas, no escalado.** El pedido va ×100 (`2000` = 20 unidades) porque
  Firebird necesita el entero; la respuesta trae `20.0`, con la máscara `##0.00` que aplica `libso.so`.
  Comparar contra el escalado que se envió da diferencias de 100× en cualquier conciliación.

Los códigos de producto (`PRO_COD`) salen tal cual de `stock.catalog.ts` (`5000` = MASSA ROLO 1KG,
`1045` = CASEIRA, …): son idénticos a los del ERP. Las fechas van en `YYYY-MM-DD` y en la zona de São
Paulo, no en UTC.

### 6.4 Espejo persistido del día (`/api/integration/aero/movements`)

El atajo corre en un solo teléfono, pero el espejo lo lee toda la planta. La respuesta del ERP se guarda
como snapshot del día para que cualquier dispositivo la vea sin volver a ejecutar el atajo:

- `POST /api/integration/aero/movements` — cuerpo `{ date: "YYYY-MM-DD", movements: [...] }`, un elemento
  por asiento con `ent_lan`, `product_code`, `product_desc`, `quantity`, `entry_date`, `operator_name` y
  `movement_type` (`"E"` o `"S"`). Responde `201 { saved }`. Validación estricta: un cuerpo que el módulo
  no sabe describir se rechaza con `400 INVALID_MOVEMENTS` y no escribe nada.
- `GET /api/integration/aero/movements?date=YYYY-MM-DD` — responde `{ date, movements }` ordenado por
  `ent_lan`. Cualquier operario autenticado puede leerlo: es una copia de lo que la empresa ya registró.
- `UNIQUE(movement_date, ent_lan)`: una segunda sincronización del mismo día actualiza los asientos que
  reconoce en lugar de duplicarlos. Los asientos que la respuesta ya no menciona **no** se borran — en
  Aero32 un asiento no desaparece, y una respuesta parcial es más probable que un borrado.
- `operator_name` se normaliza a mayúsculas al guardar: el ERP responde `ENT_USU` en mayúsculas y dos
  grafías del mismo operador no deben renderizarse como dos chips distintos.
- La tabla `aero_movements` es la **revisión 7** del esquema: se crea con `npm run db:migrate`, nunca al
  arrancar (Production Immutability Gate). Contra una base sin migrar los dos endpoints responden
  `503 AERO_MOVEMENTS_NOT_MIGRATED`.
- `synced_at` es cuándo Trindade supo del ERP, no cuándo ocurrió el movimiento: el asiento es inmutable
  en Aero32 y `entry_date` es lo que hizo la fábrica.

**Un lanzamiento conciliado no se toca.** Cuando el asiento del ERP y el lanzamiento local describen la
misma producción —misma fecha, mismo código, misma cantidad y mismo operario—, esa fila queda conciliada y
deja de ofrecer editar y eliminar, para cualquier rol. No es una restricción de permisos sino de
consistencia: Delphi ya asentó esa entrada, y corregirla en Trindade dejaría las dos bases contando cosas
distintas sin que nadie vuelva a mirar. La pantalla lo dice con la insignia **Conciliado** en lugar de los
botones apagados, y el backend rechaza el `PUT` y el `DELETE` de una fila conciliada aunque se los pida por
fuera de la interfaz. Si el asiento del ERP está mal, se corrige en Aero32; Trindade no puede ser el origen
de esa corrección.

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
| `result` | `via=shortcut aero_test=<cuerpo del ERP>` (`true`, `false` o el JSON crudo) | `null` si fue `true`; `shortcut:error` si fue `false`; `shortcut:unknown` si no se pudo leer |
| `result` | `the operator returned without a shortcut result` | `shortcut:no_verdict` |

`via=shortcut` nombra el medio, no la receta: tanto el atajo único como el viejo escriben esa palabra.
Quien quiera distinguirlos mira `launch_sync`, que cierra con `via=bridge` y la acción en la nota
(`action=entrada`), más la respuesta del ERP truncada.

## 8. Procedimiento de prueba en fábrica

Antes de ir:

- [ ] `docker compose ps` con `web` y `api` sanos, y `https://trindademasas.duckdns.org` respondiendo 200.
- [ ] El iPhone tiene el Atajo `Aero32 Bridge` creado (8 acciones) y **probado a mano** con un sobre de ejemplo.
- [ ] El iPhone está en el Wi-Fi de la fábrica (el Atajo no funciona por datos móviles: la IP es interna).

En la fábrica:

1. Abrir la Web en Safari, entrar como `wilkin`.
2. *Lançamento de Masas* → **Configuração Integração Empresa** → completar URL, usuario y contraseña.
3. **Testar Conexão**. El botón abre `Aero32 Bridge` con el sobre de `/usuarios/`; al volver, la tarjeta
   dice si la empresa respondió y con qué `CODIGO`. Lo que importa es que no quede en «sin veredicto»: eso
   significa que el puente no llegó a volver.
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
  de «bloqueo del navegador», no para validar credenciales en producción. En ese despliegue el test va por
  el puente, que sí puede.
- La respuesta vuelve por la URL del callback: un rango de movimientos muy grande puede superar lo que iOS
  está dispuesto a abrir. Es un motivo más para no pedir historiales largos desde el teléfono.

## 10. Evolución y Roadmap: Bajas de Stock y Consulta de Movimientos

A partir de la ingeniería inversa de `base.apk`, el modelo de datos de la empresa soporta dos
funcionalidades clave a incorporar. El contrato ya está listo -- el atajo único sabe llevar `saidasitem` y
`movimentoitem` --, lo que falta es la interfaz que los dispare:

### 10.1 Módulo de Bajas / Saídas de Insumos y Masas
- **Objetivo**: Permitir dar de baja stock por mermas, desecho o consumo interno directamente desde Trindade.
- **Ruta WebService**: `POST /saidasitem` con prefijo `PED_*` (`PED_DTA`, `PED_USU`, `PED_SER: "APL"`).
- **Implementación**:
  - Reutiliza la cola y el mismo atajo único, con `aero_action=saida` en la vuelta.
  - Escala la cantidad x100 y formatea el código a 6 dígitos con ceros a la izquierda.

### 10.2 Vista de Movimientos del Usuario (ERP vs Trindade) — implementado
- **Objetivo**: Mostrar en la interfaz de Trindade qué se registró efectivamente en el ERP, y permitir
  importar al lanzamiento local lo que se hizo desde otra vía.
- **Ruta WebService**: `POST /movimentoitem` con `{"NOMUSU": "WILKIN", "DTINI": "...", "DTFIM": "...", "ENTSAI": "E"}`.
  Enviando `NOMUSU: null` el ERP devuelve los movimientos de todos los operarios de la planta.
- **Implementación**:
  - Pestaña «Espelho do ERP» en Lançamento de Massas, con el snapshot del día persistido (§6.4) y
    operario por asiento en chips.
  - Cruza el historial local de Trindade con el número de asiento Firebird (`ENT_LAN`) y marca
    «Conciliado» cuando ambos lados coinciden.
  - Los asientos del ERP son **solo lectura** en Trindade; importar crea un lanzamiento local nuevo,
    nunca edita el asiento de la empresa.
  - La consulta también se dispara desde Recebimento, en el relatório: el atajo vuelve a la ruta del
    reporte (`aero_return` es la propia ruta, validada como path relativo) y el modal cuenta sólo los
    insumos. El snapshot se guarda entero en las dos pantallas; lo que cambia es el lente, que el
    backend responde ya desglosado como `{ saved, massas, insumos }` (§6.4).

