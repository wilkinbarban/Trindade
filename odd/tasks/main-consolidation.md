# Consolidación de trabajo pendiente en main

## Autorización y límites
- Usuario autoriza conservar, auditar e integrar todo el trabajo pendiente en main y dejar un árbol limpio.
- No descartar datos, reset, clean, force push, bypass de hooks ni eliminación de trabajo no integrado.
- No arranque de emulador, despliegue, publicación de credenciales o claves. Confirmaciones destructivas y consentimientos nativos siguen separados.
- Ramas/worktrees temporales son aislamiento de ejecución; destino final main. Limpieza sólo después de comprobar conservación e integración.

## Evidencia inicial (estados históricos anotados)
- Auditoría readonly mus9s0q3-2o-xh12; sus estimaciones de líneas y cobertura histórica no prueban integración actual.
- main local/origin almacenado: b8508398a1b590616985b191b2ccef0dbc8eb0c2 [SUPERADO: main histórica publicada en 7efd465; feature local en 0cbe765, sin push adicional].
- Principal admission-policy cb9f7d0: ~45 cambios tracked y numerosos untracked; conservar también nuevos módulos, ledgers y artefactos fuera de Git.
- UX 6c572df, seguridad/enrollment ec7bfda, QA 3e0a9ec: reconciliar DAG real y contenidos antes de integrar; no cherry-picks duplicados.
- Memoria 4528d620d37208fca8d3797d2adda6388c065376 publicada, 340 líneas (114 módulo + 226 tests integrados), 16 tests y 480 comparaciones; revisión review-66c52b08e6eb61c2 aprobada/ACK consumido.
- Scripts QA ~5220 ya integrados históricamente (sin reclamo de nueva importación ni aprobación retroactiva); excepción/partición aplica únicamente a la guardia restante de 9341 líneas (2436 código + 6905 tests untracked en parent preservadas; no un único archivo ni 9001 restantes). Resolver partición o conservación externa antes de publicación.

## Checkpoint de estado actual verificado
- **Puntas de rama**: main publicada histórica en `7efd465c7ab696ffb598e20fe6cc0311e37ae44a`; feature en `0cbe765c9278ecc280fda1d46d97949eaa304ddb` (rama limpia antes de esta edición documental; actualmente sólo este ledger modificado), sin push adicional.
- **Diferencias de checkout y ramas**: divergencias entre ramas y checkouts representan trabajo en etapas preservado, no pérdida de datos.
- **Seguridad y contratos**: backend 51 tests PASS, artefacto OpenAPI 0 diff, cliente 53 tests PASS sobre base previa de 749.
- **QA y memoria**: scripts QA (2775 + 2445 = 5220 líneas) ya trackeados e integrados históricamente; módulo de memoria (`memory.py` 114 + `test_memory.py` 226 = 340 líneas) completamente integrado.
- **Guardia de emulador**: scripts originales untracked en checkout principal preservados (`f3_emulator_guard.py` 2436 líneas + `test_f3_emulator_guard.py` 6905 líneas = 9341 líneas totales; no es un único archivo ni se sostiene afirmación de 9001 restantes). Guardia monolítica NO marcada como resuelta.
- **SupervisorEngine (8c)**: 8c1 claim tracker cerrado en adaec8c (árbol a3c825c, 10/299 PASS); 8c2 termination cerrado en 7e599dc (árbol 4e984c6, 11/310 PASS); 8c3 readiness cerrado en e55e27f (árbol d44ec3b, 13/323 PASS, review-fa334dc3c93ef6bb ACK consumido, 354 gross; límites: sin matriz combinada explícita de errores/kwargs/stale UNKNOWN [inspección], sin hermetismo universal, wiring 8c4a/b pendiente); 8c4a-8c4b pendientes; monolitos intactos.
- **TimeSlots y UI**: 17 tests frescos PASS en `TimeSlotsAdminScreenTest.kt`; linaje completo `review-6a7d96044905b9cf` aprobado con ACK consumido/quemado antes del commit local `0cbe765` sobre árbol candidato coincidente (no revisión de toda la rama de commits).
- **Pendientes de dispositivo**: restauración integral de formulario/diálogo/VM/Hilt no acreditada; IME, insets y gestos reales pendientes de dispositivo; export/PDF/share/TalkBack pausados.

