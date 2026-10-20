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
  });

  it('formats message showing strictly total units per product and overall total', () => {
    const view: MassasLaunchDayView = {
      date: '2026-10-20',
      products: [
        { code: '1045', name: 'MASSA CASEIRA', unitsPerBox: 10, totalBoxes: 8, totalUnits: 80 },
        { code: '39', name: 'MASSA DISCO 200G', unitsPerBox: 10, totalBoxes: 0, totalUnits: 0 },
        { code: '84', name: 'MASSA DISCO 500G', unitsPerBox: 20, totalBoxes: 2, totalUnits: 40 },
      ],
      launches: [],
      totalBoxes: 10,
      totalUnits: 120,
    };

    const msg = massasMessage(view);
    assert.match(msg, /🍝 \*Lançamento de Massas — 20\/10\/2026\*/);
    assert.match(msg, /• MASSA CASEIRA: 80 un/);
    assert.match(msg, /• MASSA DISCO 500G: 40 un/);
    // Should NOT include products with 0 units
    assert.doesNotMatch(msg, /MASSA DISCO 200G/);
    // Should show overall total in units
    assert.match(msg, /\*Total Geral: 120 un\*/);
    // Should not show box count in the product line or total
    assert.doesNotMatch(msg, /8 caixas/);
    assert.doesNotMatch(msg, /10 caixas/);
  });
});
