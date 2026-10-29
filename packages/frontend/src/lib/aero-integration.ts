/**
 * Pure client module for Aero32 company WebService integration.
 *
 * Keeps device-local settings, request envelopes, payload builders, and iOS shortcut triggers
 * separate from React and UI layers.
 */

import { aeroTestVerdictFromToken, type AeroTestVerdict } from './aero-test';

export const AERO_SETTINGS_STORAGE_KEY = 'trindade.aero.integration.v1';
export const AERO_PENDING_SYNC_STORAGE_KEY = 'trindade.aero.pending-sync.v1';
export const AERO_INTEGRATION_USERNAME = 'wilkin';

/**
 * The one shortcut: a blind HTTP proxy that posts whatever envelope the page hands it.
 *
 * The factory used to need two shortcuts -- one for the entry, one that only proved the connection -- and the
 * person installing them had to build each recipe by hand. One bridge that carries `{ url, auth, body,
 * callback }` covers the entry, the stock exit, the movement query and the connection test, so only one thing
 * is ever installed and the iPhone never has to be edited again when a credential or an address changes.
 *
 * It exists at all because the browser cannot reach a plain-HTTP WebService from an HTTPS page: the bridge is
 * the only instrument on the phone that can, and the envelope is what lets it stay ignorant of the business.
 */
export const AERO_DEFAULT_BRIDGE_SHORTCUT_NAME = 'Aero32 Bridge';

/**
 * The name the split design used for the launch shortcut, kept only as the key of the settings migration.
 *
 * A phone that stored it gets {`Aero32 Bridge` instead, because that shortcut no
 * longer exists in the factory's phone.
 */
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

/**
 * The series a stock exit carries, and the only one the ERP will ever get.
 *
 * `PED_SER` is `APL` in the Delphi binary of `base.apk` for the same reason as the entry series: it is a fact
 * about the company's system, not a decision the operator makes at the counter.
 */
export const AERO_EXIT_SERIES = 'APL';

/** Launches never travel per box: the factory counts boxes, the ERP receives units. */
export const AERO_QUANTITY_UNIT = 'UN';

/**
 * Default Tax Situation Code (Código de Situação Tributária - CST).
 *
 * Trigger ENTRADADET_BIU_SPED_VERIFICA in Firebird ERP requires a non-empty CST
 * ('EXC_CST Favor informar a CST do Item'). For manufactured food products, '000'
 * (Tributada integralmente) is the standard default.
 */
export const AERO_DEFAULT_CST = '000';

/**
 * The user code an entry carries when nobody captured the real one.
 *
 * `ENT_USU` is the ERP's numeric operator id, and the only place it can be read is `/usuarios/`, which the
 * browser cannot reach from an HTTPS page: Safari refuses the plain-HTTP target before a packet leaves.
 * So over production the code is unknown by design, and refusing to launch because of it would leave the
 * factory without stock sync on every phone. The entry goes as code 1 and the audit log records the fact.
 */
export const AERO_FALLBACK_USER_CODE = 1;

export interface AeroSettings {
  wsUrl: string;
  username: string;
  password: string;
  shortcutName: string;
  syncEnabled: boolean;
  lastSyncAt: string | null;
}

