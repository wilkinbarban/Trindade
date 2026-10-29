import { expect, type Page } from '@playwright/test';
import { getE2EIdentities, loginViaApi } from './auth-helpers';

const API = 'http://localhost:3099/api';

/**
 * The one account the company integration belongs to.
 *
 * `isAeroAllowed` asks for `wilkin`, role Administrador: the panel and the sync switch do not exist for
 * anyone else. Every spec that needs them shares this account, because a second spec creating `wilkin` with
 * another password would lock the first one out -- and that is a test collision, not a product defect.
 */
export const AERO_OWNER = { username: 'wilkin', password: 'wilkin-result-dialogs-2026' } as const;

/** Creates the owner if this run did not, through the fixture admin. A 400 means it already existed. */
export async function ensureAeroOwner(page: Page): Promise<void> {
  const identities = await getE2EIdentities(page);
  const login = await page.request.post(`${API}/auth/login`, { data: identities.admin });
  const token = (await login.json()).token as string;
  const created = await page.request.post(`${API}/admin/users`, {
    headers: { authorization: `Bearer ${token}` },
    data: { ...AERO_OWNER, display_name: 'Wilkin', role_id: 1 },
  });
  expect([201, 400]).toContain(created.status());
}

/** The owner, created if needed and logged in for the next page. */
export async function loginAsAeroOwner(page: Page): Promise<void> {
  await ensureAeroOwner(page);
  await loginViaApi(page, AERO_OWNER);
}
