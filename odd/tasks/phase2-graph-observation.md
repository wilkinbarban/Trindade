# ODD Task Record: Phase-2 Isolated Gradle Graph Observation

Estado: **CONTRATO DE ADMISIÓN ADOPTADO FORMALMENTE (sección 9) — BARRERA VIGENTE AÚN NO ADAPTADA; DOS OBSERVACIONES FAIL-CLOSED**.
Invocaciones de observación consumidas: **2** (`run-observe`: rechazo `EACCES` vs `EROFS` esperado; `run-observe-2`: rechazo `lo is UP`), ambas dry-run acotadas, 0 tareas Gradle ejecutadas y 0 XML generados.
Invocación de verificación reservada para Phase-2: **INTACTA / NO CONSUMIDA** (los runs de observación no sustituyen la ejecución de verificación reservada).
Runs exclusivos de observación: `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-observe` y `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-observe-2` (run-phase2 no fue creado, leído ni modificado).
Barrera y runner: **INTACTOS** en esta conclusión (0 procesos en host; 310 s reservados).

> **Nota de divergencia:** esta copia local tenía 7,717 bytes y sha256 `f949f4fd723982570b6a485a3e816c8307e34b81818993d9db7f5248cb8b3081` antes de incorporar la sección 9; el hash vigente de este archivo debe leerse de la herramienta de hashing, nunca de su propio texto (no se auto-referencia). El registro autoritativo indicado por el humano reside en `/home/wilkin/trindade-hilt-root-ym2yq4lo/odd/tasks/phase2-graph-observation.md` (15,419 bytes, sha256 `f4090a27…`), **no accesible desde este host**. Las secciones 1–8 de esta copia describen la primera observación; su reconciliación con la revisión autoritativa permanece pendiente. La sección 9 registra la conclusión formal adoptada.

## 1. Goal (Objetivo)
Resolver la circularidad del barrier de tipos de tareas en `snapshot/diagnostic/tests.init.gradle.kts` mediante una observación única acotada (`--dry-run`) dentro del mismo sandbox `bwrap` (`--unshare-net`, `--offline`, sin tareas ejecutadas), volcando el grafo determinista y machine-readable de `:app:testDebugUnitTest` para derivar la allowlist exacta `reviewedPhase2TaskTypes`.

## 2. Constraints (Restricciones duras)
- Superficies autorizadas: `preflight-phase2.py`, `snapshot/diagnostic/graph-dump.init.gradle.kts`, `odd/tasks/phase2-graph-observation.md`.
- No mutación de `tests.init.gradle.kts`, `prepare.py`, `preflight-phase1.py`, `acquisition.json`, ni `run-phase1/`.
- No crear ni tocar `run-phase2/`.
- Sin comandos destructivos de git, sin descargas, sin red, sin daemon, sin emulador/ADB.
- Exactamente UNA invocación a Gradle, con `--dry-run`, timeout <= 310 s. Aborto fail-closed ante cualquier fallo sin fallback privilegiado ni reintentos.
- Quarantines `mv2m527b-4-1vd4` y `mv350i4k-1w-bfus` intactas. Archivos `/tmp/pi-bash-*.log` nunca leídos.

## 3. Exact Commands Executed (Comandos exactos)
1. **Comprobación de sintaxis y AST del runner Python modificado**:
   ```bash
   PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile preflight-phase2.py
   python3 -c 'import ast; ast.parse(open("preflight-phase2.py").read())'
   ```
   *Resultado observado*: AST válido (87 nodos raíz, 1015 líneas, 0 errores sintácticos).

2. **Verificación de exclusión mutua de argumentos**:
   ```bash
   PYTHONDONTWRITEBYTECODE=1 python3 preflight-phase2.py --execute --observe-graph
   PYTHONDONTWRITEBYTECODE=1 python3 preflight-phase2.py --observe-graph --execute
   ```
   *Resultado observado*: Ambos comandos abortan inmediatamente con código 2 y mensaje:
   `ABORT: --execute and --observe-graph are mutually exclusive; refuse both together`.

3. **Verificación de `--execute` aislado (bloqueo original preservado)**:
   ```bash
   PYTHONDONTWRITEBYTECODE=1 python3 preflight-phase2.py --execute
   ```
   *Resultado observado*: Aborta con código 2:
   `ABORT: BLOCKED: Exact path/type task dependency closure cannot be safely derived statically from dynamic plugins; Fresh independent barrier/lifecycle review pending; unique run not consumed; Robolectric local sidecar is not independently trusted upstream provenance; JDK static inventory has dangling/unqualified symlinks; mount qualification unresolved`.

4. **Invocación única autorizada de observación de grafo**:
   ```bash
   cd /home/wilkin/trindade-hilt-root-ym2yq4lo && PYTHONDONTWRITEBYTECODE=1 python3 preflight-phase2.py --observe-graph
   ```
   *Resultado observado*: Salida con código 4 (fail-closed pre-JVM barrier rejection).

