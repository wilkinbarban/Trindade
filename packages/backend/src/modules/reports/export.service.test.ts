import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import Database from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { generateWhatsAppText } from './export.service.js';

/**
 * Creates an in-memory database with schema + seed, then inserts a test report
 * and returns both the db and report ID.
 */
function setupDb(): { db: Database.Database; reportId: number } {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');

  const schemaPath = join(import.meta.dirname, '../../db/schema.sql');
  const seedPath = join(import.meta.dirname, '../../db/seed.sql');
  db.exec(readFileSync(schemaPath, 'utf-8'));
  db.exec(readFileSync(seedPath, 'utf-8'));

  // Insert test user
  db.exec(`
    INSERT OR IGNORE INTO users (id, username, password_hash, display_name, role_id, is_active)
    VALUES (10, 'export-test', 'hash', 'Test User', 1, 1)
  `);

  // Create a report with various data
  const reportResult = db.prepare(
    `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
     VALUES (100, 10, 'tarde', '2026-06-12', 'Tudo em ordem, sem novidades.', '2026-06-12 18:00:00')`
  ).run();
  const reportId = reportResult.lastInsertRowid as number;

  // Add items (tasks)
  db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, 1, 1); // Pátio ✅
  db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, 2, 0); // Câmaras ⬜
  db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, 3, 1); // Ferramentas ✅
  db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, 4, 1); // Uniforme ✅

  // Add temperatures
  db.prepare(`INSERT INTO report_temperatures (report_id, location, value) VALUES (?, ?, ?)`).run(reportId, 'Câmara Principal', -18);
  db.prepare(`INSERT INTO report_temperatures (report_id, location, value) VALUES (?, ?, ?)`).run(reportId, 'Câmara de Resfriamento', -22);

  return { db, reportId };
}

