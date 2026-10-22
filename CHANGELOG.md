# Changelog — Trindade Massas

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato baseia-se em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/),
e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

---

## [0.5.1] — 2026-10-21

### 🎯 Adicionado (Lançamento de Massas e Ajuda Contextual)
- **Módulo Lançamento de Massas** (`/massas-launch`, web e Android):
  - Catálogo de 10 produtos de massas com cálculo em tempo real de caixas para unidades.
  - **Resumo Coletivo do Dia**: agregação de caixas e unidades totais em formato adaptado a dispositivos móveis, com detalhe cronológico de cada lançamento registrado (hora, operador, quantidades).
  - **RBAC e controle de autor**: botões de edição e exclusão de lançamentos habilitados exclusivamente para o próprio usuário autor (`launch.user_id == currentUserId`) ou para Administradores.
  - **Atualização pasiva em tempo real (Live Polling)**: sincronização silenciosa a cada 15 segundos (`POLL_INTERVAL_MS = 15000L`) em segundo plano enquanto a tela estiver visível e sem edição ativa, sem interromper foco nem inputs do operador.
  - **Histórico de Lançamentos de Massas** (`/massas-history`, web e Android): navegação cronológica por data com resumo acumulado dos produtos lançados.
  - **Compartilhamento WhatsApp**: formato limpo `🍝 *Lançamento de Massas — DD/MM/AAAA*` com lista de itens lançados e rodapé `*Lançado por:* Operador1, Operador2`, estritamente sem linha de total geral.
- **Componente Reutilizável de Ajuda Contextual** (`ModuleHelpDialog` / `ModuleHelpModal`):
  - Modal explicativo passo-a-passo com design consistente em Material 3 / shadcn, ícone âmbar e seções roláveis.
  - Integrado no cabeçalho dos principais módulos operacionais: Dashboard, Estoque, Assaí, Carregamento, Relatórios, Compartilhar Acesso e Lançamento de Massas.
- **Internacionalização Automática e Paridade de Idioma Nativa (`pt-BR` e `es`)**:
  - Resolução nativa de idioma automática baseada no `Locale` do dispositivo do usuário (100% automático, sem seletores manuais na interface).
  - Recurso base `values/strings.xml` por padrão em Português do Brasil (`pt-BR`) sincronizado com 463 chaves.
  - Novo recurso `values-es/strings.xml` contendo todas as 463 chaves traduzidas fielmente para Espanhol (`es`) com paridade estrita de especificadores de formato posicional (`%1$s`, `%1$d`).
  - Refatoração de strings hardcoded em componentes Compose para uso de `stringResource(R.string.*)` em todas as telas operacionais.
  - Cobertura de testes unitários: `LocaleStringsParityTest` (validação de chaves e especificadores de formato XML) e `SpanishLocaleResolutionTest` (resolução em tempo de execução via Robolectric para locales `es` e `pt-rBR`).
- **Remediação do Detector de Atualização Obrigatória**:
  - Inspeção precisa do binário local instalado (`InstalledAppInfo`) com cálculo de hash SHA-256 em streaming com cache em memória e tamanho do `base.apk`.
  - Comparação estrita de digest e tamanho contra os metadatos do GitHub Release (`GitHubAsset.digest`), eliminando falsos positivos e loops infinitos provocados pela discrepância de timestamps entre a compilação Gradle e o upload do release.
- **Dashboard Operacional**:
  - Cartão "Lançamento de Massas" posicionado fixamente na 3ª posição (entre Cronograma Ativo e Calculador de Estoque), com ícone 🍝 e atalhos para lançamento e histórico.
- **Distribuição Android v0.5.1 (Code 502)**:
  - Binário oficial assinado sob esquema APK Signature Scheme v2 (`CN=Trindade Massas Operacoes`).
  - Instalado e validado em emulador Ubuntu (`Trindade_UX_v030`).

---

## [0.5.0] — 2026-10-15

### 🎯 Adicionado (Calculadoras operacionais)
- **Calculadora de Assaí** (`/assai-calculator`, web e Android): nove produtos com as suas unidades por caixa e a regra de que **um pedido só existe em caixas inteiras**. Uma quantidade que não fecha caixas é recusada em vez de arredondada —arredondar produziria um número que não corresponde ao que chega ao portão, e no portão se contam caixas—; o aviso por produto só aparece **depois de o operador sair do campo**, porque enquanto se digita uma quantidade está a ser escrita; e a exportação sai com uma linha por produto e o total, sem a quantidade por caixa, que é aritmética que quem lê não pediu.
- **Calculador de Estoque** (`/stock-calculator`, web e Android): o catálogo de 25 produtos em **duas secções** (Terceirizados e Massas) que chega do servidor e não de uma cópia local, a acumulação corrida de caixas por produto com as unidades recalculadas a partir delas em cada escrita —assim os dois números não podem discordar—, o reset por produto que zera os totais **sem apagar o registo** do que já tinha sido contado, e os totais do dia como o servidor os respondeu.
- **Histórico de Estoque** (`/stock-history`, web e Android): uma rota própria, com a antiga `/stock/history` a redirecionar porque um link já partilhado é um link que alguém pode abrir. Lista **só os produtos contados** —vinte e quatro zeros escondem a linha por que alguém veio—, com selector de data, exportação por WhatsApp e correio, e **controlo de papel**: um Trabalhador lê, um Administrador corrige, e o servidor aplica a mesma fronteira com o seu 403.
- **Marca de água do autor**, no pé do login, do painel de administração e do perfil: «Desenvolvido por Wilkin Barban Rosabal» e «E-mail: wilkin.barban@gmail.com», em 10sp, com o tom atenuado do tema e centrada. Um componente único nas duas plataformas, porque um crédito escrito três vezes fica velho em duas.
- **Paridade Android completa (A1–A7)**: a Calculadora de Assaí, o Calculador de Estoque e o Histórico de Estoque em Compose, sobre os tipos gerados do contrato publicado, mais os três acessos no `MainActivity` como **pestañas** —não como rotas, que é o que o shell tem— e a verificação no emulador de Ubuntu (`Trindade_UX_v030`).

