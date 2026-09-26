import assert from 'node:assert/strict';
import { test } from 'node:test';

import { AuthAdmissionLimiter } from './modules/auth/auth.admission-limiter.js';
import { buildTestApp } from './test-helper.js';

test('an E2E-only admission budget permits repeated fixture logins without weakening the default', async () => {
  // Keep the fixture budget finite and instance-scoped; no shared limiter state is reset.
  const e2eLimiter = new AuthAdmissionLimiter({ maxUsernameLogin: 8, maxGlobalLogin: 16 });
  const buildWithAdmission = buildTestApp as (options: {
    authAdmissionLimiter: AuthAdmissionLimiter;
  }) => ReturnType<typeof buildTestApp>;
  const { app: e2eApp, db: e2eDb, fixtures: e2eFixtures } = await buildWithAdmission({
    authAdmissionLimiter: e2eLimiter,
  });
  const { app: defaultApp, db: defaultDb, fixtures: defaultFixtures } = await buildTestApp();

  try {
    const login = (app: typeof e2eApp, username: string, password: string) => app.inject({
      method: 'POST',
      url: '/api/auth/login',
      payload: { username, password },
    });

    for (let attempt = 1; attempt <= 6; attempt++) {
      const e2e = await login(e2eApp, e2eFixtures.worker.username, e2eFixtures.worker.password);
      assert.equal(e2e.statusCode, 200, `E2E login ${attempt} must be admitted`);
      const ordinary = await login(defaultApp, defaultFixtures.worker.username, defaultFixtures.worker.password);
      assert.equal(ordinary.statusCode, attempt === 6 ? 429 : 200,
        `default login ${attempt} must retain the five-attempt username limit`);
    }
  } finally {
    await e2eApp.close();
    await defaultApp.close();
    e2eDb.close();
    defaultDb.close();
  }
});
