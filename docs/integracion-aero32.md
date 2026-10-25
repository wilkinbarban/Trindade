# Integración Aero32 ↔ Trindade: auditoría de ingeniería inversa y hoja de ruta

Documento de referencia para la interoperabilidad entre los módulos operativos de Trindade (lanzamiento de masas y
stock) y el ERP de la empresa (**Aero32 / AeroSML**, cliente Windows del proveedor Aero Informática).

Todo lo que sigue proviene de **análisis estático de solo lectura** sobre una copia de trabajo del cliente en Linux.
El host Windows de producción no se modificó: los binarios se copiaron, se desempacaron y se analizaron fuera de él.

- Fecha: 2026-10-25
- Alcance: cliente `aerosml.exe` (v. portable instalada en `C:\Users\wilki\proyectos\Aero32`), DLLs auxiliares y archivos de configuración exportados.
- Estado: **auditoría cerrada, integración no iniciada**. Este documento es la base de decisión, no una implementación.

---

## 1. Resumen ejecutivo

**Hallazgo principal: Aero32 no expone una API HTTP para sus operaciones nucleares.** Es un monolito de escritorio
en Delphi que habla **directamente con Firebird** (protocolo nativo, puerto 3050) mediante FireDAC y, en rutas
heredadas, dbExpress/BDE. No hay capa de servicio que Trindade pueda consumir: toda la lógica de negocio está en el
ejecutable y en objetos de base de datos (procedimientos y triggers).

Consecuencias directas:

1. La única vía de datos es la base Firebird (`aerosml.gdb`, dialecto **1**), con **una base por empresa** (`EMPRESA.EMP_GDB`).
2. Escribir stock "a mano" desde Trindade significa **replicar una secuencia transaccional** que hoy ejecuta el
   cliente Delphi: saldo (`SALDOESTOQUE`), costo medio (`CUSTOMEDIO`), contadores (`KEYCONT`) y, según la ruta,
   documentos (`ENTRADA`/`ENTRADADET`) u orden de producción (`OPR00000`/`OPR10000`).
3. Existe una **segunda base, MySQL, para el producto móvil del proveedor** (`Aeromobile`), alimentada por un
   conector propio. Es una vía de solo lectura de catálogo, no un canal de escritura fiable para producción.
4. El "lanzamiento de masas" tiene un equivalente nativo confirmado: el módulo **Estoque Avulso** con tipo de
   lanzamiento **Industrialização** (y su variante **Acerto**), más el módulo **Ordem de Produção** con fórmulas
   (`PROF0000`/`PROM0000`).

**Recomendación**: no escribir tablas de Aero desde el backend de Trindade en la fase 1. Empezar con **lectura**
(catálogo, saldos, operaciones) para validar el modelo, y habilitar la escritura recién con un **puente on-prem**
que reproduzca la secuencia por transacción, credenciales propias y auditoría. Detalle y alternativas en §8.

---

## 2. Método, evidencia y límites

### 2.1 Qué se hizo (reproducible)

| Paso | Técnica | Resultado |
| --- | --- | --- |
| Copia del cliente | `scp` desde `ssh windows` a zona aislada | `aerosml.exe`, `AEROLib.dll`, `AeroNFe.dll`, `aeroconecta.exe`, `KIRConfig.exe`, INIs, `.reg`, `idapi32.cfg`, `iphist.dat` |
| Desempaquetado | `upx -d` sobre ejecutables comprimidos | `aerosml.exe` 11,7 MB → 52,5 MB |
| Cadenas | extracción ASCII y UTF-16, indexada por línea | >9.500 sentencias SQL; ~1.600 candidatas a catálogo de parámetros (93 validadas en el rango `005xxx`–`006xxx`) |
| Formularios | parseo de recursos `RT_RCDATA` (DFM binario, firma `TPF0`) | **1.134 recursos**, **943 formularios** `TF_*` con sus datasets, `SQL.Strings`, `FieldDefs` y parámetros |
| Índice SQL | extracción de `INSERT/UPDATE/DELETE/FROM/JOIN` por formulario | mapa tabla → formularios que la escriben/leen (§13-B) |

Herramienta reutilizable: [`scripts/aero32/dfm_extract.py`](../scripts/aero32/dfm_extract.py). Se debe volver a
ejecutar cuando el proveedor actualice el cliente: el esquema cambia sin aviso y sin documentación pública.

### 2.2 Límites explícitos

- **No** se accedió a la base de datos real: no hay metadatos `RDB$` (triggers, generadores, procedimientos) ni
  datos. Todo lo afirmado sobre triggers o procedimientos es **inferencia desde el cliente** y está marcado como tal.
- **No** se ejecutó el cliente ni se instalaron dependencias en Windows.
- Los secretos del entorno (contraseñas ofuscadas, API keys) **no se reproducen** en este documento (§10).
- El análisis del código Delphi (handlers de eventos) es parcial: se leyeron los datasets y su SQL declarado, no la
  lógica procedural completa de cada `OnClick`.

---

## 3. Arquitectura del sistema Aero32

### 3.1 Stack observado

| Capa | Tecnología | Evidencia |
| --- | --- | --- |
| Cliente | Delphi / C++Builder, VCL, formularios MDI (`fsMDIChild`) | DFMs `TF_*`, 943 formularios |
| Acceso a datos (actual) | **FireDAC** (`TADQuery`, `TADTransaction`, `TADUpdateSQL`), módulo `F_DMFD.AeroSisFD` | `TF_DMFD`, `AeroSisFD`, `TrGeral` |
| Acceso a datos (heredado) | dbExpress InterBase (`dbexpint.dll`, `getSQLDriverINTERBASE`) y BDE (`idapi32.cfg`) | cadenas del ejecutable |
| Driver nativo | `fbclient.dll` (y `gds32.dll` por compatibilidad) | `isc_attach_database`, `isc_dsql_*` |
| Motor | **Firebird**, `Protocol=TCPIP`, puerto **3050**, `SQLDialect=1` | plantillas de conexión en el binario |
| Reportes | `TPrintFast`, `TRLXLSPageSetup` (export a Excel), export a PDF/planilla | DFMs |
| Fiscal | `AeroNFe.dll`, ACBr (NFe/CTe/MDFe), `Capicom` | DLLs y formularios `TF_NOTAFISCAL*` |
| App móvil del proveedor | MySQL remoto (`3306`) + endpoint HTTP `…/aeromob/` | `AeromobConfig.reg`, cadenas `MYSQL`, `AeroMobile` |

