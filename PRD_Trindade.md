# PRD v2.0 — Trindade Massas Operações

# 1. Visión General

## Nombre del Proyecto

Trindade Massas Operações

## Descripción

Sistema web operativo para Trindade Massas destinado a gestionar:

* Relatórios operativos.
* Horários de carregamento.
* Histórico operacional.
* Exportación optimizada para WhatsApp.
* Gestión de usuarios.

El objetivo principal es reducir el tiempo de elaboración de relatórios y horarios de cargamento a menos de 2 minutos desde cualquier dispositivo.

---

# 2. Objetivos del Negocio

## Problemas actuales

* Relatórios escritos manualmente.
* Formatos inconsistentes.
* Pérdida de tiempo.
* Horarios de cargamento organizados manualmente.
* Riesgo de errores al programar fleteros.

## Objetivos

* Estandarizar relatórios.
* Estandarizar horarios.
* Reducir errores.
* Facilitar uso desde celular.
* Mantener histórico.
* Mejorar la presentación profesional en WhatsApp.

---

# 3. Identidad Visual

La aplicación deberá utilizar como referencia:

https://trindademassas.com.br

## Elementos visuales

* Logo oficial.
* Colores corporativos.
* Tipografía coherente.
* Diseño amigable.
* Optimización móvil primero (Mobile First).

---

# 4. Tecnologías

## Frontend

* React
* TypeScript
* Vite
* TailwindCSS
* shadcn/ui
* React Query
* React Hook Form
* i18next

## Backend

* Node.js 24 LTS
* Fastify
* TypeScript
* Zod
* JWT

## Base de Datos

* SQLite
* better-sqlite3

## Infraestructura

* Docker
* Docker Compose
* Ubuntu VPS
* Nginx o Caddy

---

# 5. Roles

## Administrador

Puede:

* Gestionar usuarios.
* Gestionar categorías.
* Gestionar tareas.
* Gestionar catálogo maestro.
* Gestionar fleteros.
* Gestionar vehículos de casa.
* Gestionar relatórios.
* Gestionar horarios.
* Ver histórico completo.

## Trabajador

Puede:

* Crear relatórios.
* Crear horarios.
* Editar registros permitidos.
* Agregar categorías.
* Agregar tareas.
* Copiar para WhatsApp.

No puede:

* Crear usuarios.
* Eliminar usuarios.
* Administrar permisos.

---

# 6. Sistema de Idiomas

Idiomas soportados:

* Portugués
* Español

Reglas:

* Detectar idioma del navegador.
* Permitir cambio manual.
* Guardar preferencia.

IMPORTANTE:

Toda exportación para WhatsApp deberá generarse en portugués independientemente del idioma utilizado dentro del sistema.

---

# 7. Módulo Relatórios

## Objetivo

Generar relatórios profesionales mediante selección visual.

Sin necesidad de escribir textos largos.

---

# 8. Determinación Automática del Turno

## Relatório da Tarde

Desde:

18:30

En adelante.

## Relatório da Noite

06:00

Fecha y hora obtenidas automáticamente del servidor.

---

# 9. Generador Inteligente de Relatório

El usuario únicamente selecciona información.

El sistema construye automáticamente el texto final.

---

# 10. Tipos de Elementos del Relatório

## Tipo 1 — Check

Ejemplo:

* Pátio organizado
* Câmara principal organizada
* Separação de romaneio

Interfaz:

☑ Realizado

---

## Tipo 2 — Lista

Ejemplo:

Recebimento de mercadorias

* Farinha
* Sal
* Gordura
* Pizza

Interfaz:

Selección múltiple.

---

## Tipo 3 — Temperatura

Ejemplo:

Câmara Principal

-18°C

Validaciones:

* Numérico
* Negativos permitidos
* Unidad °C

---

## Tipo 4 — Cantidad

Ejemplo:

Rolo 2kg

31 caixas montadas

Interfaz:

Producto seleccionado

*

Cantidad numérica

---

# 11. Categorías Dinámicas

Cualquier usuario autorizado podrá crear nuevas categorías.

Ejemplos:

* Organização
* Recebimento de mercadorias
* Temperaturas
* Lotes
* Abastecimento
* Produção

---

# 12. Catálogo Maestro de Montagem de Caixas

Administrado por el Administrador.

Los nombres deben mantenerse en portugués.

---

## Caixas Assaí Grandes

* Nhoque 1kg
* Quadrada
* Rolo 500g
* Rolo 1kg
* Rolo 2kg
* Disco 400g
* Disco 500g

---

## Caixas Pequenas

* Nhoque 400g
* Nhoque 1kg
* Quadrada
* Rolo 500g
* Rolo 1kg
* Rolo 2kg
* Disco 400g
* Disco 500g

---

# 13. Recebimento de Mercadorias

Catálogo dinámico.

Ejemplos:

* Farinha
* Sal
* Gordura
* Pizza
* Canudo
* Salgadinho

Se podrán agregar nuevos productos.

---

# 14. Fotos

Opcionales.

Permitidos:

