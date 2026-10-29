import { buildAeroBridgeEnvelope, type AeroBridgeEnvelope } from './aero-integration';

/**
 * How many products travel in one envelope to the company ERP.
 *
 * The envelope rides inside a `shortcuts://x-callback-url` deep link, which is a URL, not a channel: eight
 * bodies of a few hundred bytes each encode to about three kilobytes, which iOS opens without complaint. A
 * whole day of twenty products would be eight kilobytes, and the link starts to be a bet.
 */
export const AERO_BATCH_MAX = 8;

/** One trip to the Atajos app: what to send, in which envelope, and which round trip it opens. */
export interface AeroLaunchTrip {
  action: 'entrada' | 'entrada_lote';
  envelope: AeroBridgeEnvelope;
  /** The payloads this trip carries, in the order the shortcut will send them. */
  payloads: unknown[];
}

export interface AeroLaunchTripsArgs {
  payloads: readonly unknown[];
  wsUrl: string;
  username: string;
  password: string;
  /** The endpoint path the bridge posts to. */
  endpoint: string;
  /** Round trip for a single product, the one a shortcut without the batch branch understands. */
  singleCallbackUrl: string;
  /** Round trip for a batch, which comes back as one array of answers. */
  batchCallbackUrl: string;
}

/**
 * Splits a launch into the envelopes the phone has to run.
 *
 * The rule is the product count, and it is Wilkin's: one product travels exactly as it does today -- no
 * `bodies`, the `entrada` round trip, the branch that has been in production for weeks. Two or more share one
 * envelope and come back once. Above eight, the launch is walked in order and the leftover, if it is a single
 * product, becomes a single trip again: a phone should not run a loop for one opinion.
 *
 * The order is preserved end to end, because the answers come back in the order they were sent and the page
 * has to know which product each answer belongs to.
 */
export function buildAeroLaunchTrips(args: AeroLaunchTripsArgs): AeroLaunchTrip[] {
  const trips: AeroLaunchTrip[] = [];
  for (let start = 0; start < args.payloads.length; start += AERO_BATCH_MAX) {
    const chunk = args.payloads.slice(start, start + AERO_BATCH_MAX);
    trips.push(buildTrip(chunk, args));
  }
  return trips;
}

function buildTrip(chunk: readonly unknown[], args: AeroLaunchTripsArgs): AeroLaunchTrip {
  const payloads = [...chunk];
  const envelope = buildAeroBridgeEnvelope({
    wsUrl: args.wsUrl,
    username: args.username,
    password: args.password,
    endpoint: args.endpoint,
    payload: chunk[0],
    callbackUrl: chunk.length === 1 ? args.singleCallbackUrl : args.batchCallbackUrl,
  });

  if (chunk.length === 1) {
    return { action: 'entrada', envelope, payloads };
  }

  // `bodies` is a list of texts, not dictionaries: "Repeat with each item" hands the element straight to a
  // text request body, and a dictionary there would need re-serialising on the phone. `body` keeps the first
  // payload so a shortcut that never learned `bodies` still posts something valid instead of failing.
  return {
    action: 'entrada_lote',
    envelope: { ...envelope, bodies: chunk.map((payload) => JSON.stringify(payload)) },
    payloads,
  };
}
