import type { Page } from '@playwright/test';

const API_BASE = 'http://localhost:3099/api';

interface LoginResponse {
  token: string;
  user: { id: number; username: string; role: string };
}

type E2EIdentity = { username: string; password: string };
interface E2EFixtures {
  admin: E2EIdentity;
  worker: E2EIdentity;
}
export async function getE2EIdentities(page: Page): Promise<E2EFixtures> {
  const response = await page.request.get('http://localhost:3099/__test_fixtures');
  if (!response.ok()) throw new Error(`Fixture lookup failed: ${response.statusText()}`);
  return response.json() as Promise<E2EFixtures>;
}

/**
 * Programmatically authenticate by calling the backend API directly,
 * then store the JWT in localStorage via addInitScript before the app loads.
 *
 * Uses addInitScript to inject credentials before any page JS runs,
 * so AuthContext.restoreSession picks them up on mount.
 */
export async function loginViaApi(page: Page, identity?: E2EIdentity): Promise<void> {
  const selectedIdentity = identity ?? (await getE2EIdentities(page)).admin;
  const response = await page.request.post(`${API_BASE}/auth/login`, {
    data: selectedIdentity,
  });

  if (!response.ok()) {
    const body = await response.json();
    throw new Error(`Login failed: ${body.error || response.statusText()}`);
  }

  const data: LoginResponse = await response.json();

  // Use addInitScript to inject credentials BEFORE React mounts.
  // This avoids the about:blank localStorage restriction.
  await page.addInitScript(
    ({ token, user }) => {
      localStorage.setItem('auth_token', token);
      localStorage.setItem('auth_user', JSON.stringify(user));
    },
    { token: data.token, user: data.user }
  );
}

/**
 * Visit a protected route after logging in via API.
 * Navigates straight to the route; AuthContext picks up the injected credentials.
 */
export async function goToProtected(page: Page, path: string, identity?: E2EIdentity): Promise<void> {
  await loginViaApi(page, identity);
  await page.goto(path);
  // Wait for the app shell to render (not the login redirect)
  await page.waitForSelector('text=Trindade Massas', { timeout: 15000 });
}
