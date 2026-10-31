import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { triggerAeroMovementsSync } from './aero-mirror.js';

/**
 * One way to ask the company for the day, so the report's single button and the receipts section cannot
 * drift apart. The helper also answers whether it launched anything: without company access the caller
 * refreshes what it already has instead of leaving a button that promises a sync it cannot start.
 */
describe('triggering the ERP movements sync', () => {
  const ready = {
    wsUrl: 'http://192.168.0.10:8070',
    username: 'WILKIN',
    password: 'secret',
    shortcutName: 'Aero32 Bridge',
    syncEnabled: true,
    lastSyncAt: null,
  };

  it('launches the bridge with the whole day and answers true', () => {
    const launched: string[] = [];
    const fired = triggerAeroMovementsSync(ready, {
      date: '2026-10-30',
      callbackPath: '/reports',
      navigate: (url) => launched.push(url),
    });

    assert.equal(fired, true);
    assert.equal(launched.length, 1);
    const url = new URL(launched[0].replace('shortcuts://', 'https://shortcut.invalid/'));
    assert.equal(url.searchParams.get('name'), 'Aero32 Bridge');
    const envelope = JSON.parse(url.searchParams.get('text')!) as {
      url: string;
      headers: Record<string, string>;
      body: string;
      callback: string;
    };
    assert.equal(envelope.url, 'http://192.168.0.10:8070/movimentoitem?_method=PATCH');
    assert.match(envelope.headers.Authorization, /^Basic /);
    const payload = JSON.parse(envelope.body) as { NOMUSU: string | null; DTINI: string; DTFIM: string };
    assert.equal(payload.NOMUSU, null, 'the day of every operator, not only the asker');
    assert.equal(payload.DTINI, '2026-10-30');
    assert.equal(payload.DTFIM, '2026-10-30');
    assert.match(envelope.callback, /\/reports/);
  });

  it('answers false and launches nothing when the device has no company access', () => {
    const launched: string[] = [];
    const fired = triggerAeroMovementsSync(
      { wsUrl: '', username: '', password: '', shortcutName: 'Aero32 Bridge', syncEnabled: true, lastSyncAt: null },
      { date: '2026-10-30', callbackPath: '/reports', navigate: (url) => launched.push(url) },
    );

    assert.equal(fired, false);
    assert.equal(launched.length, 0);
  });
});