## 4. Observed Barrier Results (Resultados observados de barreras)
- **Robolectric Local Identity**: PASS (`sha512=5fc64ffccf4788154162686b3966ddd63abea8326542c465e2a7f0129fc92770d0e1e8ddcfabd7f1500e491ec02e3b4104b9e51b61fccd9cf08006c9dc02987c`, `sdk=35`, `release=15`).
- **Validated Trees**: PASS (snapshot=422 entradas, modules-2=4530 entradas, Robolectric=4 entradas).
- **Run Directory Claim**: Creado exclusivamente `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-observe` con modo `0700`.
- **Pre-JVM Barrier Execution**: FAILED FAIL-CLOSED dentro del sandbox bwrap.
  - Registro capturado en `run-observe/logs/gradle.stderr.log`:
    ```text
    Traceback (most recent call last):
      File "<string>", line 15, in <module>
        fd = os.open(path, os.O_WRONLY | os.O_NOFOLLOW)  # NEVER O_TRUNC, NEVER write
    PermissionError: [Errno 13] Permission denied: '/jdk/release'
    The above exception was the direct cause of the following exception:
    Traceback (most recent call last):
      File "<string>", line 18, in <module>
        raise RuntimeError('RO rejected: expected actual EROFS: ' + path) from error
    RuntimeError: RO rejected: expected actual EROFS: /jdk/release
    ```
  - **Causa raíz técnica**: En el kernel de Linux, la comprobación Discretionary Access Control (DAC: permisos de inodo `0644`, propietario `root:root`, proceso ejecutándose como usuario `uid=1000`) se evalúa antes de la comprobación de banderas de montaje del VFS (`MS_RDONLY`). Al intentar abrir `/jdk/release` con `O_WRONLY`, el kernel retorna `EACCES` (`Permission denied`, errno 13) y no `EROFS` (errno 30). Dado que el script `BARRIER_SCRIPT` requiere estrictamente `error.errno == errno.EROFS` y rechaza cualquier otro errno, abortó fail-closed antes de alcanzar `os.execv` a Gradle.
- **Gradle JVM Execution**: 0 ejecuciones. El proceso Gradle jamás fue iniciado debido al fallo previo de la barrera de verificación pre-JVM.
- **Host Cache Invariance**: PASS. `fingerprint('post')` comprobó que no hubo alteraciones en las caches protegidas (`changed: []`).
- **No Test Action / XML Generation**: PASS. `run-observe/output/app-build/test-results` no fue creado ni contiene archivos XML. Snapshot permaneció intacto.

## 5. Observed Graph (Grafo observado)
Ningún grafo de Gradle fue emitido porque el sandbox abortó en la barrera pre-JVM antes de transferir control a Gradle (`os.execv`).

## 6. Proposed Allowlist (Allowlist propuesta)
Bloqueada. No se pudo derivar empíricamente la allowlist `reviewedPhase2TaskTypes` debido al fallo de la barrera pre-JVM. Requiere resolver primero la invariante de verificación DAC vs EROFS en `BARRIER_SCRIPT` para archivos con permisos `0644 root:root` montados bajo `/jdk`.

## 7. Evidence Paths and Hashes (Rutas y hashes de evidencia)
- `preflight-phase2.py`: `fb3f6a02afc4069fee0ac046a8445b6faa485e15c261cf2dfd72608afdd0f76b` (43,607 bytes)
- `snapshot/diagnostic/graph-dump.init.gradle.kts`: `a53c154fb05873e37856e30dd2a2d38235c1a7dcd304d362d6c5a95f8399b99c` (4,200 bytes)
- `run-observe/bwrap-argv.json`: `14bcd68d759f23132f5f93ef6190873390c9f2d8d41d1758234a25a388c37e92` (13,559 bytes)
- `run-observe/fingerprint.json`: `3b49c5da5602222a7af38213f4ee7bfd5e8e0fabfdf27ed08f8d918959037109` (1,979,503 bytes)
- `run-observe/logs/gradle.stdout.log`: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (0 bytes)
- `run-observe/logs/gradle.stderr.log`: `4f0f123c0116fcd5d0797975735dca9ca0c65735d2dbca63fa90aba664419946` (509 bytes)