### 🔧 Alterado
- **Correio do stock**: o destino passou a ser o da planta (`xmitrindade@hotmail.com`) em vez da morada de provas, e a tabela do correio passa a ser **medida a partir dos dados** —alinhada à esquerda nas três primeiras colunas e à direita nas unidades, com separador e o total debaixo dessa coluna— em vez de tubos sem largura. O correio do autor é outra coisa e continua a ser o do crédito.
- **Sessão de duas horas**: o token de acesso deixou de expirar aos quinze minutos, que desligavam o operário a meio de uma contagem. A janela de quinze minutos do limitador de tentativas de login **ficou como estava**: essa é sobre adivinhar uma palavra-passe e não sobre terminar um trabalho.
- **Validação ao sair do campo** (as duas calculadoras): a regra é pura e testada —o campo vazio não é um erro, é um campo que ninguém tocou—, o aviso espera que o campo seja deixado e volta a apagar-se ao reentrar para corrigir, porque a primeira tecla de uma correção não é o erro que se está a corrigir; e o botão de adicionar fica apagado quando não há nada para adicionar ou o que há não se pode adicionar.
- **Esquema da base de dados: revisão 5** com a tabela `stock_counts`, aditiva e sem reescrever nada, com o catálogo em código e cada linha a guardar o nome e as unidades por caixa **do momento em que foi contada**, para que uma mudança futura do catálogo não reescreva uma contagem antiga. A migração corre com cópia de segurança preventiva verificada antes de tocar na base, e as filas ficaram intactas.
- **Android**: as três superfícies novas entraram no `RolePolicy` no conjunto partilhado —os três os vê qualquer operário com sessão, como na web— e `RolePolicyTest` obrigou a declarar a mudança em vez de a deixar passar em silêncio.

---
## [0.4.0] — 2026-10-14

### 🎯 Adicionado (Operação de carregamento e exportação WhatsApp)
- **Telefone do motorista e do fletero** (`drivers.phone`): campo **opcional** no cadastro rápido do cronograma e na aba de frota do painel administrativo, na web e no Android. O número é guardado como o operador o escreveu —a lista devolve o que ele digitou, e um valor que voltasse reformatado se leria como a aplicação tendo-o corrigido— e a limpeza tem uma regra única nos dois clientes: enquanto o operador digita, os caracteres que não podem compor um número não chegam ao campo, e ao salvar o texto é recortado e os espaços repetidos colapsados. Editar e esvaziar o campo remove o número; deixá-lo intocado não o altera.
- **Compartilhamento nativo por WhatsApp** (web e Android): o diálogo de exportação do cronograma e o do relatório passam a usar a folha de compartilhamento do sistema, com a cópia como reserva. Uma folha que o operador descarta responde como descartada e **não** copia nada: colocar o texto na área de transferência depois de ele ter dito que não seria o oposto do que pediu.
- **Envio direto, um a um, para fleteros** (web e Android): na exportação do cronograma, cada fletero **daquele lote** cujo número é conhecido ganha um atalho que abre o chat privado com o texto do dia já carregado. A lista sai do lote e não da frota inteira —um cronograma de um dia enviado a quem não está nele é uma mensagem sobre um dia que não lhe diz respeito— e cada motorista aparece uma vez, ainda que ocupe vários horários.
- **Cartão do Cronograma Ativo no Dashboard** (web e Android): mostra **o dia que está sendo carregado** e a sua contagem, e abre o lote correspondente; substitui a contagem de "Cargas de Amanhã", que era um número sobre um dia que o operador não estava carregando.

