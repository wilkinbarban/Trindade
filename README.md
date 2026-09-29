# Trindade Massas Operações

[![Release](https://img.shields.io/github/v/release/wilkinbarban/Trindade?label=Release&color=blue)](https://github.com/wilkinbarban/Trindade/releases/latest)
[![CI Gate](https://img.shields.io/badge/CI-Passing-brightgreen)](https://github.com/wilkinbarban/Trindade/actions/workflows/ci.yml)
[![Android APK](https://img.shields.io/badge/Android%20APK-v0.3.0%20(Signed)-purple)](https://github.com/wilkinbarban/Trindade/releases/tag/v0.3.0)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Plataforma operacional integral para **Trindade Massas**: relatórios de conformidade e higiene, controle de temperaturas, cronograma dinâmico de carregamentos de fleteros com exportação WhatsApp, painel administrativo e aplicativo móvel Android nativo com paridade funcional total.

> 🌐 **Idiomas / Lenguajes:**
> - [🇧🇷 Português do Brasil](#-português-do-brasil)
> - [🇪🇸 Español](#-español)

---

# 🇧🇷 Português do Brasil

## Visão Geral

O ecossistema **Trindade Massas Operações** foi desenhado para unificar a rotina da fábrica, do pátio e da administração em uma única plataforma confiável. Com o lançamento da versão **v0.3.0**, a equipe de campo conta com um aplicativo Android que oferece exatamente as mesmas funcionalidades e regras da aplicação web de mesa, sem necessidade de deslocamento até um computador para gerenciar cargas, tarefas ou relatórios.

### Pilha Tecnológica (Stack)

- **Aplicativo Android**: Kotlin 2.4 + Jetpack Compose Material 3 + Hilt + Retrofit 3.0 + Coroutines & Flow (API mínima: Android 8.0 Oreo / Alvo: Android 15).
- **Frontend Web**: React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui.
- **Backend API**: Node.js 24 + Fastify + TypeScript + Zod + JWT + bcryptjs.
- **Banco de Dados**: SQLite gerenciado via `better-sqlite3` com versionamento de esquema atômico (**Revisão 3 atual**).
- **Infraestrutura**: Docker & Docker Compose com proxy reverso Nginx e TLS automático (Let's Encrypt / Certbot).

---

## Aplicativo Móvel Android (v0.3.0)

O aplicativo móvel é distribuído diretamente via GitHub Releases com assinatura digital oficial em chave RSA 4096 (esquema APK v2).

📲 **Download da versão estável:**
👉 **[Baixar trindade-0.3.0.apk (Releases mais recente)](https://github.com/wilkinbarban/Trindade/releases/latest)**

### Destaques e Paridade Funcional

| Módulo / Tela | Descrição | Regra de Acesso |
|---|---|---|
| **Dashboard** | Indicadores operacionais do dia, cargas de amanhã e totais históricos em tempo real | Administrador e Trabalhador |
| **Relatórios** | Formulários de checklists e registros numéricos de temperatura com fotos | Administrador e Trabalhador |
| **Edição de Relatórios** | Correção de registros de produtos e fotos dentro da janela operacional | Administrador e Trabalhador |
| **Carregamento** | Cronograma por horários, gestão de cotas por fletero e texto para WhatsApp | Administrador e Trabalhador |
| **Histórico Operacional** | Busca e consulta de cronogramas e relatórios passados | Administrador e Trabalhador |
| **Catálogo de Tarefas** | Tipos de tarefas operacionais com cadastro rápido em campo | Ambos (Trabalhador possui leitura protegida) |
| **Catálogo de Motoristas** | Gestão de fleteros e motoristas da frota com ativação rápida | Administrador e Trabalhador |
| **Categorias de Relatórios** | Gestão de hierarquia de itens, ordem e modo checklist/temperatura | Exclusivo Administrador |
| **Veículos** | Controle de veículos próprios da empresa e placas | Exclusivo Administrador |
| **Horários** | Configuração de slots dinâmicos com substituição e gravação imediata | Exclusivo Administrador |
| **Usuários** | Aprovação de novos cadastros, definição de funções e proteções de auto-exclusão | Exclusivo Administrador |
| **Auditoria** | Log paginado de eventos de segurança e alterações de dados com filtros | Exclusivo Administrador |
| **Perfil da Conta** | Troca de senha, checagem da versão instalada e logout seguro | Administrador e Trabalhador |

---

## Primeiros Passos e Desenvolvimento Local

### Pré-requisitos
- Node.js >= 24.0.0
- npm >= 10.0.0
- Docker & Docker Compose (para testes de integração e builds conteinerizados)

### Instalação e Execução

```bash
# 1. Clonar repositório
git clone https://github.com/wilkinbarban/Trindade.git
cd Trindade

# 2. Instalar dependências em todos os workspaces
make install

# 3. Configurar variáveis de ambiente
cp .env.example .env
# Edite .env e defina um JWT_SECRET seguro (mínimo 32 caracteres)

# 4. Iniciar ambiente de desenvolvimento
make dev
```

- **Frontend Web**: `http://localhost:5173`
- **Backend API**: `http://localhost:3000` (ou porta configurada)

---

## Banco de Dados e Esquema (Revisão 3)

O banco de dados SQLite é versionado através de `PRAGMA user_version`:
- **Revisão 1**: Estrutura operacional base (14 tabelas).
- **Revisão 2**: Adição de `auth_sessions` para suporte a refresh tokens rotativos (15 tabelas).
- **Revisão 3 (Atual)**: Coluna `security_version` na tabela `users` com invalidação atômica de sessões anteriores.

### Comandos de Manutenção

```bash
# Inspecionar integridade e versão do esquema (somente leitura)
make db-status

# Aplicar migrações pendentes de forma segura
make db-migrate

# Gerar backup consistente do banco de produção
make db-backup BACKUP_DIR=./backups

# Restaurar backup verificado
make db-restore BACKUP_DIR=./backups/backup-escolhido CONFIRM=true
```

---

## Qualidade, Testes e Integração Contínua (CI)

A integridade do repositório é protegida por testes rigorosos em todas as camadas:

```bash
# Executar a compuerta limpa de CI (Playwright E2E + Backend + Contratos)
make ci-clone

# Executar suíte de testes Android no container do SDK oficial (530 testes)
make ci-android
```

---

# 🇪🇸 Español

## Descripción General

El ecosistema **Trindade Massas Operaciones** fue diseñado para unificar la operativa diaria de la fábrica, el patio y la administración en una única plataforma robusta y confiable. Con la llegada de la versión **v0.3.0**, el equipo de patio cuenta con una aplicación Android nativa que ofrece exactamente las mismas capacidades y reglas que la versión web de escritorio, eliminando la necesidad de trasladarse a una oficina para registrar cargas, tareas o inspecciones.

### Stack Tecnológico

- **Aplicación Android**: Kotlin 2.4 + Jetpack Compose Material 3 + Hilt + Retrofit 3.0 + Coroutines & Flow (API mínima: Android 8.0 Oreo / Objetivo: Android 15 / Compilador: API 37).
- **Frontend Web**: React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui.
- **Backend API**: Node.js 24 + Fastify + TypeScript + Zod + JWT + bcryptjs.
- **Base de Datos**: SQLite gestionado con `better-sqlite3` con versionado atómico (**Revisión 3 actual**).
- **Infraestructura**: Docker & Docker Compose con proxy inverso Nginx y TLS automatizado (Let's Encrypt / Certbot).

---

## Aplicación Móvil Android (v0.3.0)

La app móvil se distribuye directamente a través de GitHub Releases con firma criptográfica oficial en clave RSA 4096 (esquema APK v2).

📲 **Descarga de la versión estable:**
👉 **[Descargar trindade-0.3.0.apk (Última Release)](https://github.com/wilkinbarban/Trindade/releases/latest)**

### Superficies Operativas y Paridad Funcional

| Módulo / Pantalla | Descripción | Regla de Acceso |
|---|---|---|
| **Visión General (Dashboard)** | Indicadores en vivo de reportes, cargas de mañana y totales históricos | Administrador y Trabajador |
| **Generador de Reportes** | Checklists dinámicos y tomas de temperatura con adjuntos fotográficos | Administrador y Trabajador |
| **Edición de Reportes** | Corrección de productos y fotos en la ventana operativa habilitada | Administrador y Trabajador |
| **Cronograma de Carga** | Asignación de fleteros por horario, validación de cupos y texto WhatsApp | Administrador y Trabajador |
| **Historial Operativo** | Consulta de cronogramas y reportes de fechas anteriores | Administrador y Trabajador |
| **Catálogo de Tareas** | Tipos de tareas de fábrica con alta rápida en el patio | Ambos (Trabajador con permisos de lectura protegida) |
| **Catálogo de Choferes** | Gestión de fleteros y transportistas con activación inmediata | Administrador y Trabajador |
| **Categorías de Reporte** | Control de jerarquía, orden y tipo de checklist o temperatura | Exclusivo Administrador |
| **Vehículos** | Gestión y activación de la flota propia de la empresa | Exclusivo Administrador |
| **Horarios de Carga** | Configuración de turnos y guardado atómico inmediato | Exclusivo Administrador |
| **Usuarios** | Aprobación de registros, asignación de roles y protección de cuenta propia | Exclusivo Administrador |
| **Auditoría** | Registro paginado de seguridad y eventos con filtros por acción y entidad | Exclusivo Administrador |
| **Perfil de Cuenta** | Cambio de clave, comprobación de versión instalada y cierre de sesión | Administrador y Trabajador |

---

## Guía de Inicio Rápido (Desarrollo Local)

### Requisitos Previos
- Node.js >= 24.0.0
- npm >= 10.0.0
- Docker & Docker Compose (para pruebas completas y entornos reproducibles)

### Instalación y Puesta en Marcha

```bash
# 1. Clonar el repositorio
git clone https://github.com/wilkinbarban/Trindade.git
cd Trindade

# 2. Instalar dependencias del monorepo
make install

# 3. Configurar variables de entorno
cp .env.example .env
# Edita .env y configura un JWT_SECRET seguro (mínimo 32 caracteres)

# 4. Iniciar servidores en modo desarrollo
make dev
```

- **Frontend Web**: `http://localhost:5173`
- **Backend API**: `http://localhost:3000`

---

## Base de Datos y Migraciones (Revisión 3)

La base de datos SQLite se controla de manera inmutable mediante `PRAGMA user_version`:
- **Revisión 1**: Estructura de catálogos y reportes base (14 tablas).
- **Revisión 2**: Incorporación de `auth_sessions` para tokens de refresco rotativos (15 tablas).
- **Revisión 3 (Producción Actual)**: Introducción de `users.security_version` con invalidación inmediata de sesiones activas ante cambios de credenciales.

### Comandos de Operación

```bash
# Inspección de integridad y estado del esquema (solo lectura)
make db-status

# Ejecución segura de migraciones pendientes
make db-migrate

# Crear copia de seguridad completa y verificada
make db-backup BACKUP_DIR=./backups

# Restaurar copia de seguridad existente
make db-restore BACKUP_DIR=./backups/backup-especifico CONFIRM=true
```

---

## Pruebas y Compuertas de Calidad (CI)

El proyecto cuenta con suites automatizadas que garantizan cero regresiones:

```bash
# Ejecutar verificación completa en clon limpio (Playwright E2E + Backend + Contratos)
make ci-clone

# Ejecutar suite de pruebas unitarias de Android en contenedor SDK (530 tests)
make ci-android
```

---

## Licencia

Este proyecto está distribuido bajo los términos de la **Licencia MIT**. Consulta el archivo [LICENSE](LICENSE) para más detalles.