### 3.2 Dónde vive la configuración de conexión

La conexión principal **no** está en los `.INI` (esos solo guardan estado de ventanas): está en el registro.

```
HKCU\Software\Aero Informatica\AeroSML\DBAERO        ← conexión Firebird del ERP (form TF_ConfiguracaoBanco)
HKCU\Software\Aero Informatica\Aeromobile            ← conector AeroMobile (Firebird + MySQL + API key)
```

Formato de parámetros observado (plantillas FireDAC dentro del binario):

```
Protocol=TCPIP
Database=<ruta o alias de la base>
User_Name=<usuario>
Password=<secreto>
SQLDialect=1            ← dialecto heredado: fechas y strings con semántica antigua
RoleName=
```

Existe además `iphist.dat` (historial de IPs de servidor) y `TF_Registry` (registro/serial de licencia del producto).

**Dato de red observado** (export de registro del equipo de la empresa, en el conector móvil):

- Firebird on-prem: `192.168.25.250`, base `/aero32/aerosml/aerosml.gdb`, usuario `SYSDBA`.
- MySQL del proveedor: host `aeromob.vpshost8232.mysql.dbaas.com.br`, puerto `3306`, esquema `aeromob`, usuario `aeromob`.
- `COMMIT=1000` (tamaño de lote del sincronizador).

### 3.3 Multiempresa: una base por empresa

La tabla `EMPRESA` incluye `EMP_GDB` (ruta de la base) y `EMP_ALIAUX` (alias), editables desde `TF_EMPGDBUPDATE`:

```sql
SELECT EMP_COD, EMP_NOM, …, EMP_GDB, …, EMP_ALIAUX, … FROM EMPRESA WHERE EMP_COD = :EMP_COD
INSERT INTO … (EMP_COD, EMP_GDB, EMP_ALIAUX) VALUES (:NEW_EMP_COD, :NEW_EMP_GDB, :NEW_EMP_ALIAUX)
```

Implicación: **el stock no es global**; cada empresa/filial es un archivo `.gdb` distinto. Cualquier integración
necesita resolver `empresa → base` antes de escribir, y las tablas de producción usan sufijo numérico por empresa
(`OPR00000`, `OPR10000`, `OPR20000` aparecen en el binario). Verificar la regla exacta en sitio (§11-A, consulta 3).

### 3.4 Componentes auxiliares

- `AeroNFe.dll` / ACBr: emisión y distribución de NF-e, CTe, MDFe.
- `KIRConfig.exe`: configurador (impresoras/reportes).
- `aeroconecta.exe`: conector del ecosistema móvil.
- Módulo `Aeromobile`: replica catálogo y pedidos entre Firebird y MySQL; el ERP muestra "Permite liberar pedido
  AeroMobile" y "Avisar Clientes Novos Cadastrados no AeroMob".

---

## 4. Modelo de datos relevante

Nomenclatura Aero: prefijo de 3-4 letras por tabla (`PRO`, `EST`, `ENT`, `OPE`, `MED`, `LOG`, `F_`), y en las tablas
"nuevas" (fórmulas, OP) columnas con prefijo `F_`. Tipos numéricos con 2-4 decimales para cantidades.

### 4.1 `SALDOESTOQUE` — saldo por producto

Una fila por producto (clave `EST_PRO`). El saldo se reparte en columnas, no en filas por depósito.

```sql
-- lectura que usa el cliente (FetchRow de QrySaldoEstoque)
SELECT EST_PRO, EST_QUA, EST_COM, EST_VEN, EST_VAL, EST_CUS, EST_PRA, EST_SEM,
       EST_G01, EST_G02, EST_G03, EST_G04, EST_G05, EST_G06, EST_G07, EST_G08,
       EST_G09, EST_G10, EST_G11, EST_G12, EST_FOR, EST_DEP, EST_ENT, EST_SAI,
       EST_EGF, EST_AGF, SAL_TRO, EST_TELVEN, SAL_VIR, EST_OUT, SAL_ESTOUT,
       EST_CUSANT, EST_SALDIS, EST_QUA2, EST_CUS2, EST_VLRFOR, EST_QTDCONT,
       EST_QTDINI, EST_SALATU, EST_TABFOR, EST_ALTER, EST_IPL, EST_QRT, EST_QUABKP
FROM SALDOESTOQUE
WHERE EST_PRO = :EST_PRO
```

```sql
-- escritura (TADUpdateSQL declarado en TF_EstoqueAvulso)
INSERT INTO SALDOESTOQUE
  (EST_PRO, EST_QUA, EST_FOR, EST_DEP, EST_ENT, EST_SAI, EST_EGF, EST_AGF,
   EST_TELVEN, SAL_VIR, EST_OUT, SAL_ESTOUT)
VALUES
  (:NEW_EST_PRO, :NEW_EST_QUA, :NEW_EST_FOR, :NEW_EST_DEP, :NEW_EST_ENT, :NEW_EST_SAI,
   :NEW_EST_EGF, :NEW_EST_AGF, :NEW_EST_TELVEN, :NEW_SAL_VIR, :NEW_EST_OUT, :NEW_SAL_ESTOUT)

UPDATE SALDOESTOQUE SET
  EST_QUA = :NEW_EST_QUA, EST_FOR = :NEW_EST_FOR, EST_DEP = :NEW_EST_DEP,
  EST_ENT = :NEW_EST_ENT, EST_SAI = :NEW_EST_SAI, EST_EGF = :NEW_EST_EGF,
  EST_AGF = :NEW_EST_AGF, EST_TELVEN = :NEW_EST_TELVEN, SAL_VIR = :NEW_SAL_VIR,
  EST_OUT = :NEW_EST_OUT, SAL_ESTOUT = :NEW_SAL_ESTOUT
WHERE EST_PRO = :OLD_EST_PRO
```

Semántica de columnas (inferida de uso y de los parámetros `006xxx`):

