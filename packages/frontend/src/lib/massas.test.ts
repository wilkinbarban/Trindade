import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { massasMessage, type MassasLaunchDayView } from './massas.js';

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
    assert.match(msg, /🍝 \*Lançamento de Massas — 20\/10\/2026\*/);
    assert.match(msg, /• MASSA CASEIRA: 80 un/);
    assert.match(msg, /• MASSA DISCO 500G: 40 un/);
    // Should NOT include products with 0 units
    assert.doesNotMatch(msg, /MASSA DISCO 200G/);
    // Should NOT have Total Geral
    assert.doesNotMatch(msg, /\*Total Geral:/);
    // Should list unique operators
    assert.match(msg, /\*Lançado por:\* João Silva, Maria Oliveira/);
  });
});
