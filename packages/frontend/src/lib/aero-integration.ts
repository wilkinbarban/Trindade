/**
 * Pure client module for Aero32 company WebService integration.
 *
 * Keeps device-local settings, request envelopes, payload builders, and iOS shortcut triggers
 * separate from React and UI layers.
 */

export const AERO_SETTINGS_STORAGE_KEY = 'trindade.aero.integration.v1';
export const AERO_PENDING_SYNC_STORAGE_KEY = 'trindade.aero.pending-sync.v1';
export const AERO_INTEGRATION_USERNAME = 'wilkin';
export const AERO_DEFAULT_SHORTCUT_NAME = 'Aero32 Estoque';
export const AERO_TEST_TIMEOUT_MS = 8000;

/**
 * The entry series the ERP expects, and the only one it will ever get.
 *
 * `APL` is burnt into the Delphi binary of `base.apk`, so a series is not a decision the operator makes --
 * it is a fact about the company's system. Keeping it here, out of the settings, is what lets the panel ask
 * for three things instead of five.
 */
export const AERO_ENTRY_SERIES = 'APL';

/** Launches never travel per box: the factory counts boxes, the ERP receives units. */
export const AERO_QUANTITY_UNIT = 'UN';

export interface AeroSettings {
  wsUrl: string;
  username: string;
  password: string;
  userCode: number | null;
  shortcutName: string;
  syncEnabled: boolean;
  lastSyncAt: string | null;
}

export const DEFAULT_AERO_SETTINGS: AeroSettings = {
  wsUrl: '',
  username: '',
  password: '',
  userCode: null,
  shortcutName: AERO_DEFAULT_SHORTCUT_NAME,
  syncEnabled: false,
  lastSyncAt: null,
};

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/**
 * Access control flag for the Aero32 integration.
 * Strict business rule: exclusive to user 'wilkin' with 'Administrador' role.
 */
export function isAeroAllowed(
  user: { username?: string | null; role?: string | null } | null | undefined,
): boolean {
  return Boolean(user && user.username === AERO_INTEGRATION_USERNAME && user.role === 'Administrador');
}

export const isAeroIntegrationOwner = isAeroAllowed;

/**
 * Loads company WebService settings from local storage, merging with defaults.
 */
export function loadAeroSettings(storage?: Pick<Storage, 'getItem'> | StorageLike | null): AeroSettings {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (!target) return { ...DEFAULT_AERO_SETTINGS };

  try {
    const raw = target.getItem(AERO_SETTINGS_STORAGE_KEY);
    if (!raw) return { ...DEFAULT_AERO_SETTINGS };

    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ...DEFAULT_AERO_SETTINGS };
    }

    const userCode =
      typeof parsed.userCode === 'number' && Number.isInteger(parsed.userCode) && parsed.userCode > 0
        ? parsed.userCode
        : null;

    return {
      wsUrl: typeof parsed.wsUrl === 'string' ? parsed.wsUrl : DEFAULT_AERO_SETTINGS.wsUrl,
      username: typeof parsed.username === 'string' ? parsed.username : DEFAULT_AERO_SETTINGS.username,
      password: typeof parsed.password === 'string' ? parsed.password : DEFAULT_AERO_SETTINGS.password,
      userCode,
      shortcutName:
        typeof parsed.shortcutName === 'string' && parsed.shortcutName.trim()
          ? parsed.shortcutName
          : DEFAULT_AERO_SETTINGS.shortcutName,
      syncEnabled:
        typeof parsed.syncEnabled === 'boolean' ? parsed.syncEnabled : DEFAULT_AERO_SETTINGS.syncEnabled,
      lastSyncAt: typeof parsed.lastSyncAt === 'string' ? parsed.lastSyncAt : null,
    };
  } catch {
    return { ...DEFAULT_AERO_SETTINGS };
  }
}

/**
 * Persists company WebService settings to local storage.
 */
export function saveAeroSettings(
  settings: AeroSettings,
  storage?: Pick<Storage, 'setItem'> | StorageLike | null,
): AeroSettings {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.setItem(AERO_SETTINGS_STORAGE_KEY, JSON.stringify(settings));
  }
  return settings;
}

/**
 * Removes company WebService settings from local storage.
 */
export function clearAeroSettings(storage?: Pick<Storage, 'removeItem'> | StorageLike | null): void {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.removeItem(AERO_SETTINGS_STORAGE_KEY);
  }
}

/**
 * Indicates whether the settings contain all minimum parameters needed for ERP synchronization.
 */
export function aeroSettingsReady(settings: AeroSettings): boolean {
  if (!settings) return false;
  const normalized = normalizeAeroWsUrl(settings.wsUrl ?? '');
  if (!normalized) return false;
  if (!settings.username || !settings.username.trim()) return false;
  if (!settings.password) return false;
  if (typeof settings.userCode !== 'number' || !Number.isInteger(settings.userCode) || settings.userCode <= 0) {
    return false;
  }
  return true;
}

/**
 * Validates and normalizes a WebService base URL:
 * - Trims whitespace
 * - Requires http:// or https:// with a valid host
 * - Collapses trailing slashes
 * - Appends /api when the URL carries no path
 */
