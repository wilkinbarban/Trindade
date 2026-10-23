import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const authContextPath = join(import.meta.dirname, 'AuthContext.tsx');
const authContextSource = readFileSync(authContextPath, 'utf8');

test('AuthContext persists auth_refresh_token in localStorage at login', () => {
  assert.match(
    authContextSource,
    /localStorage\.setItem\(\s*['"]auth_refresh_token['"]/,
    'AuthContext must persist auth_refresh_token in localStorage during login'
  );
});

test('AuthContext clears auth_refresh_token alongside access token on logout', () => {
  assert.match(
    authContextSource,
    /clearStoredSession\(\)/,
    'AuthContext must invoke clearStoredSession() on logout'
  );
});

test('AuthContext updates token state and persists refresh token at renewal', () => {
  assert.match(
    authContextSource,
    /AUTH_SESSION_REFRESHED_EVENT|auth-session-refreshed/,
    'AuthContext must listen for AUTH_SESSION_REFRESHED_EVENT to update state and storage'
  );
});
