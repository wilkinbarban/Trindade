import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { basicAuthHeader } from './aero-integration';
import {
  aeroImportPayload,
  aeroImportPlan,
  aeroMirrorSeats,
  aeroMovementsEnvelope,
  readAeroMovementsSnapshot,
} from './aero-mirror';

const credentials = {
  wsUrl: 'http://192.168.25.250:1453/api',
  username: 'wilkin',
  password: 'senha',
};

describe('aero-mirror: the movements query through the bridge', () => {
  it('asks /movimentoitem for the whole day, every operator, and comes back to the mirror', () => {
    const envelope = aeroMovementsEnvelope({
      ...credentials,
      date: '2026-10-28',
      callbackUrl: 'https://trindademasas.duckdns.org/massas-launch?aero_action=movimentos&aero_data=',
    });

    assert.equal(envelope.url, 'http://192.168.25.250:1453/api/movimentoitem?_method=PATCH');
    assert.equal(envelope.method, 'POST');
    assert.equal(envelope.headers.Authorization, basicAuthHeader('wilkin', 'senha'));
    assert.equal(envelope.headers['Content-Type'], 'application/json');
    assert.equal(
      envelope.callback,
      'https://trindademasas.duckdns.org/massas-launch?aero_action=movimentos&aero_data=',
    );

    assert.deepEqual(JSON.parse(envelope.body), {
      _method: 'PATCH',
      CODFOR: null,
      NOMUSU: null,
      CODPRO: null,
      DTINI: '2026-10-28',
      DTFIM: '2026-10-28',
      ENTSAI: 'E',
    });
  });

  it('narrows to one operator when the mirror is asked for one', () => {
    const envelope = aeroMovementsEnvelope({
      ...credentials,
      date: '2026-10-28',
      userName: 'marcos',
      callbackUrl: 'https://example.test/massas-launch?aero_action=movimentos&aero_data=',
    });

    assert.equal(JSON.parse(envelope.body).NOMUSU, 'MARCOS');
  });

  it('carries the body as text, because a dictionary reaches the ERP as a property list', () => {
    const envelope = aeroMovementsEnvelope({
      ...credentials,
      date: '2026-10-28',
      callbackUrl: 'https://example.test/massas-launch?aero_action=movimentos&aero_data=',
    });

    assert.equal(typeof envelope.body, 'string');
    assert.deepEqual(JSON.parse(envelope.body).DTINI, '2026-10-28');
  });
});

