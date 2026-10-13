import { test } from 'node:test';
import assert from 'node:assert/strict';
import { closeReportDatabases, setupReportsDb } from './reports-test-helper.js';
import { countPhotosByReport, getPhotoByPublicToken, getPhotosByReport, savePhoto } from './reports.lifecycle.service.js';

test('new photo tokens use 128-bit identifiers and preserve metadata and URL lookup', (t) => {
  t.after(closeReportDatabases);
  const db = setupReportsDb();
  db.prepare("INSERT INTO reports (id, user_id, turno, report_date) VALUES (1, 1, 'tarde', '2026-01-01')").run();
  const owner = { sub: 1, role: 'Trabalhador' };
  const photo = savePhoto(db, 1, 'synthetic.jpg', 4, 'image/jpeg', owner);
  assert.match(photo.public_token, /^[a-f0-9]{32}$/);
  assert.equal(photo.report_id, 1);
  assert.equal(photo.file_path, 'synthetic.jpg');
  assert.equal(photo.file_size, 4);
  assert.equal(photo.mime_type, 'image/jpeg');
  assert.equal(photo.url, `/api/reports/photos/${photo.id}`);
  assert.equal(photo.publicUrl, `/p/${photo.public_token}`);
  assert.deepEqual(getPhotoByPublicToken(db, photo.public_token), photo);
  const projected = getPhotosByReport(db, 1, 'https://trindademassas.com.br');
  assert.equal(projected[0].publicUrl, `https://trindademassas.com.br/p/${photo.public_token}`);
  const another = savePhoto(db, 1, 'another.jpg', 4, 'image/jpeg', owner);
  assert.match(another.public_token, /^[a-f0-9]{32}$/);
  assert.notEqual(another.public_token, photo.public_token);
  assert.equal(countPhotosByReport(db, 1), 2);
});

test('photo permission and five-photo limit reject without changing the public count', (t) => {
  t.after(closeReportDatabases);
  const db = setupReportsDb();
  db.prepare("INSERT INTO reports (id, user_id, turno, report_date) VALUES (1, 1, 'tarde', '2026-01-01')").run();
  const owner = { sub: 1, role: 'Trabalhador' };
  assert.throws(() => savePhoto(db, 1, 'denied.jpg', 4, 'image/jpeg', { sub: 2, role: 'Trabalhador' }), {
    status: 403, message: 'Registro somente leitura. Fotos só podem ser alteradas pelo criador no dia atual ou no dia anterior.',
  });
  assert.equal(countPhotosByReport(db, 1), 0);
  for (let index = 0; index < 5; index += 1) savePhoto(db, 1, `fixture-${index}.jpg`, 4, 'image/jpeg', owner);
  const before = getPhotosByReport(db, 1);
  assert.throws(() => savePhoto(db, 1, 'sixth.jpg', 4, 'image/jpeg', owner), {
    status: 409, message: 'Limite de 5 fotos por relatório atingido.',
  });
  assert.equal(countPhotosByReport(db, 1), 5);
  assert.deepEqual(getPhotosByReport(db, 1), before);
});
