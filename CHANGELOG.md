# Changelog — Trindade Massas

Todas as alterações notáveis neste projeto serão documentadas neste arquivo.
O formato baseia-se em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/),
e este projeto adere ao [Versionamento Semântico](https://semver.org/lang/pt-BR/).

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
