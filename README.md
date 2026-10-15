# Trindade Massas Operações

[![Release](https://img.shields.io/github/v/release/wilkinbarban/Trindade?label=Release&color=blue)](https://github.com/wilkinbarban/Trindade/releases/latest)
[![CI Gate](https://img.shields.io/badge/CI-Passing-brightgreen)](https://github.com/wilkinbarban/Trindade/actions/workflows/ci.yml)
[![Android APK](https://img.shields.io/badge/Android%20APK-Signed%20(v0.4.0)-purple)](https://github.com/wilkinbarban/Trindade/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Plataforma operacional integral para **Trindade Massas**: relatórios de conformidade e higiene, controle de temperaturas, cronograma dinâmico de carregamentos de fleteros com exportação WhatsApp, painel administrativo e aplicativo móvel Android nativo com paridade funcional total.

> 🌐 **Idiomas / Lenguajes:**
> - [🇧🇷 Português do Brasil](#-português-do-brasil)
> - [🇪🇸 Español](#-español)

---

# 🇧🇷 Português do Brasil

## Visão Geral

O ecossistema **Trindade Massas Operações** foi desenhado para unificar a rotina da fábrica, do pátio e da administração em uma única plataforma confiável. Com o lançamento da versão **v0.4.0**, a equipe de campo conta com um aplicativo Android que oferece exatamente as mesmas funcionalidades e regras da aplicação web de mesa, sem necessidade de deslocamento até um computador para gerenciar cargas, tarefas ou relatórios. Esta versão acrescenta o telefone do motorista, o compartilhamento nativo por WhatsApp —com envio direto, um a um, a cada fletero do lote com número conhecido— e o cartão do cronograma ativo no Dashboard.

### Pilha Tecnológica (Stack)

- **Aplicativo Android**: Kotlin 2.4 + Jetpack Compose Material 3 + Hilt + Retrofit 3.0 + Coroutines & Flow (API mínima: Android 8.0 Oreo / Alvo: Android 15).
- **Frontend Web**: React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui.
- **Backend API**: Node.js 24 + Fastify + TypeScript + Zod + JWT + bcryptjs.
- **Banco de Dados**: SQLite gerenciado via `better-sqlite3` com versionamento de esquema atômico (**Revisão 4 atual**).
- **Infraestrutura**: Docker & Docker Compose com proxy reverso Nginx e TLS automático (Let's Encrypt / Certbot).

---

## Aplicativo Móvel Android (v0.5.0)

O aplicativo móvel é distribuído diretamente via GitHub Releases com assinatura digital oficial em chave RSA 4096 (esquema APK v2).

📲 **Download da versão estável:**
👉 **[Baixar o APK mais recente (trindade-0.4.0.apk)](https://github.com/wilkinbarban/Trindade/releases/latest)**

### Destaques e Paridade Funcional

| Módulo / Tela | Descrição | Regra de Acesso |
|---|---|---|
| **Dashboard** | Indicadores operacionais do dia, cronograma ativo com o dia que está sendo carregado e totais históricos em tempo real | Administrador e Trabalhador |
| **Relatórios** | Formulários de checklists e registros numéricos de temperatura com fotos | Administrador e Trabalhador |
| **Edição de Relatórios** | Correção de registros de produtos e fotos dentro da janela operacional | Administrador e Trabalhador |
| **Carregamento** | Cronograma por horários, gestão de cotas por fletero e texto para WhatsApp com compartilhamento nativo e envio direto a cada fletero | Administrador e Trabalhador |
| **Histórico Operacional** | Busca e consulta de cronogramas e relatórios passados | Administrador e Trabalhador |
| **Catálogo de Tarefas** | Tipos de tarefas operacionais com cadastro rápido em campo | Ambos (Trabalhador possui leitura protegida) |
| **Catálogo de Motoristas** | Gestão de fleteros e motoristas da frota com ativação rápida e telefone (WhatsApp) | Administrador e Trabalhador |
| **Categorias de Relatórios** | Gestão de hierarquia de itens, ordem e modo checklist/temperatura | Exclusivo Administrador |
| **Veículos** | Controle de veículos próprios da empresa e placas | Exclusivo Administrador |
| **Horários** | Configuração de slots dinâmicos com substituição e gravação imediata | Exclusivo Administrador |
| **Usuários** | Aprovação de novos cadastros, definição de funções e proteções de auto-exclusão | Exclusivo Administrador |
| **Auditoria** | Log paginado de eventos de segurança e alterações de dados com filtros | Exclusivo Administrador |
| **Perfil da Conta** | Troca de senha, checagem da versão instalada e logout seguro | Administrador e Trabalhador |

---

## Calculadoras Operacionais (v0.5.0)

Três ferramentas de balcão, na web e no Android, sobre a mesma regra e o mesmo contrato. Cada uma tem a sua
regra de negócio num módulo puro, com os seus testes, e as duas plataformas respondem a mesma coisa.

| Ferramenta | Rota (web) | O que faz |
| --- | --- | --- |
| **Calculadora de Assaí** | `/assai-calculator` | Nove produtos; o pedido só existe em caixas inteiras e sai por WhatsApp com uma linha por produto e o total |
| **Calculador de Estoque** | `/stock-calculator` | O catálogo de 25 produtos em duas secções; acumulação corrida de caixas por produto, unidades recalculadas a partir delas e totais do dia |
| **Histórico de Estoque** | `/stock-history` | Os dias já contados, só com os produtos contados, com exportação e controlo de papel (Trabalhador lê, Administrador corrige) |

O **correio do stock** vai para a planta (`xmitrindade@hotmail.com`); a **marca de água** do autor, no pé das
pantallas principais, é outra coisa e usa a morada do autor.

Documentação detalhada: [`docs/calculadoras.md`](docs/calculadoras.md).

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

## Banco de Dados e Esquema (Revisão 5)

O banco de dados SQLite é versionado através de `PRAGMA user_version`:
- **Revisão 1**: Estrutura operacional base (14 tabelas).
- **Revisão 2**: Adição de `auth_sessions` para suporte a refresh tokens rotativos (15 tabelas).
- **Revisão 3**: Coluna `security_version` na tabela `users` com invalidação atômica de sessões anteriores.
- **Revisão 4 (Atual)**: Coluna `phone` opcional na tabela `drivers`, com migração aditiva que não invalida sessões.

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

# Executar suíte de testes Android no container do SDK oficial (797 testes)
make ci-android
```

---

# 🇪🇸 Español

## Descripción General

El ecosistema **Trindade Massas Operaciones** fue diseñado para unificar la operativa diaria de la fábrica, el patio y la administración en una única plataforma robusta y confiable. Con la llegada de la versión **v0.4.0**, el equipo de patio cuenta con una aplicación Android nativa que ofrece exactamente las mismas capacidades y reglas que la versión web de escritorio, eliminando la necesidad de trasladarse a una oficina para registrar cargas, tareas o inspecciones. Esta versión incorpora el teléfono del chofer, el compartir nativo por WhatsApp —con envío directo, uno a uno, a cada fletero del lote que tenga número conocido— y la tarjeta del cronograma activo en el Dashboard.

### Stack Tecnológico

- **Aplicación Android**: Kotlin 2.4 + Jetpack Compose Material 3 + Hilt + Retrofit 3.0 + Coroutines & Flow (API mínima: Android 8.0 Oreo / Objetivo: Android 15 / Compilador: API 37).
- **Frontend Web**: React 18 + TypeScript + Vite + TailwindCSS + shadcn/ui.
- **Backend API**: Node.js 24 + Fastify + TypeScript + Zod + JWT + bcryptjs.
- **Base de Datos**: SQLite gestionado con `better-sqlite3` con versionado atómico (**Revisión 4 actual**).
- **Infraestructura**: Docker & Docker Compose con proxy inverso Nginx y TLS automatizado (Let's Encrypt / Certbot).

---

## Aplicación Móvil Android (v0.5.0)

La app móvil se distribuye directamente a través de GitHub Releases con firma criptográfica oficial en clave RSA 4096 (esquema APK v2).

📲 **Descarga de la versión estable:**
👉 **[Descargar el APK más reciente (trindade-0.4.0.apk)](https://github.com/wilkinbarban/Trindade/releases/latest)**

### Superficies Operativas y Paridad Funcional

| Módulo / Pantalla | Descripción | Regla de Acceso |
|---|---|---|
| **Visión General (Dashboard)** | Indicadores en vivo de reportes, cronograma activo con el día que se está cargando y totales históricos | Administrador y Trabajador |
| **Generador de Reportes** | Checklists dinámicos y tomas de temperatura con adjuntos fotográficos | Administrador y Trabajador |
| **Edición de Reportes** | Corrección de productos y fotos en la ventana operativa habilitada | Administrador y Trabajador |
| **Cronograma de Carga** | Asignación de fleteros por horario, validación de cupos y texto WhatsApp con compartir nativo y envío directo a cada fletero | Administrador y Trabajador |
| **Historial Operativo** | Consulta de cronogramas y reportes de fechas anteriores | Administrador y Trabajador |
| **Catálogo de Tareas** | Tipos de tareas de fábrica con alta rápida en el patio | Ambos (Trabajador con permisos de lectura protegida) |
| **Catálogo de Choferes** | Gestión de fleteros y transportistas con activación inmediata y teléfono (WhatsApp) | Administrador y Trabajador |
| **Categorías de Reporte** | Control de jerarquía, orden y tipo de checklist o temperatura | Exclusivo Administrador |
| **Vehículos** | Gestión y activación de la flota propia de la empresa | Exclusivo Administrador |
| **Horarios de Carga** | Configuración de turnos y guardado atómico inmediato | Exclusivo Administrador |
| **Usuarios** | Aprobación de registros, asignación de roles y protección de cuenta propia | Exclusivo Administrador |
| **Auditoría** | Registro paginado de seguridad y eventos con filtros por acción y entidad | Exclusivo Administrador |
| **Perfil de Cuenta** | Cambio de clave, comprobación de versión instalada y cierre de sesión | Administrador y Trabajador |

---

## Calculadoras Operacionales (v0.5.0)

Tres herramientas de balcón, en la web y en Android, sobre la misma regla de negocio y el mismo contrato. Cada
una tiene su regla en un módulo puro con sus pruebas, y las dos plataformas responden lo mismo.

| Herramienta | Ruta (web) | Qué hace |
| --- | --- | --- |
| **Calculadora de Assaí** | `/assai-calculator` | Nueve productos; el pedido solo existe en cajas enteras y sale por WhatsApp con una línea por producto y el total |
| **Calculador de Estoque** | `/stock-calculator` | El catálogo de 25 productos en dos secciones; acumulación corrida de cajas por producto, unidades recalculadas de ellas y totales del día |
| **Historial de conteo** | `/stock-history` | Los días ya contados, solo con los productos contados, con exportación y control de rol (Trabajador lee, Administrador corrige) |

El **correo del stock** va al buzón de la planta (`xmitrindade@hotmail.com`); la **marca de agua** del autor, al
pie de las pantallas principales, es otra cosa y usa la dirección del autor.

Documentación detallada: [`docs/calculadoras.md`](docs/calculadoras.md).

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

## Base de Datos y Migraciones (Revisión 5)

La base de datos SQLite se controla de manera inmutable mediante `PRAGMA user_version`:
- **Revisión 1**: Estructura de catálogos y reportes base (14 tablas).
- **Revisión 2**: Incorporación de `auth_sessions` para tokens de refresco rotativos (15 tablas).
- **Revisión 3**: Introducción de `users.security_version` con invalidación inmediata de sesiones activas ante cambios de credenciales.
- **Revisión 4 (Producción Actual)**: Columna `phone` opcional en la tabla `drivers`, con migración aditiva que no invalida sesiones.

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

# Ejecutar suite de pruebas unitarias de Android en contenedor SDK (797 tests)
make ci-android
```

---

## Licencia

Este proyecto está distribuido bajo los términos de la **Licencia MIT**. Consulta el archivo [LICENSE](LICENSE) para más detalles.
