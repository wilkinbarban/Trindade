import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  massasMessage,
  POLL_INTERVAL_MS,
  shouldPollMassas,
  extractOperators,
  filterLaunchesByOperator,
  calculateOperatorSubtotal,
  groupProductsFromLaunches,
  productWorkerNames,
  compareWorkerNames,
  type MassasLaunchDayView,
  type MassaIndividualLaunch,
  type MassaProductTotal,
} from './massas.js';

describe('massas message formatting', () => {
  it('formats empty day when no massas have been launched', () => {
    const emptyView: MassasLaunchDayView = {
      date: '2026-10-20',
      products: [
        { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10, totalBoxes: 0, totalUnits: 0 },
        { code: '39', name: 'MASSA DISCO 200G', unitsPerBox: 10, totalBoxes: 0, totalUnits: 0 },
      ],
      launches: [],
      totalBoxes: 0,
      totalUnits: 0,
    };

    const msg = massasMessage(emptyView);
    assert.match(msg, /🍝 \*Lançamento de Massas — 20\/10\/2026\*/);
    assert.match(msg, /Nenhum lançamento registrado até agora\./);
    assert.doesNotMatch(msg, /\*Total Geral:/);
    assert.doesNotMatch(msg, /\*Lançado por:\*/);
  });

  it('formats message showing units per product and operators list, without total geral', () => {
    const view: MassasLaunchDayView = {
      date: '2026-10-20',
      products: [
        { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10, totalBoxes: 8, totalUnits: 80 },
        { code: '39', name: 'MASSA DISCO 200G', unitsPerBox: 10, totalBoxes: 0, totalUnits: 0 },
        { code: '84', name: 'MASSA DISCO 500G', unitsPerBox: 20, totalBoxes: 2, totalUnits: 40 },
      ],
      launches: [
        {
          id: 1,
          launch_date: '2026-10-20',
          product_code: '1045',
          product_name: 'MASSA CASEIRA',
          boxes: 5,
          units: 50,
          user_id: 1,
          username: 'joao',
          display_name: 'João Silva',
          created_at: '2026-10-20T10:00:00Z',
          updated_at: '2026-10-20T10:00:00Z',
        },
        {
          id: 2,
          launch_date: '2026-10-20',
          product_code: '1045',
          product_name: 'MASSA CASEIRA',
          boxes: 3,
          units: 30,
          user_id: 2,
          username: 'maria',
          display_name: 'Maria Oliveira',
          created_at: '2026-10-20T10:30:00Z',
          updated_at: '2026-10-20T10:30:00Z',
        },
        {
          id: 3,
          launch_date: '2026-10-20',
          product_code: '84',
          product_name: 'MASSA DISCO 500G',
          boxes: 2,
          units: 40,
          user_id: 1,
          username: 'joao',
          display_name: 'João Silva',
          created_at: '2026-10-20T11:00:00Z',
          updated_at: '2026-10-20T11:00:00Z',
        },
      ],
      totalBoxes: 10,
      totalUnits: 120,
    };

    const msg = massasMessage(view);
    const expected = [
      '🍝 *Lançamento de Massas — 20/10/2026*',
      '',
      '*Resumo Coletivo:*',
      '• CASEIRA: 80 un (8 cx)',
      '• DISCO 500G: 40 un (2 cx)',
      '',
      '*Por Trabalhador:*',
      '👤 *João Silva:*',
      '• CASEIRA: 50 un (5 cx)',
      '• DISCO 500G: 40 un (2 cx)',
      '',
      '👤 *Maria Oliveira:*',
      '• CASEIRA: 30 un (3 cx)',
    ].join('\n');

    assert.strictEqual(msg, expected);
    assert.doesNotMatch(msg, /MASSA /);
    assert.doesNotMatch(msg, /\*Lançado por:\*/);
  });
});

describe('massas live polling guards', () => {
  it('defines 15-second polling interval', () => {
    assert.strictEqual(POLL_INTERVAL_MS, 15000);
  });

  it('allows polling when tab is visible and there is no active user interaction', () => {
    const allowed = shouldPollMassas({
      visibilityState: 'visible',
      isEditing: false,
      isSubmitting: false,
    });
    assert.strictEqual(allowed, true);
  });

  it('suspends polling when document is hidden (background tab or locked screen)', () => {
    const hidden = shouldPollMassas({
      visibilityState: 'hidden',
      isEditing: false,
      isSubmitting: false,
    });
    assert.strictEqual(hidden, false);

    const prerender = shouldPollMassas({
      visibilityState: 'prerender',
      isEditing: false,
      isSubmitting: false,
    });
    assert.strictEqual(prerender, false);
  });

  it('pauses polling while user is actively editing an individual launch', () => {
    const paused = shouldPollMassas({
      visibilityState: 'visible',
      isEditing: true,
      isSubmitting: false,
    });
    assert.strictEqual(paused, false);
  });

  it('pauses polling while user is actively submitting launches', () => {
    const paused = shouldPollMassas({
      visibilityState: 'visible',
      isEditing: false,
      isSubmitting: true,
    });
    assert.strictEqual(paused, false);
  });

  it('pauses polling when both editing and submitting', () => {
    const paused = shouldPollMassas({
      visibilityState: 'visible',
      isEditing: true,
      isSubmitting: true,
    });
    assert.strictEqual(paused, false);
  });

  it('suspends polling when viewing past dates (isToday: false)', () => {
    const past = shouldPollMassas({
      visibilityState: 'visible',
      isEditing: false,
      isSubmitting: false,
      isToday: false,
    });
    assert.strictEqual(past, false);
  });
});