export const DEFAULT_AERO_SETTINGS: AeroSettings = {
  wsUrl: '',
  username: '',
  password: '',
  shortcutName: AERO_DEFAULT_BRIDGE_SHORTCUT_NAME,
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
 * Resolves a stored shortcut name, migrating the ones the split design wrote.
 *
 * The page used to trigger two shortcuts that the factory no longer has on its phone. A stored name that is
 * exactly one of those legacy defaults is not a choice the operator made, so it becomes the bridge; any other
 * name was typed on purpose and survives verbatim.
 */
function aeroShortcutName(parsed: unknown, legacyDefault: string): string {
  const name = typeof parsed === 'string' && parsed.trim() ? parsed.trim() : '';
  if (!name || name === legacyDefault) return AERO_DEFAULT_BRIDGE_SHORTCUT_NAME;
  return name;
}

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

    return {
      wsUrl: typeof parsed.wsUrl === 'string' ? parsed.wsUrl : DEFAULT_AERO_SETTINGS.wsUrl,
      username: typeof parsed.username === 'string' ? parsed.username : DEFAULT_AERO_SETTINGS.username,
      password: typeof parsed.password === 'string' ? parsed.password : DEFAULT_AERO_SETTINGS.password,
      shortcutName: aeroShortcutName(parsed.shortcutName, AERO_DEFAULT_SHORTCUT_NAME),
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
 * Indicates whether the settings contain everything the operator is responsible for.
 *
 * Deliberately three fields: where the ERP is, and who it is. Nothing else is asked of a person at the counter,
 * and no value the ERP reveals gates the switch -- the identity travels as the username in uppercase, which is
 * what `ENT_USU` carries in the Delphi binary.
 */
export function aeroSettingsReady(settings: AeroSettings): boolean {
  if (!settings) return false;
  const normalized = normalizeAeroWsUrl(settings.wsUrl ?? '');
  if (!normalized) return false;
  if (!settings.username || !settings.username.trim()) return false;
  if (!settings.password) return false;
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

  if (record.success === true) {
    if ('data' in record && typeof record.data === 'object' && record.data !== null) {
      const data = record.data as Record<string, unknown>;
      const rawCode = data.CODIGO;
      const userCode =
        typeof rawCode === 'number' && Number.isInteger(rawCode) && rawCode > 0
          ? rawCode
          : AERO_FALLBACK_USER_CODE;
      const userName =
        typeof data.NOME === 'string' && data.NOME.trim()
          ? data.NOME.trim()
          : typeof record.message === 'string' && record.message.trim()
            ? record.message.trim()
            : 'WILKIN';
      return { ok: true, userCode, userName };
    }
    const userName =
      typeof record.message === 'string' && record.message.trim()
        ? record.message.trim()
        : 'WILKIN';
    return { ok: true, userCode: AERO_FALLBACK_USER_CODE, userName };
  }

  return { ok: false, message: 'Missing success indicator in response' };
}

export interface AeroEntryPayload {
  PRO_COD: string;
  PRO_DES: string;
  PRO_QTD: number;
  PRO_CST: string;
  ENT_DTA: string;
  ENT_USU: number | string;
  ENT_SER: string;
  ENT_DTANOT: string;
  ENT_DTMOVI: string;
  ENT_OBS: string;
  LOTES: unknown[];
}

/**
 * Scales units for Aero32 / Firebird ERP stock entry.
 * Firebird ERP expects the integer value scaled by 100 (e.g. 90 units = 9000, 15 units = 1500),
 * otherwise it records as 0.90 or 0.15 in the database.
 */
export function formatAeroQuantity(quantity: number | null | undefined): number {
  if (typeof quantity !== 'number' || !Number.isFinite(quantity)) {
    return 0;
  }
  return Math.round(quantity * 100);
}

/**
 * Renders a date as the `YYYY-MM-DD` the ERP reads, in one place.
 *
 * A `Date` is formatted in UTC by `toISOString`, which is what the launch already sends; a string is kept as
 * written, trimmed to the date part so an ISO timestamp never leaks its time into the payload.
 */
function aeroDateOnly(value: Date | string): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).trim().slice(0, 10);
}

/**
 * Formats CST item code for Aero32 / Firebird ERP. Defaults to '000' (Tributada integralmente).
 */
export function formatAeroCst(cst: string | null | undefined): string {
  return cst && cst.trim() ? cst.trim() : AERO_DEFAULT_CST;
}

/**
 * Resolves the operator identity (numeric code or username string) an entry carries.
 * Matches base.apk which passes txtUsuario.Text directly into ENT_USU.
 */