## 8. Remaining Blockers (Bloqueos pendientes)
1. **Conflicto de invariante en comprobación pre-JVM de EROFS**: Archivos propiedad de `root:root` con modo `0644` (como `/jdk/release`) producen `EACCES` (errno 13) en lugar de `EROFS` (errno 30) al abrirse con `O_WRONLY` desde un UID no privilegiado (`1000`). Se requiere revisión y adaptación de la prueba representativa de sólo lectura (por ejemplo, comprobar `EROFS` sobre un archivo legible/escribible por el UID o tolerar `EACCES` además de `EROFS` cuando el archivo no es escribible según DAC).
2. **Obtención del grafo de tareas de Gradle**: Pendiente hasta que la barrera pre-JVM permita el paso a la configuración de Gradle en dry-run.
3. **Allowlist `reviewedPhase2TaskTypes`**: Permanece vacía; `tests.init.gradle.kts` continúa rechazando cualquier intento de ejecución.
4. **Verificación reservada Phase-2**: Permanece intacta y bloqueada.

## 9. Formal Conclusion — Adopted Admission Contract (Decoupled Exclusive Loopback)

**Decisión humana registrada:** el contrato técnico formal de admisión de Fase 2 **adopta loopback desacoplado exclusivo**, y **abandona el requisito de `lo DOWN`**.

**Razón técnica:** `lo DOWN` es **estructuralmente incompatible** con Gradle 9.6, cuyo `TestWorker` usa IPC TCP local (`MessagingServer`/`TcpIncomingConnector` antes de ejecutar tests). Además, `bwrap --unshare-net` sube `lo` por diseño (`loopback_setup()` invocado bajo `opt_unshare_net`), y un netns nuevo ya posee `127.0.0.1/8`, `::1/128` y sus rutas locales con `RTF_UP`. Por lo tanto la barrera `lo DOWN` es insatisfacible por construcción, no un defecto de configuración.

**Contrato adoptado (especificación formal):**
- Netns final **distinto** del host.
- Sólo `lo`, con `IFF_LOOPBACK`; `IFF_UP` permitido **exclusivamente** para `lo`.
- Direcciones admitidas: únicamente `127.0.0.0/8` y `::1/128`.
- Rutas/reglas admitidas: **únicamente las locales de loopback** (`RTF_UP` sobre `127/8` y `::1/128`) más las reglas locales inherentes al netns.
- **Sin gateways, sin rutas default con forwarding, sin otras interfaces, sin destinations no-loopback**, sin nexthop/encapsulación no revisados.
- Inventarios `RTM_GETLINK` (`AF_UNSPEC`), `RTM_GETADDR`, `RTM_GETROUTE` y `RTM_GETRULE` (`AF_INET`/`AF_INET6`) completos y fallo-cerrado ante error, truncamiento o inventario incompleto.
- Loopback = **permiso de IPC intra-namespace**, nunca permiso de escape de red.

Además, el contrato adoptado incluye: cinco conjuntos de capacidades en cero (`CapEff/CapPrm/CapInh/CapAmb/CapBnd`) y `NoNewPrivs=1` con parseo estricto; enumeración completa y allowlist de FDs pre-exec por objeto/origen/endpoint/modo; y puertas separadas de creación y entrada de namespaces.

**Artefacto formal:** `/var/tmp/trindade-staging/adenda-fase2-admission.md` (9,476 bytes, 109 líneas, sha256 `0dbae2cc442fb15ef5f2634b8881da4737fa93064fd5ed7cec0671c26aac3c51`), **PRELIMINAR** en cuanto a implementación: no implementada, no aprobada, no ejecutada.

**Estado de superficies:** barrera vigente (`preflight-phase2.py`) y runner **intactos**; sin host tocado (0 procesos); reserva de 310 s **no consumida**; cuarentenas, logs prohibidos y `.pyc` preservado sin acceder. La adaptación de la barrera a este contrato **no** está autorizada por esta conclusión.

**Bloqueos abiertos tras la adopción:**
1. Adaptar `BARRIER_SCRIPT` al contrato de loopback desacoplado (incluye reemplazar el rechazo `lo DOWN` y admitir las rutas locales `RTF_UP` de `127/8` y `::1/128`), con prueba pura previa.
2. Cerrar la brecha de FDs pre-exec: enumeración/allowlist/verificación posterior; `fdwalk` retorna 0 sin enumerar ante fallo de apertura (`utils.c:372–426`) y `cleanup_fd` no verifica errores.
3. Extender credenciales a los cinco capsets (la barrera actual sólo comprueba `CapEff`/`NoNewPrivs`).
4. Mapear herencia launcher→JVM→workers (fuentes no disponibles localmente).
5. Obtener el grafo Gradle y derivar `reviewedPhase2TaskTypes` (requiere una nueva invocación de observación autorizada).

## 10. Especificación de la tupla IPv6 `::1/128` (tarea 25)

**Decisión:** la entrada IPv6 revisada es un **mapeo**, no una tupla exacta. Una tupla exacta no puede ser veraz para una fila real de `/proc/net/ipv6_route`.