| Columna | Uso observado |
| --- | --- |
| `EST_QUA` | saldo general del producto |
| `EST_DEP` | saldo de **depósito** (parámetro 006025: saldo por depósito S/N) |
| `EST_ENT` / `EST_SAI` | acumulados de entrada/salida |
| `EST_EGF` / `EST_AGF` | saldos de "estoque gerenciado" / agrícola-fiscal (variantes del mismo stock) |
| `EST_CUS`, `EST_CUSANT`, `EST_CUS2` | costo actual / anterior / alternativo |
| `EST_G01…G12` | saldos por **grade** (talle/color): hasta 12 posiciones |
| `EST_TELVEN`, `SAL_VIR`, `EST_OUT`, `SAL_ESTOUT` | saldo de telas/venta, saldo virtual, salidas pendientes |
| `EST_QTDCONT`, `EST_QTDINI` | contador y saldo inicial de inventario |
| `EST_SALATU`, `EST_ALTER`, `EST_IPL`, `EST_QRT`, `EST_QUABKP` | saldo actualizado, marca de alteración, y respaldos de saldo |

Tablas satélite de saldo: `SALDOESTOQUECLIENTE` (saldo por cliente, clave `SAL_CLI + SAL_PRO`),
`SALDOESTOQUECLIENTEDIARIO`, `SALDOESTOQUETIPOPH` (saldo por tipo/PH), `saldoestoquesetoremp`, `SALDOLOTESETOR`,
`SALDODEPOSITOPROPRIEDADE`, `PRODUTO_SALDOESTOQUEPALM` (coletor portátil).

### 4.2 `PRODUTO` — catálogo

Campos usados en las consultas de stock: `PRO_COD` (clave, string), `PRO_DES`, `PRO_DESGRA`, `PRO_GRU`, `PRO_MAT`
(materia prima: `PRO_MAT = 1` en la consulta de "producto intermedio"), `PRO_UNI`, `PRO_PES`, `PRO_PRA`, precios.

### 4.3 `ENTRADA` / `ENTRADADET` — documento de entrada

Cabecera y detalle de notas de entrada (compra, devolución, ajuste documentado):

```sql
INSERT INTO ENTRADA
  (ENT_EMP, ENT_COD, ENT_FOR, ENT_LAN, ENT_DTA, ENT_TOT, ENT_ICMBAS, ENT_ICMVLR,
   ENT_OUT, ENT_OPE, ENT_CTA, ENT_COF, ENT_DTMOVI, ENT_SER, ENT_PRO, ENT_CLIEST,
   ENT_VLRENT, ENT_CENCUS)
VALUES
  (:ENT_EMP, :ENT_COD, :ENT_FOR, :ENT_LAN, :ENT_DTA, :ENT_TOT, :ENT_ICMBAS, :ENT_ICMVLR,
   :ENT_OUT, :ENT_OPE, :ENT_CTA, :ENT_COF, :ENT_DTMOVI, :ENT_SER, :ENT_PRO, :ENT_CLIEST,
   :ENT_VLRENT, :ENT_CENCUS)

INSERT INTO ENTRADADET
  (ENT_EMP, ENT_COD, ENT_FOR, PRO_COD, ENT_LAN, ENT_DTA, PRO_QTD, PRO_PRE,
   ENT_TOT, ENT_OPE, PRO_DES, ENT_SER, ENT_DTANOT)
VALUES
  (:ENT_EMP, :ENT_COD, :ENT_FOR, :PRO_COD, :ENT_LAN, :ENT_DTA, :PRO_QTD, :PRO_PRE,
   :ENT_TOT, :ENT_OPE, :PRO_DES, :ENT_SER, :ENT_DTANOT)
```

`ENT_EMP` ata el documento a la empresa; `ENT_OPE` referencia la operación (tipo de movimiento).

### 4.4 `OPERACOES` — tipos de movimiento (135 columnas)

Cada movimiento de stock pasa por una **operación**, que define su comportamiento fiscal, de costo y de stock. Las
columnas relevantes para integración:

| Columna | Efecto |
| --- | --- |
| `OPE_COD`, `OPE_DES`, `OPE_HIS` | código, descripción y **texto de histórico** del movimiento |
| `OPE_TIP`, `OPE_SEQ`, `OPE_SER` | tipo (entrada/salida/transferencia), secuencia y serie de numeración |
| `OPE_EST` | **mueve stock** (si está desactivado, el movimiento no toca saldo) |
| `OPE_CUS`, `OPE_CUSENT` | recalcula / toma costo |
| `OPE_ENT`, `OPE_DEP`, `OPE_EGF`, `OPE_AGF` | a qué bolsón de saldo impacta (`EST_QUA`, `EST_DEP`, `EST_EGF`, `EST_AGF`) |
| `OPE_FIS`, `OPE_FISCAL`, `OPE_CST`, `OPE_PISCOF`, `OPE_ALICBS`… | comportamiento fiscal (no tocar) |
| `OPE_DUP`, `OPE_PGT`, `OPE_CON`, `OPE_VEN` | generación de cuentas a pagar/cobrar y condiciones |
| `OPE_RASTRO`, `OPE_SALLOT` | control de lote / trazabilidad |
| `OPE_ENVIAMOB` | marca el movimiento para replicación al móvil |
| `OPE_DEV`, `OPE_SUB`, `OPE_SUBINT`, `OPE_CONDB`, `OPE_TROCA`, `OPE_BON`, `OPE_FIE` | devolución, sustitución, combos, troca, bonificación, fianza |

Consulta declarada por `TF_EstoqueAvulso`:

```sql
SELECT OPE_COD, OPE_DES, OPE_EST, OPE_DUP, OPE_DEP, OPE_FIX, OPE_FIS, OPE_CON,
       OPE_EGF, OPE_AGF, OPE_CUS, OPE_VEN, OPE_PGT, OPE_TPR, OPE_FIE, OPE_CONDB,
       OPE_SUB, OPE_SUBINT, OPE_DEV, OPE_RASTRO, OPE_CHK
FROM OPERACOES
WHERE OPE_COD = :CODIGO
```

**Regla de oro**: la operación la define el usuario/administrador del ERP, no Trindade. La integración debe
referenciar `OPE_COD` por configuración (nunca inventarlo) y validar `OPE_EST` antes de escribir.

### 4.5 `CUSTOMEDIO` — costo medio por índice

