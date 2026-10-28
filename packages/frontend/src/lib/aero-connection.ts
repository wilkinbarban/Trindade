import {
  aeroConnectionBlocker,
  aeroEndpoint,
  basicAuthHeader,
  normalizeAeroWsUrl,
  readAeroTestResult,
  type AeroSettings,
} from './aero-integration';

/**
 * What the browser could actually learn from the company WebService, in the one vocabulary the panel
 * and the audit log share.
 *
 * The browser is a poor instrument here and pretending otherwise is how a factory test goes wrong:
 * Safari refuses `http://` targets from an `https://` page before any packet leaves, and a server that
 * answers without CORS headers is indistinguishable from one that never answered. So the probe reports
 * *which* wall it hit rather than a generic failure, and the shortcut -- a native process with no page
 * origin -- remains the path that really sends the entry.
 */
export type AeroConnectionProbe =
  | { kind: 'ok'; httpStatus: number; userCode: number; userName: string; snippet: string }
  | { kind: 'http_error'; httpStatus: number; message: string; snippet: string }
  | { kind: 'reachable_without_cors'; snippet: string }
  | { kind: 'blocked'; reason: 'mixed_content' | 'insecure_target' }
  | { kind: 'invalid_config' }
  | { kind: 'network'; message: string }
  | { kind: 'timeout' };

export interface AeroConnectionProbeArgs {
  settings: Pick<AeroSettings, 'wsUrl' | 'username' | 'password'>;
  /** `window.location.protocol`, passed in so the probe can be exercised without a browser. */
  pageProtocol: string;
  fetchImpl?: typeof fetch;
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 8000;

/** A response body is quoted in the log, so the quote has to stay a quote. */
const SNIPPET_LIMIT = 300;

function snippetOf(raw: string): string {
  return raw.length > SNIPPET_LIMIT ? `${raw.slice(0, SNIPPET_LIMIT)}…` : raw;
}

function isAbort(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

/**
 * Rings the `/usuarios/` endpoint with the company credentials.
 *
 * The route is POST on the factory server: Laravel answers a GET with `405 Supported methods: POST`, which is
 * what the first factory test actually returned.
 *
 * Two requests at most: the authenticated one, and -- only when the browser refused to hand over the
 * answer -- an opaque `no-cors` GET to the same URL. The second one cannot read the body or the status,
 * but it is the difference between "the Wi-Fi or the IP is wrong" and "the server is there and does not
 * speak CORS", which is exactly the difference the operator needs before blaming the shortcut.
 */
export async function probeAeroConnection(args: AeroConnectionProbeArgs): Promise<AeroConnectionProbe> {
  const wsUrl = normalizeAeroWsUrl(args.settings.wsUrl ?? '');
  if (!wsUrl || !args.settings.username || !args.settings.password) {
    return { kind: 'invalid_config' };
  }

  const blocker = aeroConnectionBlocker({ pageProtocol: args.pageProtocol, wsUrl });
  if (blocker) {
    return { kind: 'blocked', reason: blocker };
  }

  const target = aeroEndpoint(wsUrl, 'usuarios/');  const fetchImpl = args.fetchImpl ?? fetch;
  const controller = new AbortController();
  const timeoutMs = args.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetchImpl(target, {
      method: 'POST',
      headers: {
        Authorization: basicAuthHeader(args.settings.username, args.settings.password),
        Accept: 'application/json',
      },
      signal: controller.signal,
    });

    const raw = await response.text().catch(() => '');
    const parsed = parseJson(raw);
    const result = readAeroTestResult(parsed);

    if (response.ok && result.ok) {
      return {
        kind: 'ok',
        httpStatus: response.status,
        userCode: result.userCode,
        userName: result.userName,
        snippet: snippetOf(raw),
      };
    }

    return {
      kind: 'http_error',
      httpStatus: response.status,
      message: result.ok ? `HTTP ${response.status}` : result.message,
      snippet: snippetOf(raw),
    };
  } catch (error) {
    if (isAbort(error)) return { kind: 'timeout' };

    // The opaque retry can only tell that *something* answered: it never exposes a status or a body.
    try {
      await fetchImpl(target, { method: 'POST', mode: 'no-cors', signal: controller.signal });
      return { kind: 'reachable_without_cors', snippet: '' };
    } catch {
      return { kind: 'network', message: error instanceof Error ? error.message : 'network error' };
    }
  } finally {
    clearTimeout(timer);
  }
}

function parseJson(raw: string): unknown {
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}
