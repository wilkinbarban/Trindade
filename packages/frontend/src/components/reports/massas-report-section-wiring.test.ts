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
const reportsPage = readFileSync(new URL('../../pages/ReportsPage.tsx', import.meta.url), 'utf8');
const reportViewPage = readFileSync(new URL('../../pages/ReportViewPage.tsx', import.meta.url), 'utf8');

describe('massas report section wiring', () => {
  it('reads the reconciled day, not the launches the day was typed with', () => {
    assert.ok(section.includes('/massas-launch/report?date='));
    assert.equal(section.includes('/massas-launch?date='), false);
  });

  it('mounts on the report being filed, with today as its day', () => {
    assert.ok(reportsPage.includes('<MassasReportSection date={saoPauloToday()}'));
  });

  it("mounts on the report being read, with that report's own day", () => {
    assert.ok(reportViewPage.includes('<MassasReportSection date={report.report_date}'));
  });

  it('keeps the card reachable and its controls at the 44px touch floor', () => {
    assert.ok(section.includes('data-testid="massas-report-section"'));
    assert.ok(section.includes('min-h-[44px]'));
  });
});