export function normalizeAeroWsUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return null;
    }
    if (!parsed.hostname) {
      return null;
    }
    let pathname = parsed.pathname.replace(/\/+$/, '');
    if (!pathname || pathname === '') {
      pathname = '/api';
    }
    return `${parsed.origin}${pathname}`;
  } catch {
    return null;
  }
}

/**
 * Joins a WebService base URL and an endpoint path with exactly one slash,
 * preserving any trailing slash in the path.
 */
export function aeroEndpoint(wsUrl: string, path: string): string {
  const cleanBase = wsUrl.replace(/\/+$/, '');
  const cleanPath = path.replace(/^\/+/, '');
  return `${cleanBase}/${cleanPath}`;
}

/**
 * Generates an HTTP Basic Authentication header value supporting UTF-8 credentials.
 */
export function basicAuthHeader(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join('');
  return `Basic ${btoa(binary)}`;
}

/**
 * Detects browser-level network blockers before spending a request:
 * - mixed_content: HTTPS page calling plain HTTP WebService
 * - insecure_target: protocol other than HTTP/HTTPS
 */
export function aeroConnectionBlocker(args: {
  pageProtocol: string;
  wsUrl: string;
}): 'mixed_content' | 'insecure_target' | null {
  const pageProto = args.pageProtocol.toLowerCase().replace(/:$/, '');
  let targetProto: string | null = null;
  try {
    targetProto = new URL(args.wsUrl).protocol.replace(/:$/, '');
  } catch {
    const match = args.wsUrl.match(/^([a-zA-Z][a-zA-Z0-9+.-]*):/);
    targetProto = match ? match[1].toLowerCase() : null;
  }

  if (targetProto !== 'http' && targetProto !== 'https') {
    return 'insecure_target';
  }

  if (pageProto === 'https' && targetProto === 'http') {
    return 'mixed_content';
  }

  return null;
}

/**
 * Interprets the company WebService response from /usuarios/ authentication test.
 * Never throws regardless of response format.
 */
export function readAeroTestResult(
  body: unknown,
): { ok: true; userCode: number; userName: string } | { ok: false; message: string } {
  if (typeof body !== 'object' || body === null) {
    return { ok: false, message: 'Invalid response body' };
  }

  const record = body as Record<string, unknown>;

  if ('success' in record && record.success === false) {
    const msg =
      typeof record.message === 'string' && record.message.trim()
        ? record.message.trim()
        : 'Authentication failed';
    return { ok: false, message: msg };
  }

  if (!('data' in record) || typeof record.data !== 'object' || record.data === null) {
    return { ok: false, message: 'Missing data in response' };
  }

  const data = record.data as Record<string, unknown>;
  const rawCode = data.CODIGO;
  const userCode = typeof rawCode === 'number' && Number.isInteger(rawCode) && rawCode > 0 ? rawCode : null;

  if (userCode === null) {
    return { ok: false, message: 'Missing or invalid user code (CODIGO)' };
  }

  const userName = typeof data.NOME === 'string' ? data.NOME.trim() : '';

  return {
    ok: true,
    userCode,
    userName,
  };
}

export interface AeroEntryPayload {
  PRO_COD: string;
  PRO_DES: string;
  PRO_QTD: number;
  ENT_DTA: string;
  ENT_USU: number;
  ENT_SER: string;
  ENT_DTANOT: string;
  ENT_DTMOVI: string;
  ENT_OBS: string;
  LOTES: unknown[];
}

/**
 * Constructs the exact JSON payload expected by POST /entradasitem in Aero32 ERP.
 * Property order matches docs/registro-integracion-aero-webservice.md §2.3.
 */
export function buildEntradaItemPayload(args: {
  productCode: string;
  productName: string;
  quantity: number;
  entryDate: Date | string;
  userCode: number;
  /** Only for a caller that must quote a series: the ERP entry always carries {@link AERO_ENTRY_SERIES}. */
  entrySeries?: string;
  observation?: string | null;
}): AeroEntryPayload {
  const dateStr =
    args.entryDate instanceof Date
      ? args.entryDate.toISOString().slice(0, 10)
      : String(args.entryDate).trim().slice(0, 10);
  const obs =
    args.observation && args.observation.trim()
      ? args.observation.trim()
      : 'Lancamento de massas via Trindade';

  return {
    PRO_COD: args.productCode,
    PRO_DES: args.productName,
    PRO_QTD: args.quantity,
    ENT_DTA: dateStr,
    ENT_USU: args.userCode,
    ENT_SER: args.entrySeries ?? AERO_ENTRY_SERIES,
    ENT_DTANOT: dateStr,
    ENT_DTMOVI: dateStr,
    ENT_OBS: obs,
    LOTES: [],
  };
}

/**
 * Adapts the day's box count into the units the ERP records.
 *
 * There is no per-box launch to choose from: five boxes of ten units are fifty units on the company's stock,
 * and the series is the one the binary already expects.
 */
