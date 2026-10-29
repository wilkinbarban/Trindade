import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const read = (...parts: string[]) => fs.readFileSync(path.resolve(__dirname, ...parts), 'utf8');

const panel = read('AeroIntegrationPanel.tsx');
const launch = read('..', 'MassasLaunchPage.tsx');

describe('aero bridge wiring: the panel asks for three things and dispatches the bridge', () => {
  it('asks the operator for the address, the user and the password, and nothing else', () => {
    const fields = [...panel.matchAll(/data-testid="aero-(ws-url|ws-user|ws-pass|user-code)"/g)].map((m) => m[1]);
    assert.deepEqual(fields, ['ws-url', 'ws-user', 'ws-pass']);
    // Lo que el ERP informa en la prueba (`CODIGO`) se muestra y se registra, pero no se pide ni se guarda.
    assert.equal(panel.includes('draft.userCode'), false);
    assert.equal(panel.includes('settings.userCode'), false);
    assert.equal(panel.includes('aero-user-code'), false);
  });

  it('sends the connection test through the same bridge every launch uses', () => {
    assert.ok(panel.includes('buildAeroBridgeEnvelope('));
    assert.ok(panel.includes("endpoint: 'usuarios/'"));
    assert.ok(panel.includes("aeroBridgeCallbackFor('test')"));
    assert.ok(panel.includes('buildAeroBridgeTriggerUrl({ name: settings.shortcutName, envelope })'));
    // Nada del diseño partido sobrevive en el panel.
    assert.equal(panel.includes('aeroTestShortcutTriggerUrl'), false);
    assert.equal(panel.includes('testShortcutName'), false);
  });

  it('keeps the browser probe for when the page is not blocked', () => {
    // El puente es el único camino sobre HTTPS; en local el navegador responde en línea y es más rápido.
    assert.ok(panel.includes('aeroConnectionBlocker('));
    assert.ok(panel.includes('probeAeroConnection('));
  });
});

describe('aero bridge wiring: the launch hands the bridge a full envelope', () => {
  it('signs the entry with the username in uppercase and no stored code', () => {
    assert.equal(launch.includes('userCode:'), true);
    assert.equal(launch.includes('aeroSettings.userCode'), false);
    assert.ok(launch.includes('aeroSettings.username?.trim() || user?.username?.trim() || null'));
  });

  it('posts to /entradasitem through the bridge, with its own callback', () => {
    assert.ok(launch.includes("endpoint: 'entradasitem'"));
    assert.ok(launch.includes("aeroBridgeCallbackFor('entrada')"));
    assert.ok(launch.includes('buildAeroBridgeTriggerUrl({ name: aeroSettings.shortcutName, envelope })'));
    assert.equal(launch.includes('aeroShortcutTriggerUrl('), false);
  });

  it('reads the ERP answer the bridge brought back instead of trusting the visit', () => {
    assert.ok(launch.includes('readAeroBridgeResult(parsed.data)'));
    assert.ok(launch.includes("parsed.kind === 'bridge'"));
    assert.ok(launch.includes("parsed.action !== 'entrada'"));
    // La rama legacy sigue viva mientras un teléfono no rehaga el atajo.
    assert.ok(launch.includes("parsed.kind !== 'sync'"));
  });
});