describe('massas operator filtering and subtotal', () => {
  const sampleLaunches: MassaIndividualLaunch[] = [
    {
      id: 1,
      launch_date: '2026-10-21',
      product_code: '1045',
      product_name: 'MASSA CASEIRA',
      boxes: 4,
      units: 40,
      user_id: 10,
      username: 'carlos',
      display_name: 'Carlos Bento',
      created_at: '2026-10-21T09:00:00Z',
      updated_at: '2026-10-21T09:00:00Z',
    },
    {
      id: 2,
      launch_date: '2026-10-21',
      product_code: '1047',
      product_name: 'MASSA ROLO 2KG',
      boxes: 3,
      units: 18,
      user_id: 20,
      username: 'ana',
      display_name: 'Ana Silva',
      created_at: '2026-10-21T10:00:00Z',
      updated_at: '2026-10-21T10:00:00Z',
    },
    {
      id: 3,
      launch_date: '2026-10-21',
      product_code: '39',
      product_name: 'MASSA DISCO 200G',
      boxes: 2,
      units: 20,
      user_id: 10,
      username: 'carlos',
      display_name: 'Carlos Bento',
      created_at: '2026-10-21T11:00:00Z',
      updated_at: '2026-10-21T11:00:00Z',
    },
  ];

  it('extracts unique operators sorted alphabetically', () => {
    const ops = extractOperators(sampleLaunches);
    assert.strictEqual(ops.length, 2);
    assert.deepStrictEqual(ops[0], { id: 20, name: 'Ana Silva' });
    assert.deepStrictEqual(ops[1], { id: 10, name: 'Carlos Bento' });
  });

  it('filters launches by operator id or returns all when all is selected', () => {
    assert.strictEqual(filterLaunchesByOperator(sampleLaunches, 'all').length, 3);
    const carlosLaunches = filterLaunchesByOperator(sampleLaunches, 10);
    assert.strictEqual(carlosLaunches.length, 2);
    assert.ok(carlosLaunches.every((l) => l.user_id === 10));

    const anaLaunches = filterLaunchesByOperator(sampleLaunches, 20);
    assert.strictEqual(anaLaunches.length, 1);
    assert.strictEqual(anaLaunches[0].id, 2);
  });

  it('calculates subtotal of boxes and units for selected operator', () => {
    assert.strictEqual(calculateOperatorSubtotal(sampleLaunches, 'all'), null);
    const carlosSubtotal = calculateOperatorSubtotal(sampleLaunches, 10);
    assert.deepStrictEqual(carlosSubtotal, { boxes: 6, units: 60 });

    const anaSubtotal = calculateOperatorSubtotal(sampleLaunches, 20);
    assert.deepStrictEqual(anaSubtotal, { boxes: 3, units: 18 });
  });
});



describe('massas collective summary derived from filtered launches', () => {
  const catalog: MassaProductTotal[] = [
    { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10, totalBoxes: 8, totalUnits: 80 },
    { code: '84', name: 'MASSA DISCO 500G', unitsPerBox: 20, totalBoxes: 2, totalUnits: 40 },
  ];

  const launches: MassaIndividualLaunch[] = [
    {
      id: 1,
      launch_date: '2026-10-20',
      product_code: '1045',
      product_name: 'MASSA CASEIRA',
      boxes: 5,
      units: 50,
      user_id: 1,
      username: 'joao',
      display_name: 'João Silva',
      created_at: '2026-10-20T10:00:00Z',
      updated_at: '2026-10-20T10:00:00Z',
    },
    {
      id: 2,
      launch_date: '2026-10-20',
      product_code: '1045',
      product_name: 'MASSA CASEIRA',
      boxes: 3,
      units: 30,
      user_id: 2,
      username: 'maria',
      display_name: 'Maria Oliveira',
      created_at: '2026-10-20T10:30:00Z',
      updated_at: '2026-10-20T10:30:00Z',
    },
    {
      id: 3,
      launch_date: '2026-10-20',
      product_code: '84',
      product_name: 'MASSA DISCO 500G',
      boxes: 2,
      units: 40,
      user_id: 1,
      username: 'joao',
      display_name: 'João Silva',
      created_at: '2026-10-20T11:00:00Z',
      updated_at: '2026-10-20T11:00:00Z',
    },
  ];

  it('consolidates every launch when all workers are selected', () => {
    const products = groupProductsFromLaunches(launches, catalog);
    assert.deepStrictEqual(
      products.map((p) => [p.code, p.name, p.totalBoxes, p.totalUnits]),
      [
        ['1045', 'MASSA CASEIRA', 8, 80],
        ['84', 'MASSA DISCO 500G', 2, 40],
      ]
    );
    assert.deepStrictEqual(productWorkerNames(launches, '1045'), [
      'João Silva',
      'Maria Oliveira',
    ]);
  });

  it('consolidates only the selected worker launches', () => {
    const onlyMaria = filterLaunchesByOperator(launches, 2);
    const products = groupProductsFromLaunches(onlyMaria, catalog);
    assert.deepStrictEqual(
      products.map((p) => [p.code, p.totalBoxes, p.totalUnits]),
      [['1045', 3, 30]]
    );
    assert.deepStrictEqual(productWorkerNames(onlyMaria, '1045'), ['Maria Oliveira']);
  });

  it('drops products without units and keeps the catalog order', () => {
    const products = groupProductsFromLaunches([launches[2], launches[0]], catalog);
    assert.deepStrictEqual(
      products.map((p) => p.code),
      ['1045', '84']
    );
  });
});

describe('massas worker ordering', () => {
  it('orders names by code unit, not by locale, so web and android agree', () => {
    const names = ['Álvaro Nunes', 'Bruno Lima', 'Ana Souza'];
    assert.deepStrictEqual(names.sort(compareWorkerNames), [
      'Ana Souza',
      'Bruno Lima',
      'Álvaro Nunes',
    ]);
  });
});