```sql
SELECT MED_PRO, MED_IND, MED_CUS, MED_QUA, MED_CUSMED, MED_CUSINV, MED_CUSPAR, MED_VALOR
FROM CUSTOMEDIO WHERE MED_IND = :INDICE AND MED_PRO = :PRODUTO
```

Columnas: `MED_PRO`, `MED_IND` (índice desde `INDEXADOR.IND_COD`), `MED_CUS`, `MED_QUA`, `MED_CUSMED` (costo medio),
`MED_CUSINV`, `MED_CUSPAR`, `MED_VALOR`, `MED_CUSVER`, `MED_CUSICM`, `MED_CUSSUBBAS`, `MED_CUSSUB`, `MED_CUSMEDUSS`.
Se escribe junto con `CUSTOMEDIODIARIO` (histórico diario). **Toda** escritura de entrada con costo debe
mantener la coherencia `MED_QUA`/`MED_CUSMED`, o el costo medio del ERP queda corrupto (efecto contable).

### 4.6 `KEYCONT` — contadores manuales (crítico)

El ERP **no usa generadores** para los códigos de documentos: usa una tabla de contadores.

```sql
SELECT KEY_VLR FROM KEYCONT WHERE KEY_NOM = :KEY_NOM
SELECT Coalesce(KEY_VLR, 0) + 1 AS VALOR FROM KEYCONT WHERE KEY_NOM = :KEY_NOM
UPDATE KEYCONT SET KEY_VLR = (Coalesce(KEY_VLR, 0) + 1) WHERE KEY_NOM = :KEY_NOM
INSERT INTO KEYCONT (KEY_NOM, KEY_DES, KEY_VLR) VALUES (:KEY_NOM, :KEY_DES, :KEY_VLR)
```

Contadores nombrados observados: `NotaEnt`, `Orcamento`, `CentroCust`, `CTe`, y otros por módulo.

Riesgos: (a) el patrón `SELECT`+`UPDATE` **no es atómico** → dos escritores concurrentes pueden colisionar en la
clave; (b) si Trindade inserta documentos debe incrementar el contador correcto en la **misma transacción**, o el
ERP reutilizará el código. Ver §6.

### 4.7 Fórmulas (recetas) — `PROF0000` / `PROM0000` / `COMPOSICAO`

```sql
-- materiales de una fórmula
SELECT * FROM PROM0000 WHERE F_PROCOD = :PROCOD
-- costo unitario calculado por el motor
SELECT F_CUSUNI, F_CUSCOM FROM PROC_GETPROF0000(:FOR_PRO, :FOR_COD)
-- fórmulas donde el producto participa
SELECT P.* FROM PROF0000 P …
SELECT Sum(F_PROPRE) As SLDTOTAL FROM PROM0000 …
```

| Tabla | Rol | Columnas |
| --- | --- | --- |
| `PROF0000` | cabecera de fórmula | `F_FORCOD`, `F_FORDES`, `F_FORPRO` (producto resultante), `F_FORDUP`, `F_FOREEM`, `F_FORMIX`, `F_FORMMO` (mano de obra), `F_FORSAC`, `F_FORTCV`, `F_FORTIR`, `F_CUSPER`, `F_TCVCOD` |
| `PROM0000` | materiales/insumos | `F_PROCOD`, `F_PROFOR`, `F_PROMAT` (materia prima), `F_PROQTD`, `F_PROPRE`, `F_PROPER` (pérdida %), `F_PROIMP`, `F_PROALT`, `F_PROREF`, `F_PROTIP` |
| `COMPOSICAO` | composición/combo comercial | referenciada por 12 formularios (producto, NF de entrada, contratos, reportes) |

`PROC_GETPROF0000` demuestra que hay **procedimientos almacenados con lógica de negocio** en la base: el modelo de
datos no es solo tablas.

### 4.8 Orden de Producción — `OPR00000` / `OPR10000`

```sql
SELECT * FROM OPR00000                     -- cabecera
SELECT * FROM OPR00000 Opr
  LEFT OUTER JOIN PRODUTO Pro …
  LEFT OUTER JOIN PROF0000 Formula …       -- la OP se ata a una fórmula
ORDER BY Opr.F_OPRPRE, Opr.F_OPRPRO

SELECT * FROM OPR10000 WHERE F_OPRCOD = :F_OPRCOD   -- ítems / insumos de la OP
```

Campos de cabecera observados: `F_OPRCOD`, `F_OPRDTA`, `F_OPRPRO` (producto), `F_OPRFOR` (fórmula/proveedor interno),
`F_OPRPRE`, `F_OPRSTA` (estado; el código maneja `'B'` y `'P'`), `F_OPRVEN`, `F_OPRPED`, `F_OPRMST`, `F_CUSPER`.

Tablas "paralelas" detectadas: `OPR00000`, `OPR10000`, `OPR20000` (patrón `<base><empresa>0000`). En sitio hay que
listar `RDB$RELATIONS LIKE 'OPR%'` y `'PRO%0000'` para fijar la regla exacta (§11-A).

### 4.9 Ubicaciones y lotes

- `LOGISTICA` (dirección logística del producto): `LOG_RUA`, `LOG_BLO`, `LOG_NIV`, `LOG_APT`, `LOG_PRO`, `LOG_TIP`
  (`'1'`/`'3'` en la consulta de stock), `LOG_SAL`, `LOG_ALT`, `LOG_VEN`, `LOG_ORD`, `LOG_SALMIN`, `LOG_SALMAX`,
  `DEP_COD`, `DEP_DTAQUA`.
- `LOGISTICAMVTO`: movimientos entre ubicaciones (lo escribe `TF_CONFERENCIACEGA`, `TF_ALIMENTACAOMANUAL`, NF, etc.).
- `LOTE`, `LOTEMVTO`, `RASTREAMENTO_LOTE`: trazabilidad; lo escriben `TF_LOTE`, `TF_NOTAAVULSOLOTE`,
  `TF_ORDEMENTREGA(-GALOTE)`.

### 4.10 Parámetros que gobiernan el stock (`PARAMETROS`, códigos `006xxx`)

Interruptores que cambian el significado de una escritura. **La integración debe leerlos, no asumirlos.**