export function aeroUserCode(userCode: string | number | null | undefined): number | string {
  if (typeof userCode === 'number' && Number.isInteger(userCode) && userCode > 0) {
    return userCode;
  }
  if (typeof userCode === 'string') {
    const trimmed = userCode.trim();
    if (trimmed) {
      const parsed = Number.parseInt(trimmed, 10);
      if (Number.isInteger(parsed) && String(parsed) === trimmed && parsed > 0) {
        return parsed;
      }
      return trimmed.toUpperCase();
    }
  }
  return AERO_FALLBACK_USER_CODE;
}

/**
 * Formats a product code to 6 characters with leading zeros, matching Aero32 ERP format.
 * The Delphi client in base.apk explicitly applies StringOfChar('0', 6 - Length(PRO_COD)) + PRO_COD
 * so Firebird triggers can resolve the item in the PRODUTO table.
 */
export function formatAeroProductCode(code: string | number | null | undefined): string {
  const clean = String(code ?? '').trim();
  if (!clean) return '';
  return clean.length < 6 ? clean.padStart(6, '0') : clean;
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
  /** The ERP operator id or username; anything unusable falls back to {@link AERO_FALLBACK_USER_CODE}. */
  userCode?: string | number | null;
  /** Only for a caller that must quote a series: the ERP entry always carries {@link AERO_ENTRY_SERIES}. */
  entrySeries?: string;
  observation?: string | null;
  /** Tax situation code (CST). Defaults to {@link AERO_DEFAULT_CST} ('000'). */
  productCst?: string | null;
}): AeroEntryPayload {
  const dateStr = aeroDateOnly(args.entryDate);
  const obs =
    args.observation && args.observation.trim()
      ? args.observation.trim()
      : 'Lancamento de massas via Trindade';

  return {
    PRO_COD: formatAeroProductCode(args.productCode),
    PRO_DES: args.productName,
    PRO_QTD: formatAeroQuantity(args.quantity),
    PRO_CST: formatAeroCst(args.productCst),
    ENT_DTA: dateStr,
    ENT_USU: aeroUserCode(args.userCode),
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
  userCode?: string | number | null;
  observation?: string;
  productCst?: string | null;
}): AeroEntryPayload {
  return buildEntradaItemPayload({
    productCode: args.productCode,
    productName: args.productName,
    quantity: args.boxes * args.unitsPerBox,
    entryDate: args.date,
    userCode: args.userCode,
    observation: args.observation,
    productCst: args.productCst,
  });
}

export interface AeroSaidaPayload {
  PRO_COD: string;
  PRO_DES: string;
  PRO_QTD: number;
  PED_DTA: string;
  PED_USU: number | string;
  PED_SER: string;
  PED_DTANOT: string;
  PED_OBS: string;
  LOTES: unknown[];
}

/**
 * Constructs the exact JSON payload expected by POST /saidasitem in Aero32 ERP.
 *
 * Property order matches docs/registro-integracion-aero-webservice.md §2.4, which was recovered from
 * `libso.so` and confirmed against the Delphi client. A stock exit is not a mirror of the entry: the header
 * fields carry `PED_*` prefixes, there is no `PED_DTMOVI`, and the item carries no `PRO_CST`, because the
 * Firebird trigger that demands the CST hangs off the entry detail table and not off the order.
 */
export function buildSaidaItemPayload(args: {
  productCode: string;
  productName: string;
  quantity: number;
  exitDate: Date | string;
  /** The ERP operator id or username; anything unusable falls back to {@link AERO_FALLBACK_USER_CODE}. */
  userCode?: string | number | null;
  /** Only for a caller that must quote a series: the ERP exit always carries {@link AERO_EXIT_SERIES}. */
  exitSeries?: string;
  observation?: string | null;
}): AeroSaidaPayload {
  const dateStr = aeroDateOnly(args.exitDate);
  const obs =
    args.observation && args.observation.trim()
      ? args.observation.trim()
      : 'Saida de massas via Trindade';

  return {
    PRO_COD: formatAeroProductCode(args.productCode),
    PRO_DES: args.productName,
    PRO_QTD: formatAeroQuantity(args.quantity),
    PED_DTA: dateStr,
    PED_USU: aeroUserCode(args.userCode),
    PED_SER: args.exitSeries ?? AERO_EXIT_SERIES,
    PED_DTANOT: dateStr,
    PED_OBS: obs,
    LOTES: [],
  };
}

