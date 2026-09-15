import { test, expect } from '@playwright/test';
import { getE2EIdentities, goToProtected } from './auth-helpers';

test.describe('Limited Worker Admin Panel', () => {
  test('worker sees limited admin panel tabs and no destructive catalog actions', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await goToProtected(page, '/admin', identities.worker);

    await expect(page.getByRole('heading', { name: /Painel do Trabalhador|Panel del Trabajador/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Tarefas|Tareas/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Motoristas|Conductores/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Perfil' })).toBeVisible();

    await expect(page.getByRole('button', { name: /Categorias|Categorías/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Funcionários|Trabajadores/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Desativar|Desactivar|Ativar|Activar/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Excluir|Eliminar/ })).toHaveCount(0);
  });

  test('worker can create a shared task and fletero from the limited panel', async ({ page }) => {
    const identities = await getE2EIdentities(page);
    await goToProtected(page, '/admin', identities.worker);

    await page.getByRole('button', { name: '+ Novo' }).click();
    await page.locator('select').selectOption({ index: 1 });
    await page.locator('input').first().fill(`Tarefa E2E ${Date.now()}`);
    await page.getByRole('button', { name: /Criar|Crear/ }).click();
    await expect(page.getByText(/Tarefa E2E/)).toBeVisible();

    await page.getByRole('button', { name: /Motoristas|Conductores/ }).click();
    await page.getByRole('button', { name: '+ Novo' }).click();
    await page.locator('input').first().fill(`Fletero E2E ${Date.now()}`);
    await expect(page.locator('select option[value="casa"]')).toHaveCount(0);
    await page.getByRole('button', { name: /Criar|Crear/ }).click();
    await expect(page.getByText(/Fletero E2E/)).toBeVisible();
  });
});

test('worker-created task and fletero are available through shared builder/schedule APIs', async ({ page }) => {
  const identities = await getE2EIdentities(page);
  const loginRes = await page.request.post('http://localhost:3099/api/auth/login', {
    data: identities.worker,
  });
  expect(loginRes.ok()).toBeTruthy();
  const { token } = await loginRes.json();
  const auth = { authorization: `Bearer ${token}` };
  const suffix = Date.now();

  const taskRes = await page.request.post('http://localhost:3099/api/admin/tasks', {
    headers: auth,
    data: { category_id: 1, name_pt: `Tarefa Compartilhada ${suffix}`, name_es: `Tarea Compartida ${suffix}` },
  });
  expect(taskRes.status()).toBe(201);

  const driverRes = await page.request.post('http://localhost:3099/api/admin/drivers', {
    headers: auth,
    data: { name: `Fletero Compartilhado ${suffix}`, driver_type: 'fletero' },
  });
  expect(driverRes.status()).toBe(201);

  const reportCategories = await page.request.get('http://localhost:3099/api/reports/categories', { headers: auth });
  expect(reportCategories.ok()).toBeTruthy();
  const categoryBody = await reportCategories.json();
  expect(JSON.stringify(categoryBody)).toContain(`Tarefa Compartilhada ${suffix}`);

  const loadingDrivers = await page.request.get('http://localhost:3099/api/loading/drivers', { headers: auth });
  expect(loadingDrivers.ok()).toBeTruthy();
  const driverBody = await loadingDrivers.json();
  expect(JSON.stringify(driverBody)).toContain(`Fletero Compartilhado ${suffix}`);
});