## Tareas
1. [x] Conservar cambios y referencias mediante snapshot privado verificable; producir manifiesto y cotejar estado estable. Snapshot `.git/consolidation-recovery/main-consolidation-20261003T110416Z` (tar 51660800 bytes / bundle 2311085 bytes / 40 refs) verificado y preservado.
2. [x] Reconciliar DAG, estado remoto y política de main; medir unidades reales y elegir ruta de integración sin duplicación. main publicada histórica en 7efd465; feature en 0cbe765 sin push adicional.
3. [x] Integrar y verificar unidades de seguridad, contratos y enrollment con commits/revisión acotados (backend 51 PASS, OpenAPI 0 diff, cliente 53 PASS).
4. [ ] Integrar y verificar UX Android, QA y memoria: scopes verificadas (memoria 114+226 integrada, QA 5220 trackeado históricamente, TimeSlots 17 PASS con linaje review-6a7d96044905b9cf previo a commit 0cbe765 cerrado sobre árbol candidato, export foundation integrada); pendientes: restauración integral de formulario/diálogo/VM/Hilt no acreditada; IME, insets y gestos reales pendientes de dispositivo; export/PDF/share/TalkBack pausados.
5. [ ] Resolver guardia/QA oversized y ledgers pendientes: QA 5220 ya integrado históricamente (sin nueva importación ni aprobación retroactiva); memoria integrada; excepción y partición aplican únicamente a la guardia original restante (2436 + 6905 = 9341 untracked en parent) preservada pero pendiente de extracción modular (no marcar resuelta la guardia completa).
6. [ ] Verificar conjunto integrado y publicar main respetando política remota: main histórica publicada en 7efd465; feature en 0cbe765 sin push adicional hasta consolidar.
7. [ ] Reconciliar checkout principal y retirar sólo ramas/worktrees ya preservados e integrados: diferencias de ramas/checkout preservadas (no pérdida de datos).

## Plan por etapas
| Etapa | Alcance | Estado | Límites y dependencias |
|---|---|---|---|
| 1. Reconciliación del ledger | Actualización documental de `odd/tasks/main-consolidation.md` | En curso (esta unidad) | Solo doc (<=200 diff lines), sin código ni comandos destructivos. |
| 2. Extracción modular clock/TTL | Módulo puro y tests unitarios herméticos de reloj y TTL (~175 líneas estimadas) | Planificada (sin implementación aún) | Lógica pura; sin ADB, señales de proceso, CLI, red ni dependencias de dispositivo/emulador. |

## Checks y cierre
Pendientes: extracción modular de componentes de guardia, validación en dispositivo (IME/insets/gestos/restauración y reanudación de export/share/TalkBack), verificación global final y reconciliación de checkout principal.
Cada unidad mantendrá commits acotados, diff medido, checks observables y preservación estricta de trabajo existente.