export function buildAeroEntryPayload(args: {
  productCode: string;
  productName: string;
  boxes: number;
  unitsPerBox: number;
  date: string;
  userCode: number;
  observation?: string;
}): AeroEntryPayload {
  return buildEntradaItemPayload({
    productCode: args.productCode,
    productName: args.productName,
    quantity: args.boxes * args.unitsPerBox,
    entryDate: args.date,
    userCode: args.userCode,
    observation: args.observation,
  });
}

export interface AeroShortcutEnvelope {
  url: string;
  authorization: string;
  body: string;
}

/**
 * Assembles the transmission envelope for iOS Shortcut execution.
 */
export function aeroLaunchEnvelope(args: {
  wsUrl: string;
  username: string;
  password: string;
  payload: unknown;
}): AeroShortcutEnvelope {
  return {
    url: aeroEndpoint(args.wsUrl, 'entradasitem'),
    authorization: basicAuthHeader(args.username, args.password),
    body: JSON.stringify(args.payload),
  };
}

export function buildAeroShortcutEnvelope(args: {
  settings: AeroSettings;
  payload: AeroEntryPayload;
}): AeroShortcutEnvelope {
  return aeroLaunchEnvelope({
    wsUrl: args.settings.wsUrl,
    username: args.settings.username,
    password: args.settings.password,
    payload: args.payload,
  });
}

/**
 * Builds the deep link triggering the Apple Shortcuts app with the encoded envelope.
 */
export function aeroShortcutTriggerUrl(args: {
  name: string;
  envelope: AeroShortcutEnvelope;
}): string {
  const envelopeJson = JSON.stringify(args.envelope);
  return `shortcuts://x-callback-url/run-shortcut?name=${encodeURIComponent(args.name)}&input=text&text=${encodeURIComponent(envelopeJson)}`;
}

export function aeroShortcutUrl(envelope: AeroShortcutEnvelope, shortcutName: string): string {
  return aeroShortcutTriggerUrl({ name: shortcutName, envelope });
}

/**
 * Builds the URL the iOS shortcut opens upon completing execution.
 */
export function aeroCallbackUrl(args: {
  origin: string;
  basePath?: string;
  status: 'ok' | 'error';
  message?: string | null;
  launchId?: number | null;
}): string {
  const cleanOrigin = args.origin.replace(/\/+$/, '');
  const rawBase = args.basePath ?? '';
  const cleanBase = !rawBase || rawBase === '/' ? '' : `/${rawBase.replace(/^\/+|\/+$/g, '')}`;
  const path = `${cleanOrigin}${cleanBase}/massas-launch`;
  const params: string[] = [`aero_sync=${encodeURIComponent(args.status)}`];
  if (args.message !== undefined && args.message !== null) {
    params.push(`aero_msg=${encodeURIComponent(args.message)}`);
  }
  if (args.launchId !== undefined && args.launchId !== null) {
    params.push(`aero_launch=${encodeURIComponent(String(args.launchId))}`);
  }
  return `${path}?${params.join('&')}`;
}

/**
 * Parses query parameters returning from the iOS shortcut callback.
 */
export function parseAeroCallback(
  search: string,
): { status: 'ok' | 'error'; message: string | null } | null {
  if (!search) return null;
  const queryString = search.startsWith('?') ? search.slice(1) : search;
  if (!queryString) return null;
  const params = new URLSearchParams(queryString);
  const status = params.get('aero_sync');
  if (status !== 'ok' && status !== 'error') {
    return null;
  }
  const message = params.get('aero_msg');
  return {
    status,
    message,
  };
}

export interface AeroPendingSync {
  launchIds: number[];
  date: string;
  requestedAt: string;
  destinationUrl: string;
}

/**
 * Persists pending synchronization state during iOS shortcut round-trips.
 */
export function saveAeroPendingSync(
  pending: AeroPendingSync,
  storage?: StorageLike | Pick<Storage, 'setItem'> | null,
): void {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.setItem(AERO_PENDING_SYNC_STORAGE_KEY, JSON.stringify(pending));
  }
}

/**
 * Restores pending synchronization state, sanitizing malformed entries.
 */
export function loadAeroPendingSync(
  storage?: StorageLike | Pick<Storage, 'getItem'> | null,
): AeroPendingSync | null {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (!target) return null;
  const raw = target.getItem(AERO_PENDING_SYNC_STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    if (
      !Array.isArray(parsed.launchIds) ||
      !parsed.launchIds.every((id: unknown) => typeof id === 'number')
    ) {
      return null;
    }
    if (typeof parsed.date !== 'string' || !parsed.date) return null;
    if (typeof parsed.requestedAt !== 'string' || !parsed.requestedAt) return null;
    if (typeof parsed.destinationUrl !== 'string' || !parsed.destinationUrl) return null;
    return {
      launchIds: parsed.launchIds,
      date: parsed.date,
      requestedAt: parsed.requestedAt,
      destinationUrl: parsed.destinationUrl,
    };
  } catch {
    return null;
  }
}

/**
 * Clears pending synchronization state.
 */
export function clearAeroPendingSync(
  storage?: StorageLike | Pick<Storage, 'removeItem'> | null,
): void {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.removeItem(AERO_PENDING_SYNC_STORAGE_KEY);
  }
}
