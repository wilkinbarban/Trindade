import { test, expect } from '@playwright/test';

test('setup status requires operator provisioning without exposing public credentials or POST', async ({ page }) => {
  // React 19 StrictMode double-mounts the setup gate in dev, so the initial screen may
  // issue more than one status GET. Drive the response from a mutable flag instead of a
  // per-call counter so every mount request observes setupRequired=true.
  let setupRequired = true;
  let statusGets = 0;
  const setupPosts: string[] = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/auth/setup')) setupPosts.push(request.url());
  });
  await page.route('**/auth/setup/status', async route => {
    expect(route.request().method()).toBe('GET');
    statusGets++;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupRequired }) });
  });

  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Configuração inicial' })).toBeVisible();
  await expect(page.getByText(/O primeiro administrador deve ser criado pelo operador, fora deste site/)).toBeVisible();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
  expect(setupPosts).toEqual([]);
  expect(statusGets).toBeGreaterThanOrEqual(1);

  const beforeVerify = statusGets;
  setupRequired = false;
  await page.getByRole('button', { name: 'Verificar configuração' }).click();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  // The verify action must issue its own fresh GET after the setup flag flips false.
  expect(statusGets - beforeVerify).toBeGreaterThanOrEqual(1);
  expect(setupPosts).toEqual([]);
});

test('status network failure offers retry and recovers on a fresh GET', async ({ page }) => {
  // StrictMode double-mounts the gate, so fail every request while the network is down
  // rather than only the first one; otherwise the second mount would silently recover.
  let unhealthy = true;
  let statusGets = 0;
  await page.route('**/auth/setup/status', async route => {
    expect(route.request().method()).toBe('GET');
    statusGets++;
    if (unhealthy) {
      await route.abort('failed');
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupRequired: false }) });
    }
  });

  await page.goto('/login');
  await expect(page.getByRole('alert')).toContainText('Não foi possível verificar a configuração inicial.');
  await expect(page.getByRole('button', { name: 'Tentar novamente' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toHaveCount(0);
  expect(statusGets).toBeGreaterThanOrEqual(1);

  const beforeRetry = statusGets;
  unhealthy = false;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  // Retry must perform a fresh GET after the network recovers.
  expect(statusGets - beforeRetry).toBeGreaterThanOrEqual(1);
  await expect(page.getByRole('alert')).toHaveCount(0);
});
