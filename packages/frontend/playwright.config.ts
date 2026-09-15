import { defineConfig, devices } from '@playwright/test';

const PORT = 3099;
const FRONTEND_PORT = 5173;

export default defineConfig({
  testDir: './src/__e2e__',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: [
    {
      command: `npx tsx ../backend/src/e2e-server.ts`,
      port: PORT,
      reuseExistingServer: !process.env.CI,
      timeout: 15_000,
      env: { PORT: String(PORT), HOST: '127.0.0.1' },
      cwd: '../backend',
    },
    {
      command: `npx vite --port ${FRONTEND_PORT} --host 127.0.0.1`,
      port: FRONTEND_PORT,
      reuseExistingServer: !process.env.CI,
      timeout: 15_000,
      env: { VITE_API_TARGET: `http://localhost:${PORT}`, VITE_APP_BASE: '/' },
    },
  ],
});
