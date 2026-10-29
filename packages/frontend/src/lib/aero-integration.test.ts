import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  AERO_DEFAULT_BRIDGE_SHORTCUT_NAME,
  AERO_DEFAULT_SHORTCUT_NAME,
  AERO_DEFAULT_CST,
  AERO_INTEGRATION_USERNAME,
  AERO_PENDING_SYNC_STORAGE_KEY,
  AERO_SETTINGS_STORAGE_KEY,
  AERO_ENTRY_SERIES,
  AERO_TEST_TIMEOUT_MS,
  DEFAULT_AERO_SETTINGS,
  aeroBridgeCallbackPrefix,
  aeroBridgeShortcutRecipe,
  aeroConnectionBlocker,
  aeroEndpoint,
  aeroSettingsReady,
  aeroShortcutTriggerUrl,
  basicAuthHeader,
  buildAeroBridgeEnvelope,
  buildAeroBridgeTriggerUrl,
  buildAeroEntryPayload,
  buildEntradaItemPayload,
  buildAeroMovementsSnapshot,
  buildMovimentoItemPayload,
  readAeroMovements,
  reconcileAeroMovements,
  buildSaidaItemPayload,
  clearAeroPendingSync,
  clearAeroSettings,
  formatAeroCst,
  formatAeroProductCode,
  formatAeroQuantity,
  isAeroAllowed,
  isAeroIntegrationOwner,
  loadAeroPendingSync,
  loadAeroSettings,
  normalizeAeroWsUrl,
  parseAeroCallback,
  readAeroBridgeResult,
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
      shortcutName: 'Aero32 Bridge',
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

  it('drops the unit, the series, the ERP code and the second shortcut a previous build configured', () => {
    // The panel used to offer CX, a free-text ENT_SER, a numeric ERP user code and a second shortcut name.
    // None of them is a decision the operator makes -- ENT_USU is the username in uppercase, and one bridge
    // covers every call -- so a phone that still has them stored must come back without them.
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
    assert.equal('userCode' in loaded, false);
    assert.equal('testShortcutName' in loaded, false);
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
  const valid: AeroSettings = {
    wsUrl: 'http://192.168.1.50:8000/api',
    username: 'wilkin',
    password: 'pwd',
    shortcutName: 'Aero',
    syncEnabled: true,
    lastSyncAt: null,
  };

  it('decides on the three fields the operator types, not on the code only the ERP can reveal', () => {
    assert.equal(aeroSettingsReady(valid), true);

    // Nada que el ERP revela puede condicionar el interruptor: la identidad viaja como el username.
    assert.equal('userCode' in valid, false);

    // Missing credentials
    assert.equal(aeroSettingsReady({ ...valid, username: '' }), false);
    assert.equal(aeroSettingsReady({ ...valid, username: '   ' }), false);
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
      'PRO_CST',
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
      PRO_COD: '001001',
      PRO_DES: 'MASSA TRADICIONAL 500G',
      PRO_QTD: 5000,
      PRO_CST: '000',
      ENT_DTA: '2026-10-27',
      ENT_USU: 12,
      ENT_SER: 'APL',
      ENT_DTANOT: '2026-10-27',
      ENT_DTMOVI: '2026-10-27',
      ENT_OBS: 'Lanzamiento desde Trindade',
      LOTES: [],
    });
  });

  it('pads product codes to 6 digits with leading zeros via formatAeroProductCode', () => {
    assert.equal(formatAeroProductCode('22'), '000022');
    assert.equal(formatAeroProductCode('1192'), '001192');
    assert.equal(formatAeroProductCode('1045'), '001045');
    assert.equal(formatAeroProductCode('000022'), '000022');
    assert.equal(formatAeroProductCode('123456'), '123456');
    assert.equal(formatAeroProductCode(22), '000022');
    assert.equal(formatAeroProductCode(null), '');
    assert.equal(formatAeroProductCode(undefined), '');
    assert.equal(formatAeroProductCode('   '), '');
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

  it('sends the documented fallback user code when no code was captured', () => {
    const base = {
      productCode: '1045',
      productName: 'MASSA CASEIRA',
      quantity: 10,
      entryDate: '2026-10-27',
    };

    // A null or absent code is the normal state in production: the browser cannot probe over HTTPS.
    assert.equal(buildEntradaItemPayload({ ...base, userCode: null }).ENT_USU, 1);
    assert.equal(buildEntradaItemPayload(base).ENT_USU, 1);
    // A code the operator typed is honoured.
    assert.equal(buildEntradaItemPayload({ ...base, userCode: 12 }).ENT_USU, 12);
    // An operator username string is formatted in uppercase as in base.apk txtUsuario.
    assert.equal(buildEntradaItemPayload({ ...base, userCode: 'wilkin' }).ENT_USU, 'WILKIN');
    assert.equal(buildEntradaItemPayload({ ...base, userCode: '  wilkin  ' }).ENT_USU, 'WILKIN');
    assert.equal(buildEntradaItemPayload({ ...base, userCode: '15' }).ENT_USU, 15);
  });

  it('always launches in units under the fixed APL series, scaled by 100 for Firebird ERP', () => {
    // The factory has no per-box launch and the Delphi binary has APL burnt in: five boxes of ten is fifty
    // units on the ERP, scaled by 100 (50 * 100 = 5000) so Firebird numeric precision records 50.00 and not 0.50.
    const payload = buildAeroEntryPayload({
      productCode: '1045',
      productName: 'MASSA CASEIRA',
      boxes: 5,
      unitsPerBox: 10,
      date: '2026-10-27',
      userCode: 12,
    });

    assert.equal(payload.PRO_QTD, 5000);
    assert.equal(payload.ENT_SER, AERO_ENTRY_SERIES);
    assert.equal(AERO_ENTRY_SERIES, 'APL');
  });

  it('multiplies units by 100 via formatAeroQuantity so Firebird stores the exact integer units', () => {
    assert.equal(formatAeroQuantity(90), 9000);
    assert.equal(formatAeroQuantity(15), 1500);
    assert.equal(formatAeroQuantity(1), 100);
    assert.equal(formatAeroQuantity(0), 0);
    assert.equal(formatAeroQuantity(null), 0);
    assert.equal(formatAeroQuantity(undefined), 0);
    assert.equal(formatAeroQuantity(Number.NaN), 0);
  });

  it('includes default PRO_CST "000" and respects custom CST when supplied', () => {
    const base = {
      productCode: '22',
      productName: 'MASSA ROLO 500G',
      quantity: 15,
      entryDate: '2026-10-28',
    };

    // Default when omitted or null
    assert.equal(buildEntradaItemPayload(base).PRO_CST, AERO_DEFAULT_CST);
    assert.equal(AERO_DEFAULT_CST, '000');
    assert.equal(buildEntradaItemPayload({ ...base, productCst: null }).PRO_CST, '000');
    assert.equal(buildEntradaItemPayload({ ...base, productCst: '   ' }).PRO_CST, '000');

    // Custom CST honored
    assert.equal(buildEntradaItemPayload({ ...base, productCst: '010' }).PRO_CST, '010');

    // Passed via buildAeroEntryPayload
    const aeroPayload = buildAeroEntryPayload({
      productCode: '22',
      productName: 'MASSA ROLO 500G',
      boxes: 3,
      unitsPerBox: 5,
      date: '2026-10-28',
      productCst: '020',
    });
    assert.equal(aeroPayload.PRO_CST, '020');
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

  it('handles non-object, missing success, and invalid data gracefully without throwing', () => {
    assert.equal(readAeroTestResult(null).ok, false);
    assert.equal(readAeroTestResult('invalid string').ok, false);
    assert.equal(readAeroTestResult({}).ok, false);
    assert.equal(readAeroTestResult({ success: true, data: null }).ok, true);
    assert.equal(readAeroTestResult({ success: true, data: { CODIGO: 'not-a-number' } }).ok, true);
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

});

describe('aero-integration: callbacks', () => {
  it('parses the launch callback from a query string', () => {
    assert.deepEqual(parseAeroCallback('?aero_sync=ok'), { kind: 'sync', status: 'ok', message: null });
    assert.deepEqual(parseAeroCallback('aero_sync=ok'), { kind: 'sync', status: 'ok', message: null });
    assert.deepEqual(parseAeroCallback('?aero_sync=error&aero_msg=Erro%20ao%20gravar'), {
      kind: 'sync',
      status: 'error',
      message: 'Erro ao gravar',
    });
    assert.deepEqual(parseAeroCallback('?aero_sync=ok&aero_msg='), {
      kind: 'sync',
      status: 'ok',
      message: '',
    });

    // Garbage / empty inputs
    assert.equal(parseAeroCallback(''), null);
    assert.equal(parseAeroCallback('?'), null);
    assert.equal(parseAeroCallback('?other=123'), null);
    assert.equal(parseAeroCallback('?aero_sync=unknown'), null);
  });

  it('reads the connection test verdict the shortcut appends', () => {
    // The shortcut forwards the `success` field of /usuarios/, and Shortcuts renders a boolean in more than
    // one way depending on the iOS version. The page accepts every rendering it can produce, and keeps the
    // raw token so the audit line states exactly what came back instead of an interpretation of it.
    for (const token of ['true', 'ok', '1', 'sim', 'yes', 'TRUE']) {
      assert.deepEqual(parseAeroCallback(`?aero_test=${token}`), {
        kind: 'test',
        verdict: 'ok',
        raw: token,
      });
    }

    for (const token of ['false', 'error', '0', 'nao', 'no', 'FALSE']) {
      assert.deepEqual(parseAeroCallback(`?aero_test=${token}`), {
        kind: 'test',
        verdict: 'error',
        raw: token,
      });
    }

    // An empty or unrecognised value is not a verdict, and saying otherwise would be a lie in the panel.
    assert.deepEqual(parseAeroCallback('?aero_test='), { kind: 'test', verdict: 'unknown', raw: '' });
    assert.deepEqual(parseAeroCallback('?aero_test=hmm'), {
      kind: 'test',
      verdict: 'unknown',
      raw: 'hmm',
    });
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

describe('aero-integration: the unified Aero32 Bridge shortcut', () => {
  it('names one bridge shortcut and points both settings defaults at it', () => {
    assert.equal(AERO_DEFAULT_BRIDGE_SHORTCUT_NAME, 'Aero32 Bridge');
    assert.equal(DEFAULT_AERO_SETTINGS.shortcutName, 'Aero32 Bridge');
    assert.equal('testShortcutName' in DEFAULT_AERO_SETTINGS, false);

    // El nombre viejo sigue exportado: es la llave de la migración de un teléfono que ya lo tenía.
    assert.equal(AERO_DEFAULT_SHORTCUT_NAME, 'Aero32 Estoque');
  });

  it('migrates a phone that still stores the shortcut names of the split design', () => {
    const storage = new FakeStorage();
    storage.setItem(
      AERO_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        wsUrl: 'http://192.168.1.50:8000/api',
        username: 'wilkin',
        password: 'pwd',
        shortcutName: 'Aero32 Estoque',
        testShortcutName: 'Aero32 Prueba',
      }),
    );

    const loaded = loadAeroSettings(storage);
    assert.equal(loaded.shortcutName, 'Aero32 Bridge');

    // Un nombre elegido a propósito no es un default viejo y sobrevive tal cual.
    storage.setItem(
      AERO_SETTINGS_STORAGE_KEY,
      JSON.stringify({ ...DEFAULT_AERO_SETTINGS, shortcutName: 'Minha Ponte' }),
    );
    assert.equal(loadAeroSettings(storage).shortcutName, 'Minha Ponte');
  });
});

describe('aero-integration: saida payload for the bridge', () => {
  it('uses PED_* prefixes, units scaled by 100, a six-digit code and the operator in uppercase', () => {
    const payload = buildSaidaItemPayload({
      productCode: '1192',
      productName: 'MASSA QUADRADA',
      quantity: 15,
      exitDate: '2026-10-28',
      userCode: 'wilkin',
    });

    assert.deepEqual(payload, {
      PRO_COD: '001192',
      PRO_DES: 'MASSA QUADRADA',
      PRO_QTD: 1500,
      PED_DTA: '2026-10-28',
      PED_USU: 'WILKIN',
      PED_SER: 'APL',
      PED_DTANOT: '2026-10-28',
      PED_OBS: 'Saida de massas via Trindade',
      LOTES: [],
    });

    // El orden es el del contrato: la salida no lleva CST ni PED_DTMOVI, y no debe inventarlos.
    assert.deepEqual(Object.keys(payload), [
      'PRO_COD',
      'PRO_DES',
      'PRO_QTD',
      'PED_DTA',
      'PED_USU',
      'PED_SER',
      'PED_DTANOT',
      'PED_OBS',
      'LOTES',
    ]);
    assert.equal('PRO_CST' in payload, false);
    assert.equal('PED_DTMOVI' in payload, false);
  });

  it('accepts a Date and keeps the observation the caller states', () => {
    const payload = buildSaidaItemPayload({
      productCode: '1192',
      productName: 'MASSA QUADRADA',
      quantity: 2.5,
      exitDate: new Date('2026-10-28T12:00:00Z'),
      userCode: 12,
      observation: '  Desejo de cliente  ',
    });

    assert.equal(payload.PED_DTA, '2026-10-28');
    assert.equal(payload.PED_DTANOT, '2026-10-28');
    assert.equal(payload.PED_USU, 12);
    assert.equal(payload.PRO_QTD, 250);
    assert.equal(payload.PED_OBS, 'Desejo de cliente');
  });
});

describe('aero-integration: movimento payload for the bridge', () => {
  it('asks for the day with every filter open, the operator in uppercase and no invented value', () => {
    const payload = buildMovimentoItemPayload({
      userName: 'wilkin',
      startDate: '2026-10-28',
    });

    assert.deepEqual(payload, {
      CODFOR: null,
      NOMUSU: 'WILKIN',
      CODPRO: null,
      DTINI: '2026-10-28',
      DTFIM: '2026-10-28',
      ENTSAI: null,
    });
    assert.deepEqual(Object.keys(payload), ['CODFOR', 'NOMUSU', 'CODPRO', 'DTINI', 'DTFIM', 'ENTSAI']);
  });

  it('narrows by product, range and movement side when the caller asks', () => {
    const payload = buildMovimentoItemPayload({
      userName: '  wilkin ',
      productCode: 22,
      startDate: '2026-10-01',
      endDate: '2026-10-28',
      entrySaida: 'S',
    });

    assert.equal(payload.NOMUSU, 'WILKIN');
    assert.equal(payload.CODPRO, '000022');
    assert.equal(payload.DTINI, '2026-10-01');
    assert.equal(payload.DTFIM, '2026-10-28');
    assert.equal(payload.ENTSAI, 'S');
  });
});

describe('aero-integration: bridge envelope', () => {
  const credentials = {
    wsUrl: 'http://192.168.1.50:8000/api',
    username: 'wilkin',
    password: 'senha',
  };

  const saida = buildSaidaItemPayload({
    productCode: '1192',
    productName: 'MASSA QUADRADA',
    quantity: 15,
    exitDate: '2026-10-28',
    userCode: 'wilkin',
  });

  it('carries the credentials inside the envelope so the phone never has to be edited again', () => {
    const envelope = buildAeroBridgeEnvelope({
      ...credentials,
      endpoint: 'saidasitem',
      payload: saida,
      callbackUrl: aeroBridgeCallbackPrefix({ origin: 'https://trindademasas.duckdns.org', action: 'saida' }),
    });

    assert.deepEqual(Object.keys(envelope), ['url', 'method', 'headers', 'body', 'callback']);
    assert.equal(envelope.url, 'http://192.168.1.50:8000/api/saidasitem');
    assert.equal(envelope.method, 'POST');
    assert.deepEqual(envelope.headers, {
      Authorization: basicAuthHeader('wilkin', 'senha'),
      'Content-Type': 'application/json',
      Accept: 'application/json',
    });
    assert.equal(
      envelope.callback,
      'https://trindademasas.duckdns.org/massas-launch?aero_action=saida&aero_data=',
    );
  });

  it('serialises the body as a plain JSON string, because a dictionary is not JSON on the phone', () => {
    const envelope = buildAeroBridgeEnvelope({
      ...credentials,
      endpoint: 'saidasitem',
      payload: saida,
      callbackUrl: aeroBridgeCallbackPrefix({ origin: 'https://trindademasas.duckdns.org', action: 'saida' }),
    });

    assert.equal(typeof envelope.body, 'string');
    assert.deepEqual(JSON.parse(envelope.body), saida);
    // Sin espacios ni saltos: el texto se pega tal cual en «Cuerpo de la petición» del atajo.
    assert.equal(envelope.body, JSON.stringify(saida));
  });

  it('hands the whole envelope to the shortcut as its one text input', () => {
    const envelope = buildAeroBridgeEnvelope({
      ...credentials,
      endpoint: 'usuarios/',
      payload: {},
      callbackUrl: aeroBridgeCallbackPrefix({ origin: 'https://trindademasas.duckdns.org', action: 'test' }),
    });

    const url = buildAeroBridgeTriggerUrl({ name: 'Aero32 Bridge', envelope });
    const parsed = new URL(url);

    assert.equal(parsed.protocol, 'shortcuts:');
    assert.equal(parsed.searchParams.get('name'), 'Aero32 Bridge');
    assert.equal(parsed.searchParams.get('input'), 'text');
    assert.deepEqual(JSON.parse(parsed.searchParams.get('text') ?? ''), envelope);
  });

  it('leaves the data slot open in the callback, for every action', () => {
    assert.equal(
      aeroBridgeCallbackPrefix({ origin: 'https://trindademasas.duckdns.org', basePath: '/', action: 'entrada' }),
      'https://trindademasas.duckdns.org/massas-launch?aero_action=entrada&aero_data=',
    );
    assert.equal(
      aeroBridgeCallbackPrefix({ origin: 'https://trindademasas.duckdns.org/', basePath: '/app', action: 'movimentos' }),
      'https://trindademasas.duckdns.org/app/massas-launch?aero_action=movimentos&aero_data=',
    );
  });
});

describe('aero-integration: the return path of a bridge callback', () => {
  const origin = 'https://trindademasas.duckdns.org';

  it('comes back to the page the operator left from', () => {
    // The shortcut is a blind proxy: it opens the callback it was handed. Sending the report's own route
    // is what makes the sync start from Recebimento and end there.
    assert.equal(
      aeroBridgeCallbackPrefix({ origin, basePath: '/', action: 'movimentos', path: '/reports/67' }),
      `${origin}/reports/67?aero_action=movimentos&aero_data=`,
    );
  });

  it('keeps the base path in front of the return route', () => {
    assert.equal(
      aeroBridgeCallbackPrefix({ origin, basePath: '/app', action: 'movimentos', path: '/reports/new' }),
      `${origin}/app/reports/new?aero_action=movimentos&aero_data=`,
    );
  });

  it('falls back to the launch page instead of trusting a route that could leave the site', () => {
    for (const path of [
      'https://evil.example/reports/67',
      '//evil.example/reports/67',
      '/reports/67?next=https://evil.example',
      '/reports/67#fragment',
      'reports/67',
    ]) {
      assert.equal(
        aeroBridgeCallbackPrefix({ origin, action: 'movimentos', path }),
        `${origin}/massas-launch?aero_action=movimentos&aero_data=`,
        `${path} must not become the return URL`,
      );
    }
  });
});

describe('aero-integration: bridge callbacks', () => {
  const answer = { success: true, message: 'Gravado com sucesso' };

  it('decodes the ERP answer the bridge brings back for an entry', () => {
    const raw = JSON.stringify(answer);
    assert.deepEqual(parseAeroCallback(`?aero_action=entrada&aero_data=${encodeURIComponent(raw)}`), {
      kind: 'bridge',
      action: 'entrada',
      data: answer,
      raw,
    });
  });

  it('decodes the movement list the bridge brings back', () => {
    const list = [{ ENT_LAN: 10542, PRO_COD: '001192', PRO_QTD: 15.0, ENTSAI: 'E' }];
    const raw = JSON.stringify(list);
    const parsed = parseAeroCallback(`aero_action=movimentos&aero_data=${encodeURIComponent(raw)}`);
    assert.deepEqual(parsed, { kind: 'bridge', action: 'movimentos', data: list, raw });
  });

  it('reads the bridge connection test as the same verdict the panel already understands', () => {
    const okBody = { success: true, data: { CODIGO: 12, NOME: 'WILKIN' } };
    assert.deepEqual(parseAeroCallback(`?aero_action=test&aero_data=${encodeURIComponent(JSON.stringify(okBody))}`), {
      kind: 'test',
      verdict: 'ok',
      raw: JSON.stringify(okBody),
    });

    const rejectedBody = { success: false, message: 'Senha inválida' };
    assert.deepEqual(
      parseAeroCallback(`?aero_action=test&aero_data=${encodeURIComponent(JSON.stringify(rejectedBody))}`),
      { kind: 'test', verdict: 'error', raw: JSON.stringify(rejectedBody) },
    );
  });

  it('keeps the ERP text exactly as the shortcut appended it, percent signs included', () => {
    const raw = '{"success":true,"message":"Soma 1+1 e 2%3D2"}';
    const parsed = parseAeroCallback(`?aero_action=entrada&aero_data=${encodeURIComponent(raw)}`);
    assert.equal(parsed?.kind, 'bridge');
    assert.equal(parsed?.raw, raw);
    assert.deepEqual(parsed?.kind === 'bridge' ? parsed.data : null, { success: true, message: 'Soma 1+1 e 2%3D2' });
  });

  it('refuses an action it does not know and keeps a missing body honest', () => {
    assert.equal(parseAeroCallback('?aero_action=baixa&aero_data=%7B%7D'), null);
    assert.deepEqual(parseAeroCallback('?aero_action=saida'), {
      kind: 'bridge',
      action: 'saida',
      data: null,
      raw: '',
    });
  });

  it('keeps reading the callbacks the previous design produced', () => {
    // Retrocompatibilidad: un teléfono que todavía tiene los atajos viejos sigue funcionando igual.
    assert.deepEqual(parseAeroCallback('?aero_sync=ok'), { kind: 'sync', status: 'ok', message: null });
    assert.deepEqual(parseAeroCallback('?aero_test=true'), { kind: 'test', verdict: 'ok', raw: 'true' });
  });
});

describe('aero-integration: reading the bridge result', () => {
  it('turns the ERP answer into the two outcomes the page acts on', () => {
    assert.deepEqual(readAeroBridgeResult({ success: true, message: 'Gravado com sucesso' }), {
      status: 'ok',
      message: 'Gravado com sucesso',
    });
    assert.deepEqual(readAeroBridgeResult({ success: false, message: 'Item sem CST' }), {
      status: 'error',
      message: 'Item sem CST',
    });
  });

  it('never reports success for an answer it could not read', () => {
    assert.deepEqual(readAeroBridgeResult(null), { status: 'error', message: null });
    assert.deepEqual(readAeroBridgeResult('not json'), { status: 'error', message: null });
    assert.deepEqual(readAeroBridgeResult({ data: [] }), { status: 'error', message: null });
    // Un mensaje que no es texto no se inventa como texto.
    assert.deepEqual(readAeroBridgeResult({ success: false, message: 42 }), { status: 'error', message: null });
  });
});

describe('aero-integration: the bridge shortcut recipe', () => {
  const recipe = aeroBridgeShortcutRecipe({
    wsUrl: 'http://192.168.25.250:1453/api',
    username: 'wilkin',
    password: 'senha',
    origin: 'https://trindademasas.duckdns.org',
    basePath: '/',
  });

  it('names the single shortcut and the four dictionary keys its actions read', () => {
    assert.equal(recipe.name, 'Aero32 Bridge');
    assert.deepEqual(recipe.keys, { url: 'url', auth: 'auth', body: 'body', callback: 'callback' });
    assert.equal(recipe.contentType, 'application/json');
    assert.equal(recipe.actionCount, 8);
  });

  it('lists the endpoints the one bridge can reach, so the dialog does not lie about them', () => {
    assert.deepEqual(recipe.endpoints, {
      entrada: 'http://192.168.25.250:1453/api/entradasitem',
      saida: 'http://192.168.25.250:1453/api/saidasitem',
      movimentos: 'http://192.168.25.250:1453/api/movimentoitem',
      test: 'http://192.168.25.250:1453/api/usuarios',
    });
  });

  it('carries a ready envelope for the connection test, the one that needs no launch data', () => {
    const envelope = recipe.sampleEnvelope;
    assert.equal(envelope.url, 'http://192.168.25.250:1453/api/usuarios');
    assert.equal(envelope.headers.Authorization, basicAuthHeader('wilkin', 'senha'));
    assert.equal(
      envelope.callback,
      'https://trindademasas.duckdns.org/massas-launch?aero_action=test&aero_data=',
    );
    assert.deepEqual(JSON.parse(envelope.body), {});
  });
});

describe('aero-integration: ERP movements mirror', () => {
  const answer = {
    success: true,
    data: [
      {
        ENT_LAN: 10542,
        PRO_COD: '001192',
        PRO_DES: 'MASSA TRADICIONAL 500G',
        PRO_QTD: 90,
        ENT_DTA: '2026-10-28',
        ENT_USU: 'WILKIN',
        ENTSAI: 'E',
      },
      {
        ENT_LAN: 10543,
        PRO_COD: '005000',
        PRO_DES: 'MASSA ROLO 1KG',
        PRO_QTD: 45,
        ENT_DTA: '2026-10-28',
        ENT_USU: 'MARCOS',
        ENTSAI: 'E',
      },
    ],
  };

  it('asks for every operator of the plant when no operator is named', () => {
    for (const userName of [null, '', '   ', 'all', 'ALL']) {
      const payload = buildMovimentoItemPayload({ userName, startDate: '2026-10-28', entrySaida: 'E' });
      assert.equal(payload.NOMUSU, null, `expected NOMUSU null for ${JSON.stringify(userName)}`);
    }
  });

  it('still narrows to one operator when a name is named', () => {
    const payload = buildMovimentoItemPayload({ userName: ' wilkin ', startDate: '2026-10-28' });
    assert.equal(payload.NOMUSU, 'WILKIN');
  });

  it('reads the ERP answer into seats, one per ENT_LAN', () => {
    const result = readAeroMovements(answer);
    assert.equal(result.status, 'ok');
    if (result.status !== 'ok') return;

    assert.equal(result.skipped, 0);
    assert.deepEqual(result.movements, [
      {
        entLan: 10542,
        productCode: '001192',
        productDesc: 'MASSA TRADICIONAL 500G',
        quantity: 90,
        entryDate: '2026-10-28',
        operatorName: 'WILKIN',
        movementType: 'E',
      },
      {
        entLan: 10543,
        productCode: '005000',
        productDesc: 'MASSA ROLO 1KG',
        quantity: 45,
        entryDate: '2026-10-28',
        operatorName: 'MARCOS',
        movementType: 'E',
      },
    ]);
  });

  it('parses real factory seats with string quantities and datetime timestamps', () => {
    const factoryAnswer = {
      success: true,
      data: [
        {
          ENT_COD: 146461,
          PRO_QTD: '30.00',
          ENT_DTA: '2026-10-29 00:00:00',
          PRO_DES: 'MASSA PARA PASTEL DISCO 500G',
          ENT_SER: 'APL',
          ENT_DTANOT: '2026-10-29 00:00:00',
          ENT_TOT: '0.01',
          ENT_OBS: null,
          ENT_USU: 'VINICIUS R',
          PRO_COD: '000084',
          ENT_LAN: 35908,
          ENT_FOR: 1,
          ENTSAI: 'E',
        },
      ],
      message: 'Movimento encontrado com sucesso',
    };

    const result = readAeroMovements(factoryAnswer);
    assert.equal(result.status, 'ok');
    if (result.status !== 'ok') return;
    assert.equal(result.skipped, 0);
    assert.deepEqual(result.movements, [
      {
        entLan: 35908,
        productCode: '000084',
        productDesc: 'MASSA PARA PASTEL DISCO 500G',
        quantity: 30,
        entryDate: '2026-10-29',
        operatorName: 'VINICIUS R',
        movementType: 'E',
      },
    ]);
  });

  it('keeps the quantities the ERP answered in units, never scaled by a hundred', () => {
    const result = readAeroMovements(answer);
    assert.equal(result.status, 'ok');
    if (result.status !== 'ok') return;
    assert.deepEqual(
      result.movements.map((movement) => movement.quantity),
      [90, 45],
      'PRO_QTD already comes back human: scaling it again reports 100x the boxes the factory made',
    );
  });

  it('refuses an answer the ERP marked as failed, with the message it gave', () => {
    const result = readAeroMovements({ success: false, message: 'Usuario invalido' });
    assert.deepEqual(result, { status: 'error', message: 'Usuario invalido' });
  });

  it('refuses a body that is not a list of seats', () => {
    assert.deepEqual(readAeroMovements(null), { status: 'error', message: null });
    assert.deepEqual(readAeroMovements({ success: true, data: 'none' }), { status: 'error', message: null });
  });

  it('skips a seat it cannot describe and keeps the rest of the day', () => {
    const result = readAeroMovements({
      success: true,
      data: [
        answer.data[0],
        { ENT_LAN: 'AB-12', PRO_COD: '001192', PRO_DES: 'X', PRO_QTD: 1, ENT_DTA: '2026-10-28', ENT_USU: 'W', ENTSAI: 'E' },
        { ...answer.data[1], ENTSAI: 'X' },
      ],
    });

    assert.equal(result.status, 'ok');
    if (result.status !== 'ok') return;
    assert.equal(result.skipped, 2);
    assert.deepEqual(result.movements.map((movement) => movement.entLan), [10542]);
  });

  it('turns the seats into the snapshot the backend stores', () => {
    const result = readAeroMovements(answer);
    if (result.status !== 'ok') return assert.fail('expected the seats to be read');

    assert.deepEqual(buildAeroMovementsSnapshot('2026-10-28', result.movements), {
      date: '2026-10-28',
      movements: [
        {
          ent_lan: 10542,
          product_code: '001192',
          product_desc: 'MASSA TRADICIONAL 500G',
          quantity: 90,
          entry_date: '2026-10-28',
          operator_name: 'WILKIN',
          movement_type: 'E',
        },
        {
          ent_lan: 10543,
          product_code: '005000',
          product_desc: 'MASSA ROLO 1KG',
          quantity: 45,
          entry_date: '2026-10-28',
          operator_name: 'MARCOS',
          movement_type: 'E',
        },
      ],
    });
  });

  it('marks a seat reconciled only when Trindade also has that launch', () => {
    const result = readAeroMovements(answer);
    if (result.status !== 'ok') return assert.fail('expected the seats to be read');

    const reconciliation = reconcileAeroMovements(result.movements, [
      { id: 7, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'wilkin' },
      { id: 8, launch_date: '2026-10-28', product_code: '5000', units: 45, username: 'marcos' },
      { id: 9, launch_date: '2026-10-28', product_code: '1045', units: 30, username: 'wilkin' },
    ]);

    assert.deepEqual(reconciliation.reconciled, [10542, 10543]);
    assert.deepEqual(reconciliation.matchedLaunchIds, [7, 8]);
    assert.deepEqual(reconciliation.unmatchedLaunchIds, [9]);
  });

  it('matches the ERP code with or without its leading zeros, because the two sides pad differently', () => {
    const result = readAeroMovements(answer);
    if (result.status !== 'ok') return assert.fail('expected the seats to be read');

    const reconciliation = reconcileAeroMovements(result.movements, [
      { id: 3, launch_date: '2026-10-28', product_code: '001192', units: 90, username: 'WILKIN' },
    ]);

    assert.deepEqual(reconciliation.reconciled, [10542]);
  });

  it('never hands another operator\'s seat to a local launch, however equal the boxes look', () => {
    // Jefferson launched a rolo on the Delphi tablet and never opened Trindade. Wilkin launched the same
    // rolo in the app. Matching on date, code and quantity alone marked Jefferson as reconciled and left
    // Wilkin's own seat "only in the ERP", which is the opposite of what the plant needs to see.
    const seats = [
      { entLan: 35914, productCode: '005000', productDesc: 'MASSA ROLO 1KG', quantity: 15, entryDate: '2026-10-28', operatorName: 'JEFFERSON', movementType: 'E' as const },
      { entLan: 35916, productCode: '005000', productDesc: 'MASSA ROLO 1KG', quantity: 15, entryDate: '2026-10-28', operatorName: 'WILKIN', movementType: 'E' as const },
    ];
    const wilkinLaunch = [
      { id: 21, launch_date: '2026-10-28', product_code: '5000', units: 15, username: 'wilkin' },
    ];

    const reconciliation = reconcileAeroMovements(seats, wilkinLaunch);

    assert.deepEqual(reconciliation.reconciled, [35916]);
    assert.deepEqual(reconciliation.matchedLaunchIds, [21]);
  });

  it('leaves every seat unmatched when no local launch belongs to that operator', () => {
    const seats = [
      { entLan: 35914, productCode: '005000', productDesc: 'MASSA ROLO 1KG', quantity: 15, entryDate: '2026-10-28', operatorName: 'JEFFERSON', movementType: 'E' as const },
    ];
    const reconciliation = reconcileAeroMovements(seats, [
      { id: 21, launch_date: '2026-10-28', product_code: '5000', units: 15, username: 'wilkin' },
    ]);

    assert.deepEqual(reconciliation.reconciled, []);
    assert.deepEqual(reconciliation.matchedLaunchIds, []);
    assert.deepEqual(reconciliation.unmatchedLaunchIds, [21]);
  });

  it('compares the operator the way both sides write it: padded, cased and trimmed differently', () => {
    const seats = [
      { entLan: 1, productCode: '001192', productDesc: 'X', quantity: 90, entryDate: '2026-10-28', operatorName: '  wilkin ', movementType: 'E' as const },
    ];
    const reconciliation = reconcileAeroMovements(seats, [
      { id: 2, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'WILKIN' },
    ]);

    assert.deepEqual(reconciliation.reconciled, [1]);
  });

  it('consumes one local launch per seat, so two equal seats need two local launches', () => {
    const seats = [
      { entLan: 1, productCode: '001192', productDesc: 'X', quantity: 90, entryDate: '2026-10-28', operatorName: 'A', movementType: 'E' as const },
      { entLan: 2, productCode: '001192', productDesc: 'X', quantity: 90, entryDate: '2026-10-28', operatorName: 'B', movementType: 'E' as const },
    ];

    const oneLocal = reconcileAeroMovements(seats, [
      { id: 4, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'a' },
    ]);
    assert.deepEqual(oneLocal.reconciled, [1]);
    assert.deepEqual(oneLocal.matchedLaunchIds, [4]);

    const twoLocal = reconcileAeroMovements(seats, [
      { id: 4, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'a' },
      { id: 5, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'b' },
    ]);
    assert.deepEqual(twoLocal.reconciled, [1, 2]);
  });

  it('never reconciles a stock exit against a Trindade launch, because Trindade only records entries', () => {
    const seats = [
      { entLan: 9, productCode: '001192', productDesc: 'X', quantity: 90, entryDate: '2026-10-28', operatorName: 'A', movementType: 'S' as const },
    ];

    const reconciliation = reconcileAeroMovements(seats, [
      { id: 4, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'a' },
    ]);

    assert.deepEqual(reconciliation.reconciled, []);
    assert.deepEqual(reconciliation.unmatchedLaunchIds, [4]);
  });

  it('does not reconcile across days', () => {
    const seats = [
      { entLan: 9, productCode: '001192', productDesc: 'X', quantity: 90, entryDate: '2026-10-27', operatorName: 'A', movementType: 'E' as const },
    ];

    const reconciliation = reconcileAeroMovements(seats, [
      { id: 4, launch_date: '2026-10-28', product_code: '1192', units: 90, username: 'a' },
    ]);

    assert.deepEqual(reconciliation.reconciled, []);
  });
});
