import { test, expect } from '@playwright/test';
import { goToProtected } from './auth-helpers';

test('admin configures and preserves temperature readings on a task', async ({ page }) => {
  await goToProtected(page, '/admin');
  const token = await page.evaluate(() => localStorage.getItem('auth_token'));
  const categoriesResponse = await page.request.get('http://localhost:3099/api/admin/categories', {
    headers: { Authorization: `Bearer ${token}` },
  });
  const categories = (await categoriesResponse.json()).categories as { id: number; category_type: string }[];
  const temperatureCategory = categories.find((category) => category.category_type === 'temperature');
  const checkCategory = categories.find((category) => category.category_type === 'check');
  expect(temperatureCategory).toBeTruthy();
  expect(checkCategory).toBeTruthy();

  await page.getByRole('button', { name: /Tarefas|Tareas/ }).click();
  await page.getByRole('button', { name: '+ Novo' }).click();
  const categorySelect = page.locator('form select').first();

  await categorySelect.selectOption(String(checkCategory!.id));
  await expect(page.getByTestId('temperature-readings-select')).toHaveCount(0);

  await categorySelect.selectOption(String(temperatureCategory!.id));
  const readings = page.getByTestId('temperature-readings-select');
  await expect(readings).toHaveValue('1');
  await readings.selectOption('3');

  const taskName = `Câmara UI ${Date.now()}`;
  await page.locator('form input').first().fill(taskName);
  await page.getByRole('button', { name: /Criar|Crear/ }).click();
  const row = page.getByRole('row', { name: new RegExp(taskName) });
  await expect(row).toContainText('3');

  await row.getByRole('button', { name: /Editar|Editar/ }).click();
  await expect(page.getByTestId('temperature-readings-select')).toHaveValue('3');
});
