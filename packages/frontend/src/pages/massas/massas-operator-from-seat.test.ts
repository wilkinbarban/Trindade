import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * Creating a worker from an unlinked ERP seat (S16-S19).
 *
 * An unlinked seat has ownerUserId === null. For an administrator, the table shows the inline button
 * "+ Criar Operário: <NAME>" which creates the user and refreshes the mirror. For a non-administrator,
 * the creation button is never rendered and an explanation is shown instead.
 */
const table = readFileSync(new URL('./MassasUnifiedTable.tsx', import.meta.url), 'utf8');
const page = readFileSync(new URL('../MassasLaunchPage.tsx', import.meta.url), 'utf8');

describe('operator creation from ERP seat (S16-S19)', () => {
  it('shows inline button + Criar Operário: <NOMBRE> for administrator when seat has no owner', () => {
    assert.ok(table.includes('canCreateOperatorRow'));
    assert.ok(table.includes('massas-create-operator-'));
    assert.ok(table.includes("t('massas.unified.createOperator'"));
  });

  it('keeps creation controls at the 44px touch floor and shows spinner while creating', () => {
    assert.ok(table.includes('data-testid={`massas-create-operator-${row.entLans[0]}`'));
    const creationButtonMatch = table.match(/<button[\s\S]*?data-testid=\{`massas-create-operator-[\s\S]*?>/);
    assert.ok(creationButtonMatch, 'creation button must exist');
    assert.ok(creationButtonMatch[0].includes('min-h-[44px]'));
    assert.ok(table.includes('creatingOperatorEntLan === row.entLans[0]'));
  });

  it('wires creation in MassasLaunchPage with username, display name, deterministic password and role 2', () => {
    assert.ok(page.includes('handleCreateOperator'));
    assert.ok(page.includes('deriveUsername'));
    assert.ok(page.includes('deriveDisplayName'));
    assert.ok(page.includes('quickWorkerPassword'));
    assert.ok(page.includes('QUICK_WORKER_ROLE_ID'));
    assert.ok(page.includes("api.post('/admin/users'"));
  });

  it('refreshes both the launches and the mirror immediately after creation (S18)', () => {
    assert.ok(page.includes('await Promise.all([load(date), loadMirror(date)])'));
  });
});
