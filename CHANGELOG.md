# Changelog — Trindade Massas

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato baseia-se em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/),
e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

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