| Código | Parámetro |
| --- | --- |
| 006001 | Controla Estoque |
| 006002 | Controla Saldo Depósito por Nota |
| 006003 | Permite Estoque Negativo |
| 006004 | Gravar estoque ao gerar inventário |
| 006005 | Estoque compartilhado com Filiais? |
| 006006 / 006007 | Operação de Saída / Entrada p/ Transferência |
| 006012 / 006013 | Inventário "de trás pra frente" (1/2) · Modelo de transferência |
| 006016 | Informar fornecedor diferenciado no estoque avulso? |
| 006020 | **Controlar Estoque por Gatilho S/N** (indica trigger/automatismo) |
| 006022 | **Estoque Avulso** (habilita el módulo) |
| 006025 | Poder escolher se controla saldo p/ depósito S/N |
| 006026 | Usar movimento avulso nos inventários S/N |
| 006030 | Número de casas decimais no inventário |
| 006031 | Estoque individual por Setor? (S/N) |
| 006034 | Retroagir custo médio |
| 006038 | Saldo no lugar de giro – consulta de estoque |

Además, en el binario aparece el mensaje **"Desativando triggers"**: el cliente desactiva triggers en operaciones
masivas (inventario/actualización). Consecuencia: el estado de los triggers **no** es estable en todo momento y la
integración no puede asumir que un `UPDATE` dispara siempre la misma lógica.

---

## 5. Rutas de escritura de stock identificadas

Mapa de quién escribe `SALDOESTOQUE` (19 formularios) y las tablas asociadas:

| Ruta (formulario) | Escribe | Cuándo se usa |
| --- | --- | --- |
| `TF_ESTOQUEAVULSO` | `SALDOESTOQUE`, `CUSTOMEDIO`, `SALDOESTOQUECLIENTE`, `LOGISTICA`, `KEYCONT` | **lanzamiento manual**: Industrialização / Acerto |
| `TF_ATUALIZAESTOQUE`, `TF_IMPLANTACAOESTOQUE` | `SALDOESTOQUE` | ajuste puntual / carga inicial |
| `TF_RELINVENTARIO` (+ `SALDOAFIXAR`, `NCMINVENTARIO`, `PRC_RELINVENTARIO`) | `NCMINVENTARIO`, `SALDOAFIXAR` | inventario/contagem |
| `TF_NOTAFISCALENTRADA` (`…2`, `…ACERTO`, `…CANCEL`) | `ENTRADA`, `ENTRADADET`, `SALDOESTOQUE`, `CUSTOMEDIO`, `PAGARDET` | entrada documentada (NF) |
| `TF_ORDEMPRODUCAO` | `OPR00000`, `OPR10000` | **producción con fórmula** |
| `TF_CONFERENCIACEGA` | `LOGISTICA`, `LOGISTICAMVTO`, `PRENOTA` | picking/conferencia |
| `TF_CORTEMERCADORIA(-CLIENTE)`, `TF_DEVOLUCAOAVULSO`, `TF_ORDEMENTREGA` | `SALDOESTOQUE`, `CUSTOMEDIO`, `SALDOESTOQUECLIENTE` | cortes, devoluciones, entregas |

### 5.1 Estoque Avulso — la pantalla que hoy usan a mano

Estructura del formulario `TF_EstoqueAvulso` (evidencia del DFM):

- Cabecera: **Cliente**, **Representante**, **Operação**, **Motivo**, **Observação**, **Centro de Custo**, **Requisição**.
- `rdgTipoLcto` ("Tipo Lançamento"): **`Industrialização` | `Acerto`**.
- Grilla de productos (`DbgProdutos` sobre `RxProdutos`, `TRxMemoryData` — memoria local, se confirma al grabar):
  `RxProduto`, `RxDescricao`, `RxGramatura`, `RxPh`, `RxEstoque`, `RxDesejado`, `RxQuantidade`, `RxCustoReal`,
  `RxUnitario`, `RxTotal`, `RxFornecedor`, `RxPrecoVenda`, `RxVarejo`.
- Pestañas: **Estoque** · **Acerto de Custos** · **Relatório** · **Acerto de Saldo**.
- "Acerto de Saldo": operaciones padrón de entrada/salida, producto, saldo actual, nuevo saldo → botón
  **"Atualizar Estoque"**.

Consulta de producto usada al editar una línea:

```sql
SELECT pro.*, est.est_Qua
FROM PRODUTO pro
LEFT OUTER JOIN saldoestoque est ON pro.pro_cod = est.est_pro
WHERE pro.PRO_COD = :PRODUTO
```

**Diferencia clave entre los dos tipos de lanzamiento** (inferida, a confirmar con captura del cliente):

- **Acerto**: fija/ajusta el saldo del producto (una línea = un producto, sin insumos).
- **Industrialização**: convierte insumos en producto terminado; es el equivalente funcional del **lanzamiento de
  masas** de Trindade (consumo de materia prima + alta de producto terminado).

### 5.2 Secuencia transaccional inferida (y sus invariantes)

Del SQL declarado y de los `TADTransaction` (`TrGeral`) se desprende que cada movimiento se ejecuta en **una
transacción** que, como mínimo:

1. Lee `OPERACOES` y valida `OPE_EST` (si no mueve stock, no toca saldo).
2. Actualiza/inserta `SALDOESTOQUE` (`EST_QUA` ± cantidad, `EST_ENT`/`EST_SAI`, `EST_DEP`/`EST_EGF`/`EST_AGF` según `OPE_*`).
3. Recalcula `CUSTOMEDIO` (+ `CUSTOMEDIODIARIO` si el movimiento cambia costo).
4. Actualiza saldos auxiliares: `SALDOESTOQUECLIENTE`, `SALDOESTOQUETIPOPH`, `LOGISTICA` (si hay ubicación).
5. Si genera documento, toma el código de `KEYCONT` e inserta cabecera/detalle (`ENTRADA`/`ENTRADADET`).
6. Escribe lote/trazabilidad si `OPE_RASTRO` / `OPE_SALLOT`.

Invariantes que una integración **debe** respetar:

- `EST_PRO` es la clave; el saldo no puede quedar negativo si `006003` lo prohíbe.
- Un movimiento sin `OPE_COD` válido es un huérfano contable (el ERP lo ignora o lo rechaza).
- El costo medio debe cuadrar con `MED_QUA`; un ajuste de cantidad sin costo descuadra el costo promedio.
- Los códigos de documento se consumen de `KEYCONT`, no de `MAX(codigo)+1`.
- La base es por empresa: escribir en la base equivocada contamina otra razón social.

