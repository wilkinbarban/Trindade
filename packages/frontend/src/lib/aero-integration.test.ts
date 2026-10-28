import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  AERO_DEFAULT_SHORTCUT_NAME,
  AERO_INTEGRATION_USERNAME,
  AERO_PENDING_SYNC_STORAGE_KEY,
  AERO_SETTINGS_STORAGE_KEY,
  AERO_ENTRY_SERIES,
  AERO_TEST_TIMEOUT_MS,
  DEFAULT_AERO_SETTINGS,
  aeroCallbackUrl,
  aeroConnectionBlocker,
  aeroEndpoint,
  aeroSettingsReady,
  aeroShortcutTriggerUrl,
  aeroShortcutRecipe,
  basicAuthHeader,
  buildAeroEntryPayload,
  buildEntradaItemPayload,
  clearAeroPendingSync,
  clearAeroSettings,
  isAeroAllowed,
  isAeroIntegrationOwner,
  loadAeroPendingSync,
  loadAeroSettings,
  normalizeAeroWsUrl,
  parseAeroCallback,
  readAeroTestResult,
  saveAeroPendingSync,
  saveAeroSettings,
  type AeroPendingSync,
  type AeroSettings,
  type StorageLike,
} from './aero-integration';

class FakeStorage implements StorageLike {
  private store = new Map<string, string>();

  getItem(key: string): string | null {
    return this.store.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.store.set(key, value);
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  clear(): void {
    this.store.clear();
  }
}

describe('aero-integration: constants & types', () => {
  it('defines expected storage keys, usernames, defaults and timeouts', () => {
    assert.equal(AERO_SETTINGS_STORAGE_KEY, 'trindade.aero.integration.v1');
    assert.equal(AERO_PENDING_SYNC_STORAGE_KEY, 'trindade.aero.pending-sync.v1');
    assert.equal(AERO_INTEGRATION_USERNAME, 'wilkin');
    assert.equal(AERO_DEFAULT_SHORTCUT_NAME, 'Aero32 Estoque');
    assert.equal(AERO_TEST_TIMEOUT_MS, 8000);
    assert.deepEqual(DEFAULT_AERO_SETTINGS, {
      wsUrl: '',
      username: '',
      password: '',
      userCode: null,
      shortcutName: 'Aero32 Estoque',
      syncEnabled: false,
      lastSyncAt: null,
    });
  });
});

describe('aero-integration: access control', () => {
  it('allows only wilkin with Administrador role', () => {
    assert.equal(isAeroAllowed({ username: 'wilkin', role: 'Administrador' }), true);
    assert.equal(isAeroIntegrationOwner({ username: 'wilkin', role: 'Administrador' }), true);

    // Another admin
    assert.equal(isAeroAllowed({ username: 'maria', role: 'Administrador' }), false);
    // Case sensitivity on username
    assert.equal(isAeroAllowed({ username: 'Wilkin', role: 'Administrador' }), false);
    // A worker
    assert.equal(isAeroAllowed({ username: 'wilkin', role: 'Operador' }), false);
    // Null / undefined inputs
    assert.equal(isAeroAllowed(null), false);
    assert.equal(isAeroAllowed(undefined), false);
    assert.equal(isAeroAllowed({ username: '', role: '' }), false);
  });
});

describe('aero-integration: settings persistence', () => {
  it('returns default settings when storage is empty or unsupplied', () => {
    const storage = new FakeStorage();
    const settings = loadAeroSettings(storage);
    assert.deepEqual(settings, DEFAULT_AERO_SETTINGS);
  });

  it('round-trips settings through fake storage', () => {
    const storage = new FakeStorage();
    const custom: AeroSettings = {
      wsUrl: 'http://192.168.1.50:8000/api',
      username: 'wilkin',
      password: 'secret-password',
      userCode: 12,
      shortcutName: 'Custom Shortcut',
      syncEnabled: true,
      lastSyncAt: '2026-10-27T10:00:00Z',
    };

    saveAeroSettings(custom, storage);
    const loaded = loadAeroSettings(storage);
    assert.deepEqual(loaded, custom);
  });

  it('tolerates corrupted or non-object JSON and falls back to defaults', () => {
    const storage = new FakeStorage();
    storage.setItem(AERO_SETTINGS_STORAGE_KEY, '{ invalid json');
    assert.deepEqual(loadAeroSettings(storage), DEFAULT_AERO_SETTINGS);

    storage.setItem(AERO_SETTINGS_STORAGE_KEY, '"just a string"');
    assert.deepEqual(loadAeroSettings(storage), DEFAULT_AERO_SETTINGS);

    storage.setItem(AERO_SETTINGS_STORAGE_KEY, '12345');
    assert.deepEqual(loadAeroSettings(storage), DEFAULT_AERO_SETTINGS);
  });

  it('drops the unit and series a previous build let the operator configure', () => {
    // The panel used to offer CX and a free-text ENT_SER. Neither is a decision the operator makes, so a
    // phone that still has them stored must come back with settings that carry no such thing at all.
    const storage = new FakeStorage();
    storage.setItem(
      AERO_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        wsUrl: 'http://192.168.1.50:8000/api',
        username: 'wilkin',
        password: 'pwd',
        userCode: -5,
        quantityUnit: 'CX',
        entrySeries: 'EXP',
        shortcutName: 'Aero',
        syncEnabled: true,
      }),
    );

    const loaded = loadAeroSettings(storage);
    assert.equal('quantityUnit' in loaded, false);
    assert.equal('entrySeries' in loaded, false);
    assert.equal(loaded.userCode, null);
    assert.equal(loaded.username, 'wilkin');
  });

