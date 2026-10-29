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
const collective = read('MassasCollectiveTable.tsx');
const mirrorCard = read('ErpMirrorCard.tsx');

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
    assert.ok(panel.includes("endpoint: 'usuarios'"));
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

describe('aero bridge wiring: a launch of many products leaves once', () => {
  it('splits the queue into one envelope and keeps what does not fit waiting', () => {
    assert.ok(launch.includes('takeAeroBatch(queue, AERO_BATCH_MAX)'));
    assert.ok(launch.includes('buildAeroLaunchTrips({'));
    // Un producto viaja por `entrada` y un lote por `entrada_lote`: los dos callbacks se piden acá, no en el
    // atajo, que no sabe de acciones.
    assert.ok(launch.includes("singleCallbackUrl: aeroBridgeCallbackFor('entrada')"));
    assert.ok(launch.includes("batchCallbackUrl: aeroBridgeCallbackFor('entrada_lote')"));
    assert.ok(launch.includes('writeAeroQueue({ ...taken.rest, inFlight: taken.batch })'));
  });

  it('reads the batch answer against the products that were in the phone', () => {
    assert.ok(launch.includes("parsed.action === 'entrada_lote'"));
    assert.ok(launch.includes('readAeroBatchResult(parsed.data, expected)'));
    assert.ok(launch.includes('const sent = queue?.inFlight ?? []'));
    assert.ok(launch.includes('batchSyncFeedback(batch)'));
  });

  it('puts only the refused products back at the front of the queue', () => {
    assert.ok(launch.includes('batch.failedIndexes'));
    assert.ok(launch.includes('batch.state === \'complete\' ? [] : batch.state === \'partial\' ? failedItems : sent'));
    assert.ok(launch.includes('{ ...queue, items: [...retryItems, ...queue.items], inFlight: [] }'));
  });

  it('logs one line per trip instead of one per product', () => {
    assert.ok(launch.includes('`batch ok=${batch.ok} failed=${batch.failed} · via=bridge`'));
    assert.ok(launch.includes('`batch ${payloads.length}'));
  });

  it('closes the single-product trip instead of draining an empty queue', () => {
    // Un producto viaja por `entrada` y vuelve confirmado: la cola ya no tiene nada pendiente. Antes el
    // cierre miraba sólo `items`, que ahora está vacío porque el producto espera en `inFlight`, y el
    // operario volvía a una pantalla sin veredicto.
    assert.ok(launch.includes('const rest = { ...queue, inFlight: [] };'));
    assert.ok(launch.includes('if (rest.items.length > 0) {'));
    assert.ok(launch.includes("message: outcome.message ?? t('massas.aero.syncConfirmYes')"));
  });

  it('returns a refused single product to the queue instead of dropping it', () => {
    assert.ok(launch.includes("items: [...queue.inFlight, ...queue.items]"));
    // El rechazo queda en la bitácora como error, no como una respuesta cualquiera.
    assert.ok(launch.includes("error: outcome.status === 'error' ? outcome.message ?? 'bridge_unreadable' : null"));
  });

  it('offers the retry to the operator and never sends it by itself', () => {
    assert.ok(launch.includes('const retryFailedLaunches = useCallback'));
    assert.ok(launch.includes('onClick={retryFailedLaunches}'));
    assert.ok(launch.includes('data-testid="massa-result-retry"'));
    // El reintento sólo reencola y drena lo que el operario pidió: nada de un segundo viaje automático.
    assert.equal(launch.includes('setTimeout(() => triggerAeroSync'), false);
  });
});

describe('aero bridge wiring: the ERP mirror', () => {
  it('asks /movimentoitem through the bridge, with its own callback', () => {
    assert.ok(launch.includes('aeroMovementsEnvelope('));
    assert.ok(launch.includes("aeroBridgeCallbackFor('movimentos')"));
    assert.ok(launch.includes("parsed.action === 'movimentos'"));
    // La consulta se publica como lectura del historial, no como lanzamiento.
    assert.ok(launch.includes("event: 'read_history'"));
  });

  it('keeps the day in Trindade, so the plant reads it without running the shortcut', () => {
    assert.ok(launch.includes("'/integration/aero/movements'"));
    assert.ok(launch.includes('/integration/aero/movements?date='));
    assert.ok(launch.includes('buildAeroMovementsSnapshot('));
    assert.ok(launch.includes('readAeroMovementsSnapshot('));
  });

  it('says the installation is not migrated instead of showing an empty day', () => {
    assert.ok(launch.includes('err.status === 503'));
    assert.ok(launch.includes("t('massas.erp.notMigrated')"));
  });

  it('imports a seat as a new local launch and never writes to the company record', () => {
    assert.ok(launch.includes("api.post('/massas-launch'"));
    assert.equal(/api\.(put|del)\(`\/integration\/aero/.test(launch), false);
    assert.equal(/integration\/aero\/movements`\s*,\s*\{?\s*method: 'PUT'/.test(launch), false);
  });

  it('opens the mirror in its own tab, next to the local launches', () => {
    assert.ok(collective.includes('role="tablist"'));
    assert.ok(collective.includes('data-testid="massas-tab-trindade"'));
    assert.ok(collective.includes('data-testid="massas-tab-erp"'));
    assert.ok(collective.includes('<ErpMirrorCard'));
    // El historial no tiene espejo y no debe inventar una pestaña vacía.
    assert.ok(collective.includes('erpMirror?:'));
  });

  it('keeps the mirror read-only and every control at the 44px touch floor', () => {
    assert.equal(mirrorCard.includes('onDelete'), false);
    assert.equal(mirrorCard.includes('onStartEdit'), false);
    const buttons = [...mirrorCard.matchAll(/<Button[\s\S]*?className="([^"]*)"/g)].map((m) => m[1]);
    assert.ok(buttons.length > 0);
    for (const className of buttons) {
      assert.ok(className.includes('min-h-[44px]'), `a mirror control lost its 44px floor: ${className}`);
    }
  });
});
