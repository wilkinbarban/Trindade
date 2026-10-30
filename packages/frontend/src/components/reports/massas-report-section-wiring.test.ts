import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The wiring of the Massas section, read as source.
 *
 * The behaviour is covered by the browser run in `__e2e__/massas-report-section.spec.ts`; what is asserted
 * here is that both report screens mount the same card with the day the report describes, and that the card
 * reads the reconciled day instead of the launches alone.
 */
const section = readFileSync(new URL('./MassasReportSection.tsx', import.meta.url), 'utf8');
const balanceBar = readFileSync(new URL('../../pages/massas/MassasBalanceBar.tsx', import.meta.url), 'utf8');
const reportsPage = readFileSync(new URL('../../pages/ReportsPage.tsx', import.meta.url), 'utf8');
const reportViewPage = readFileSync(new URL('../../pages/ReportViewPage.tsx', import.meta.url), 'utf8');

describe('massas report section wiring', () => {
  it('reads the reconciled day, not the launches the day was typed with', () => {
    assert.ok(section.includes('/massas-launch/report?date='));
    assert.equal(section.includes('/massas-launch?date='), false);
  });

  it('mounts on the report being filed, with the date read once for the whole report', () => {
    // One reading of the clock feeds both cards: two calls could land on different days at midnight, and the
    // massas the operator is reporting would stop matching the receipts beside them.
    assert.ok(reportsPage.includes('const reportDate = useMemo(() => saoPauloToday(), [])'));
    assert.ok(reportsPage.includes('<MassasReportSection date={reportDate}'));
    assert.equal(reportsPage.includes('date={saoPauloToday()}'), false);
  });

  it('shows the same balance bar the launch screen draws', () => {
    // Produzido, No ERP and the difference with the sign's colour: the supervisor reads the same three numbers
    // on the report and on Produção do dia, drawn by one component instead of two copies that drift.
    assert.ok(section.includes('testId="massas-report-balance"'));
    assert.ok(section.includes("from '../../pages/massas/MassasBalanceBar'"));
    assert.ok(balanceBar.includes('massas.balance.produced'));
    assert.ok(balanceBar.includes('massas.balance.registered'));
    assert.ok(balanceBar.includes('massas.balance.difference'));
  });

  it("mounts on the report being read, with that report's own day", () => {
    assert.ok(reportViewPage.includes('<MassasReportSection date={report.report_date}'));
  });

  it('keeps the card reachable and its controls at the 44px touch floor', () => {
    assert.ok(section.includes('data-testid="massas-report-section"'));
    assert.ok(section.includes('min-h-[44px]'));
  });
});