  it('clears settings from storage', () => {
    const storage = new FakeStorage();
    saveAeroSettings({ ...DEFAULT_AERO_SETTINGS, username: 'wilkin' }, storage);
    assert.ok(storage.getItem(AERO_SETTINGS_STORAGE_KEY));
    clearAeroSettings(storage);
    assert.equal(storage.getItem(AERO_SETTINGS_STORAGE_KEY), null);
  });
});

describe('aero-integration: aeroSettingsReady', () => {
  it('requires a normalizable URL, non-empty credentials, and positive integer userCode', () => {
    const valid: AeroSettings = {
      wsUrl: 'http://192.168.1.50:8000/api',
      username: 'wilkin',
      password: 'pwd',
      userCode: 12,
      shortcutName: 'Aero',
      syncEnabled: true,
      lastSyncAt: null,
    };
    assert.equal(aeroSettingsReady(valid), true);

    // Missing userCode
    assert.equal(aeroSettingsReady({ ...valid, userCode: null }), false);
    // Non-positive userCode
    assert.equal(aeroSettingsReady({ ...valid, userCode: 0 }), false);
    assert.equal(aeroSettingsReady({ ...valid, userCode: -1 }), false);
    // Missing credentials
    assert.equal(aeroSettingsReady({ ...valid, username: '' }), false);
    assert.equal(aeroSettingsReady({ ...valid, password: '' }), false);
    // Invalid URL
    assert.equal(aeroSettingsReady({ ...valid, wsUrl: 'not-a-url' }), false);
    assert.equal(aeroSettingsReady({ ...valid, wsUrl: '' }), false);
  });
});

describe('aero-integration: normalizeAeroWsUrl', () => {
  it('appends /api when URL carries no path and collapses trailing slashes', () => {
    assert.equal(normalizeAeroWsUrl('http://192.168.1.50:8000'), 'http://192.168.1.50:8000/api');
    assert.equal(normalizeAeroWsUrl('http://192.168.1.50:8000/'), 'http://192.168.1.50:8000/api');
    assert.equal(normalizeAeroWsUrl('http://192.168.1.50:8000/api'), 'http://192.168.1.50:8000/api');
    assert.equal(normalizeAeroWsUrl('http://192.168.1.50:8000/api/'), 'http://192.168.1.50:8000/api');
    assert.equal(normalizeAeroWsUrl('https://erp.empresa.com:8443'), 'https://erp.empresa.com:8443/api');
    assert.equal(normalizeAeroWsUrl('http://192.168.1.50:8000/custom/path///'), 'http://192.168.1.50:8000/custom/path');
    assert.equal(normalizeAeroWsUrl('   http://192.168.1.50:8000/api/   '), 'http://192.168.1.50:8000/api');
  });

  it('rejects empty strings, non-http protocols and malformed URLs', () => {
    assert.equal(normalizeAeroWsUrl(''), null);
    assert.equal(normalizeAeroWsUrl('   '), null);
    assert.equal(normalizeAeroWsUrl('ftp://192.168.1.50:8000'), null);
    assert.equal(normalizeAeroWsUrl('ws://192.168.1.50:8000'), null);
    assert.equal(normalizeAeroWsUrl('not-a-url'), null);
    assert.equal(normalizeAeroWsUrl('http://'), null);
  });
});

