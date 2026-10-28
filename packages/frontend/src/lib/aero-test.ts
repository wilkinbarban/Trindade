/**
 * The connection test as it happens on the phone.
 *
 * Safari cannot open a socket to a plain-HTTP WebService from an HTTPS page: it refuses before a packet
 * leaves the device, and no amount of retrying changes that. The shortcut can, because it is a native
 * process with no page origin. So the test leaves the browser the same way the launch does, and this
 * module keeps the vocabulary of that round trip -- the pending attempt, the verdict the shortcut brings
 * back, and the two audit lines that describe it.
 */

export const AERO_PENDING_TEST_STORAGE_KEY = 'trindade.aero.pending-test.v1';

/** What the endpoint answered, as far as the phone could tell. */
export type AeroTestVerdict = 'ok' | 'error' | 'unknown';

/**
 * Why the browser handed the test to the shortcut.
 *
 * `mixed_content` is the production case: HTTPS page, HTTP WebService. `insecure_target` is a URL that is
 * not HTTP at all, kept so a typo is still named instead of surfacing as a generic failure.
 */
export type AeroTestBlockReason = 'mixed_content' | 'insecure_target';

/**
 * What the page hands to the panel once the phone has answered.
 */
export interface AeroTestOutcome {
  verdict: AeroTestVerdict;
  raw: string;
  destinationUrl: string;
}

export interface AeroPendingTest {
  destinationUrl: string;
  startedAt: string;
  reason: AeroTestBlockReason;
}

/**
 * One line of the audit trail, shaped to be handed to `/integration/aero/log` as is.
 *
 * Declared here rather than imported from the audit client so this module stays pure: the log format is
 * a contract of the server, and the client only carries it.
 */
export interface AeroTestAuditLine {
  event: 'test_connection';
  phase: 'requested' | 'result';
  destinationUrl: string;
  httpStatus: number | null;
  payload?: unknown;
  response?: string | null;
  error: string | null;
  note: string;
}

/**
 * Reads the verdict the shortcut appended to the return URL.
 *
 * The shortcut forwards the `success` field of `/usuarios/`, and Shortcuts renders a boolean in more than
 * one way: `true`, `1`, or a localised-looking word depending on the iOS version and the shortcut's own
 * configuration. Every rendering it can produce is accepted here, and anything else is `unknown` -- the
 * panel then says it does not know instead of inventing a success.
 */
export function aeroTestVerdictFromToken(token: string | null | undefined): AeroTestVerdict {
  const normalized = (token ?? '').trim().toLowerCase();
  if (normalized === 'true' || normalized === 'ok' || normalized === '1' || normalized === 'sim' || normalized === 'yes') {
    return 'ok';
  }
  if (
    normalized === 'false' ||
    normalized === 'error' ||
    normalized === '0' ||
    normalized === 'nao' ||
    normalized === 'não' ||
    normalized === 'no'
  ) {
    return 'error';
  }
  return 'unknown';
}

/**
 * Remembers the attempt while the phone is in another app.
 *
 * Safari may discard and reload the page during the round trip; without this the page would come back
 * with no idea that it had asked for a test, and the result line would have no attempt to belong to.
 */
export function saveAeroPendingTest(
  pending: AeroPendingTest,
  storage?: Pick<Storage, 'setItem'> | null,
): void {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.setItem(AERO_PENDING_TEST_STORAGE_KEY, JSON.stringify(pending));
  }
}

export function loadAeroPendingTest(
  storage?: Pick<Storage, 'getItem'> | null,
): AeroPendingTest | null {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (!target) return null;
  const raw = target.getItem(AERO_PENDING_TEST_STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    const { destinationUrl, startedAt, reason } = parsed as Record<string, unknown>;
    if (typeof destinationUrl !== 'string' || !destinationUrl) return null;
    if (typeof startedAt !== 'string' || !startedAt) return null;
    if (reason !== 'mixed_content' && reason !== 'insecure_target') return null;
    return { destinationUrl, startedAt, reason };
  } catch {
    return null;
  }
}

export function clearAeroPendingTest(storage?: Pick<Storage, 'removeItem'> | null): void {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null);
  if (target) {
    target.removeItem(AERO_PENDING_TEST_STORAGE_KEY);
  }
}

/**
 * The attempt, written before the page hands control to the phone.
 *
 * A test that never reports is still a fact worth having: this line plus its missing result is how the
 * log distinguishes "nobody tried" from "the phone tried and never came back".
 */
export function aeroTestRequestedEvent(args: {
  destinationUrl: string;
  reason: AeroTestBlockReason | null;
}): AeroTestAuditLine {
  return {
    event: 'test_connection',
    phase: 'requested',
    destinationUrl: args.destinationUrl,
    httpStatus: null,
    error: null,
    note: args.reason ? `via=shortcut reason=${args.reason}` : 'via=browser',
  };
}

/**
 * What the endpoint answered, with the raw token kept so the line states the fact and not a reading of it.
 */
export function aeroTestResultEvent(args: {
  verdict: AeroTestVerdict;
  raw: string;
  destinationUrl: string;
}): AeroTestAuditLine {
  return {
    event: 'test_connection',
    phase: 'result',
    destinationUrl: args.destinationUrl,
    httpStatus: null,
    response: null,
    error: args.verdict === 'ok' ? null : `shortcut:${args.verdict === 'error' ? 'error' : 'unknown'}`,
    note: `via=shortcut aero_test=${args.raw}`,
  };
}

/**
 * The operator came back to Safari without the shortcut having anything to say.
 *
 * Closing the attempt is what keeps the trail readable: an open `requested` line forever is noise.
 */
export function aeroTestNoVerdictEvent(args: { destinationUrl: string }): AeroTestAuditLine {
  return {
    event: 'test_connection',
    phase: 'result',
    destinationUrl: args.destinationUrl,
    httpStatus: null,
    error: 'shortcut:no_verdict',
    note: 'the operator returned without a shortcut result',
  };
}