**Evidencia de varianza del kernel (sourced):**
- `flags 0x00000001` con `metric 0x00000100` — volcado de `/proc/net/ipv6_route` en el issue #3438 de scapy.
- `flags 0x80200001` con `metric 0x00000000` — volcado público de la caché de rutas IPv6.
- `flags 0x80240001` — parche netdev v2 “fix excessive RTF_ADDRCONF flag on ::1/128 local route (and others)”.
- `refcnt`/`use` son contadores vivos: cambian entre volcados de la misma máquina.
- Definición de bits: `include/uapi/linux/ipv6_route.h` (RTF_NONEXTHOP `0x00200000`, RTF_ADDRCONF `0x00040000`, RTF_PCPU `0x40000000`, RTF_LOCAL `0x80000000`) y `rtnetlink.h` (RTF_UP `0x1`, RTF_GATEWAY `0x2`, RTF_REJECT `0x200`, RTF_NOFORWARD `0x1000`, RTF_THROW `0x2000`, RTF_DEFAULT `0x10000`, RTF_ALLONLINK `0x20000`).

**Especificación adoptada:**
- Campos pinneados: `dest = ::1`, `dest_prefixlen = 128`, `src = ::/0`, `nexthop = ::`, `iface = lo`.
- Flags: `RTF_UP` **requerido**; **prohibidos** RTF_GATEWAY, RTF_REJECT, RTF_NOFORWARD, RTF_THROW, RTF_DEFAULT, RTF_ALLONLINK. Los bits informativos observados (NONEXTHOP, ADDRCONF, PCPU, LOCAL) no se exigen ni se prohíben.
- Grabados, **no** pinneados, y reportados en la evidencia: `metric`, `refcnt`, `use`. Pinnables con `exact={...}` cuando el revisor quiera fijarlos.
- Entradas IPv6 expresadas como tupla ⇒ rechazo explícito (“must be mappings”).
- `DEFAULT_LOOPBACK_POLICY` sigue fail-closed; `preflight-phase2.next.py` habilita la entrada revisada.

**Asimetría de riesgo:** habilitar la entrada es **fail-safe**. Un valor imprevisto de flags o metric sólo puede producir un **falso rechazo** con la fila observada impresa verbatim; nunca una admisión no revisada. Un «falso positivo» exigiría una fila con destino `::1/128` sin nexthop sobre `lo`, que por construcción no encamina hacia afuera.

**Verificación:** 92 tests de módulo + 15 de integración = **107 OK**, 0 `*.pyc`. El test de integración valida la política **efectivamente habilitada** (no un fixture) contra un estado realista (`::1/128` con flags `0x80240001`, metric `0x100`, refcnt `3`) y comprueba el rechazo por bit de gateway, interfaz extra y gateway IPv4.

**Artefactos:** `barrier_validation.py` `20b39d40d6be74a2762cdeec223a3bb13cb546c5542c9f0190c711104d6ab503`; `preflight-phase2.next.py` `8fa0680643bd1794f69a8691badecb2771e5476090de9b89b559147fddf9a915` (53,859 B, 644); runner congelado `preflight-phase2.py` `8c83942b…` (49,959 B, **0444 intacto**); `test_barrier_validation.py` `827a64f39d5ce83709402a707b88ea26a235d15372a692d45d6c2dffb36a2a20`; `test_barrier_integration.py` `375dc9ddf96f5ba50403d9ef44df95cff3ee700a3fbc5fd2a9bad8285ecc3b4f`.

**Sin evidencia runtime:** el gate nuevo nunca fue ejecutado dentro de un netns; la reserva de 310 s sigue intacta.

## 11. Primera observación en runtime (`run-observe-3`)

**Ejecución:** corrida única dry-run autorizada (`--observe-graph`) con `preflight-phase2.next.py` consumida en el slot `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-observe-3` (nodo `ubuntu`).
- Salida: código 4 (abort fail-closed del sandbox con código 1).
- Tareas Gradle: 0 ejecutadas.
- Archivos XML: 0 generados.
- Mutaciones de caché protegida: `changed: []`, 0 alteraciones.
- Runner congelado `preflight-phase2.py`: **8c83942b… en 0444 intacto**.
- Reserva oficial Phase-2 de 310 s: **INTACTA / NO CONSUMIDA**.

**Validaciones de runtime superadas:**
1. **`lo is UP` ACEPTADO:** el nuevo gate y comprobación ioctl aceptaron la interfaz de loopback activa creada por bwrap.
2. **Pruebas de sólo lectura superadas:** los 83 montajes RO y el write probe clasificado por `readonly_probe_verdict` pasaron sin objeciones DAC/EROFS.
3. **Namespace y lifecycle:** handshake `{"namespace_init": 482885, "pid_ns": "pid:[4026532760]"}` completado y pinned.
4. **Entrada `::1/128`:** la ruta de loopback IPv6 fue observada y coincidió plenamente con `REVIEWED_IPV6_LOOPBACK_ENTRY` (no fue reportada como faltante ni no-autorizada).