describe('Export Service (Generador Inteligente)', () => {
  let db: Database.Database;
  let reportId: number;

  before(() => {
    const result = setupDb();
    db = result.db;
    reportId = result.reportId;
  });

  after(() => {
    db.close();
  });

  it('returns null for non-existent report', () => {
    const text = generateWhatsAppText(db, 99999);
    assert.strictEqual(text, null);
  });

  it('fails explicitly when a report item loses its required task reference', () => {
    db.pragma('foreign_keys = OFF');
    db.prepare('UPDATE report_items SET task_id = 99999 WHERE report_id = ? AND task_id = 1').run(reportId);
    assert.throws(() => generateWhatsAppText(db, reportId), /Missing required report reference: item .* task 99999/);
    db.prepare('UPDATE report_items SET task_id = 1 WHERE report_id = ? AND task_id = 99999').run(reportId);
    db.pragma('foreign_keys = ON');
  });

  it('renders multiple temperature readings on one labeled WhatsApp line', () => {
    db.prepare(`INSERT INTO report_temperatures (report_id, location, reading_index, value) VALUES (?, ?, ?, ?)`)
      .run(reportId, 'Câmara Principal', 2, -17.5);
    const text = generateWhatsAppText(db, reportId)!;
    assert.ok(text.includes('🌡️ Câmara Principal: *-18°C* / *-17.5°C*'));
  });

  it('generates output with emoji section headers', () => {
    const text = generateWhatsAppText(db, reportId);
    assert.ok(text, 'should return text');

    // Tarde shift should have 🌅 emoji
    assert.ok(text!.includes('🌅'), `Should include 🌅 emoji for tarde shift. Got: ${text!.slice(0, 100)}`);
    assert.ok(text!.includes('*RELATÓRIO'), 'Should include RELATÓRIO header');
  });

  it('includes metadata: date and responsible', () => {
    const text = generateWhatsAppText(db, reportId);
    assert.ok(text!.includes('📅'), 'Should include date icon');
    assert.ok(text!.includes('12/06/2026'), 'Should include formatted date');
    assert.ok(text!.includes('👤'), 'Should include responsible icon');
    assert.ok(text!.includes('Test User'), 'Should include user display name');
  });

  it('all text content is in Brazilian Portuguese', () => {
    const text = generateWhatsAppText(db, reportId)!;
    assert.ok(typeof text === 'string');
    assert.ok(text.length > 0);

    // Category headers should use name_pt
    assert.ok(
      text.includes('HIGIENE E ORGANIZAÇÃO') || text.includes('Higiene e Organização'),
      'Should include Portuguese category name'
    );
    assert.ok(
      text.includes('TEMPERATURAS') || text.includes('Temperaturas'),
      'Should include Temperaturas category'
    );

    // Task names should use name_pt
    const ptTerms = (db.prepare('SELECT name_pt FROM report_tasks WHERE id IN (1, 2, 3, 4) ORDER BY id').all() as { name_pt: string }[]).map((row) => row.name_pt);

    for (const term of ptTerms) {
      assert.ok(
        text.includes(term),
        `Should include Portuguese task name: "${term}"`
      );
    }

    // Footer
    assert.ok(
      text.includes('Trindade Massas'),
      'Should include Trindade Massas in footer'
    );
  });

  it('task sections match categories with icons', () => {
    const text = generateWhatsAppText(db, reportId)!;

    // Checked items should have ✅
    const tasks = db.prepare('SELECT id, name_pt FROM report_tasks WHERE id IN (1, 2, 3)').all() as { id: number; name_pt: string }[];
    assert.ok(text.includes(`✅ ${tasks.find((task) => task.id === 1)!.name_pt}`), 'Checked item should have ✅');
    assert.ok(text.includes(`✅ ${tasks.find((task) => task.id === 3)!.name_pt}`), 'Checked item should have ✅');

    // Unchecked items should have ⬜
    assert.ok(text.includes(`⬜ ${tasks.find((task) => task.id === 2)!.name_pt}`), 'Unchecked item should have ⬜');
  });

  it('temperature section is formatted correctly', () => {
    const text = generateWhatsAppText(db, reportId)!;

    assert.ok(
      text.includes('🌡️'),
      'Should include temperature emoji'
    );

    // Check for temperature values with °C
    assert.ok(
      text.includes('-18') && text.includes('°C'),
      'Should include temperature value with °C'
    );
  });




  it('includes Portuguese photo links section with public URLs', () => {
    db.prepare(
      `INSERT INTO report_photos (report_id, file_path, file_size, mime_type, public_token, created_at)
       VALUES (?, 'photo.jpg', 10, 'image/jpeg', 'public-token-123', '2026-06-12 18:05:00')`
    ).run(reportId);

    const text = generateWhatsAppText(db, reportId, 'https://trindade.example')!;

    assert.ok(text.includes('📸 *FOTOS:*'), 'Should include Portuguese photos section');
    assert.ok(text.includes('https://trindade.example/p/public-token-123'));
  });

  it('includes notes section when present', () => {
    const text = generateWhatsAppText(db, reportId)!;

    assert.ok(
      text.includes('📝'),
      'Should include notes emoji'
    );
    assert.ok(
      text.includes('Observações'),
      'Should include Observações header'
    );
    assert.ok(
      text.includes('Tudo em ordem'),
      'Should include note content'
    );
  });

  it('noite shift uses 🌙 emoji', () => {
    // Create a noite report
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
       VALUES (101, 10, 'noite', '2026-06-12', 'Turno da noite', '2026-06-12 22:00:00')`
    ).run();

    // Add at least one item so categories appear
    db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(101, 1, 1);

    const text = generateWhatsAppText(db, 101);
    assert.ok(text, 'should return text for noite report');
    assert.ok(text!.includes('🌙'), `Should include 🌙 emoji for noite shift. Got: ${text!.slice(0, 100)}`);
    assert.ok(text!.includes('NOITE'), 'Should include NOITE in header');
  });


  it('includes selected products while keeping WhatsApp text in Brazilian Portuguese', () => {
    db.prepare(`
      INSERT INTO report_categories (id, name_pt, name_es, category_type, sort_order, is_active)
      VALUES (801, 'Montagem Assaí', 'Montaje Assaí', 'check_assai', 801, 1)
    `).run();
    db.prepare(`
      INSERT INTO report_tasks (id, category_id, name_pt, name_es, is_active)
      VALUES (1801, 801, 'Produtos montados', 'Productos montados', 1)
    `).run();
    db.prepare(`
      INSERT INTO report_items (report_id, task_id, checked, selected_products)
      VALUES (?, 1801, 1, ?)
    `).run(reportId, JSON.stringify(['Nhoque Kg', 'Disgo 500g']));

    const text = generateWhatsAppText(db, reportId)!;

    assert.ok(text.includes('MONTAGEM ASSAÍ'), 'Should include Portuguese product category name');
    assert.ok(text.includes('Produtos montados'), 'Should include Portuguese product task name');
    assert.ok(text.includes('Nhoque Kg'), 'Should include selected Assaí product');
    assert.ok(text.includes('Disgo 500g'), 'Should preserve exact product spelling from the spec');
    assert.ok(!text.includes('Productos montados'), 'Should not include Spanish task name');
  });

  it('is strictly Portuguese regardless of what UI locale would be', () => {
    // This test proves the UI independence spec requirement:
    // "GIVEN the user's UI is set to Spanish or English,
    //  WHEN the user requests a WhatsApp export,
    //  THEN the system MUST generate the exported text in Portuguese"

    const text = generateWhatsAppText(db, reportId)!;

    // Should NOT contain Spanish terms from name_es
    const esTerms = [
      'Patio organizado',
      'Cámaras limpias',
      'Herramientas guardadas',
    ];

    for (const term of esTerms) {
      assert.ok(
        !text.includes(term),
        `Should NOT include Spanish term: "${term}". Output is Portuguese-only.`
      );
    }

    // Should contain Portuguese terms
    const ptTerms = (db.prepare('SELECT name_pt FROM report_tasks WHERE id IN (1, 2, 3) ORDER BY id').all() as { name_pt: string }[]).map((row) => row.name_pt);

    for (const term of ptTerms) {
      assert.ok(
        text.includes(term),
        `Should include Portuguese term: "${term}"`
      );
    }
  });

  it('shortens the category divider lines by 10% (16 chars) and inserts cold chamber warnings when thresholds are exceeded', () => {
    // Insert a new report with no user notes but with high cold chamber temperatures
    db.prepare(`
      INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
      VALUES (200, 10, 'tarde', '2026-06-12', NULL, '2026-06-12 18:00:00')
    `).run();

    // Chamber 1 at 5°C (warning >4)
    db.prepare(`INSERT INTO report_temperatures (report_id, location, value) VALUES (?, ?, ?)`).run(200, 'Câmara Principal', 5);
    // Chamber 2 at 9°C (warning >8)
    db.prepare(`INSERT INTO report_temperatures (report_id, location, value) VALUES (?, ?, ?)`).run(200, 'Câmara de Resfriamento', 9);

    const text = generateWhatsAppText(db, 200)!;

    // Verify dividers are 14 chars long (we should not find 16 chars, but should find 14 chars)
    assert.ok(text.includes('━━━━━━━━━━━━━━'));
    assert.ok(!text.includes('━━━━━━━━━━━━━━━━'));

    // Verify warnings
    assert.ok(text.includes('📝 *Observações:*'));
    assert.ok(text.includes('A Câmara Principal tem uma temperatura de 5°C, recomenda-se limpar os ares-condicionados.'));
    assert.ok(text.includes('A Câmara de Resfriamento tem uma temperatura de 9°C, recomenda-se revisar os ares-condicionados, os produtos podem estragar.'));

    // Now test with user notes combined
    db.prepare(`UPDATE reports SET notes = 'Usuário anotou algo.' WHERE id = 200`).run();
    const textWithNotes = generateWhatsAppText(db, 200)!;
    assert.ok(textWithNotes.includes('Usuário anotou algo.\n\nA Câmara Principal tem uma temperatura de 5°C, recomenda-se limpar os ares-condicionados.\nA Câmara de Resfriamento tem uma temperatura de 9°C, recomenda-se revisar os ares-condicionados, os produtos podem estragar.'));
  });
});