describe('aero-mirror: reading the snapshot the backend stored', () => {
  const stored = {
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
        synced_at: '2026-10-28T12:00:00.000Z',
      },
      {
        ent_lan: 10543,
        product_code: '005000',
        product_desc: 'MASSA ROLO 1KG',
        quantity: 45,
        entry_date: '2026-10-28',
        operator_name: 'MARCOS',
        movement_type: 'E',
        synced_at: '2026-10-28T12:05:00.000Z',
      },
    ],
  };

  it('reads the day into the same seat shape the ERP answer produces', () => {
    const snapshot = readAeroMovementsSnapshot(stored);
    assert.ok(snapshot);
    assert.equal(snapshot.date, '2026-10-28');
    assert.deepEqual(snapshot.movements, [
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

  it('reports when the plant last heard from the ERP, the latest line not the first', () => {
    const snapshot = readAeroMovementsSnapshot(stored);
    assert.ok(snapshot);
    assert.equal(snapshot.syncedAt, '2026-10-28T12:05:00.000Z');
  });

  it('answers an empty day without an error and without a sync time', () => {
    const snapshot = readAeroMovementsSnapshot({ date: '2026-10-28', movements: [] });
    assert.deepEqual(snapshot, { date: '2026-10-28', movements: [], syncedAt: null });
  });

  it('refuses a body that is not the snapshot it asked for', () => {
    assert.equal(readAeroMovementsSnapshot(null), null);
    assert.equal(readAeroMovementsSnapshot({ movements: [] }), null);
    assert.equal(readAeroMovementsSnapshot({ date: '2026-10-28', movements: 'none' }), null);
    assert.equal(readAeroMovementsSnapshot({ date: '28-10-2026', movements: [] }), null);
  });

  it('drops a row it cannot read instead of hiding the day', () => {
    const snapshot = readAeroMovementsSnapshot({
      date: '2026-10-28',
      movements: [stored.movements[0], { ent_lan: 'AB', product_code: '001192' }],
    });

    assert.ok(snapshot);
    assert.deepEqual(snapshot.movements.map((movement) => movement.entLan), [10542]);
  });
});

describe('aero-mirror: bringing an ERP seat into Trindade', () => {
  const catalog = [
    { code: '1192', name: 'MASSA TRADICIONAL 500G', unitsPerBox: 10 },
    { code: '5000', name: 'MASSA ROLO 1KG', unitsPerBox: 15 },
  ];
  const seat = (overrides: Partial<Parameters<typeof aeroImportPlan>[0]> = {}) => ({
    entLan: 10542,
    productCode: '001192',
    productDesc: 'MASSA TRADICIONAL 500G',
    quantity: 90,
    entryDate: '2026-10-28',
    operatorName: 'WILKIN',
    movementType: 'E' as const,
    ...overrides,
  });

  it('turns the ERP units into the boxes the local launch records', () => {
    assert.deepEqual(aeroImportPlan(seat(), catalog), { productCode: '1192', boxes: 9 });
  });

  it('matches the ERP code with or without its leading zeros', () => {
    assert.deepEqual(aeroImportPlan(seat({ productCode: '1192' }), catalog), { productCode: '1192', boxes: 9 });
  });

  it('refuses a quantity the catalog cannot express in whole boxes', () => {
    assert.equal(aeroImportPlan(seat({ quantity: 95 }), catalog), null);
  });

  it('refuses a product the plant does not have', () => {
    assert.equal(aeroImportPlan(seat({ productCode: '009999' }), catalog), null);
  });

  it('never imports a stock exit, because a launch here is production', () => {
    assert.equal(aeroImportPlan(seat({ movementType: 'S' }), catalog), null);
  });

  it('refuses an empty or fractional quantity', () => {
    assert.equal(aeroImportPlan(seat({ quantity: 0 }), catalog), null);
    assert.equal(aeroImportPlan(seat({ quantity: 4.5 }), catalog), null);
  });

  it('sends the seat and the operator the tablet wrote, not the importer', () => {
    // Without these two the launch belongs to whoever pressed the button and the seat is forgotten, which
    // is exactly the pair that never reconciled.
    assert.deepEqual(aeroImportPayload(seat({ entLan: 35937, operatorName: 'VICTOR' }), { productCode: '5000', boxes: 27 }, '2026-10-30'), {
      date: '2026-10-30',
      ent_lan: 35937,
      operator_name: 'VICTOR',
      items: [{ product_code: '5000', boxes: 27 }],
    });
  });
});

describe('aero-mirror: the seats this tab is about', () => {
  const catalog = [
    { code: '1192', name: 'MASSA TRADICIONAL 500G', unitsPerBox: 10 },
    { code: '5000', name: 'MASSA ROLO 1KG', unitsPerBox: 15 },
  ];
  const seat = (overrides: Partial<Parameters<typeof aeroImportPlan>[0]> = {}) => ({
    entLan: 10542,
    productCode: '001192',
    productDesc: 'MASSA TRADICIONAL 500G',
    quantity: 90,
    entryDate: '2026-10-28',
    operatorName: 'WILKIN',
    movementType: 'E' as const,
    ...overrides,
  });

  it("drops the supplies, which are the report's business and have no button here", () => {
    const day = [
      seat({ entLan: 35911, productCode: '001774', productDesc: 'CAIXA DE PAPELAO', quantity: 3606 }),
      seat({ entLan: 35918, productCode: '001805', productDesc: 'FARINHA', quantity: 500 }),
      seat({ entLan: 35914, productCode: '005000', productDesc: 'MASSA ROLO 1KG', quantity: 15 }),
    ];

    assert.deepEqual(
      aeroMirrorSeats(day, catalog).map((movement) => movement.entLan),
      [35914],
    );
  });

  it('matches a catalogue code with or without its leading zeros', () => {
    const day = [seat({ entLan: 7, productCode: '1192' }), seat({ entLan: 8, productCode: '005000' })];
    assert.deepEqual(
      aeroMirrorSeats(day, catalog).map((movement) => movement.entLan),
      [7, 8],
    );
  });

  it('answers nothing for a day that only carried supplies', () => {
    const day = [seat({ entLan: 35923, productCode: '001774', productDesc: 'CAIXA', quantity: 100 })];
    assert.deepEqual(aeroMirrorSeats(day, catalog), []);
  });

  it('keeps a stock exit of a catalogue product out of the entry mirror nothing to reconcile', () => {
    const day = [seat({ entLan: 9, productCode: '001192', movementType: 'S' })];
    // The tab is an entry mirror: whether a product is in the catalogue is the filter, not the direction.
    assert.deepEqual(
      aeroMirrorSeats(day, catalog).map((movement) => movement.entLan),
      [9],
    );
  });
});