**Evidencia empírica de kernel capturada (causa del abort fail-closed):**
1. **IPv4 `/proc/net/route`:** reportó `0` filas de rutas. En Linux, `/proc/net/route` expone exclusivamente la tabla `main` (254), la cual se encuentra vacía en un netns recién creado (la ruta `127.0.0.0/8` se ubica en la tabla `local` 255). El gate requería obligatoriamente la presencia de una fila en `/proc/net/route`.
2. **IPv6 `/proc/net/ipv6_route`:** el kernel Linux instala por defecto dos instancias de la plantilla nula inerte `fib6_null_entry_template` (tablas `main` y `local`):
   - `dest`: `::/0`
   - `src`: `::/0`
   - `nexthop`: `::`
   - `metric`: `0xffffffff`
   - `iface`: `lo`
   - `flags`: `0x00200200` (`RTF_NONEXTHOP | RTF_REJECT`)
   Tal como anticipó la adenda de admisión (sección 2: "tratar defaults de descarte inerte separadamente de defaults de forwarding"), esta ruta de descarte no es una vía de escape, pero fue rechazada por contener el bit `RTF_REJECT` (prohibido en la política inicial) y destino `::/0`.

## 12. Superación total del Pre-JVM Barrier y descubrimiento de la invariante Gradle 9.6 (`run-observe-4`)

**Hito alcanzado:** en la corrida de observación autorizada (`run-observe-4`) en el nodo `ubuntu`, **el pre-JVM barrier fue superado con éxito por primera vez en Fase 2.**
- Salida en `gradle.stdout.log`:
  ```text
  PRE_JVM_BARRIERS=PASS same namespace; statvfs ST_RDONLY/mountinfo ro; net distinct/decoupled loopback admission; 5 capsets 0/NNP=1; observed ifaces=[lo] lo_flags=0x0049 v6[00000000000000000000000000000000/0 src=00000000000000000000000000000000/0 nh=00000000000000000000000000000000 flags=0x00200200 metric=0xffffffff refcnt=0x00000001 use=0x00000000] v6[00000000000000000000000000000001/128 src=00000000000000000000000000000000/0 nh=00000000000000000000000000000000 flags=0x80200001 metric=0x00000000 refcnt=0x00000002 use=0x00000000] v6[00000000000000000000000000000000/0 src=00000000000000000000000000000000/0 nh=00000000000000000000000000000000 flags=0x00200200 metric=0xffffffff refcnt=0x00000001 use=0x00000000]
  Welcome to Gradle 9.6.0!
  To honour the JVM settings for this build a single-use Daemon process will be forked.
  Shared read-only dependency cache is an incubating feature.
  ```
- El proceso Gradle 9.6.0 arrancó dentro del sandbox `bwrap` bajo `NoNewPrivs=1`, capacidades cero y red desacoplada exclusiva (`lo` UP).

**Descubrimiento arquitectónico de Gradle 9.6:**
Tras arrancar, Gradle 9.6 abortó durante la configuración del proyecto con dos fallos estructurales documentados en `gradle.stderr.log`:
1. **Invariante `projectDirectory.canWrite()` de Gradle 9.0+:**
   ```text
   Configuring project ':' without an existing directory is not allowed. The configured projectDirectory '/project/packages/android' does not exist, can't be written to or is not a directory.
   ```
   Gradle 9.0 introdujo la exigencia estricta de que el directorio del proyecto sea escribible (`canWrite() == true`).
2. **Problems API HTML Report:**
   ```text
   java.nio.file.FileSystemException: /project/packages/android/build/reports/problems/problems-report.html: Read-only file system
   ```
   La Problems API intenta escribir su informe HTML bajo `${projectDirectory}/build/reports/problems/`.

**Conflicto de diseño de Fase 2:**
En Fase 2, `preflight-phase2.py` montó `SNAPSHOT` en `/project` como `--ro-bind` estricto y exigió `statvfs ST_RDONLY` sobre `/project`. En Fase 1, `preflight-phase1.py` montaba `SNAPSHOT` con `--bind` local efímero por corrida. Gradle 9 es inherentemente incompatible con un `projectDirectory` montado puramente como `ro-bind`.

**Preservación de seguridad:**
- 0 tareas ejecutadas, 0 XML generados.
- Caché protegida inalterada (`changed: []`).
- Runner congelado `preflight-phase2.py` en `0444` intacto.
- Reserva oficial Phase-2 de 310 s **100% intacta y no consumida**.

## 13. Volcado exitoso del Grafo de Gradle y derivación de la Allowlist (`run-observe-5`)