describe('aero-integration: aeroEndpoint', () => {
  it('joins base and path with exactly one slash, preserving path trailing slash', () => {
    assert.equal(aeroEndpoint('http://192.168.1.50:8000/api', 'usuarios/'), 'http://192.168.1.50:8000/api/usuarios/');
    assert.equal(aeroEndpoint('http://192.168.1.50:8000/api/', 'usuarios/'), 'http://192.168.1.50:8000/api/usuarios/');
    assert.equal(aeroEndpoint('http://192.168.1.50:8000/api/', '/usuarios/'), 'http://192.168.1.50:8000/api/usuarios/');
    assert.equal(aeroEndpoint('http://192.168.1.50:8000/api', 'entradasitem'), 'http://192.168.1.50:8000/api/entradasitem');
    assert.equal(aeroEndpoint('http://192.168.1.50:8000/api/', '/entradasitem'), 'http://192.168.1.50:8000/api/entradasitem');
  });
});

describe('aero-integration: basicAuthHeader', () => {
  it('formats Basic header with base64 encoded user:pass', () => {
    assert.equal(basicAuthHeader('wilkin', 'senha'), 'Basic d2lsa2luOnNlbmhh');
  });

  it('round-trips UTF-8 accented passwords properly', () => {
    const user = 'wilkin';
    const pass = 'p@sswórd—açúcar';
    const header = basicAuthHeader(user, pass);
    assert.ok(header.startsWith('Basic '));

    const base64Part = header.slice(6);
    const decodedBinary = atob(base64Part);
    const bytes = Uint8Array.from(decodedBinary, (c) => c.charCodeAt(0));
    const decoded = new TextDecoder().decode(bytes);
    assert.equal(decoded, `${user}:${pass}`);
  });
});

describe('aero-integration: aeroConnectionBlocker', () => {
  it('detects mixed_content when page is https: and target is http:', () => {
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'https:', wsUrl: 'http://192.168.1.50:8000/api' }),
      'mixed_content',
    );
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'https', wsUrl: 'http://192.168.1.50:8000/api' }),
      'mixed_content',
    );
  });

  it('allows http target from http page and https target from https page', () => {
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'http:', wsUrl: 'http://192.168.1.50:8000/api' }),
      null,
    );
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'https:', wsUrl: 'https://erp.empresa.com/api' }),
      null,
    );
  });

  it('detects insecure_target when protocol is not http/https', () => {
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'https:', wsUrl: 'ftp://ftp.empresa.com/api' }),
      'insecure_target',
    );
    assert.equal(
      aeroConnectionBlocker({ pageProtocol: 'http:', wsUrl: 'ws://socket.empresa.com/api' }),
      'insecure_target',
    );
  });
});

