# Manual de Despliegue y Rollback en Producción — Trindade Massas

Este documento define el procedimiento operativo oficial para desplegar actualizaciones de Trindade Massas en producción garantizando consistencia verificable en SQLite, cero pérdida accidental de datos y capacidad de rollback inmediato.

---

## 1. Invariantes de la Arquitectura

- **Topología**: La producción opera detrás de un proxy inverso compartido. El contenedor web se une a la red externa de Docker `portafolio_default`, y el Nginx de ese proyecto lo alcanza **por nombre de contenedor** (`proxy_pass http://trindade-web-1:80`).
- **Servicios**: Definidos en `docker-compose.yml` (`trindade-api-1` para la API Fastify en Node 24; `trindade-web-1` para la SPA en Nginx).
- **Volumen de Datos**: Volumen nombrado `trindade_sqlite_data` montado en `/app/packages/backend/data`. Declarado `external: true` con nombre explícito para evitar que Docker cree bases de datos vacías por error o borre datos con `down -v`.
  - Base de datos: `/app/packages/backend/data/trindade.db` (SQLite en modo WAL).
  - Fotos de reportes: `/app/packages/backend/data/photos`.
- **Compuerta de Inmutabilidad**: El arranque del servidor **nunca** ejecuta migraciones automáticas ni modifica el esquema. Si la base de datos existe, el proceso la clasifica e informa su estado (`database schema notice`) sin alterarla.
- **Compuerta de Recuperación**: Antes de cualquier cambio en producción, se debe capturar una instantánea consistente en línea mediante las herramientas oficiales (`make db-backup`).

---

## 2. Regla de Orden de Despliegue (Revisión 3)

La Revisión 3 representa una transición controlada con invalidación de sesiones de seguridad:
1. Detener la API antes de capturar la copia de seguridad consistente final.
2. Aplicar la migración de esquema de forma aislada (`make db-migrate`).
3. Validar el estado del esquema con `make db-status` antes de encender la nueva versión del servicio.
4. **Regla de oro**: Nunca ejecutar código de la versión 2 sobre datos de la versión 3.

---

## 3. Procedimiento de Despliegue Paso a Paso

### Paso 1: Generar Copia de Seguridad Preventiva
```bash
make db-backup BACKUP_DIR=./backups
```

### Paso 2: Construir la Imagen de Producción Actualizada
```bash
docker build -f docker/Dockerfile.api -t trindade-api:v0.3.0 .
```

### Paso 3: Aplicar Migraciones de Base de Datos
```bash
# Ejecutar migración utilizando el contenedor con el código nuevo
docker run --rm \
  -v trindade_sqlite_data:/app/packages/backend/data \
  trindade-api:v0.3.0 \
  node packages/backend/dist/db/migrate.js /app/packages/backend/data/trindade.db
```

### Paso 4: Validar Estado del Esquema
```bash
docker run --rm \
  -v trindade_sqlite_data:/app/packages/backend/data \
  trindade-api:v0.3.0 \
  node packages/backend/dist/db/status.js /app/packages/backend/data/trindade.db
# Debe responder: verdict: current — revision 3; 15 tables present
```

### Paso 5: Desplegar el Contenedor de la API
```bash
docker compose up -d api
```

### Paso 6: Verificación de Salud
```bash
curl -f https://trindademasas.duckdns.org/api/health
# Debe retornar HTTP 200 con status "ok"
```

---

## 4. Procedimiento de Rollback (Vuelta Atrás Inmediata)

Si la nueva versión presenta fallos críticos en producción:

1. **Detener el contenedor de la API:**
   ```bash
   docker stop trindade-api-1
   ```

2. **Restaurar la copia de seguridad previa:**
   ```bash
   make db-restore BACKUP_DIR=./backups/trindade.db.backup-pre-rev3-20260929 CONFIRM=true
   ```

3. **Volver a levantar la versión anterior de la imagen:**
   ```bash
   docker compose up -d api
   ```

4. **Verificar estado:**
   ```bash
   curl -f https://trindademasas.duckdns.org/api/health
   ```