export interface AeroMovimentoPayload {
  CODFOR: null;
  /** The operator to filter by, or `null` to ask the plant for everybody's movements. */
  NOMUSU: string | null;
  CODPRO: string | null;
  DTINI: string;
  DTFIM: string;
  ENTSAI: 'E' | 'S' | null;
}

/**
 * Constructs the exact JSON payload expected by POST /movimentoitem in Aero32 ERP.
 *
 * Property order matches docs/registro-integracion-aero-webservice.md §2.5. The query is the ERP's own answer
 * to "what did this operator actually record?", so the filters it leaves open it leaves explicitly null rather
 * than absent: `CODFOR` is always null, `CODPRO` is null when every product is asked for, and `ENTSAI` is null
 * when both sides of the movement are wanted.
 */
export function buildMovimentoItemPayload(args: {
  /** An operator name, or `null`/`'all'`/blank to ask for the whole plant. */
  userName: string | null | undefined;
  startDate: Date | string;
  endDate?: Date | string | null;
  productCode?: string | number | null;
  entrySaida?: 'E' | 'S' | null;
}): AeroMovimentoPayload {
  const start = aeroDateOnly(args.startDate);
  const code = args.productCode === undefined || args.productCode === null ? '' : String(args.productCode).trim();

  return {
    CODFOR: null,
    NOMUSU: aeroMovementUserName(args.userName),
    CODPRO: code ? formatAeroProductCode(code) : null,
    DTINI: start,
    DTFIM: args.endDate === undefined || args.endDate === null ? start : aeroDateOnly(args.endDate),
    ENTSAI: args.entrySaida ?? null,
  };
}

/**
 * Resolves the operator filter for a movements query.
 *
 * The ERP asks in uppercase and answers every operator when the filter is null. A caller that wants the
 * whole plant may say so with `null`, with `'all'`, or by leaving the field blank; anything else is one
 * operator's name.
 */
function aeroMovementUserName(userName: string | null | undefined): string | null {
  const name = String(userName ?? '').trim();
  if (!name || name.toLowerCase() === 'all') return null;
  return name.toUpperCase();
}

/**
 * One movement seat as the company ERP answered it, already read into the names this side uses.
 *
 * `entLan` is `ENT_LAN`, the seat's own identifier in Firebird, and it is the key everything reconciles on:
 * it is unique per day and Trindade never invents one.
 */
export interface AeroErpMovementItem {
  entLan: number;
  /** The ERP's six-digit code, kept as the company wrote it. */
  productCode: string;
  productDesc: string;
  /** In units, human: the ERP answers `PRO_QTD` already unscaled. */
  quantity: number;
  /** The day the factory did it, `YYYY-MM-DD` in São Paulo. */
  entryDate: string;
  /** `ENT_USU`, uppercase, as the ERP records the operator. */
  operatorName: string;
  movementType: 'E' | 'S';
}

/**
 * What came back from the movements query: the seats, or the reason there are none.
 *
 * `skipped` counts seats the ERP sent that this module could not describe. They are dropped instead of
 * throwing, because one odd seat must not hide the rest of the day; the page shows the count so a factory
 * with a serial printing something new notices.
 */
export type AeroMovementsReadResult =
  | { status: 'ok'; movements: AeroErpMovementItem[]; skipped: number }
  | { status: 'error'; message: string | null };

function aeroMovementText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function aeroMovementDate(value: unknown): string | null {
  const text = aeroMovementText(value);
  if (!text) return null;
  const match = /^(\d{4}-\d{2}-\d{2})/.exec(text);
  return match ? match[1] : null;
}

/**
 * Reads one seat, or refuses it.
 *
 * Strict on purpose: the seat is the company's record, and a shape this module half-understands is worse
 * than an absent one -- a seat with a made-up quantity reconciles against the wrong launch.
 */
