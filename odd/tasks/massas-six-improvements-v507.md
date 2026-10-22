# Massas: seis melhorias (Web + Android) e release v507

Reordenar individual/coletivo, mostrar trabalhadores no resumo coletivo, remover o Total Geral, dar hierarquia visual ao resumo, filtrar o coletivo pelo trabalhador selecionado e reescrever a prévia do WhatsApp — com paridade estrita entre Web e Android.

## Specs

S1 — Ordem das vistas. A vista **"Lançamentos Individuais"** vem primeiro e o **"Resumo Coletivo do Dia"** fica imediatamente abaixo dela.
- Web: dentro de `MassasCollectiveTable.tsx`, a Card de lançamentos individuais (chips de trabalhador, subtotal e lista com editar/eliminar) é renderizada antes da Card do resumo coletivo.
- Android: dois `Card` irmãos em `MassasLaunchScreen.kt`; o de lançamentos individuais primeiro, o do resumo coletivo logo abaixo. Hoje ambos vivem num único `Card` ("BLOQUE 2"), com o resumo num sub-bloco "4. Totais consolidados" — esse sub-bloco sai de lá e vira Card próprio.

S2 — Nome dos trabalhadores no resumo coletivo. Cada linha/cartão de produto do resumo coletivo mostra também os trabalhadores que lançaram aquele produto, no formato exato `👤 João Silva, Maria Souza` (nomes distintos, separados por `, `, ordenados alfabeticamente). Se não houver nome disponível, a linha não é exibida. Fonte: os lançamentos daquele produto, respeitando o filtro do S5.

S3 — Sem "Total Geral". Remover por completo o total geral do dia na vista coletiva das duas plataformas.
- Web: o `<tfoot>` com "Total Geral" da tabela de escritório e o bloco móvel de "Total Geral" (`MassasCollectiveTable.tsx:122` e `:161-163`).
- Android: a linha `"${state.dayView?.totalBoxes ?: 0} cx · ${state.dayView?.totalUnits ?: 0} un"` do sub-bloco de totais.
- Não tocar em `massas.totalToLaunch` ("Total Geral a Lançar"), que é o total do formulário de lançamento e outra coisa.

S4 — Hierarquia visual do resumo coletivo.
- Android: cada produto vira um cartão/chip Material 3 destacado (âmbar `0xFFFEF3C7` como o subtotal existente, ou `surfaceVariant` de alto contraste), com o nome em `titleSmall`/`bodyMedium` `FontWeight.Bold`, e badges proeminentes separados para caixas (`cx`) e unidades (`un`) com `FontWeight.ExtraBold`; nomes dos trabalhadores do S2 claramente legíveis abaixo ou ao lado.
- Web: badges de alto contraste para `un` e `cx` com tipografia destacada, mantendo o look das outras tabelas do app.

S5 — Filtro conjunto por trabalhador. O filtro de trabalhador (chips) filtra **simultaneamente** lançamentos individuais e resumo coletivo.
- `Todos os trabalhadores` (`all` / `null`) consolida todo o dia.
- Um trabalhador selecionado faz o resumo coletivo conter só os produtos e quantidades que ele lançou, com o nome dele no S2.
- Web: derivar os produtos ativos a partir de `displayedLaunches` (lançamentos já filtrados), não do array `products` do servidor — usando `products` apenas como catálogo (nome, `unitsPerBox`, ordem).
- Android: idem em `MassasLaunchScreen.kt`/`MassasLaunchViewModel.kt`.

S6 — Prévia do WhatsApp. `massasMessage` (TS) e `formatMassasWhatsAppMessage` (Kotlin) passam a produzir **exatamente** este formato, saída idêntica nas duas plataformas:

```
🍝 *Lançamento de Massas — 22/10/2026*

*Resumo Coletivo:*
• CASEIRA: 80 un (8 cx)
• DISCO 200G: 40 un (4 cx)

*Por Trabalhador:*
👤 *João Silva:*
• CASEIRA: 50 un (5 cx)
• DISCO 200G: 40 un (4 cx)

👤 *Maria Oliveira:*
• CASEIRA: 30 un (3 cx)
```

