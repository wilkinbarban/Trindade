# Consulta de Movimientos ERP («Espelho do ERP») y Sincronización en Trindade Web

Permite consultar e importar en el módulo de Lanzamiento de Masas (`/massas-launch`) los movimientos registrados en el ERP de la empresa (Aero32) para la fecha seleccionada. Resuelve la visualización de lo lanzado por el usuario autenticado (desde este u otros dispositivos como la app Android) y lo lanzado por otros operarios de la planta, todo en modo lectura estricto para los datos del ERP, manteniendo la edición de los lanzamientos propios locales de Trindade.

## Specs

- **S1 (Backend Schema & Service)**:
  - Crear tabla y servicio `aero_movements` en SQLite (`packages/backend/src/modules/integration/`):
    Campos: `id`, `date` (YYYY-MM-DD), `ent_lan` (asiento ERP), `product_code` (6 dígitos), `product_desc`, `quantity` (float unidades), `entry_date`, `operator_name` (`ENT_USU`), `movement_type` (`ENTSAI`: 'E'/'S'), `synced_at`.
  - Endpoints autenticados:
    - `GET /api/integration/aero/movements?date=YYYY-MM-DD`: devuelve la lista de asientos del ERP para el día.
    - `POST /api/integration/aero/movements`: guarda o actualiza el snapshot del día traído por el puente.

- **S2 (Core & Builders en Frontend)**:
  - En `packages/frontend/src/lib/aero-integration.ts`:
    - Ajustar `buildMovimentoItemPayload`: si `userName` es `null`, omitido o `'all'`, enviar `NOMUSU: null` para que el WebService devuelva los asientos de **todos los operarios** del día.
    - Añadir tipos e interfaces: `AeroErpMovementItem`, `AeroMovementsResponse`.
    - Función de conciliación: cotejar lanzamientos locales de Trindade contra los asientos `ENT_LAN` del ERP por `productCode` y `quantity`.

- **S3 (Cableado de Disparo y Callback en `MassasLaunchPage.tsx`)**:
  - En la barra de navegación / fecha de la página de lanzamiento:
    - Botón táctil móvil (mínimo 44px): **«Sincronizar do ERP»** con ícono de refresco / nube.
    - Al tocarlo, despacha el sobre del puente `Aero32 Bridge` apuntando a `/movimentoitem` con `DTINI: date`, `DTFIM: date`, `NOMUSU: null`, `ENTSAI: 'E'`, y `callback` con `aero_action=movimentos`.
  - En el `useEffect` de retorno:
    - Cuando `parsed.kind === 'bridge'` y `parsed.action === 'movimentos'`:
      - Deserializa los datos del ERP.
      - Guarda el snapshot en el backend (`POST /api/integration/aero/movements`).
      - Actualiza el estado local y muestra aviso de éxito con el total de movimientos sincronizados.

- **S4 (UI Mobile-First: Pestañas en `MassasCollectiveTable.tsx`)**:
  - **Cuidado estricto de la vista de celular (iPhone)**:
    - Pestañas táctiles de al menos 44px de alto (`min-h-[44px]`), sin solapamientos ni desbordes horizontales en pantallas de 375px/390px.
    - Pestaña 1: **«Lançamentos Trindade»** (la actual):
      - Tus masas: editables y borrables.
      - Masas de otros operarios: modo lectura.
    - Pestaña 2: **«Espelho do ERP (Aero32)»** *(Modo Lectura Estricto)*:
      - Banner de cabecera: *"Registros oficiais gravados no ERP Aero32 da empresa (somente leitura)."*
      - Selector de filtro rápido: «Todos os operários» / «Apenas meus lançamentos».
      - Tarjetas o filas adaptadas a móvil:
        - Badge `#ENT_LAN` (número de asiento).
        - Producto: código 6 dígitos + nombre de la masa.
        - Cantidad: unidades humanas (ej. `90 un`).
        - Operario: Chip con `ENT_USU` (badge diferenciado: azul «Você» si coincide con el usuario conectado, o gris con el nombre del operario externo).
        - Badge verde `✓ Conciliado` si coincide con un registro local de Trindade.
        - Botón táctil de 44px **«Importar para Trindade»** si el asiento no existe en la base local y se desea sumar a la grilla de Trindade.