---

## 6. Riesgos técnicos del motor (Firebird antiguo)

| Riesgo | Detalle | Control propuesto |
| --- | --- | --- |
| **Dialecto 1** | El cliente conecta con `SQLDialect=1`: fechas como `DATE`/`TIMESTAMP` con semántica heredada, strings `CHAR` con relleno, sin `BIGINT` idiomático | Fijar `dialect: 1` en el driver, probar fechas/horas contra la base real, nunca usar `RETURNING` |
| **Charset** | Datos en `WIN1252` (cp1252); el cliente Delphi muestra acentos ya en ese charset | Trindade (UTF-8) debe transcodificar en el borde; probar `ç ã õ é` en `PRO_DES` y `OPE_HIS` |
| **Contadores** | `KEYCONT` con `SELECT`+`UPDATE` no atómico | Un solo escritor (puente), y `UPDATE … SET KEY_VLR = KEY_VLR + 1` en la misma transacción |
| **Triggers inestables** | El cliente desactiva triggers en procesos masivos; hay lógica en SPs (`PROC_GETPROF0000`) | Auditar `RDB$TRIGGERS`/`RDB$PROCEDURES` antes de escribir; no duplicar lógica que ya hace un trigger |
| **Sin API** | Cualquier cambio de esquema rompe la integración sin aviso | Re-ejecutar el extractor (§2.1) en cada actualización del proveedor; test de contrato contra la base real |
| **Licencias** | Hay registro/serial (`TF_Registry`) y módulos habilitados por licencia | Confirmar con la empresa qué módulos están licenciados (Estoque, Produção, NF) antes de depender de ellos |
| **Acceso** | El cliente usa `SYSDBA`; Firebird puede no aceptar conexiones remotas desde fuera de la LAN | Usuario propio `TRINDADE_SYNC` con permisos mínimos y túnel/VPN; decisión de IT |
| **Concurrencia** | El ERP no bloquea filas de `SALDOESTOQUE` de forma explícita | Escrituras fuera del horario o con reintento/verificación posterior |

---

## 7. Correspondencia Trindade → Aero

| Concepto Trindade | Tabla/objeto Aero | Dirección | Nota |
| --- | --- | --- | --- |
| Catálogo de productos | `PRODUTO` | Aero → Trindade | lectura; mapear `PRO_COD` como clave externa |
| Lanzamiento de masas (consumo + producción) | `SALDOESTOQUE` (+ `CUSTOMEDIO`, `OPERACOES`) o `OPR00000`/`OPR10000` | Trindade → Aero | decidir ruta: "Industrialização" (simple) vs OP (trazable) |
| Cálculo de stock / consulta | `SALDOESTOQUE`, `SALDOESTOQUECLIENTE`, `CUSTOMEDIO` | Aero → Trindade | solo lectura en fase 1 |
| Fórmulas/recetas | `PROF0000` + `PROM0000` | Aero → Trindade (o bidireccional) | el costo lo calcula `PROC_GETPROF0000` |
| Operarios / sectores | `SETOR`, `FUNCIONARIOSETOR` | Aero → Trindade | en Trindade ya hay usuarios propios |
| Históricos de movimientos | `ENTRADA*`, `LOGISTICAMVTO`, `OPR*` | Aero → Trindade | no hay tabla única de "movimientos" |
| Ubicaciones/depósitos | `LOGISTICA`, `LOGISTICAMVTO`, `CADASTRODEPOSITO` | lectura | si se quiere depositar por ubicación |

**Decisión pendiente del negocio** (bloqueante para implementar): ¿el lanzamiento de masas debe entrar como
*Industrialização* (mueve stock del producto terminado y, opcionalmente, insumos) o como *Ordem de Produção*
(documento con fórmula, consumos y estado)? La OP es más rica y auditable; Industrialização es más simple y es lo
que probablemente se hace hoy a mano. Se resuelve con una captura de pantalla del flujo real en el cliente.

---

## 8. Opciones de integración

| # | Opción | Ventajas | Riesgos | Veredicto |
| --- | --- | --- | --- | --- |
| **A** | Escritura directa Firebird desde el backend de Trindade (`node-firebird`) | simple, sin infraestructura extra | expone la base a Internet, dialecto 1, sin validaciones del ERP, concurrencia con `KEYCONT` | **no recomendada** |
| **B** | **Puente on-prem** (servicio pequeño en la LAN que expone HTTP/JSON a Trindade y escribe Firebird por transacción) | aísla credenciales, controla validaciones/auditoría, reintentos, un solo escritor, mismo lenguaje que Trindade | una pieza más que operar (Docker en la LAN o en el server Windows) | **recomendada** |
| **C** | Usar `Aeromobile` (MySQL + endpoint del proveedor) como bus de datos | ya existe | es del proveedor, orientado a pedidos/catálogo; sin garantía de stock ni de producción; API key de terceros | descartada para escritura |
| **D** | Importación por archivo/planilla (el ERP lee Excel/CSV en varios módulos) | cero riesgo de esquema | manual, no idempotente, depende del operador | solo como contingencia |
| **E** | RPA sobre la UI del cliente (automatizar `Estoque Avulso`) | respeta toda la lógica | frágil, requiere sesión Windows, no auditable | última ratio |

**Fase 1 (lectura)**: cualquiera de A/B en modo `SELECT`. **Fase 2 (escritura)**: B, con estas reglas:

1. Credenciales propias (`TRINDADE_SYNC`), permisos mínimos por tabla.
2. Una transacción por lanzamiento, con la secuencia de §5.2 y `KEYCONT` bloqueado.
3. Idempotencia: clave de correlación de Trindade (`launch_id`) registrada en una tabla propia **dentro de la misma
   transacción**; si el `launch_id` ya existe, no se reescribe el movimiento.
4. Verificación posterior: releer `SALDOESTOQUE`/`CUSTOMEDIO` y devolver el resultado a Trindade (saldo antes/después).
5. Modo seco (`dry_run`) que arma la transacción y hace `ROLLBACK`, para pruebas.

---

## 9. Contrato de datos propuesto (lanzamiento de masas)

