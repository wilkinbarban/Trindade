import { test } from 'node:test';
import assert from 'node:assert/strict';
import { closeReportDatabases, setupReportsDb } from './reports-test-helper.js';
import {
  PUBLIC_TOKEN_TTL_MS,
  getPhotoByPublicToken,
  isPublicTokenLive,
} from './reports.lifecycle.service.js';

function sqliteTimestamp(ms: number): string {
  return new Date(ms).toISOString().slice(0, 19).replace('T', ' ');
}

test('a photo link is live for 24 hours from issue and dead afterwards', (t) => {
  t.after(closeReportDatabases);
  const db = setupReportsDb();
  db.prepare("INSERT INTO reports (id, user_id, turno, report_date) VALUES (1, 1, 'tarde', '2026-01-01')").run();
  const now = Date.parse('2026-06-15T12:00:00.000Z');
  const insert = db.prepare(
    `INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type, public_token, created_at)
     VALUES (?, 1, ?, 4, 'image/jpeg', ?, ?)`
  );
  const cases: Array<[number, number, string]> = [
    [1, 0, 'issued at the current moment'],
    [2, 23 * 60 * 60 * 1000, 'issued 23 hours ago'],
    [3, PUBLIC_TOKEN_TTL_MS, 'issued exactly 24 hours ago'],
    [4, PUBLIC_TOKEN_TTL_MS + 1000, 'issued just over 24 hours ago'],
    [5, 30 * 24 * 60 * 60 * 1000 - 1000, 'still inside the 30-day file retention'],
  ];
  for (const [id, age, label] of cases) {
    const createdAt = sqliteTimestamp(now - age);
    insert.run(id, `photo-${id}.jpg`, `token-${id}`, createdAt);
    const expectedLive = age < PUBLIC_TOKEN_TTL_MS;
    assert.equal(isPublicTokenLive(createdAt, now), expectedLive, label);
    const resolved = getPhotoByPublicToken(db, `token-${id}`, now);
    if (expectedLive) assert.ok(resolved, label);
    else assert.equal(resolved, null, label);
  }
  assert.equal(db.prepare('SELECT COUNT(*) FROM report_photos').pluck().get(), 5, 'rows are kept regardless of the link window');
});

test('an unreadable timestamp never makes a link live', (t) => {
  t.after(closeReportDatabases);
  const db = setupReportsDb();
  db.prepare("INSERT INTO reports (id, user_id, turno, report_date) VALUES (1, 1, 'tarde', '2026-01-01')").run();
  db.prepare(
    `INSERT INTO report_photos (id, report_id, file_path, file_size, mime_type, public_token, created_at)
     VALUES (1, 1, 'broken.jpg', 4, 'image/jpeg', 'bad-date', 'not-a-date')`
  ).run();
  assert.equal(isPublicTokenLive('not-a-date'), false);
  assert.equal(getPhotoByPublicToken(db, 'bad-date'), null);
});
