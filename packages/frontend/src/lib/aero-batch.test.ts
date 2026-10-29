import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { AERO_BATCH_MAX, buildAeroLaunchTrips } from './aero-batch';

/**
 * The rule Wilkin set: one product travels exactly like today, two or more share one envelope, and the
 * envelope is the thing the phone iterates. These tests own that rule, so the page only has to render it.
 */
const base = {
  wsUrl: 'http://192.168.1.50:8000',
  username: 'wilkin',
  password: 'secreto',
  endpoint: 'entradasitem',
  singleCallbackUrl: 'https://trindademasas.duckdns.org/massas-launch?aero_action=entrada&aero_data=',
  batchCallbackUrl: 'https://trindademasas.duckdns.org/massas-launch?aero_action=entrada_lote&aero_data=',
};

const payload = (code: string, quantity = 15) => ({ PRO_COD: code, QUANTIDADE: quantity });

describe('aero batch: the trip a launch takes to the shortcut', () => {
  it('sends one product exactly as today, with no batch branch in the envelope', () => {
    const trips = buildAeroLaunchTrips({ ...base, payloads: [payload('001192')] });

    assert.equal(trips.length, 1);
    const [trip] = trips;
    assert.equal(trip!.action, 'entrada');
    assert.equal(trip!.envelope.bodies, undefined);
    assert.equal(trip!.envelope.body, JSON.stringify(payload('001192')));
    assert.equal(trip!.envelope.callback, base.singleCallbackUrl);
    assert.equal(trip!.payloads.length, 1);
  });

  it('puts two products in one envelope, with the first one also in `body` for an unpatched phone', () => {
    const trips = buildAeroLaunchTrips({ ...base, payloads: [payload('001192'), payload('000084', 456)] });

    assert.equal(trips.length, 1);
    const [trip] = trips;
    assert.equal(trip!.action, 'entrada_lote');
    assert.equal(trip!.envelope.callback, base.batchCallbackUrl);
    assert.deepEqual(trip!.envelope.bodies, [
      JSON.stringify(payload('001192')),
      JSON.stringify(payload('000084', 456)),
    ]);
    // The envelope stays valid for a shortcut that never learned `bodies`: it posts the first body as ever.
    assert.equal(trip!.envelope.body, JSON.stringify(payload('001192')));
    assert.deepEqual(trip!.payloads, [payload('001192'), payload('000084', 456)]);
  });

  it('carries the bodies as texts, because the phone repeats them into a text request body', () => {
    const payloads = [payload('1'), payload('2')];

    const trips = buildAeroLaunchTrips({ ...base, payloads });

    const bodies = trips[0]!.envelope.bodies ?? [];
    assert.equal(bodies.length, 2);
    for (const body of bodies) assert.equal(typeof body, 'string');
    // A list of texts, not a list of dictionaries: the shortcut hands each element straight to the request.
    assert.deepEqual(
      bodies.map((body) => JSON.parse(body)),
      payloads,
    );
  });

  it('sends the same address, method, headers and callback for every item in the batch', () => {
    const trips = buildAeroLaunchTrips({ ...base, payloads: [payload('1'), payload('2'), payload('3')] });

    const [trip] = trips;
    assert.equal(trip!.envelope.url, 'http://192.168.1.50:8000/entradasitem');
    assert.equal(trip!.envelope.method, 'POST');
    assert.deepEqual(trip!.envelope.headers, {
      Authorization: `Basic ${Buffer.from('wilkin:secreto').toString('base64')}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    });
  });

  it('fills one envelope up to the ceiling of eight', () => {
    const payloads = Array.from({ length: AERO_BATCH_MAX }, (_, index) => payload(String(index + 1)));

    const trips = buildAeroLaunchTrips({ ...base, payloads });

    assert.equal(AERO_BATCH_MAX, 8);
    assert.equal(trips.length, 1);
    assert.equal(trips[0]!.action, 'entrada_lote');
    assert.equal(trips[0]!.envelope.bodies!.length, AERO_BATCH_MAX);
  });

  it('splits nine into eight plus one, and that one travels as a single product again', () => {
    const payloads = Array.from({ length: 9 }, (_, index) => payload(String(index + 1)));

    const trips = buildAeroLaunchTrips({ ...base, payloads });

    assert.equal(trips.length, 2);
    assert.equal(trips[0]!.action, 'entrada_lote');
    assert.equal(trips[0]!.payloads.length, 8);
    assert.equal(trips[1]!.action, 'entrada');
    assert.equal(trips[1]!.envelope.bodies, undefined);
    assert.deepEqual(trips[1]!.payloads, [payloads[8]]);
  });

  it('walks a long launch in order, so the answers line up with the products that produced them', () => {
    const payloads = Array.from({ length: 17 }, (_, index) => payload(String(index + 1)));

    const trips = buildAeroLaunchTrips({ ...base, payloads });

    assert.deepEqual(
      trips.map((trip) => trip.payloads.length),
      [8, 8, 1],
    );
    assert.deepEqual(
      trips.flatMap((trip) => trip.payloads),
      payloads,
    );
  });

  it('takes no trip when there is nothing to send', () => {
    assert.deepEqual(buildAeroLaunchTrips({ ...base, payloads: [] }), []);
  });
});