```jsonc
{
  "launch_id": "uuid",                 // idempotencia, origen Trindade
  "empresa": 1,                        // EMP_COD → resuelve la base
  "tipo": "industrializacao",          // industrializacao | acerto
  "fecha": "2026-10-25",
  "operacao_entrada": 0,               // OPE_COD configurado (validar OPE_EST)
  "operacao_saida": 0,                 // OPE_COD para consumo de insumos (opcional)
  "centro_custo": "…",                 // ENT_CENCUS / EditCenCus
  "motivo": "…",                       // EditMotivo
  "observacao": "…",                   // EditObs (≤ maxlength del campo)
  "cliente": null,                     // opcional; impacta SALDOESTOQUECLIENTE
  "lineas": [
    { "produto": "000123", "cantidad": 12.5, "custo_unitario": 4.75, "sentido": "entrada" },
    { "produto": "000045", "cantidad": 25.0, "sentido": "saida" }
  ]
}
```

Respuesta esperada del puente: `{ ok, saldo_antes, saldo_despues, custo_medio_antes, custo_medio_despues, documento }`.

Validaciones mínimas antes de escribir: producto existe y `PRO_MAT` coherente con el sentido; operación existe y
`OPE_EST` activo; cantidad > 0; stock suficiente si `006003` prohíbe negativos; `006022` (Estoque Avulso) habilitado
si se usa esa ruta.

---

## 10. Seguridad y secretos

- El export de registro del equipo contiene **credenciales en claro u ofuscadas** (usuario `SYSDBA`, contraseña
  ofuscada en hexadecimal, usuario/clave del MySQL del proveedor y una **API key** de un servicio externo).
  En este documento **no se reproducen los valores**; el archivo original no debe versionarse.
- La ofuscación de las contraseñas (`PSORIA`, `PSORIADO`) es reversible con el mismo cliente: se resuelve
  dinámicamente en `aeroconecta.exe`. **No se necesita descifrarla**: la integración debe usar credenciales nuevas.
- Recomendaciones: rotar la clave del MySQL del proveedor y la API key (ya viajaron fuera del equipo), no reutilizar
  `SYSDBA` en la integración, y guardar los secretos en variables de entorno del puente, nunca en el repositorio.
- El análisis se hizo sobre copias; el binario original queda intacto y versionado por el proveedor.

---

## 11. Verificación en sitio (solo lectura)

Ejecutar en la base real con `isql`/DBeaver, sin privilegios de escritura. Estas consultas cierran las incógnitas
que el análisis estático no puede resolver.

**A. Metadatos del motor**

```sql
-- 1. versión y dialecto
SELECT rdb$get_context('SYSTEM', 'ENGINE_VERSION') AS version FROM rdb$database;

-- 2. triggers por tabla (y su estado: el cliente los desactiva en procesos masivos)
SELECT TRIM(r.RDB$RELATION_NAME) AS tabla, TRIM(t.RDB$TRIGGER_NAME) AS trigger,
       t.RDB$TRIGGER_TYPE, t.RDB$TRIGGER_INACTIVE, t.RDB$SYSTEM_FLAG
FROM RDB$TRIGGERS t JOIN RDB$RELATIONS r ON r.RDB$RELATION_NAME = t.RDB$RELATION_NAME
WHERE COALESCE(t.RDB$SYSTEM_FLAG, 0) = 0 AND r.RDB$SYSTEM_FLAG = 0
ORDER BY 1, 2;

-- 3. regla de tablas por empresa (OPR/PROM/PROF y demás sufijadas)
SELECT TRIM(RDB$RELATION_NAME) FROM RDB$RELATIONS
WHERE RDB$RELATION_NAME LIKE 'OPR%' OR RDB$RELATION_NAME LIKE 'PRO%0000'
   OR RDB$RELATION_NAME LIKE 'PROM%' OR RDB$RELATION_NAME LIKE 'PROF%'
ORDER BY 1;

-- 4. procedimientos y su código fuente (hay lógica de negocio embebida)
SELECT TRIM(RDB$PROCEDURE_NAME), RDB$PROCEDURE_SOURCE FROM RDB$PROCEDURES
WHERE COALESCE(RDB$SYSTEM_FLAG,0) = 0 ORDER BY 1;

-- 5. generadores vs KEYCONT (¿qué usa realmente el motor?)
SELECT TRIM(RDB$GENERATOR_NAME) FROM RDB$GENERATORS WHERE COALESCE(RDB$SYSTEM_FLAG,0) = 0 ORDER BY 1;
SELECT * FROM KEYCONT ORDER BY KEY_NOM;

-- 6. claves y constraints de las tablas de stock
SELECT TRIM(rc.RDB$RELATION_NAME) AS tabla, TRIM(i.RDB$INDEX_NAME) AS indice,
       i.RDB$UNIQUE_FLAG, i.RDB$INDEX_TYPE
FROM RDB$INDICES i JOIN RDB$RELATION_CONSTRAINTS rc ON rc.RDB$INDEX_NAME = i.RDB$INDEX_NAME
WHERE rc.RDB$RELATION_NAME IN ('SALDOESTOQUE','CUSTOMEDIO','ENTRADA','ENTRADADET','OPERACOES','OPR00000')
ORDER BY 1,2;
```

**B. Contraste funcional (lectura)**

```sql
-- saldo actual de un producto de prueba (antes y después de un lanzamiento manual en el ERP)
SELECT EST_PRO, EST_QUA, EST_DEP, EST_EGF, EST_ENT, EST_SAI FROM SALDOESTOQUE WHERE EST_PRO = '…';
SELECT MED_PRO, MED_IND, MED_QUA, MED_CUSMED FROM CUSTOMEDIO WHERE MED_PRO = '…' ORDER BY MED_IND;
-- últimas operaciones y su efecto declarado
SELECT OPE_COD, OPE_DES, OPE_EST, OPE_CUS, OPE_TIP FROM OPERACOES ORDER BY OPE_COD;
```

**C. Prueba de extremo a extremo recomendada**: hacer **un** lanzamiento manual en el cliente Windows (tipo
Industrialização, 1 producto, 1 insumo) y capturar antes/después con las consultas de B. Eso fija la secuencia real
(qué tablas cambian, con qué valores) y es la prueba que valida o corrige este documento.