- **S5 (Internacionalización - Locales)**:
  - Actualizar `pt-BR.json` y `es.json` con todos los textos de pestañas, badges, alertas y botones de la vista ERP.

- **S6 (Verificación, Tests Automatizados y Despliegue)**:
  - Pruebas unitarias de las rutas de backend (`aero.routes.test.ts`).
  - Pruebas unitarias de parsing, builders y componentes de frontend.
  - `npm run typecheck` limpio en ambos paquetes.
  - Suites completas (`npm test`) pasando al 100%.
  - Commit directo en `main` bajo Conventional Commits, sin `Co-Authored-By` ni atribución de IA.
  - `git push origin main`.
  - Reconstrucción de contenedores con `docker compose up -d --build` y verificación de salud en `https://trindademasas.duckdns.org`.

## Tasks

- T1 (S1, S2): Backend — endpoints y persistencia de movimientos en `packages/backend/src/modules/integration/` con tests unitarios. Core frontend en `packages/frontend/src/lib/aero-integration.ts` con soporte `NOMUSU: null` y tipado.
- T2 (S3): Frontend — cableado del disparador de movimientos y procesamiento del callback en `MassasLaunchPage.tsx`.
- T3 (S4, S5): UI Móvil — Pestañas en `MassasCollectiveTable.tsx` («Lançamentos Trindade» vs «Espelho do ERP»), tarjetas adaptadas a iPhone, badges `ENT_LAN`, operarios y conciliación. Textos en `pt-BR.json` y `es.json`.
- T4 (S6): Verificación completa, typecheck, suites completas, commit en `main`, push, rebuild de Docker y validación de salud en producción.

## Log

L1 — pedido del usuario, literal:

  Colega, Wilkin aprobó el reporte de arquitectura y encomendó la implementación bajo ODD y TDD estricto
  de la nueva funcionalidad:
  «Consulta e Ingesta de Movimientos del ERP (Espelho do ERP) en Lanzamiento de Masas»

  Tenés el documento de especificación completo y detallado listo en:
  `odd/tasks/consulta-movimientos-erp-espejo.md` (Specs S1–S6, Tasks T1–T4).

  Puntos clave y restricciones estrictas del usuario:
  1. Vista de Celular (iPhone) cuidada al milímetro:
     - Todo se operará desde Safari en un iPhone.
     - Piso táctil mínimo de 44px en botones y pestañas (min-h-[44px]).
     - Cero desbordes horizontales (overflow) en pantallas de 375px/390px.
     - Pestañas en MassasCollectiveTable.tsx: «Lançamentos Trindade» vs «Espelho do ERP (Aero32)».
  2. Matriz de permisos y edición:
     - En Trindade: el usuario edita/borra lo suyo; otros operarios en modo lectura.
     - En el ERP: 100% MODO LECTURA para todos los registros (asientos inmutables ENT_LAN). Mostrar chip
       de operario (ENT_USU: azul para el usuario actual, gris para otros), cantidad en unidades y badge
       «✓ Conciliado» si coincide con Trindade. Botón táctil opcional «Importar para Trindade» para
       lanzamientos nacidos fuera de la web.
  3. El atajo unificado ya instalado (Aero32 Bridge):
     - No se toca el atajo del iPhone. El atajo ya despacha /movimentoitem y vuelve con
       aero_action=movimentos.
     - Al consultar con NOMUSU: null, el ERP devuelve los lanzamientos de TODOS los operarios del día.
  4. Persistencia en Backend:
     - Los datos traídos por el bridge se guardan en el backend de Trindade (endpoints GET/POST en
       /api/integration/aero/movements) para que cualquier dispositivo en la planta vea el snapshot del
       día sin tener que re-ejecutar el atajo.
  5. Disciplina de entrega:
     - TDD estricto con pruebas unitarias en backend y frontend.
     - Trunk-based directo en main, sin ramas, sin Co-Authored-By ni atribución de IA.
     - Push a origin/main, docker compose up -d --build y verificación de salud en
       https://trindademasas.duckdns.org.

  Procedé inmediatamente con T1 y T2. Te superviso con pausas de 5 minutos.
