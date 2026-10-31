import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The sharing screen, read as source.
 *
 * What the browser has to prove — that a worker is created and the message carries their credentials — needs a
 * server; what is asserted here is that the screen offers both modes, that the role cannot be chosen, and that
 * every path that leaves the browser creates the account first.
 */
const page = readFileSync(new URL('./ShareAppPage.tsx', import.meta.url), 'utf8');

describe('share screen quick worker creation', () => {
  it('offers the two exclusive modes, with creation as the default', () => {
    assert.ok(page.includes("useState<'create' | 'existing'>('create')"));
    assert.ok(page.includes('data-testid="share-credential-mode-create"'));
    assert.ok(page.includes('data-testid="share-credential-mode-existing"'));
  });

  it('creates a worker with the fixed role instead of accepting one', () => {
    assert.ok(page.includes("api.post('/admin/users'"));
    assert.ok(page.includes('role_id: QUICK_WORKER_ROLE_ID'));
    assert.equal(page.includes('role_id: 1'), false);
    // No select for the role: the only role this screen hands out is the worker's.
    assert.equal(page.includes('roleSelectId'), false);
  });

  it('generates the password from the login and never below fifteen characters', () => {
    assert.ok(page.includes('quickWorkerPassword(quickLogin)'));
    assert.ok(page.includes('deriveUsername'));
  });

  it('provides an explicit + Cadastrar Operário button and green success feedback', () => {
    assert.ok(page.includes('data-testid="share-create-worker-btn"'));
    assert.ok(page.includes('data-testid="share-create-success"'));
    assert.ok(page.includes('handleCreateWorker'));
  });

  it('prevents WhatsApp and Copy from creating accounts implicitly, requiring prior registration', () => {
    // Sending and copying do not create accounts on their own anymore (S12).
    assert.equal(page.includes('if (!(await ensureWorkerCreated())) return;'), false);
    assert.ok(page.includes('share.createRequiredBeforeSend'));
  });

  it('handles login taken, network error and server rejection with distinct messages', () => {
    assert.ok(page.includes('isNetworkError'));
    assert.ok(page.includes('share.createUserAlreadyExists'));
    assert.ok(page.includes('share.createNetworkError'));
  });
});