Regras:
1. Nomes de produto sem o prefixo `MASSA ` (e sem `MASSA DE `): `MASSA CASEIRA` → `CASEIRA`, `MASSA DISCO 200G` → `DISCO 200G`. Aplicar só no início do nome, mantendo o resto intacto.
2. `*Resumo Coletivo:*` consolida o dia inteiro (todos os trabalhadores), uma linha por produto lançado, na ordem do catálogo: `• {NOME}: {unidades} un ({caixas} cx)`. Unidades antes das caixas.
3. Linha em branco, depois `*Por Trabalhador:*`, depois um bloco por trabalhador ordenado por nome: `👤 *{Nome}:*` seguido das linhas de produto dele na ordem do catálogo. Entre blocos de trabalhadores, exatamente uma linha em branco; nenhuma linha em branco entre o cabeçalho `*Por Trabalhador:*` e o primeiro bloco.
4. A antiga linha `*Lançado por:* ...` desaparece: o desglose por trabalhador a substitui.
5. Dia sem lançamentos: `Nenhum lançamento registrado até agora.` depois do cabeçalho e nada mais.
6. Só entram produtos com `units > 0` e trabalhadores com ao menos um lançamento.

S7 — Web verde: `pnpm --filter @trindade/frontend test` e `pnpm --filter @trindade/frontend exec tsc --noEmit` sem falhas, com `massas.test.ts` cobrindo a saída exata do S6 (string completa, incluindo linhas em branco) e o filtro do S5.

S8 — Android verde: `./gradlew testDebugUnitTest --no-daemon` no Docker SDK 35 sem falhas, com `MassasLogicTest.kt` cobrindo a saída exata do S6.

S9 — Release: `versionCode=507`, `versionName=0.5.1`, APK Release assinado (v2), `aapt2 dump badging` com `versionCode='507'`, assets `--clobber` no GitHub Release `v0.5.1`, cópia em `/home/wilkin/Desktop/trindade-0.5.1.apk`, commits convencionais em `main` sem `Co-Authored-By`, push a `origin main`, `docker container prune` + `docker builder prune`, e **nenhum `adb install`** no emulador.

## Tasks

- [x] T1 → S1-S6 (Web), S7 — Rota: writer web (executado inline; subagentes indisponíveis por credencial de provedor). Superfícies: `packages/frontend/src/pages/massas/MassasCollectiveTable.tsx`, `packages/frontend/src/pages/MassasLaunchPage.tsx`, `packages/frontend/src/pages/MassasHistoryPage.tsx`, `packages/frontend/src/lib/massas.ts`, `packages/frontend/src/lib/massas.test.ts`. Evidência: `node .../typescript/bin/tsc --noEmit` exit 0; `pnpm --filter @trindade/frontend test` → 128 testes, 0 falhas.
- [x] T2 → S1-S6 (Android), S8 — Rota: writer android (executado inline). Superfícies: `MassasLaunchScreen.kt`, `MassasLogic.kt`, `MassasLogicTest.kt`. Evidência: `MassasLogicTest`/`MassasLaunchViewModelTest` verdes em Docker SDK 35.
- [x] T3 → S9 — Bump 507 em `build.gradle.kts` (default de debug), `CHANGELOG.md`, `docs/release-android.md`.
- [x] T4 → S9 — Build Release assinado em Docker SDK 35 com `-PversionCode=507 -PversionName=0.5.1 -PrequireSigned=true`: `aapt2` reporta `versionCode='507'`, `versionName='0.5.1'`; `apksigner verify` → `Verifies`, v2 `true`, 1 signer, SHA-256 `ee0e404b…c431f6`; `BuildConfig.API_BASE_URL="https://trindademasas.duckdns.org/"`; sem `app-release-unsigned.apk`; `10,814,264` bytes, `sha256 3d03d58194…675cf7` (rebuild final após o ajuste de ordenação).
- [x] T5 → S9 — `gh release upload v0.5.1 … --clobber` (APK + `.sha256`) e cópia em `/home/wilkin/Desktop/trindade-0.5.1.apk` com mesmo digest. O asset duplicado `app-release.apk` do mesmo tag recebeu os mesmos bytes, porque `UpdateDetector` escolhe o primeiro asset que termina em `.apk`. Nenhum `adb install`.
- [x] T6 → S9 — Commits convencionais em `main` (`7cefd2a` web, `9833cda` android, `8a69faa` release, `9075bad` paridade de ordenação, `7e2d16d` digest, `a2508b6` docs) e push a `origin main`.
- [x] T7 → S9 — `docker container prune` + `docker builder prune` e `df -h`.
- [x] T8 → S8/S9 — Suíte completa Android: 967 testes, 0 falhas, 0 skipped (após um timeout isolado e não relacionado em `AdminApiPayloadTest`, confirmado verde em rerun focado).