function readAeroMovementSeat(raw: unknown): AeroErpMovementItem | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const record = raw as Record<string, unknown>;

  const rawLan = record.ENT_LAN;
  const entLan = typeof rawLan === 'number' ? rawLan : Number(rawLan);
  const rawQtd = record.PRO_QTD;
  const quantity = typeof rawQtd === 'number' ? rawQtd : parseFloat(String(rawQtd));
  const productCode = aeroMovementText(record.PRO_COD);
  const productDesc = aeroMovementText(record.PRO_DES);
  const entryDate = aeroMovementDate(record.ENT_DTA);
  const operatorName = aeroMovementText(record.ENT_USU);
  const movementType = record.ENTSAI;

  if (!Number.isInteger(entLan) || entLan <= 0) return null;
  if (typeof quantity !== 'number' || !Number.isFinite(quantity) || quantity < 0) return null;
  if (!productCode || !productDesc || !entryDate || !operatorName) return null;
  if (movementType !== 'E' && movementType !== 'S') return null;

  return {
    entLan,
    productCode,
    productDesc,
    quantity,
    entryDate,
    operatorName: operatorName.toUpperCase(),
    movementType,
  };
}

/**
 * Reads the ERP's answer to `POST /movimentoitem`.
 *
 * The answer is `{ success, data: [seats] }` and `success` is what decides: a body that fails is an error
 * even when it carries seats, exactly like the stock writes. A `data` that is not a list is an error too,
 * but a list whose seats are partly unreadable is still the day -- those seats are counted in `skipped`.
 */
export function readAeroMovements(data: unknown): AeroMovementsReadResult {
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    return { status: 'error', message: null };
  }
  const record = data as Record<string, unknown>;
  const message = aeroBridgeMessage(record);

  if (record.success !== true) {
    return { status: 'error', message };
  }
  if (!Array.isArray(record.data)) {
    return { status: 'error', message };
  }

  const movements: AeroErpMovementItem[] = [];
  let skipped = 0;
  for (const raw of record.data) {
    const seat = readAeroMovementSeat(raw);
    if (seat) movements.push(seat);
    else skipped += 1;
  }

  return { status: 'ok', movements, skipped };
}

/** The snapshot body the backend stores for one day, in the names its route validates. */
export interface AeroMovementsSnapshot {
  date: string;
  movements: Array<{
    ent_lan: number;
    product_code: string;
    product_desc: string;
    quantity: number;
    entry_date: string;
    operator_name: string;
    movement_type: 'E' | 'S';
  }>;
}

export function buildAeroMovementsSnapshot(
  date: string,
  movements: readonly AeroErpMovementItem[],
): AeroMovementsSnapshot {
  return {
    date: aeroDateOnly(date),
    movements: movements.map((movement) => ({
      ent_lan: movement.entLan,
      product_code: movement.productCode,
      product_desc: movement.productDesc,
      quantity: movement.quantity,
      entry_date: movement.entryDate,
      operator_name: movement.operatorName,
      movement_type: movement.movementType,
    })),
  };
}

/** The parts of a Trindade launch the mirror compares, so the page can pass its own rows. */
export interface AeroLocalLaunchLike {
  id: number;
  launch_date: string;
  product_code: string;
  /** The launch in units: the ERP records units, and boxes are only how the operator counted them. */
  units: number;
}

export interface AeroMovementReconciliation {
  /** `ENT_LAN` of the seats Trindade also has. */
  reconciled: number[];
  /** Local launches already present in the ERP. */
  matchedLaunchIds: number[];
  /** Local launches the ERP did not report, so the page can offer to resend them. */
  unmatchedLaunchIds: number[];
}

/**
 * Compares the ERP seats against the local launches of the day.
 *
 * Two sides describe the same boxes with different padding and different rounding, so the code is compared
 * without its leading zeros (`001192` is `1192` here) and the quantity to three decimals. Each local launch
 * reconciles at most one seat: two identical seats mean the operator launched twice, and consuming the
 * launches in order is what keeps a duplicate from being reported as reconciled twice over.
 *
 * A stock exit is never reconciled: Trindade records entries only, and a seat on the other side has nothing
 * here to answer for it.
 */