## Reanudación autorizada: todos los pendientes (2026-10-06)
Límite vigente: sin push, merge ni eliminación de ramas/worktrees; preservar datos y monolitos. HEAD observado por explorador: cbfe881. Los 750 PASS son JVM/Robolectric, no validación nativa.
- [ ] Guardia: health_parse (cerrado); probe_eval (cerrado); partición controlador de procesos 7a/7b.1/7b.2/7c (cerrado en 2cb8788); SupervisorScheduler 8a.1 (cerrado en c9bbec6); proc_identity 8b.1 cerrado en af388f0; 8b.2a cerrado en bef7d7d (árbol 65dff7663249cd7ba237e95693a9f2a11f880203, review-126904374fb7ef5f, ACK quemado rev ebe0e2aaa1b12f5c8c20e53e715ed22d1f254348a869052910620237b90a73a2, 454/460 líneas candidatas); 8b.2b1 cerrado en 5b4244a; 8b.2b2 cerrado en f43abd0 (árbol 4805d73ff90934b13170fc474c531264ce7f6726, review-1765610e0d57b77b, ACK quemado, 38/289 PASS, AST/hash intact, 384/400 gross, corrección 33/39; estados pendientes previos de safe_sigterm superados por cierre); 8c en curso (8c1 claim tracker cerrado en adaec8c, árbol a3c825c, 10/299 PASS; 8c2 termination cerrado en 7e599dc, árbol 4e984c6, 11/310 PASS; 8c3 readiness cerrado en e55e27f, árbol d44ec3b, 13/323 PASS, review-fa334dc3c93ef6bb ACK consumido, 354 gross -> 8c4a assembly -> 8c4b step; <=400 c/u, semántica UNKNOWN-stale preservada, monolitos intactos).
- [ ] QA y ledgers: reconciliar cobertura y evidencia histórica sin nueva importación implícita.
- [ ] Export/PDF/share: reconciliar divergencias ReportDetail/Loading y requerimientos existentes antes de ampliar wiring.
- [ ] Nativo: PDF, FileProvider/share, TalkBack, Keystore/TLS, notificaciones, IME/insets/gestos y restauración TimeSlots/ReportForm; sin borrar datos ni mutar producción.
- [ ] Verificación integrada fresca de backend/OpenAPI/cliente y Android sobre candidato exacto; 51/53/0diff previos son históricos.
- [ ] Checkout/residuos/worktrees/ramas: inventario y conservación; eliminación fuera de autorización.
- [ ] Autoridad/tooling: inspeccionar lock de mantenimiento y residuos por protocolo, sin borrado ni kills ciegos.
Primera unidad en curso: health_parse, fuente original 375–513 (139 líneas), imports ~12 y tests estimados 190–210; medir diff real <=400. Probe_eval 514–544 separado. No reducir cobertura por ahorro cosmético.

### Incidente previo a producción health_parse
Writer muxfc9rx-4q-6adj falló por cleanup no confirmado (1000ms), capacidad en cuarentena. Respuesta retenida reporta tests199líneas/14métodos y RED importerror; todavía no evidencia independiente ni confirmación de salida. Producción pausada. Verificador muxfhze2-4r-0851 diagnostica solo lectura, estado de archivos y RED independiente; sin señales, eliminación de locks ni liberar cuarentena manualmente.

### Integración health_parse cerrada (2026-10-07)
- **Estado de health_parse**: SUPERADO / CERRADO. El incidente previo y estado pendiente quedan superados de forma factual por la integración del commit aprobado.
- **Commit integrado**: `5a69a927c4335441939ebd76dc10bf6b200a8d1b` (`fix(guard): extract health parser with bounded semantic corrections`).
- **Árbol / Tree SHA**: `f793ceaf7df30daf529963d901c0850e0266daa4`.
- **Revisión / Aprobación / ACK**: Revisión `review-f16d0c2a57c361ae` aprobada y token ACK consumido/quemado antes del commit.
- **Líneas y presupuesto**: 381 líneas añadidas (162 en `scripts/f3_guard/health_parse.py` + 219 en `scripts/f3_guard/tests/test_health_parse.py`), dentro del presupuesto estricto de <=400 líneas. Corrección semántica acotada: 26 <= 39 líneas de corrección frente a la base del monolito original.
- **Verificación fresca observada**:
  - `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest scripts.f3_guard.tests.test_health_parse`: 17 tests PASS en 0.003s.
  - `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s scripts/f3_guard/tests -p 'test_*.py'`: 83 tests PASS en 0.028s (suite modular completa sin regresiones, sin ADB ni emulador).
- **Integridad y autorización**: Integración ejecutada mediante fast-forward autorizado (`git merge --ff-only 5a69a92`), excepción autorizada explícitamente por el usuario para la rama de feature `feature/principal-review-chain` (manteniendo la prohibición general de merges no autorizados en main). Los archivos de staging/worktree previos fueron respaldados de forma privada aditiva en `.git/consolidation-recovery/health-integration-20261007T034938Z` (permisos 0700 dir, 0600 archivos) con manifiesto verificado de hashes antes de la sincronización. La guardia monolítica en su conjunto continúa incompleta; el siguiente paso modular es `probe_eval`.

