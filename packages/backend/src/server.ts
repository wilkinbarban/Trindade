import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import Fastify from 'fastify';
import cors from '@fastify/cors';
import multipart from '@fastify/multipart';
import { loadRuntimeConfig } from './runtime-config.js';
import { openDatabase } from './db/index.js';
import { schemaNoticeForStartup } from './db/schema-notice.js';
import { createAuthenticate } from './modules/auth/auth.middleware.js';
import {
  createSessionStoreCheck,
  purgeDeadSessions,
  sessionStoreExists,
} from './modules/auth/auth.sessions.service.js';
import { cleanupExpiredPhotos } from './modules/reports/reports.lifecycle.service.js';
import { registerApiRoutes } from './routes.js';

const config = loadRuntimeConfig();
const db = openDatabase(config.databasePath);

// Photo storage directory
const DATA_DIR = join(import.meta.dirname, '../data');
const PHOTOS_DIR = process.env.PHOTOS_DIR || join(DATA_DIR, 'photos');
const PUBLIC_APP_URL = process.env.PUBLIC_APP_URL || process.env.APP_URL || `http://localhost:${process.env.PORT || '3000'}`;
mkdirSync(PHOTOS_DIR, { recursive: true });

const server = Fastify({
  logger: {
    level: process.env.LOG_LEVEL || 'info',
  },
});

// Report the schema revision the database was found in, and nothing else: startup never
// migrates, stamps, or refuses. This is a log notice for operators (`db:status` answers
// the same question on demand, and the only sanctioned write path is `db:migrate`). It is
// emitted here because the logger does not exist yet where the database is opened above.
const schemaNotice = schemaNoticeForStartup(db);
if (schemaNotice.level === 'warn') {
  server.log.warn(schemaNotice.payload, schemaNotice.message);
} else {
  server.log.info(schemaNotice.payload, schemaNotice.message);
}

// The session endpoints need one table that startup deliberately never creates. Check for it once
// and tell the operator what to do, instead of letting the first sign-in fail with a driver error
// that names an internal table.
const sessionStoreAvailable = sessionStoreExists(db);
// Re-checked on the paths that need it, so a migration performed while this process runs is
// picked up without a restart.
const sessionStoreCheck = createSessionStoreCheck(db, sessionStoreAvailable);
if (!sessionStoreAvailable) {
  server.log.warn(
    'auth_sessions is missing: sign-in, refresh and logout will answer 503 until an operator runs db:migrate',
  );
}

// CORS — allow Vite dev server and any origin in development
await server.register(cors, {
  origin: true,
});

// Multipart — 5MB file size limit
await server.register(multipart, {
  limits: {
    fileSize: 5 * 1024 * 1024, // 5 MB
  },
});

// Decorate fastify instance with the database connection
server.decorate('db', db);

// Decorate with JWT authenticate (must be at top level, not inside a plugin)
server.decorate('authenticate', createAuthenticate(config.jwtSecret));

// Every route the API serves, registered from one place so the contract test enumerates the same
// set this process does.
await registerApiRoutes(server, {
  jwtSecret: config.jwtSecret,
  refreshTokenTtlDays: config.refreshTokenTtlDays,
  sessionStoreAvailable: sessionStoreCheck,
  photosDir: PHOTOS_DIR,
  publicBaseUrl: PUBLIC_APP_URL,
});



// Both cleanups below delete dead data at startup and leave the schema untouched. Each keeps its
// own window because photos and sessions are pruned for different reasons, but neither is a magic
// number buried in a call.
const PHOTO_RETENTION_DAYS = 30;
const SESSION_RETENTION_DAYS = 30;

function runPhotoRetentionCleanup() {
  try {
    const result = cleanupExpiredPhotos(db, PHOTOS_DIR, PHOTO_RETENTION_DAYS);
    if (result.deleted > 0 || result.errors > 0) {
      server.log.info({ result }, 'Report photo retention cleanup finished');
    }
  } catch (err) {
    server.log.warn({ err }, 'Report photo retention cleanup failed');
  }
}

runPhotoRetentionCleanup();
const photoRetentionInterval = setInterval(runPhotoRetentionCleanup, 24 * 60 * 60 * 1000);
photoRetentionInterval.unref?.();

// Same shape as the photo retention cleanup above. Guarded by the session-store check because the
// table only exists once an operator has migrated, and startup must never create it.
function runSessionRetentionCleanup() {
  if (!sessionStoreCheck()) return;
  try {
    const deleted = purgeDeadSessions(db, SESSION_RETENTION_DAYS);
    if (deleted > 0) {
      server.log.info({ deleted }, 'Session retention cleanup finished');
    }
  } catch (err) {
    server.log.warn({ err }, 'Session retention cleanup failed');
  }
}

runSessionRetentionCleanup();
const sessionRetentionInterval = setInterval(runSessionRetentionCleanup, 24 * 60 * 60 * 1000);
sessionRetentionInterval.unref?.();

// Start server
const port = parseInt(process.env.PORT || '3000', 10);
const host = process.env.HOST || '0.0.0.0';

try {
  await server.listen({ port, host });
} catch (err) {
  server.log.error(err);
  process.exit(1);
}

export default server;