export function reconcileAeroMovements(
  movements: readonly AeroErpMovementItem[],
  launches: readonly AeroLocalLaunchLike[],
): AeroMovementReconciliation {
  const byKey = new Map<string, number[]>();
  for (const launch of launches) {
    const key = aeroMovementKey(launch.launch_date, launch.product_code, launch.units);
    const ids = byKey.get(key);
    if (ids) ids.push(launch.id);
    else byKey.set(key, [launch.id]);
  }

  const reconciled: number[] = [];
  const matchedLaunchIds: number[] = [];
  for (const movement of movements) {
    if (movement.movementType !== 'E') continue;
    const key = aeroMovementKey(movement.entryDate, movement.productCode, movement.quantity);
    const ids = byKey.get(key);
    if (!ids || ids.length === 0) continue;
    reconciled.push(movement.entLan);
    matchedLaunchIds.push(ids.shift() as number);
  }

  const matched = new Set(matchedLaunchIds);
  return {
    reconciled,
    matchedLaunchIds,
    unmatchedLaunchIds: launches.filter((launch) => !matched.has(launch.id)).map((launch) => launch.id),
  };
}

/** The same physical boxes, written as one comparable key on both sides. */
function aeroMovementKey(date: string, code: string, quantity: number): string {
  const digits = String(code).trim().replace(/^0+/, '') || '0';
  return `${aeroDateOnly(date)}|${digits}|${Math.round(quantity * 1000) / 1000}`;
}

/**
 * What the bridge can be asked to do, one word each.
 *
 * The word travels in the callback query string and in the envelope's `url`, so it is also the name the page
 * uses to decide what a returning phone means.
 */
export type AeroBridgeAction = 'entrada' | 'saida' | 'movimentos' | 'test';

/**
 * The one thing the bridge shortcut receives: where to post, who it is, what to send and where to come back.
 *
 * `body` is a string on purpose. A dictionary value dropped into the phone's "Request Body: Text" field is
 * not re-serialised as JSON -- Shortcuts renders it as a property list -- so an object here would produce a
 * body the ERP refuses while the header still claimed `application/json`. The page serialises it before the
 * phone ever sees it.
 */
export interface AeroBridgeEnvelope {
  url: string;
  method: 'POST';
  headers: { Authorization: string; 'Content-Type': string; Accept: string };
  body: string;
  callback: string;
}

/**
 * Builds the envelope the bridge posts, credentials included.
 *
 * The header travels in the envelope rather than inside the shortcut ("opción B"): the factory changes a
 * password in the web panel and the iPhone keeps working without editing anything. The cost is two more
 * dictionary reads on the phone, which is cheaper than a shortcut nobody can update.
 */
