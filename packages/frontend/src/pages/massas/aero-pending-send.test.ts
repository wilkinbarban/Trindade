import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * «Enviar ao ERP» sends the row, and sends it as its operator.
 *
 * The pending row used to call the same handler as the header's sync, so the button asked the plant for the
 * day's movements and returned without writing anything: the launch stayed pending and the operator had no
 * way to know the trip had gone to the wrong endpoint. The row's trip is `entradasitem` (a `launch_sync`),
 * and its `ENT_USU` is the launch's operator, never whoever configured the phone.
 */
const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const unified = read('./MassasUnifiedTable.tsx');
const collective = read('./MassasCollectiveTable.tsx');
const launch = read('../MassasLaunchPage.tsx');

describe('the pending row sends its own launch', () => {
  it('gives the row its own action instead of reusing the day sync', () => {
    assert.match(unified, /onSendToErp\?: \(row: MassasUnifiedRow\) => void/);
    assert.match(unified, /onClick=\{\(\) => onSendToErp\(row\)\}/);
    // The old wiring: the row's button called the movements read, which never reaches `entradasitem`.
    assert.equal(/onClick=\{onSync\}/.test(unified), false, 'the row must not reuse the movements sync');
    assert.match(collective, /onSendToErp=\{onSendToErp\}/);
  });

  it('hands the page the pending row and trips the launch envelope', () => {
    assert.match(launch, /onSendToErp=\{handleSendPendingLaunch\}/);
    assert.match(launch, /const handleSendPendingLaunch = useCallback\(/);
    assert.match(launch, /pendingLaunchQueueItem\(\{/);
    // The row's operator and its launches are what make the envelope traceable back to the person.
    assert.match(launch, /operatorName: row\.operatorName/);
    assert.match(launch, /launchIds: row\.launchIds/);
    assert.match(launch, /triggerAeroSync\(queue\)/);
    // The movements read stays where it belongs: the header's "Sincronizar com ERP".
    assert.match(launch, /onClick=\{handleSyncFromErp\}/);
  });

  it('signs each queued entry with its own operator, falling back to the session', () => {
    assert.match(launch, /userCode: item\.operatorName \?\? operatorUser/);
  });
});
