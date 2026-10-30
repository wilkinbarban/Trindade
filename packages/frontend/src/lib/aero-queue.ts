/**
 * The entries a single launch left pending for the company ERP.
 *
 * `POST /entradasitem` takes one product per request, so a launch of three products is three requests,
 * and the page can only open one shortcut at a time. The queue is what survives that: the operator
 * leaves the page for the Atajos app, the shortcut comes back through a URL, and the next entry is
 * already waiting there.
 *
 * It lives in `localStorage` rather than `sessionStorage` on purpose. iOS may hand the returning URL to a
 * fresh tab, and a queue that vanished on the way back would silently drop the second product of the day.
 * The lifetime is therefore explicit and short: a queue older than the shift is a leftover, not work.
 */
export interface AeroQueueItem {
  productCode: string;
  productName: string;
  quantity: number;
  /**
   * The operator whose launch this is, carried to the ERP as `ENT_USU`.
   *
   * The phone's configured user is whoever set the integration up; the launch belongs to the person who
   * typed the boxes, and the seat the company records has to name that person.
   */
  operatorName?: string;
  /** The local launches behind this entry, quoted to the ERP so a doubtful seat can be traced back. */
  launchIds?: number[];
}

export interface AeroQueue {
  /** ISO timestamp of the launch that created the queue. */
  createdAt: string;
  /** `YYYY-MM-DD` launch date, carried so the log can name the ERP entry day. */
  date: string;
  /** The products still waiting for a trip. */
  items: AeroQueueItem[];
  /**
   * The products already inside the envelope the phone is running right now.
   *
   * A batch leaves the queue before the phone leaves the page, so the return has nothing left to say which
   * products those answers belong to. Keeping them here is what lets a partial batch put exactly the refused
   * products back at the front instead of asking the operator to remember.
   */
  inFlight: AeroQueueItem[];
}

/**
 * The queue entry for one pending row the operator asked to send.
 *
 * Units, never boxes: the ERP records what left the plant, and the row's `boxes` is a packaging detail the
 * entry payload has always converted before it leaves.
 */
export function pendingLaunchQueueItem(row: {
  productCode: string;
  productName: string;
  units: number;
  operatorName?: string | null;
  launchIds?: readonly number[];
}): AeroQueueItem {
  return {
    productCode: row.productCode,
    productName: row.productName,
    quantity: row.units,
    ...(row.operatorName?.trim() ? { operatorName: row.operatorName.trim() } : {}),
    ...(row.launchIds && row.launchIds.length > 0 ? { launchIds: [...row.launchIds] } : {}),
  };
}

export const AERO_QUEUE_KEY = 'trindade.aero.queue.v1';

/** Long enough for a shift, short enough that yesterday's leftovers never fire. */
export const AERO_QUEUE_TTL_MS = 8 * 60 * 60 * 1000;

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function safeStorage(): StorageLike | null {
  try {
    return typeof globalThis.localStorage === 'undefined' ? null : globalThis.localStorage;
  } catch {
    return null;
  }
}

function isItem(value: unknown): value is AeroQueueItem {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<AeroQueueItem>;
  return (
    typeof item.productCode === 'string' &&
    item.productCode.length > 0 &&
    typeof item.productName === 'string' &&
    typeof item.quantity === 'number' &&
    Number.isFinite(item.quantity) &&
    item.quantity > 0
  );
}

/**
 * Reads the pending entries, discarding anything that is not a fresh, well-formed queue.
 *
 * A half-written queue is worse than none: it would send a product with the wrong quantity. Anything
 * unexpected clears itself instead of being repaired.
 *
 * A queue whose products are all in the phone has an empty `items` and a full `inFlight`, and that is a
 * valid queue: it is the one the shortcut returns to. Only a queue with nothing pending *and* nothing in
 * flight says nothing and can go.
 */
export function readAeroQueue(storage: StorageLike | null = safeStorage(), now = Date.now()): AeroQueue | null {
  if (!storage) return null;
  let raw: string | null = null;
  try {
    raw = storage.getItem(AERO_QUEUE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<AeroQueue>;
    if (typeof parsed.createdAt !== 'string' || typeof parsed.date !== 'string' || !Array.isArray(parsed.items)) {
      throw new Error('shape');
    }
    const age = now - new Date(parsed.createdAt).getTime();
    if (!Number.isFinite(age) || age < 0 || age > AERO_QUEUE_TTL_MS) {
      throw new Error('stale');
    }
    const items = parsed.items.filter(isItem);
    const inFlight = Array.isArray(parsed.inFlight) ? parsed.inFlight.filter(isItem) : [];
    if (items.length === 0 && inFlight.length === 0) throw new Error('empty');
    return { createdAt: parsed.createdAt, date: parsed.date, items, inFlight };
  } catch {
    try {
      storage.removeItem(AERO_QUEUE_KEY);
    } catch {
      /* a storage that cannot forget is not a reason to fail the launch */
    }
    return null;
  }
}

export function writeAeroQueue(queue: AeroQueue, storage: StorageLike | null = safeStorage()): void {
  if (!storage) return;
  try {
    storage.setItem(AERO_QUEUE_KEY, JSON.stringify(queue));
  } catch {
    /* private mode or a full quota: the launch is already saved, the queue is a convenience */
  }
}

export function clearAeroQueue(storage: StorageLike | null = safeStorage()): void {
  if (!storage) return;
  try {
    storage.removeItem(AERO_QUEUE_KEY);
  } catch {
    /* nothing to do */
  }
}

/**
 * The products the next envelope carries, plus what stays behind.
 *
 * A batch is up to `size` entries; the rest keeps waiting in the queue and becomes the next trip once this
 * one comes back. The queue is already the list of what is missing, so a long day is several envelopes, not
 * one oversized link.
 */
export function takeAeroBatch(queue: AeroQueue, size: number): { batch: AeroQueueItem[]; rest: AeroQueue } {
  const batch = queue.items.slice(0, Math.max(0, size));
  return { batch, rest: { ...queue, items: queue.items.slice(batch.length) } };
}

/** The entry to send next, plus what remains. */
export function takeNextAeroItem(queue: AeroQueue): { item: AeroQueueItem; rest: AeroQueue } | null {
  if (queue.items.length === 0) return null;
  const [item, ...rest] = queue.items;
  return { item: item!, rest: { ...queue, items: rest } };
}
