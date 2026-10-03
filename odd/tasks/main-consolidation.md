# Consolidación de trabajo pendiente en main

## Autorización y límites
- Usuario autoriza conservar, auditar e integrar todo el trabajo pendiente en main y dejar un árbol limpio.
- No descartar datos, reset, clean, force push, bypass de hooks ni eliminación de trabajo no integrado.
- No arranque de emulador, despliegue, publicación de credenciales o claves. Confirmaciones destructivas y consentimientos nativos siguen separados.
- Ramas/worktrees temporales son aislamiento de ejecución; destino final main. Limpieza sólo después de comprobar conservación e integración.

## Evidencia inicial
- Auditoría readonly mus9s0q3-2o-xh12; sus estimaciones de líneas y cobertura histórica no prueban integración actual.
- main local/origin almacenado: b8508398a1b590616985b191b2ccef0dbc8eb0c2; estado remoto vivo pendiente.
- Principal admission-policy cb9f7d0: ~45 cambios tracked y numerosos untracked; conservar también nuevos módulos, ledgers y artefactos fuera de Git.
- UX 6c572df, seguridad/enrollment ec7bfda, QA 3e0a9ec: reconciliar DAG real y contenidos antes de integrar; no cherry-picks duplicados.
- Memoria 4528d620d37208fca8d3797d2adda6388c065376 publicada, 340 líneas, 16 tests y 480 comparaciones; revisión review-66c52b08e6eb61c2 aprobada/ACK consumido.
- Guardia monolítica 9341 líneas y scripts QA ~5220: sin excepción masiva autorizada. Resolver partición o conservación externa antes de publicación.

## Tareas
1. [ ] Conservar cambios y referencias mediante snapshot privado verificable; producir manifiesto y cotejar estado estable. Worker musa37ix-2p-4fke reporta snapshot `.git/consolidation-recovery/main-consolidation-20261003T110416Z`: tar 51660800 bytes / 5179 entradas cotejadas; bundle 2311085 bytes / 40 refs verificado; 8 worktrees estables. Independiente musadsx7-2q-e2ux PASS: permisos 0700/0600, bundle 40 refs, 5179/5179 entradas y 8 índices exactos; 8/8 HEAD/índices/status actuales coinciden. Una deriva de contenido sin ausencias, identificar antes de reconciliar; snapshot original válido. Cierre documental con commit pendiente. No garantía de secretos por sólo screening de nombres; ignored excluidos.
2. [ ] Reconciliar DAG, estado remoto y política de main; medir unidades reales y elegir ruta de integración sin duplicación. Scout readonly musae8p5-2r-xuv7 en curso, sin fetch/mutaciones; consulta remota sólo lectura autorizada al origin configurado.
3. [ ] Integrar y verificar unidades de seguridad, contratos y enrollment con commits/revisión acotados.
4. [ ] Integrar y verificar UX Android, QA y memoria conservando cambios locales no representados por ramas.
5. [ ] Resolver guardia/QA oversized y ledgers pendientes sin fingir historial ni aprobar excepciones implícitas.
6. [ ] Verificar conjunto integrado y publicar main respetando política remota.
7. [ ] Reconciliar checkout principal y retirar sólo ramas/worktrees ya preservados e integrados con autorizaciones aplicables; verificar árbol limpio.

## Checks y cierre
Pendientes: snapshot, pruebas integradas, revisión de cada candidato nuevo, comprobación de secretos, estado remoto, merge y limpieza. Ningún merge a main efectuado.
Cada unidad registrará commit, diff total, checks observados y rollback. TDD no genera RED artificial para integrar código existente; comportamiento nuevo exige checks aplicables test-first.