### 🔧 Alterado
- **Janela operativa de 15 horas**: o resumo do Dashboard passa a considerar a operação que termina às 10:00 (horário de São Paulo). Depois desse corte, o dia ativo aponta para a **próxima data de carregamento** —sexta avança para segunda— em sincronia com o anúncio exportado, em vez de somar um dia no calendário.
- **Contrato da API**: `/api/dashboard/summary` passa a expor `activeScheduleDate`, `activeScheduleCount` e `activeScheduleBatchDate`. O texto exportado do cronograma e o do relatório foram verificados ponta a ponta, e a semântica de `date` —a data do lote, não a do dia de carga— ficou documentada no contrato.
- **Android**: o cartão do Dashboard e o diálogo de exportação do cronograma abrem, respectivamente, o dia do lote e a pantalla de edição daquele dia; a regra de "que dia é hoje em São Paulo" passou a ser uma única função, lida pelas duas telas, em vez de duas cópias que podem discordar à meia-noite.

### 🔒 Backend & Banco de Dados (Revisão 4)
- **Revisão 4 do esquema SQLite (`PRAGMA user_version = 4`)**: coluna `drivers.phone` opcional, com migração aditiva `3 → 4`. A migração só acrescenta a coluna e **não invalida sessões**.
- **Nulo e ausente são intenções distintas no PATCH administrativo**: `null` remove o número, uma chave ausente deixa-o como está, e o cliente web e o Android enviam cada uma das duas quando corresponde.

### 🧪 Testes e Qualidade
- 471 testes de backend, 34 testes puros de frontend, 54 testes ponta a ponta (Playwright/Chromium) e 797 testes JVM do Android com Robolectric e Compose UI Test, todos passando sobre o mesmo commit.
- APK release **0.4.0** (versionCode 400) construído e verificado: assinatura conferida contra a impressão digital fixada do projeto, esquema APK v2, não depurável e `API_BASE_URL` de produção.

---

## [0.3.0] — 2026-09-29

### 🎯 Adicionado (Android)
- **Paridade Funcional Android–Web Completa (11 telas + Perfil)**:
  - `DashboardScreen`: Painel de indicadores em tempo real (relatórios de hoje, cargas de amanhã, usuários ativos, totais históricos).
  - `ReportEditScreen`: Edição e correção de relatórios operacionais diretamente pelo celular.
  - `LoadingEditScreen`: Edição de cronogramas e remanejamento de fleteros por horário.
  - `TasksScreen`: Catálogo de tipos de tarefas com suporte a criação rápida e distinção de permissão.
  - `DriversScreen`: Catálogo de motoristas e fleteros cadastrados.
  - `CategoriesScreen`: Gestão administrativa de categorias de relatórios, ordem e hierarquia (exclusivo Administrador).
  - `VehiclesScreen`: Gestão de frota e veículos próprios da empresa (exclusivo Administrador).
  - `TimeSlotsScreen`: Configuração dinâmica e substituição atômica de horários de carregamento com persistência imediata (exclusivo Administrador).
  - `UsersScreen`: Gestão de usuários, aprovação de cadastros, alteração de funções e proteções estritas contra auto-exclusão/desativação (exclusivo Administrador).
  - `AuditScreen`: Consulta paginada do log de auditoria com filtros combináveis por ação, entidade e ID de usuário (exclusivo Administrador).
- **Navegação Móvel Adaptativa**:
  - Implementação de rolagem horizontal fluida (`horizontalScroll`) e insets de tela cheia (`safeDrawingPadding`) na barra superior de navegação em `MainActivity.kt`.
- **Acesso Estrito por Função (Role-Gating)**:
  - `RolePolicy.kt`: Mapeamento centralizado de pontos de entrada alinhado aos guards do backend (`adminGuard` e `catalogGuard`). O trabalhador recebe visualização focada e avisos de *Somente leitura*.

### 🔒 Backend & Banco de Dados (Revisão 3)
- **Revisão 3 do Esquema SQLite (`PRAGMA user_version = 3`)**:
  - Adição da coluna `security_version` na tabela `users`.
  - Invalidação atômica de sessões e refresh tokens existentes na migração e em eventos de alteração de credenciais.
- **Aperfeiçoamento de Segurança e Admissão**:
  - Rate limiting adaptativo e controle de admissão em `/api/auth/login` e `/api/auth/refresh`.
  - Revogação atômica de sessões em alterações administrativas e troca de senha do próprio usuário.
  - Alinhamento de contratos OpenAPI para cobertura integral das operações administrativas e de auditoria.

### 🧪 Testes e Qualidade
- 530 testes unitários JVM no módulo Android com Robolectric e Compose UI Test passando com 0 falhas.
- 432 testes de backend com cobertura rigorosa de concorrência e migrações.
- 55 testes ponta a ponta (E2E) com Playwright no navegador Chromium em ambiente limpo (`ci-clone`).
- Inspeção e validação visual de matriz em emulador real Pixel 8 (Android 14) contra a API de produção.

---

## [0.2.1] — 2026-09-20
- Correção de verificação de atualização no aplicativo Android e alinhamento de digests do SDK de build.
- Implementação de visualização de versão na tela de Perfil.

## [0.2.0] — 2026-09-20
- Primeira versão pública do aplicativo Android com assinatura oficial e automação de release no GitHub Actions.
- Suporte a geração de relatórios de produção e cronograma de carregamento com exportação WhatsApp.

## [0.1.0] — 2026-09-17
- Lançamento inicial da plataforma web e infraestrutura de produção Trindade Massas.