* JPG
* JPEG
* PNG
* WEBP

Tamaño máximo:

5 MB

Validaciones:

* Solo imágenes.
* Rechazar cualquier otro formato.

---

# 15. Historial de Relatórios

Guardar todos los relatórios.

Filtros:

* Hoy
* Ayer
* Últimos 7 días
* Últimos 30 días
* Personalizado

---

# 16. Reglas de Edición

Puede editarse:

* Día actual
* Día anterior

Más antiguos:

Solo lectura.

---

# 17. Formato de Exportación del Relatório

Ejemplo:

📋 Relatório da Tarde — 11/06/2026

━━━━━━━━━━━━━━

✅ ORGANIZAÇÃO

• Pátio organizado
• Câmara principal organizada
• Separação de romaneio
• Abastecimento realizado

━━━━━━━━━━━━━━

📦 MONTAGEM DE CAIXAS

🔸 Caixas Assaí Grandes

• Nhoque 1kg
• Quadrada
• Rolo 1kg

🔸 Caixas Pequenas

• Nhoque 400g
• Disco 500g

━━━━━━━━━━━━━━

🌡️ TEMPERATURAS

• Câmara Principal: -18°C
• Câmara Secundária: -20°C

━━━━━━━━━━━━━━

📥 RECEBIMENTO

• Farinha
• Sal
• Pizza

━━━━━━━━━━━━━━

🎯 RESULTADO

✅ Operação concluída com sucesso

---

# 18. Módulo Horário de Carregamento

## Objetivo

Organizar el cargamento del día siguiente.

---

# 19. Tipos de Motoristas

## Fleteros

Características:

* Externos.
* Nombre obligatorio.
* Placa opcional.
* Sujetos a regla de capacidad.

## Casa

Características:

* Vehículos de la empresa.
* Placa obligatoria.
* No cuentan para la regla de capacidad.

---

# 20. Regla de Capacidad

Límite indicativo:

3 fleteros por horario.

La regla es orientativa, no un bloqueo. El sistema **no rechaza** al cuarto fletero: lo acepta y
marca el exceso para que logística lo vea y decida. Responde a la operación real de la empresa,
no a una validación del software.

Ejemplo:

05:00

* Pedro
* José
* Marcos

Se agrega:

* Antônio

Resultado:

Se acepta.

La pantalla muestra 4/3 y una advertencia de que se superó el límite indicativo.

---

# 21. Horarios Permitidos

* 04:00
* 04:30
* 05:00
* 05:30
* 06:00
* 06:30
* 07:00

Configurables en futuras versiones.

---

# 22. Exportación WhatsApp del Horario

Orden obligatorio:

Ascendente por horario.

Formato:

🚛 HORÁRIO DE CARREGAMENTO

📅 Sexta-feira — 12/06/2026

| Horário | Nome           | Placa |
| ------- | -------------- | ----- |
| 04:00   | Juverson       | RKM   |
| 04:00   | Luís Guilherme | —     |
| 04:00   | Raffael        | RXK   |
| 04:30   | André          | —     |
| 05:00   | Leonardo       | —     |
| 05:00   | Roberto        | —     |
| 05:00   | Matheus        | —     |
| 06:00   | Luís Eloi      | —     |

---

# 23. Exportaciones

## Relatórios

* Copiar WhatsApp
* TXT
* PDF

## Horarios

* Copiar WhatsApp
* TXT
* PDF

---

# 24. Dashboard

Tarjetas:

📋 Relatórios de hoy

🚛 Cargamentos de mañana

👥 Usuarios

📈 Resumen operativo

---

# 25. Base de Datos

Tablas:

users

roles

report_categories

report_tasks

report_products

report_templates

reports

report_items

report_temperatures

report_quantities

report_photos

drivers

vehicles

loading_schedules

audit_logs

settings

---

# 26. Auditoría

Registrar:

* Usuario
* Acción
* Fecha
* Hora
* IP

Eventos:

* Login
* Creación
* Edición
* Eliminación

---

# 27. Requisitos No Funcionales

* Responsive.
* Mobile First.
* Tiempo de respuesta menor a 1 segundo.
* Compatible con Chrome.
* Compatible con Edge.
* Compatible con Safari.
* Compatible con Android.
* Compatible con iPhone.
* Bajo consumo de recursos.
* Preparado para dominio futuro.

---

# 28. MVP Inicial

Fase 1 obligatoria:

* Login.
* Roles.
* Dashboard.
* Relatórios.
* Generador inteligente.
* Catálogo maestro de caixas.
* Temperaturas.
* Recebimento.
* Fotos.
* Horário de carregamento.
* Regla indicativa de 3 fleteros (no bloquea).
* Copiar para WhatsApp.
* Historial.
* SQLite.
* Docker Compose.

---

# 29. Objetivo Final

Convertir Trindade Massas Operações en la herramienta oficial de control operativo de la empresa para relatórios y programación de cargamentos, centralizando toda la información diaria en una única plataforma web rápida, moderna y optimizada para dispositivos móviles.