**Ejecución:** corrida única dry-run de observación consumida en `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-observe-5` (nodo `ubuntu`).
- Resultado: **`BUILD SUCCESSFUL in 58s`**.
- Salida stderr: **0 bytes** (vacía, sin excepciones).
- Cierre del grafo: **`TOTAL_TASKS: 47`**, **`CLOSURE_TASK_COUNT: 47`**, **`CLOSURE_MATCHES_ALL_TASKS: true`**.
- Tareas de test ejecutadas: **0** (todas reportadas como `SKIPPED` por `--dry-run`).
- Archivos XML de resultados: **0** generados.
- Mutaciones de caché de host / snapshot: **`changed: []`**, 0 bytes modificados.
- Runner congelado `preflight-phase2.py`: **`8c83942b…` en `0444` intacto**.
- Reserva oficial Phase-2 de 310 s: **100% INTACTA Y NO CONSUMIDA**.

### Mapa de tareas exacto derivado (`reviewedPhase2TaskTypes`):

```kotlin
val reviewedPhase2TaskTypes = mapOf<String, String>(
    ":app:bundleDebugClassesToCompileJar" to "com.android.build.gradle.internal.feature.BundleAllClasses_Decorated",
    ":app:bundleDebugClassesToRuntimeJar" to "com.android.build.gradle.internal.feature.BundleAllClasses_Decorated",
    ":app:checkDebugAarMetadata" to "com.android.build.gradle.internal.tasks.CheckAarMetadataTask_Decorated",
    ":app:checkKotlinGradlePluginConfigurationErrors" to "org.jetbrains.kotlin.gradle.plugin.diagnostics.CheckKotlinGradlePluginConfigurationErrors_Decorated",
    ":app:compileDebugJavaWithJavac" to "org.gradle.api.tasks.compile.JavaCompile_Decorated",
    ":app:compileDebugKotlin" to "org.jetbrains.kotlin.gradle.tasks.KotlinCompile_Decorated",
    ":app:compileDebugNavigationResources" to "com.android.build.gradle.tasks.CompileNavigationXmlTask_Decorated",
    ":app:compileDebugUnitTestJavaWithJavac" to "org.gradle.api.tasks.compile.JavaCompile_Decorated",
    ":app:compileDebugUnitTestKotlin" to "org.jetbrains.kotlin.gradle.tasks.KotlinCompile_Decorated",
    ":app:createDebugCompatibleScreenManifests" to "com.android.build.gradle.tasks.CompatibleScreensManifest_Decorated",
    ":app:extractDeepLinksDebug" to "com.android.build.gradle.tasks.ExtractDeepLinksTask_Decorated",
    ":app:generateDebugAssets" to "org.gradle.api.DefaultTask_Decorated",
    ":app:generateDebugBuildConfig" to "com.android.build.gradle.tasks.GenerateBuildConfig_Decorated",
    ":app:generateDebugRFile" to "com.android.build.gradle.internal.res.GenerateLibraryRFileTask_Decorated",
    ":app:generateDebugResources" to "com.android.build.gradle.internal.tasks.ValidateResourcesTask_Decorated",
    ":app:generateDebugUnitTestConfig" to "com.android.build.gradle.tasks.GenerateTestConfig_Decorated",
    ":app:hiltAggregateDepsDebug" to "dagger.hilt.android.plugin.task.AggregateDepsTask_Decorated",
    ":app:hiltAggregateDepsDebugUnitTest" to "dagger.hilt.android.plugin.task.AggregateDepsTask_Decorated",
    ":app:hiltCollectClassesDebug" to "dagger.hilt.android.plugin.task.HiltCollectClassesTask_Decorated",
    ":app:hiltCollectClassesDebugUnitTest" to "dagger.hilt.android.plugin.task.HiltCollectClassesTask_Decorated",
    ":app:hiltJavaCompileDebug" to "org.gradle.api.tasks.compile.JavaCompile_Decorated",
    ":app:hiltJavaCompileDebugUnitTest" to "org.gradle.api.tasks.compile.JavaCompile_Decorated",
    ":app:javaPreCompileDebug" to "com.android.build.gradle.tasks.JavaPreCompileTask_Decorated",
    ":app:javaPreCompileDebugUnitTest" to "com.android.build.gradle.tasks.JavaPreCompileTask_Decorated",
    ":app:kspDebugKotlin" to "com.google.devtools.ksp.gradle.KspAATask_Decorated",
    ":app:kspDebugUnitTestKotlin" to "com.google.devtools.ksp.gradle.KspAATask_Decorated",
    ":app:mapDebugSourceSetPaths" to "com.android.build.gradle.tasks.MapSourceSetPathsTask_Decorated",
    ":app:mergeDebugAssets" to "com.android.build.gradle.tasks.MergeSourceSetFolders_Decorated",
    ":app:mergeDebugResources" to "com.android.build.gradle.tasks.MergeResources_Decorated",
    ":app:mergeDebugUnitTestManifest" to "com.android.build.gradle.tasks.ProcessTestManifest_Decorated",
    ":app:packageDebugResources" to "com.android.build.gradle.tasks.MergeResources_Decorated",
    ":app:packageDebugUnitTestForUnitTest" to "com.android.build.gradle.internal.tasks.PackageForHostTest_Decorated",
    ":app:parseDebugLocalResources" to "com.android.build.gradle.internal.res.ParseLibraryResourcesTask_Decorated",
    ":app:preBuild" to "org.gradle.api.DefaultTask_Decorated",
    ":app:preDebugBuild" to "com.android.build.gradle.internal.tasks.AndroidVariantTask_Decorated",
    ":app:preDebugUnitTestBuild" to "com.android.build.gradle.internal.tasks.AndroidVariantTask_Decorated",
    ":app:processDebugJavaRes" to "com.android.build.gradle.internal.tasks.ProcessJavaResTask_Decorated",
    ":app:processDebugMainManifest" to "com.android.build.gradle.tasks.ProcessApplicationManifest_Decorated",
    ":app:processDebugManifest" to "com.android.build.gradle.tasks.ProcessMultiApkApplicationManifest_Decorated",
    ":app:processDebugManifestForPackage" to "com.android.build.gradle.tasks.ProcessPackagedManifestTask_Decorated",
    ":app:processDebugNavigationResources" to "com.android.build.gradle.internal.tasks.ProcessNavigationXmlTask_Decorated",
    ":app:processDebugResources" to "com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask_Decorated",
    ":app:processDebugUnitTestJavaRes" to "com.android.build.gradle.internal.tasks.ProcessJavaResTask_Decorated",
    ":app:processDebugUnitTestManifest" to "com.android.build.gradle.tasks.ProcessTestManifestPackaging_Decorated",
    ":app:testDebugUnitTest" to "com.android.build.gradle.tasks.factory.AndroidUnitTest_Decorated",
    ":app:transformDebugClassesWithAsm" to "com.android.build.gradle.tasks.TransformClassesWithAsmTask_Decorated",
    ":app:transformDebugUnitTestClassesWithAsm" to "com.android.build.gradle.tasks.TransformClassesWithAsmTask_Decorated"
)
```