describe('aero-integration: payload builders', () => {
  it('builds payload matching exact JSON schema and key order from docs §2.3', () => {
    const payload = buildEntradaItemPayload({
      productCode: '1001',
      productName: 'MASSA TRADICIONAL 500G',
      quantity: 50.0,
      entryDate: '2026-10-27',
      userCode: 12,
      observation: 'Lanzamiento desde Trindade',
    });

    // Check exact keys and key order documented in docs/registro-integracion-aero-webservice.md §2.3
    const expectedKeys = [
      'PRO_COD',
      'PRO_DES',
      'PRO_QTD',
      'ENT_DTA',
      'ENT_USU',
      'ENT_SER',
      'ENT_DTANOT',
      'ENT_DTMOVI',
      'ENT_OBS',
      'LOTES',
    ];
    assert.deepEqual(Object.keys(payload), expectedKeys);

    assert.deepEqual(payload, {
      PRO_COD: '1001',
      PRO_DES: 'MASSA TRADICIONAL 500G',
      PRO_QTD: 50.0,
      ENT_DTA: '2026-10-27',
      ENT_USU: 12,
      ENT_SER: 'APL',
      ENT_DTANOT: '2026-10-27',
      ENT_DTMOVI: '2026-10-27',
      ENT_OBS: 'Lanzamiento desde Trindade',
      LOTES: [],
    });
  });

  it('handles Date objects and default observation in buildEntradaItemPayload', () => {
    const payload = buildEntradaItemPayload({
      productCode: '1045',
      productName: 'MASSA CASEIRA',
      quantity: 10,
      entryDate: new Date('2026-10-28T12:00:00Z'),
      userCode: 12,
    });

    assert.equal(payload.ENT_DTA, '2026-10-28');
    assert.equal(payload.ENT_DTANOT, '2026-10-28');
    assert.equal(payload.ENT_DTMOVI, '2026-10-28');
    assert.equal(payload.ENT_OBS, 'Lancamento de massas via Trindade');
  });

  it('always launches in units under the fixed APL series', () => {
    // The factory has no per-box launch and the Delphi binary has APL burnt in: five boxes of ten is fifty
    // units on the ERP, and neither number is something the operator can pick.
    const payload = buildAeroEntryPayload({
      productCode: '1045',
      productName: 'MASSA CASEIRA',
      boxes: 5,
      unitsPerBox: 10,
      date: '2026-10-27',
      userCode: 12,
    });

    assert.equal(payload.PRO_QTD, 50);
    assert.equal(payload.ENT_SER, AERO_ENTRY_SERIES);
    assert.equal(AERO_ENTRY_SERIES, 'APL');
  });
});

describe('aero-integration: readAeroTestResult', () => {
  it('reads documented success response with userCode and userName', () => {
    const res = readAeroTestResult({
      success: true,
      data: {
        CODIGO: 12,
        SENHA: '...',
        NOME: 'WILKIN',
      },
    });
    assert.deepEqual(res, { ok: true, userCode: 12, userName: 'WILKIN' });
  });

  it('handles documented failure response', () => {
    const res = readAeroTestResult({
      success: false,
      message: 'Senha ou usuario não conferem',
    });
    assert.equal(res.ok, false);
    if (!res.ok) {
      assert.equal(res.message, 'Senha ou usuario não conferem');
    }
  });

  it('handles non-object, missing data, and invalid CODIGO gracefully without throwing', () => {
    assert.equal(readAeroTestResult(null).ok, false);
    assert.equal(readAeroTestResult('invalid string').ok, false);
    assert.equal(readAeroTestResult({ success: true, data: null }).ok, false);
    assert.equal(readAeroTestResult({ success: true, data: { CODIGO: 'not-a-number' } }).ok, false);
    assert.equal(readAeroTestResult({ success: true, data: { CODIGO: -1 } }).ok, false);
    assert.equal(readAeroTestResult({ success: true, data: { CODIGO: 0 } }).ok, false);
  });
});

