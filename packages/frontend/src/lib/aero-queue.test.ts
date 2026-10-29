import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  AERO_QUEUE_KEY,
  AERO_QUEUE_TTL_MS,
  clearAeroQueue,
  readAeroQueue,
  takeAeroBatch,
  takeNextAeroItem,
  writeAeroQueue,
  type AeroQueue,
} from './aero-queue';

/** A storage double: the queue is only ever a string under one key. */
function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    removeItem: (key: string) => void data.delete(key),
  };
}

const NOW = Date.parse('2026-02-10T12:00:00.000Z');

const queue: AeroQueue = {
  createdAt: '2026-02-10T11:55:00.000Z',
  date: '2026-02-10',
  items: [
    { productCode: '5000', productName: 'MASSA ROLO 1KG', quantity: 20 },
    { productCode: '1045', productName: 'CASEIRA', quantity: 8 },
  ],
  inFlight: [],
};

describe('aero queue', () => {
  it('round-trips a fresh queue so the second product of a launch is not lost', () => {
    const storage = memoryStorage();
    writeAeroQueue(queue, storage);
    assert.deepEqual(readAeroQueue(storage, NOW), queue);
  });

  it('drops a queue from before the shift instead of sending yesterday twice', () => {
    const storage = memoryStorage({ [AERO_QUEUE_KEY]: JSON.stringify(queue) });
    assert.equal(readAeroQueue(storage, NOW + AERO_QUEUE_TTL_MS + 1), null);
    assert.equal(storage.getItem(AERO_QUEUE_KEY), null, 'a stale queue deletes itself');
  });

  it('deletes a half-written queue rather than guessing quantities', () => {
    const storage = memoryStorage({
      [AERO_QUEUE_KEY]: JSON.stringify({ ...queue, items: [{ productCode: '5000' }] }),
    });
    assert.equal(readAeroQueue(storage, NOW), null);
    assert.equal(storage.getItem(AERO_QUEUE_KEY), null);
  });

  it('survives storage that cannot be read, written or cleaned', () => {
    const hostile = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('quota');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    assert.equal(readAeroQueue(hostile, NOW), null);
    writeAeroQueue(queue, hostile);
    clearAeroQueue(hostile);
  });

  it('hands over one entry at a time and keeps the rest', () => {
    const first = takeNextAeroItem(queue);
    assert.equal(first?.item.productCode, '5000');
    assert.deepEqual(first?.rest.items.map((item) => item.productCode), ['1045']);

    const second = takeNextAeroItem(first!.rest);
    assert.equal(second?.item.productCode, '1045');
    assert.equal(takeNextAeroItem(second!.rest), null, 'an exhausted queue has nothing to hand over');
  });


  it('hands the whole batch at once, so the phone leaves once for three products', () => {
    const nine = {
      ...queue,
      items: Array.from({ length: 9 }, (_, index) => ({
        productCode: String(index + 1),
        productName: `PRODUTO ${index + 1}`,
        quantity: 1,
      })),
    };

    const first = takeAeroBatch(nine, 8);

    assert.equal(first.batch.length, 8);
    assert.deepEqual(first.rest.items.map((item) => item.productCode), ['9']);
    // El resto queda en la cola: es el segundo viaje, no parte del primero.
    assert.deepEqual(first.batch.map((item) => item.productCode), ['1', '2', '3', '4', '5', '6', '7', '8']);
  });

  it('hands a short queue whole instead of padding the batch', () => {
    const taken = takeAeroBatch(queue, 8);

    assert.deepEqual(taken.batch.map((item) => item.productCode), ['5000', '1045']);
    assert.deepEqual(taken.rest.items, []);
  });

  it('remembers which products are already in the phone, so the return can name the failures', () => {
    const inFlight = queue.items.slice(0, 1);
    const storage = memoryStorage({ [AERO_QUEUE_KEY]: JSON.stringify({ ...queue, inFlight }) });

    const read = readAeroQueue(storage, NOW);

    assert.deepEqual(read?.inFlight, inFlight);
    assert.deepEqual(read?.items, queue.items);
  });

  it('drops an in-flight list that is not a list of entries', () => {
    const storage = memoryStorage({
      [AERO_QUEUE_KEY]: JSON.stringify({ ...queue, inFlight: [{ productCode: '5000' }] }),
    });

    assert.deepEqual(readAeroQueue(storage, NOW)?.inFlight, []);
  });
});
