import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * Where the device rule is enforced, read as source.
 *
 * The behaviour of the detection is covered in `lib/device-detection.test.ts`; what is asserted here is that
 * every screen that can move the plant's integration actually asks it. The failure this guards against is
 * quiet and expensive: a control left live on a desktop looks like it works, the operator taps it, and the
 * browser blocks the request with an error that reads like a broken ERP.
 */
const panel = readFileSync(new URL('./AeroIntegrationPanel.tsx', import.meta.url), 'utf8');
const launch = readFileSync(new URL('../MassasLaunchPage.tsx', import.meta.url), 'utf8');
const unified = readFileSync(new URL('./MassasUnifiedTable.tsx', import.meta.url), 'utf8');

describe('integration panel honours the device', () => {
  it('asks who is holding the phone instead of assuming a supported one', () => {
    assert.ok(panel.includes('canControlAero(device)'));
    assert.ok(panel.includes('<AeroDeviceBanner device={device} />'));
  });

  it('disables every write control on a system that cannot reach the ERP', () => {
    for (const control of ['aero-test', 'aero-save', 'aero-shortcut-open']) {
      const index = panel.indexOf(`data-testid="${control}"`);
      assert.ok(index > 0, `missing control ${control}`);
      // The attributes of a button are written above its test id, so the disabled rule lives before it.
      const before = panel.slice(Math.max(0, index - 300), index);
      assert.ok(before.includes('deviceLimited'), `${control} must follow the device rule`);
    }
    const clear = panel.indexOf('onClick={handleClear}');
    assert.ok(panel.slice(clear, clear + 400).includes('deviceLimited'));
  });

  it('keeps the fields visible and explains the lock instead of hiding them', () => {
    assert.ok(panel.includes("t('massas.aero.deviceLocked')"));
    const banner = panel.indexOf('<AeroDeviceBanner device={device} />');
    const body = panel.indexOf('aero-integration-body');
    assert.ok(banner > 0 && banner < body, 'the banner sits above the fields it explains');
  });
});

describe('launch screen honours the device', () => {
  it('shows the banner before the controls it explains', () => {
    const banner = launch.indexOf('<AeroDeviceBanner device={device} />');
    const syncButton = launch.indexOf('data-testid="massas-sync-erp"');
    assert.ok(banner > 0 && syncButton > banner);
  });

  it('disables the dispatch, the sync and the mirror import on a desktop', () => {
    assert.ok(launch.includes('data-testid="massas-sync-erp"'));
    const sync = launch.slice(launch.indexOf('data-testid="massas-sync-erp"') - 300, launch.indexOf('data-testid="massas-sync-erp"'));
    assert.ok(sync.includes('deviceLimited'), 'the header sync must be disabled');
    assert.ok(launch.includes('disabled={!aeroSettingsReady(aeroSettings) || deviceLimited}'));
    assert.ok(launch.includes('deviceLimited={deviceLimited}'));
  });

  it('carries the rule into the table, where the send button lives', () => {
    assert.ok(unified.includes('disabled={!canSync || deviceLimited}'));
    assert.ok(unified.includes('disabled={busy || deviceLimited}'));
  });
});