describe('aero-integration: the two-action shortcut', () => {
  // El atajo tiene que poder armarse con dos acciones nativas. Si el `text` del deep link llevara el
  // sobre { url, authorization, body }, la persona que lo instala tendría que abrir un diccionario y
  // leer tres claves antes de llegar al POST: siete pasos en un teléfono, que es exactamente lo que
  // no ocurrió. El payload viaja como JSON plano y el atajo lo entrega tal cual como cuerpo.
  it('sends the entry payload itself as the shortcut text, not a transport envelope', () => {
    const payload = buildEntradaItemPayload({
      productCode: '1001',
      productName: 'MASSA TRADICIONAL 500G',
      quantity: 50,
      entryDate: '2026-10-27',
      userCode: 12,
    });
    const url = aeroShortcutTriggerUrl({ name: 'Aero32 Estoque', payload });

    const parsed = new URL(url);
    assert.equal(parsed.protocol, 'shortcuts:');
    assert.equal(parsed.searchParams.get('input'), 'text');
    const text = parsed.searchParams.get('text');
    assert.ok(text);

    // Lo que llega al atajo es el cuerpo exacto del POST, sin envoltorio.
    assert.equal(text, JSON.stringify(payload));
    assert.deepEqual(JSON.parse(text), payload);
    assert.equal('url' in JSON.parse(text), false);
    assert.equal('authorization' in JSON.parse(text), false);
  });

  it('hands the shortcut the endpoint and the Authorization header as separate copyable strings', () => {
    const recipe = aeroShortcutRecipe({
      wsUrl: 'http://192.168.1.50:8000/api',
      username: 'wilkin',
      password: 'senha',
      callbackUrl: 'https://trindademasas.duckdns.org/massas-launch?aero_sync=ok',
    });

    assert.equal(recipe.endpoint, 'http://192.168.1.50:8000/api/entradasitem');
    assert.equal(recipe.authorization, basicAuthHeader('wilkin', 'senha'));
    assert.equal(recipe.callbackUrl, 'https://trindademasas.duckdns.org/massas-launch?aero_sync=ok');
    assert.equal(recipe.contentType, 'application/json');
  });
});

describe('aero-integration: callbacks', () => {
  it('builds callback URL with and without base path without doubling slashes', () => {
    const urlRoot = aeroCallbackUrl({
      origin: 'https://trindademasas.duckdns.org',
      basePath: '/',
      status: 'ok',
    });
    assert.equal(urlRoot, 'https://trindademasas.duckdns.org/massas-launch?aero_sync=ok');

    const urlApp = aeroCallbackUrl({
      origin: 'https://trindademasas.duckdns.org',
      basePath: '/app',
      status: 'error',
      message: 'Falha no ERP',
      launchId: 42,
    });
    assert.equal(
      urlApp,
      'https://trindademasas.duckdns.org/app/massas-launch?aero_sync=error&aero_msg=Falha%20no%20ERP&aero_launch=42',
    );
  });

  it('parses aero callback from query strings correctly', () => {
    assert.deepEqual(parseAeroCallback('?aero_sync=ok'), { status: 'ok', message: null });
    assert.deepEqual(parseAeroCallback('aero_sync=ok'), { status: 'ok', message: null });
    assert.deepEqual(parseAeroCallback('?aero_sync=error&aero_msg=Erro%20ao%20gravar'), {
      status: 'error',
      message: 'Erro ao gravar',
    });
    assert.deepEqual(parseAeroCallback('?aero_sync=ok&aero_msg='), { status: 'ok', message: '' });

    // Garbage / empty inputs
    assert.equal(parseAeroCallback(''), null);
    assert.equal(parseAeroCallback('?'), null);
    assert.equal(parseAeroCallback('?other=123'), null);
    assert.equal(parseAeroCallback('?aero_sync=unknown'), null);
  });
});

describe('aero-integration: pending sync storage', () => {
  it('round-trips and clears pending sync object through fake storage', () => {
    const storage = new FakeStorage();
    const pending: AeroPendingSync = {
      launchIds: [1, 2, 3],
      date: '2026-10-27',
      requestedAt: '2026-10-27T10:00:00.000Z',
      destinationUrl: 'http://192.168.1.50:8000/api/entradasitem',
    };

    saveAeroPendingSync(pending, storage);
    const loaded = loadAeroPendingSync(storage);
    assert.deepEqual(loaded, pending);

    clearAeroPendingSync(storage);
    assert.equal(loadAeroPendingSync(storage), null);
  });

  it('tolerates corrupt pending sync data', () => {
    const storage = new FakeStorage();
    storage.setItem(AERO_PENDING_SYNC_STORAGE_KEY, '{ bad json');
    assert.equal(loadAeroPendingSync(storage), null);

    storage.setItem(AERO_PENDING_SYNC_STORAGE_KEY, JSON.stringify({ launchIds: 'not-an-array' }));
    assert.equal(loadAeroPendingSync(storage), null);
  });
});
