import { test, expect } from '@playwright/test';

test('setup status requires operator provisioning without exposing public credentials or POST', async ({ page }) => {
  let statusGets = 0;
  let verificationRequested = false;
  const setupPosts: string[] = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/auth/setup')) setupPosts.push(request.url());
  });
  await page.route('**/auth/setup/status', async route => {
    expect(route.request().method()).toBe('GET');
    statusGets++;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ setupRequired: !verificationRequested }) });
  });

  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Configuração inicial' })).toBeVisible();
  await expect(page.getByText(/O primeiro administrador deve ser criado pelo operador, fora deste site/)).toBeVisible();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
  expect(setupPosts).toEqual([]);
  expect(statusGets).toBeGreaterThanOrEqual(1);

  const initialStatusGets = statusGets;
  verificationRequested = true;
  await page.getByRole('button', { name: 'Verificar configuração' }).click();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  expect(statusGets).toBeGreaterThan(initialStatusGets);
  expect(setupPosts).toEqual([]);
});

test('status network failure offers retry and recovers on a fresh GET', async ({ page }) => {
  let statusGets = 0;
  let retryRequested = false;
  await page.route('**/auth/setup/status', async route => {
    expect(route.request().method()).toBe('GET');
    statusGets++;
    if (!retryRequested) {
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
  const initialStatusGets = statusGets;
  retryRequested = true;
  await page.getByRole('button', { name: 'Tentar novamente' }).click();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  expect(statusGets).toBeGreaterThan(initialStatusGets);
  await expect(page.getByRole('alert')).toHaveCount(0);
});
