import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

/**
 * The wiring of the Recebimento sync, read as source.
 *
 * The behaviour is covered by the browser run in `__e2e__/report-receipts.spec.ts`; what is asserted here is
 * that the section keeps the three things that make the round trip safe: it comes back to its own page, it
 * stores the whole snapshot, and it counts only the supplies.
 */
const section = readFileSync(new URL('./ReceiptsSection.tsx', import.meta.url), 'utf8');

describe('receipts sync wiring', () => {
  it('hands the shortcut this page as the return, so the reporter never leaves the report', () => {
    assert.ok(section.includes("aeroBridgeCallbackFor('movimentos', location.pathname)"));
    assert.ok(section.includes('aeroMovementsEnvelope('));
  });

  it('reads the same callback the launch screen reads, and only for the movements action', () => {
    assert.ok(section.includes("parsed.kind !== 'bridge' || parsed.action !== 'movimentos'"));
    assert.ok(section.includes('window.history.replaceState'));
  });

  it('stores the whole day and counts the supplies lens, never the raw answer', () => {
    assert.ok(section.includes('buildAeroMovementsSnapshot(date, read.movements)'));
    assert.ok(section.includes("receiptsSyncFeedback({ status: 'ok', count: stored?.insumos ?? 0 })"));
    assert.equal(/read\.movements\.length/.test(section), false, 'the raw seat count must never reach the dialog');
  });

  it('keeps the sync and refresh controls at the 44px touch floor', () => {
    assert.ok(section.includes('data-testid="receipts-sync"'));
    assert.equal((section.match(/min-h-\[44px\]/g) ?? []).length >= 2, true);
  });
});