**Evidencia de integridad de `run-observe-5`:**
- `bwrap-argv.json`: SHA-256 `0d5180ac0034b83062b57aaa31b73438e057d59ac0d98fbe60ff677270694a71` (54,805 bytes)
- `fingerprint.json`: SHA-256 `1ca7af87e7dbefbff54f7efade312d8c9dbdb072658f2ce38e81d9354de5b8e4` (1,979,502 bytes)
- `gradle.stdout.log`: SHA-256 `71e8c26fb2ef6845c5e065b78c11fa5ada2f807f1f33d0300ff90a6d58e41c4e` (22,654 bytes)
- `gradle.stderr.log`: SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` (0 bytes)

## 14. Población y validación de `tests.init.gradle.kts` con la allowlist de 47 tareas (`run-observe-6`)

**Actualización autorizada de `tests.init.gradle.kts`:**
- Archivo: `/home/wilkin/trindade-hilt-root-ym2yq4lo/snapshot/diagnostic/tests.init.gradle.kts` (SHA-256 `1865c4d10797ef61939cfca3807aad405404474e7b1543e7fbdca8fce53909b7`, 7,590 bytes).
- Se reemplazó la allowlist vacía (`emptyMap()`) con el mapa exacto de 47 tareas `reviewedPhase2TaskTypes` derivado empíricamente en `run-observe-5`.

**Sincronización en `preflight-phase2.next.py`:**
- Se incorporó la aserción estática estricta que exige exactamente 47 entradas en `tests.init.gradle.kts`.
- Se añadió la modalidad `--verify-allowlist` para validar en dry-run contra el script oficial de tests antes de la ejecución.

**Validación formal (`run-observe-6`):**
- Ejecución: `PYTHONDONTWRITEBYTECODE=1 python3 -B preflight-phase2.next.py --verify-allowlist` en el nodo `ubuntu`.
- Resultado: **`BUILD SUCCESSFUL in 59s`**, `stderr` 0 bytes.
- Comprobación del grafo: el bloque `gradle.taskGraph.whenReady` de `tests.init.gradle.kts` evaluó las 47 tareas y validó que el 100% coincide con `reviewedPhase2TaskTypes`.
- Cero tareas ejecutadas (todas `SKIPPED`).
- Cero archivos XML generados.
- Caché protegida del host inalterada: `changed: []`.
- Runner congelado `preflight-phase2.py`: **`8c83942b…` en `0444` intacto**.
- Reserva oficial Phase-2 de 310 s: **100% INTACTA Y NO CONSUMIDA**.


## 15. Auditoría Independiente de Cierre de Fase 2 (VERDICT: PASS)

**Auditor:** `jd-judge-b` (revisor adversarial ciego independiente, tarea `mv4h9xbh-1e-65g2`).

**Dictamen formal:** **`VERDICT: PASS`**

**Evaluación por ejes de auditoría:**
1. **Barrera Pre-JVM & Credenciales:** PASS. Cumplimiento estricto de las 5 capacidades en cero (`CapEff`, `CapPrm`, `CapInh`, `CapAmb`, `CapBnd`), `NoNewPrivs=1`, netns aislado del host y prueba de escritura clasificada (`EROFS` como auto-prueba, `EACCES` requiriendo evidencia de montaje `ST_RDONLY`).
2. **Contrato de Loopback Desacoplado:** PASS. `lo UP` admitido con flags `0x0049`, tabla IPv4 `main` vacía admitida, plantillas inertes de descarte `::/0` (`fib6_null_entry_template`, `flags 0x00200200`, `metric 0xffffffff`) y `::1/128` admitidas. Rechazo estricto de cualquier gateway o interfaz no-loopback.
3. **Hermeticidad de Workspace & Invariancia de Host:** PASS. El `SNAPSHOT` del host está protegido como `--ro-bind` en `/snapshot-base` e incluido en el `fingerprint()` (confirmado `changed: []`). La copia efímera en `run_dir/project` satisface la invariante `projectDirectory.canWrite() == true` de Gradle 9.0+ sin contaminar el host.
4. **Integridad de la Allowlist de 47 Tareas:** PASS. `tests.init.gradle.kts` contiene exactamente las 47 tareas de la clausura de `:app:testDebugUnitTest`. La validación en `run-observe-6` completó en `BUILD SUCCESSFUL in 59s`, con 0 productores de APK y 0 tareas de test adicionales.
5. **Preservación de la Reserva Oficial:** PASS. El directorio de ejecución oficial `run-phase2/` no fue creado ni consumido. La reserva oficial de 310 s permanece 100% intacta.

## 16. Ejecución Oficial Autorizada de Fase 2 (`run-phase2/`)

**Autorización y ejecución:** corrida oficial autorizada (`--execute`) consumiendo los 310 s reservados en `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-phase2` (nodo `ubuntu`).
- Tiempo de ejecución: **3m 46s** (226 segundos; dentro del límite estricto de 310 s).
- Tareas de Gradle: **38 actionable tasks ejecutadas** bajo el sandbox hermético con la allowlist de 47 tareas `reviewedPhase2TaskTypes`.
- Resultado de build: **`BUILD SUCCESSFUL in 3m 46s`**.
- Salida `stderr`: **0 bytes** (vacía, sin excepciones).

**Verificación de pruebas (`xml_verification`):**
- Suite: `com.trindade.app.reports.ReportDetailScreenTest`
- Tests ejecutados: **9**
- Fallos: **0**
- Errores: **0**
- Omitidos (`skipped`): **0**
- Resultado: **`xml_verification=PASS suite=com.trindade.app.reports.ReportDetailScreenTest tests=9 failures=0 errors=0 skipped=0`**

**Casos de prueba verificados (los 9 `@Test` pasaron):**
1. `a report the server marks editable draws the way into the edit surface` — PASS
2. `navigation back and edit actions meet 48dp touch target bounds and invoke callbacks` — PASS
3. `a report the server refuses draws no way into the edit surface, even when canEdit says otherwise` — PASS
4. `prior export remains visible and copiable while a new export is pending` — PASS
5. `pending export without a payload disables loading and offers no copy` — PASS
6. `general loading leaves export loading enabled and invokes its callback once` — PASS
7. `copy replaces existing clipboard content with the exact export payload` — PASS
8. `export payload displays whitespace unicode and newlines verbatim with copy enabled` — PASS
9. `a report the server marks not read-only draws the way in with no canEdit at all` — PASS

**Evidencias de integridad y preservación:**
- Reporte XML oficial: `/home/wilkin/trindade-hilt-root-ym2yq4lo/run-phase2/test-results/TEST-com.trindade.app.reports.ReportDetailScreenTest.xml` (SHA-256 `73b29c3885270578f0f37deb9c62a27110c4a0c39176ae8b1328e65fc9968916`, 1,905 bytes).
- Huella digital de host: `changed: []` en `run-phase2/fingerprint.json` (SHA-256 `ab648e2a…`, 2,197,345 bytes) — inmutabilidad 100% preservada en el snapshot del host y en las cachés de dependencias.
- Registro stdout: `run-phase2/logs/gradle.stdout.log` (SHA-256 `007b756f3df8318272883297cbef9e981f1f641e446bc3cf60d4672af63dc3dd`, 77,294 bytes).
- Registro stderr: `run-phase2/logs/gradle.stderr.log` (SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`, 0 bytes).
- Runner congelado: `preflight-phase2.py` permanece en `0444` con SHA-256 `8c83942b…` intacto.
- Procesos host: 0 procesos residuales de Gradle, Java o sandbox en el sistema tras la ejecución.