export function buildAeroBridgeEnvelope(args: {
  wsUrl: string;
  username: string;
  password: string;
  /** The endpoint path the bridge posts to, such as `saidasitem` or `usuarios/`. */
  endpoint: string;
  /** Serialised into the envelope's `body`; never handed to the phone as a dictionary. */
  payload: unknown;
  /** Where the bridge opens once it is done, with the data slot left open. */
  callbackUrl: string;
}): AeroBridgeEnvelope {
  return {
    url: aeroEndpoint(args.wsUrl, args.endpoint),
    method: 'POST',
    headers: {
      Authorization: basicAuthHeader(args.username, args.password),
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(args.payload),
    callback: args.callbackUrl,
  };
}

/**
 * Builds the deep link that runs the bridge, handing it the whole envelope as one text input.
 */
export function buildAeroBridgeTriggerUrl(args: { name: string; envelope: AeroBridgeEnvelope }): string {
  return aeroShortcutTriggerUrl({ name: args.name, payload: args.envelope });
}

/**
 * The callback the bridge opens, with the slot the ERP answer is appended to.
 *
 * One prefix per action, all on the same page: the phone appends whatever the ERP answered, URL-encoded, and
 * the page reads it back as `aero_action` plus `aero_data`.
 */
export function aeroBridgeCallbackPrefix(args: {
  origin: string;
  basePath?: string;
  action: AeroBridgeAction;
}): string {
  return `${aeroLaunchPageUrl(args)}?aero_action=${args.action}&aero_data=`;
}

/**
 * The callback the running page has to come back to, for one action.
 *
 * The panel's test, the dialog's sample and every launch build the same URL from the page they are on, so the
 * derivation lives here instead of being retyped in three places.
 */
export function aeroBridgeCallbackFor(action: AeroBridgeAction): string {
  const origin = typeof window === 'undefined' ? '' : window.location.origin;
  const basePath = (import.meta.env?.BASE_URL ?? '/').replace(/\/$/, '');
  return aeroBridgeCallbackPrefix({ origin, basePath, action });
}

export interface AeroBridgeShortcutRecipe {
  /** The one shortcut the phone must have installed. */
  name: string;
  /** The four dictionary keys every action reads, in the order they are built. */
  keys: { url: string; auth: string; body: string; callback: string };
  /** What the POST action must declare it is sending. */
  contentType: string;
  /** What the POST action must declare it accepts, the same one every ERP call carries. */
  accept: string;
  /** The header value the page now puts in each envelope, quoted so the panel can show it. */
  authorization: string;
  /** How many actions the recipe really takes on the phone: four reads, one POST, one encode, one open. */
  actionCount: number;
  /** The endpoints the one bridge can reach, so the dialog never understates what it covers. */
  endpoints: Record<AeroBridgeAction, string>;
  /** A ready envelope for the connection test, the only action that needs no launch data. */
  sampleEnvelope: AeroBridgeEnvelope;
}

/**
 * The bridge recipe, in the shape the dialog copies it.
 *
 * Counted honestly: getting the dictionary out of the shortcut input, four "get dictionary value" reads (url,
 * auth, body, callback), the POST, the URL encode of the answer and the open. Seven actions is what the phone
 * shows for the reduced variant; eight is what "opción B" costs, and the panel says eight.
 */
export function aeroBridgeShortcutRecipe(args: {
  wsUrl: string;
  username: string;
  password: string;
  origin: string;
  basePath?: string;
}): AeroBridgeShortcutRecipe {
  return {
    name: AERO_DEFAULT_BRIDGE_SHORTCUT_NAME,
    keys: { url: 'url', auth: 'auth', body: 'body', callback: 'callback' },
    contentType: 'application/json',
    accept: 'application/json',
    authorization: basicAuthHeader(args.username, args.password),
    actionCount: 8,
    endpoints: {
      entrada: aeroEndpoint(args.wsUrl, 'entradasitem'),
      saida: aeroEndpoint(args.wsUrl, 'saidasitem'),
      movimentos: aeroEndpoint(args.wsUrl, 'movimentoitem'),
      test: aeroEndpoint(args.wsUrl, 'usuarios'),
    },
    sampleEnvelope: buildAeroBridgeEnvelope({
      wsUrl: args.wsUrl,
      username: args.username,
      password: args.password,
      endpoint: 'usuarios',
      payload: {},
      callbackUrl: aeroBridgeCallbackPrefix({ origin: args.origin, basePath: args.basePath, action: 'test' }),
    }),
  };
}

/**
 * Builds the deep link that runs a shortcut with one text as its input.
 *
 * The primitive under {@link buildAeroBridgeTriggerUrl}: `x-callback-url` hands the phone a string and the
 * shortcut decides what it means. Nothing else about the phone is assumed here.
 */
export function aeroShortcutTriggerUrl(args: { name: string; payload: unknown }): string {
  const body = JSON.stringify(args.payload);
  return `shortcuts://x-callback-url/run-shortcut?name=${encodeURIComponent(args.name)}&input=text&text=${encodeURIComponent(body)}`;
}

/**
 * The page the phone comes back to, before any query string.
 */
function aeroLaunchPageUrl(args: { origin: string; basePath?: string }): string {
  const cleanOrigin = args.origin.replace(/\/+$/, '');
  const rawBase = args.basePath ?? '';
  const cleanBase = !rawBase || rawBase === '/' ? '' : `/${rawBase.replace(/^\/+|\/+$/g, '')}`;
  return `${cleanOrigin}${cleanBase}/massas-launch`;
}

/**
 * What the phone brought back, in the shapes this integration produces.
 *
 * `bridge` is the unified one; `test` and `sync` are the two the split design produced and are still read so a
 * phone that has not rebuilt its shortcut keeps working for a while.
 */
export type AeroCallbackResult =
  | { kind: 'bridge'; action: AeroBridgeAction; data: unknown; raw: string }
  | { kind: 'test'; verdict: AeroTestVerdict; raw: string }
  | { kind: 'sync'; status: 'ok' | 'error'; message: string | null };

/** The words the bridge understands, and the only ones a callback may carry. */
const AERO_BRIDGE_ACTIONS: readonly AeroBridgeAction[] = ['entrada', 'saida', 'movimentos', 'test'];

function isAeroBridgeAction(value: string): value is AeroBridgeAction {
  return (AERO_BRIDGE_ACTIONS as readonly string[]).includes(value);
}

/**
 * Parses the ERP answer the bridge appended to the callback, keeping the text exactly as it arrived.
 *
 * A body that is not JSON -- a truncated URL, an HTML error page, nothing at all -- becomes `null` instead of
 * throwing: the page still has to say what came back, and the raw text is what the audit line records.
 */
function readAeroBridgeData(raw: string): unknown {
  if (!raw.trim()) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/**
 * Parses query parameters returning from the iOS shortcut callback.
 *
 * Three callbacks share one page: the bridge, which brings the ERP's answer for one action; the connection
 * test, which carries the `success` field of `/usuarios/` the phone read for us, either as the raw verdict it
 * used to pass or as the whole body the bridge now brings; and the legacy sync status. The raw token travels
 * with the verdict so the audit line can show what actually came back.
 */
export function parseAeroCallback(search: string): AeroCallbackResult | null {
  if (!search) return null;
  const queryString = search.startsWith('?') ? search.slice(1) : search;
  if (!queryString) return null;
  const params = new URLSearchParams(queryString);

  const action = params.get('aero_action');
  if (action !== null && isAeroBridgeAction(action)) {
    const raw = params.get('aero_data') ?? '';
    const data = readAeroBridgeData(raw);
    if (action === 'test') {
      return { kind: 'test', verdict: readAeroTestResult(data).ok ? 'ok' : 'error', raw };
    }
    return { kind: 'bridge', action, data, raw };
  }

  const testToken = params.get('aero_test');
  if (testToken !== null) {
    return { kind: 'test', verdict: aeroTestVerdictFromToken(testToken), raw: testToken };
  }

  const status = params.get('aero_sync');
  if (status !== 'ok' && status !== 'error') {
    return null;
  }
  return {
    kind: 'sync',
    status,
    message: params.get('aero_msg'),
  };
}

/**
 * Turns the ERP's answer to a stock write into the two outcomes the launch page acts on.
 *
 * A confirmation is only a confirmation when the ERP said `success: true`: an unreadable body, a missing field
 * or a message that is not text all leave the launch exactly where it was, instead of marking it synced.
 */
export function readAeroBridgeResult(data: unknown): { status: 'ok' | 'error'; message: string | null } {
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    return { status: 'error', message: null };
  }
  const record = data as Record<string, unknown>;
  if (record.success !== true) {
    return { status: 'error', message: aeroBridgeMessage(record) };
  }
  return { status: 'ok', message: aeroBridgeMessage(record) };
}

function aeroBridgeMessage(record: Record<string, unknown>): string | null {
  return typeof record.message === 'string' && record.message.trim() ? record.message.trim() : null;
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