---

## 12. Plan por fases

| Fase | Entregable | Criterio de aceptación |
| --- | --- | --- |
| **0. Acceso** | Usuario Firebird propio + conectividad (LAN/VPN) + lectura de metadatos | §11-A ejecutado, volcado guardado en `docs/` |
| **1. Lectura** | Adaptador de solo lectura (catálogo + saldos + operaciones) en backend | paridad de datos contra el cliente para 10 productos de muestra |
| **2. Prueba funcional** | Un lanzamiento manual en el ERP capturado | diff antes/después documentado, secuencia confirmada |
| **3. Puente** | Servicio on-prem con `dry_run`, idempotencia y auditoría | transacción de prueba en ROLLBACK idéntica al diff de fase 2 |
| **4. Escritura asistida** | Lanzamiento real desde Trindade, con aprobación humana | 5 lanzamientos reales sin diferencias contra el proceso manual |
| **5. Operación** | Monitoreo, reintentos, reconciliación diaria | alerta si `SALDOESTOQUE` de Trindade ≠ Aero |

Fuera de alcance por ahora: facturación/NF-e, contabilidad, y cualquier escritura en tablas fiscales (`OPE_FIS`,
`NOTAFISCAL*`, `PAGARDET`, `RECEBERDET`).

---

## 13. Anexos

### A. Índice de formularios relevantes (943 en total)

`TF_ESTOQUEAVULSO`, `TF_INVENTARIO`, `TF_RELINVENTARIO`, `TF_RELCONTAGEMDEESTOQUE`, `TF_ESTOQUECONSULTA`,
`TF_ESTOQUECONSULTALOTES`, `TF_MOVIMENTOCONSULTA`, `TF_ATUALIZAESTOQUE`, `TF_IMPLANTACAOESTOQUE`,
`TF_OPERACOES`, `TF_ORDEMPRODUCAO`, `TF_PROCOMPOSICAO`, `TF_PRODUTO`, `TF_NOTAFISCALENTRADA`(`2`, `ACERTO`,
`CANCEL`, `CONSULTA`), `TF_NOTAFISCALSAIDAAVULSO`, `TF_CONFERENCIACEGA`, `TF_LOGISTICAPOSICAO`, `TF_LOTE`,
`TF_MERPERDASDEPRODUTOS`, `TF_MERTROCASDEPRODUTOS`, `TF_CORTEMERCADORIA`(-CLIENTE), `TF_DEVOLUCAOAVULSO`,
`TF_ORDEMENTREGA`, `TF_MONITORPRODUCAO`, `TF_LISTAMATERIALOS`, `TF_SAIDAAVULSA`, `TF_ENTRADALOGISTICA`,
`TF_PARAMETRO`, `TF_EMPGDBUPDATE`, `TF_CONFIGURACAOBANCO`, `TF_REGISTRY`.

### B. Tablas por cantidad de formularios distintos que escriben (extracto)

`SALDOESTOQUE` 19 · `RECEBERDET` 13 · `CUSTOMEDIO` 12 · `LOGISTICA` 10 · `SALDOESTOQUECLIENTE` 9 ·
`CUSTOMEDIODIARIO` 9 · `KEYCONT` 8 · `BLOQUETO` 8 · `PRODUTO` 8 · `PEDIDODET` 8 · `ENTRADADET` 6 ·
`LOGISTICAMVTO` 6 · `PAGARDET` 6 · `PEDIDO` 5 · `NOTAFISCAL` 5 · `ENTRADA` 5 · `LOTE` 4 ·
`OPR00000`/`OPR10000` 1 · `PROM0000`/`PROF0000` 1.

### C. Parámetros `005xxx` (comerciales) y `006xxx` (stock)

Los `005xxx` gobiernan clientes, límites, descuentos y bloqueos (no tocar). Los `006xxx` están en §4.10. Catálogo
validado: 93 entradas `005xxx`–`006xxx` extraídas del binario; el resto del catálogo requiere lectura de la tabla
`PARAMETROS` en la base real.

### D. Reproducir la auditoría

```bash
# 1. copiar binarios (solo lectura) desde Windows
scp windows:'C:/Users/wilki/proyectos/Aero32/aerosml.exe' /tmp/aero32/

# 2. desempaquetar UPX y extraer formularios + SQL
upx -d -o /tmp/aero32/unpacked/aerosml.exe /tmp/aero32/aerosml.exe
python3 scripts/aero32/dfm_extract.py --exe /tmp/aero32/unpacked/aerosml.exe --out /tmp/aero32/res

# 3. cadenas e índice de tablas
strings -n 8 /tmp/aero32/unpacked/aerosml.exe > /tmp/aero32/strings.txt
grep -iE 'insert into|update .* set' /tmp/aero32/strings.txt | sort -u | less
```

### E. Glosario

- **Estoque Avulso**: pantalla de lanzamiento manual de stock (Industrialização / Acerto).
- **Industrialização**: transformación de insumos en producto terminado; equivalente a "lanzamiento de masas".
- **Ordem de Produção (OP)**: documento de producción con fórmula, consumos y estado (`OPR*`).
- **Operação (`OPE_*`)**: tipo de movimiento que define efecto fiscal, de costo y de stock.
- **KEYCONT**: tabla de contadores de códigos (no hay generadores para documentos).
- **GDB**: archivo de base Firebird de Aero (una por empresa).

### F. Preguntas abiertas (bloqueantes para implementar)

1. ¿El lanzamiento de masas se registra hoy como **Estoque Avulso > Industrialização** o como **Ordem de Produção**?
   (captura de pantalla del flujo real).
2. ¿Qué `OPE_COD` usa la empresa para producción, y cuál para ajuste de inventario?
3. ¿La empresa habilita `006022` (Estoque Avulso) y `006020` (control por gatilho)? ¿`006003` permite negativos?
4. ¿Hay triggers activos sobre `SALDOESTOQUE`/`CUSTOMEDIO`? ¿Cuáles y desde qué versión del ERP?
5. ¿La red permite un puente on-prem con salida a Trindade (o Trindade hacia la LAN)? ¿Quién administra Firebird?
6. ¿Qué módulos están licenciados (Estoque, Produção, NF) y quién es el contacto del proveedor Aero para cambios de esquema?
