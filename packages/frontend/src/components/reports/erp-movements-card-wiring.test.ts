import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The unified ERP movements card in ReportsPage (S5, S6, S8).
 *
 * Web and Android show one unified card "Movimentações do ERP (Massas e Recebimento)" with both blocks
 * and a single header button "[ 🔄 Sincronizar com ERP ]" that refreshes both massas and receipts.
 * The separate receipts card in categories is gone, and the local balance bar is removed.
 */
const reportsPage = readFileSync(new URL('../../pages/ReportsPage.tsx', import.meta.url), 'utf8');
const massasSection = readFileSync(new URL('./MassasReportSection.tsx', import.meta.url), 'utf8');

describe('unified ERP movements card wiring (S5, S6, S8)', () => {
  it('unifies massas and receipts in a single card with the unified title', () => {
    assert.ok(reportsPage.includes("t('reports.erpMovementsTitle'"));
    // The unified card houses both sections together
    assert.ok(reportsPage.includes('<MassasReportSection'));
    assert.ok(reportsPage.includes('<ReceiptsSection'));
  });

  it('provides a single [ 🔄 Sincronizar com ERP ] button in the card header', () => {
    assert.ok(reportsPage.includes('data-testid="erp-movements-sync"'));
    assert.ok(reportsPage.includes('t(\'massas.syncHeader.action\'') || reportsPage.includes('t(\'reports.erpMovementsSync\''));
  });

  it('eliminates the separate receipts card from the categories iteration (S5)', () => {
    // ReceiptsSection is no longer rendered in its own standalone card inside the categories loop
    assert.equal(
      reportsPage.includes('{cat.id === ERP_RECEIPT_CATEGORY_ID && usesErpReceipts(saoPauloToday()) ? ('),
      false,
    );
  });

  it('removes the comparative balance bar from the report section (Q1, S5)', () => {
    assert.equal(massasSection.includes('MassasBalanceBar'), false);
    assert.equal(massasSection.includes('testId="massas-report-balance"'), false);
  });
});