## Log

L1 (usuário, verbatim): ver `/tmp/directive_massas_six_improvements.txt`, transcrito abaixo.

```
| # DIRECTIVA TÉCNICA: 6 MEJORAS EN MÓDULO LANÇAMENTO DE MASSAS (WEB Y ANDROID) + RELEASE v507
| 
| Estimado agente en Trindade:
| Procedemos a implementar de inmediato las 6 mejoras solicitadas por el usuario para el módulo **Lançamento de Massas** con estricta paridad entre Web (Frontend) y Android, seguido por la compilación oficial Release con `versionCode = 507`, firma, publicación en GitHub Release `v0.5.1`, entrega en Desktop y purga de Docker.
| 
| ---
| 
| ### REQUERIMIENTOS ESPECÍFICOS DE LAS 6 MEJORAS
| 
| #### 1. Reordenamiento de Vistas
| - **Objetivo**: La sección/vista **"Resumo Coletivo do Dia"** debe posicionarse **justo debajo** de **"Lançamentos Individuais"** (invertir el orden actual donde el resumen estaba arriba).
| - **Web** (`packages/frontend/src/pages/massas/MassasCollectiveTable.tsx` / `MassasLaunchPage.tsx`):
|   - Renderizar primero la Card de **Lançamentos Individuais** (con selector de trabajadores, subtotal y lista de lanzamientos).
|   - Renderizar justo debajo la Card de **Resumo Coletivo do Dia**.
| - **Android** (`packages/android/app/src/main/java/com/trindade/app/massas/MassasLaunchScreen.kt`):
|   - Separar claramente o invertir los bloques para que la sección de **Lançamentos Individuais** (chips de operario, subtotal y lista con botones de editar/eliminar) esté en primer lugar, y la sección de **Resumo Coletivo do Dia** quede justo debajo de ella.
| 
| #### 2. Nombre del Trabajador en Resumo Coletivo do Dia
| - **Objetivo**: En cada ítem/producto del **Resumo Coletivo do Dia**, mostrar también el/los nombre(s) del/los trabajador(es) que lanzaron dicho producto.
| - En cada fila o tarjeta de producto (ej. `CASEIRA`, `DISCO 200G`), extraer de los lanzamientos los operarios que aportaron a ese producto e indicar visualmente: `👤 João Silva, Maria Souza` (o desglose de quién lo lanzó).
| 
| #### 3. Eliminar "Total Geral" en Resumo Coletivo do Dia
| - **Objetivo**: Eliminar por completo el dato/bloque de "Total Geral". No hace falta ni aporta valor en esa vista.
| - **Web**: Quitar el `<tfoot>` con "Total Geral" de la tabla de escritorio y eliminar el bloque móvil de "Total Geral".
| - **Android**: Eliminar cualquier tarjeta, fila o contador de "Total Geral" o `totalBoxes · totalUnits` general en el bloque de resumen colectivo.
| 
| #### 4. Resaltar Visualmente los Datos en Resumo Coletivo do Dia
| - **Objetivo**: Darle mayor connotación y jerarquía visual a los datos del resumen colectivo (en Android estaba como texto plano `bodySmall`).
| - **Android**: Convertir la lista de productos del resumen colectivo en tarjetas estilizadas o chips con Material 3: contenedores destacados (tonos ámbar o surfaceVariant bien contrastados), badges prominentes para las cajas (`cx`) y unidades (`un`), tipografía destacada (`titleSmall`/`bodyMedium` en `FontWeight.Bold`), iconos limpios y nombres de trabajadores asociados claramente visibles.
| - **Web**: Asegurar tipografía y badges destacados con alto contraste para un look profesional y homogéneo.
| 
| #### 5. Filtrado Conjunto por Trabajador
| - **Objetivo**: El filtro de trabajador (chips) existente en la vista de "Lançamentos Individuais" debe filtrar simultáneamente tanto los lanzamientos individuales como los datos de **"Resumo Coletivo do Dia"**.
| - Al seleccionar un trabajador específico (ej. `👤 João`):
|   - "Lançamentos Individuais" muestra solo los lanzamientos de João.
|   - "Resumo Coletivo do Dia" muestra únicamente los productos y cantidades consolidados que lanzó João (junto con su nombre).
| - Al seleccionar "Todos os trabalhadores" (`all` / `null`), el resumen colectivo vuelve a consolidar la producción de todos los trabajadores.
| - **Web** (`MassasCollectiveTable.tsx`): Calcular los productos activos de la vista colectiva a partir de `displayedLaunches` (los lanzamientos filtrados por el operario seleccionado) en vez del array global estático.
| - **Android** (`MassasLaunchScreen.kt` / `MassasLaunchViewModel.kt`): Calcular/agrupar los productos del resumen colectivo basados en los lanzamientos filtrados por el operario seleccionado.
| 
| #### 6. Vista Previa de WhatsApp (PRÉVIA DA MENSAGEM)
| - **Objetivo**:
|   1. Quitar la palabra *"Massa "* / *"MASSA "* (y *"de "* si aplica) de los nombres de los productos al formatear el texto. Ejemplo: `MASSA CASEIRA` -> `CASEIRA`, `MASSA DISCO 200G` -> `DISCO 200G`.
|   2. Incluir el desglose de lo lanzado por cada trabajador con sus totales por producto (estructura parecida al reporte del Relatório):
|      - Encabezado: `🍝 *Lançamento de Massas — DD/MM/YYYY*`
|      - Resumen colectivo sin la palabra "Massa":
|        ```
|        *Resumo Coletivo:*
|        • CASEIRA: 80 un (8 cx)
|        • DISCO 500G: 40 un (2 cx)
|        ```
|      - Desglose por trabajador:
|        ```
|        *Por Trabalhador:*
|        👤 *João Silva:*
|        • CASEIRA: 50 un (5 cx)
|        • DISCO 500G: 40 un (2 cx)
| 
|        👤 *Maria Oliveira:*
|        • CASEIRA: 30 un (3 cx)
|        ```
| - Actualizar `packages/frontend/src/lib/massas.ts` (`massasMessage`) y `packages/android/app/src/main/java/com/trindade/app/massas/MassasLogic.kt` (`formatMassasWhatsAppMessage`).
| - Actualizar y ejecutar los tests unitarios en Web (`massas.test.ts`) y Android (`MassasLogicTest.kt`).
| 
| ---
| 
| ### VERIFICACIÓN Y RELEASE ANDROID v507
| 
| 1. **Tests unitarios**:
|    - Web: `pnpm --filter @trindade/frontend test` y verificación de tipos `pnpm --filter @trindade/frontend exec tsc --noEmit`.
|    - Android: `./gradlew testDebugUnitTest --no-daemon` en Docker SDK 35.
| 2. **Incremento de `versionCode`**:
|    - Subir `versionCode` a **507** en `packages/android/app/build.gradle.kts`.
| 3. **Build Release**:
|    - Compilar en contenedor Docker SDK 35 (`ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9`) con `-PversionCode=507`.
|    - Firmar con `/home/wilkin/.android-keystores/trindade-release.jks`.
|    - Validar con `aapt2 dump badging` (`versionCode='507'`) y `apksigner verify`.
| 4. **Entrega y GitHub Release**:
|    - Actualizar assets en GitHub Release `v0.5.1` con `gh release upload v0.5.1 ... --clobber`.
|    - Copiar a `/home/wilkin/Desktop/trindade-0.5.1.apk`.
| 5. **Git y Docker**:
|    - Commits convencionales en `main` sin atribución a IA (`Co-Authored-By` PROHIBIDO).
|    - Push a `origin main`.
|    - Purgar Docker (`container prune`, `builder prune`).
| 6. **REGLA DE ORO**:
|    - **PROHIBIDO INSTALAR EN EL EMULADOR VÍA ADB** (`adb install`).
| 
| Por favor, confirma el inicio de la implementación y avanza hasta completar la entrega.
```
