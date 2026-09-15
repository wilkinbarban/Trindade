/**
 * E2E Test Server — standalone Fastify server backed by an in-memory SQLite
 * database preloaded with schema + seed data.
 *
 * Used by Playwright E2E tests. The test helper logic is reused; this file
 * only adds `app.listen()` so the browser can reach the API.
 *
 * Usage:  npx tsx src/e2e-server.ts   (default port 3001)
 *         PORT=3001 npx tsx src/e2e-server.ts
 */
import { buildTestApp, type TestFixtures } from './test-helper.js';

export type E2EFixtures = Pick<TestFixtures, 'admin' | 'worker'>;

const port = parseInt(process.env.PORT || '3099', 10);
const host = process.env.HOST || '0.0.0.0';

const { app } = await buildTestApp();

try {
  await app.listen({ port, host });
  console.log(`E2E server listening on http://${host}:${port}`);
} catch (err) {
  console.error('Failed to start E2E server:', err);
  process.exit(1);
}
