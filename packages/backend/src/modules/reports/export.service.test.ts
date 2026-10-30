import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import Database from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { generateWhatsAppText, stripMassasPrefix } from './export.service.js';

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
  db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, 2, 0); // Câmaras ◻️
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

    // Task names should use name_pt, and the sample is the tasks the export carries: the unmarked one is
    // not in the message, so requiring its name would have been asserting the defect.
    const ptTerms = (db.prepare('SELECT name_pt FROM report_tasks WHERE id IN (1, 3, 4) ORDER BY id').all() as { name_pt: string }[]).map((row) => row.name_pt);

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

    // An item nobody marked is not work that was done, so it is not in the message: the text says what
    // the operator did, and a list of everything that was not done is noise in the group's chat -- and
    // the reason the counts in the message did not match the report.
    assert.ok(!text.includes(tasks.find((task) => task.id === 2)!.name_pt), 'Unchecked item must not be exported');
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
    const ptTerms = (db.prepare('SELECT name_pt FROM report_tasks WHERE id IN (1, 3) ORDER BY id').all() as { name_pt: string }[]).map((row) => row.name_pt);

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

    // 5 y 9 grados están por debajo de 10, así que no llevan advertencia: la banda de limpieza empieza en
    // 10 y la crítica en más de 15. Antes estas dos temperaturas avisaban, con umbrales más bajos.
    assert.ok(!text.includes('recomenda-se'), 'Below 10 degrees there is nothing to warn about');

    // Now test with user notes combined
    db.prepare(`UPDATE reports SET notes = 'Usuário anotou algo.' WHERE id = 200`).run();
    const textWithNotes = generateWhatsAppText(db, 200)!;
    assert.ok(textWithNotes.includes('Usuário anotou algo.'));
    assert.ok(!textWithNotes.includes('recomenda-se'), 'The note is written and nothing is warned about');

    // --- La banda de la regla nueva, con sus dos bordes ---
    // Más de 15 avisa por riesgo de producto; 15 exacto ya es sólo limpieza, y 10 exacto también.
    const subir = (location: string, value: number) =>
      db.prepare('UPDATE report_temperatures SET value = ? WHERE report_id = 200 AND location = ?').run(value, location);
    subir('Câmara Principal', 16);
    subir('Câmara de Resfriamento', 12);
    const caliente = generateWhatsAppText(db, 200)!;
    assert.ok(
      caliente.includes('A Câmara Principal tem uma temperatura de 16°C, recomenda-se revisar os ares-condicionados, os produtos podem estragar.'),
      'Above 15 warns about the product',
    );
    assert.ok(
      caliente.includes('A Câmara de Resfriamento tem uma temperatura de 12°C, recomenda-se limpar os ares-condicionados.'),
      'Between 10 and 15 asks for cleaning',
    );

    subir('Câmara Principal', 15);
    subir('Câmara de Resfriamento', 10);
    const bordes = generateWhatsAppText(db, 200)!;
    assert.ok(!bordes.includes('os produtos podem estragar'), '15 is not above 15');
    assert.ok(bordes.includes('A Câmara Principal tem uma temperatura de 15°C, recomenda-se limpar os ares-condicionados.'));
    assert.ok(bordes.includes('A Câmara de Resfriamento tem uma temperatura de 10°C, recomenda-se limpar os ares-condicionados.'));
  });

  it('always prints the massas section, with the production both screens recorded', () => {
    db.exec(`
      INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
      VALUES ('2026-06-12', '1045', 4, 10, '2026-06-12 10:00:00', '2026-06-12 10:00:00')
    `);
    db.exec(`
      INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
      VALUES ('2026-06-12', '1047', 2, 10, '2026-06-12 11:00:00', '2026-06-12 11:00:00')
    `);
    // The tablet of the company loaded one box that never came through Trindade.
    db.exec(`
      INSERT INTO aero_movements
        (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
      VALUES ('2026-06-12', 9001, '1047', 'MASSA ROLO 2KG', 6, '2026-06-12', 'TIAGO', 'E', '2026-06-12T12:00:00.000Z')
    `);

    // No fourth argument: the section is not optional anymore.
    const text = generateWhatsAppText(db, reportId)!;

    assert.ok(text.includes('🍝 *LANÇAMENTO DE MASSAS*'));
    assert.ok(text.includes('Por Operário:'));
    assert.ok(text.includes('👤 Test User:'));
    assert.ok(text.includes('   - CASEIRA: 40 un'));
    assert.ok(text.includes('   - ROLO 2KG: 12 un'));
    // The operator who only exists in the ERP appears with the name they typed there.
    assert.ok(text.includes('👤 TIAGO:'));
    assert.ok(text.includes('   - ROLO 2KG: 6 un'));
    assert.ok(text.includes('Resumo Coletivo:'));
    assert.ok(text.includes('📦 CASEIRA: 40 un'));
    assert.ok(text.includes('📦 ROLO 2KG: 18 un'));
    assert.ok(!text.includes('MASSA '), 'no product line keeps the MASSA prefix');
    assert.ok(!text.includes('Total de Massas'));
    assert.ok(!text.includes('cx'));

    // The section still sits before the notes, as the group reads it.
    const massasIndex = text.indexOf('🍝 *LANÇAMENTO DE MASSAS*');
    const notesIndex = text.indexOf('📝 *Observações:*');
    assert.ok(massasIndex < notesIndex, 'Massas section must precede Observações');
  });

  it('counts a reconciled seat once in the message', () => {
    // The day is shared with the test above, so the boxes it recorded are cleared first.
    db.exec('DELETE FROM massa_launches');
    db.exec('DELETE FROM aero_movements');
    db.exec(`
      INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at)
      VALUES ('2026-06-12', '1045', 4, 10, '2026-06-12 10:00:00', '2026-06-12 10:00:00')
    `);
    db.exec(`
      INSERT INTO aero_movements
        (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
      VALUES ('2026-06-12', 9001, '1045', 'MASSA CASEIRA', 40, '2026-06-12', 'TEST USER', 'E', '2026-06-12T12:00:00.000Z')
    `);

    const text = generateWhatsAppText(db, reportId)!;

    assert.equal(text.split('   - CASEIRA: 40 un').length - 1, 1, 'the same boxes are not listed twice');
    assert.equal(text.split('👤 Test User:').length - 1, 1);
  });

  it('strips only the leading MASSA prefix from a massas product name', () => {
    assert.strictEqual(stripMassasPrefix('MASSA CASEIRA'), 'CASEIRA');
    assert.strictEqual(stripMassasPrefix('MASSAS TOKO'), 'TOKO');
    assert.strictEqual(stripMassasPrefix('Massa de Rolo 2kg'), 'Rolo 2kg');
    assert.strictEqual(stripMassasPrefix('  MASSA ROLO 2KG  '), 'ROLO 2KG');
    assert.strictEqual(stripMassasPrefix('CASEIRA'), 'CASEIRA');
    assert.strictEqual(stripMassasPrefix('PIZZA MASSA FINA'), 'PIZZA MASSA FINA');
    assert.strictEqual(stripMassasPrefix(''), '');
  });

  it('omits the massas section when the day produced nothing at all', () => {
    db.prepare(`
      INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
      VALUES (300, 10, 'tarde', '2026-06-13', NULL, '2026-06-13 18:00:00')
    `).run();
    const text = generateWhatsAppText(db, 300)!;
    assert.ok(!text.includes('LANÇAMENTO DE MASSAS'));
  });
});

/**
 * Recebimento is the one category whose content is not a checkbox: it is what the company ERP already
 * recorded for that day. The text has to say which of the three states it is in -- supplies, a consulted
 * day that received nothing, or a day nobody synced -- because they mean different things to whoever reads
 * the group.
 */
describe('Export Service — recebimento desde el espejo del ERP', () => {
  let db: Database.Database;

  function newReport(id: number, date: string): number {
    db.prepare(
      `INSERT INTO reports (id, user_id, turno, report_date, notes, created_at)
       VALUES (?, 10, 'tarde', ?, NULL, ?)`,
    ).run(id, date, `${date} 18:00:00`);
    return id;
  }

  function receiptItem(reportId: number, taskId: number, checked: number): void {
    db.prepare(`INSERT INTO report_items (report_id, task_id, checked) VALUES (?, ?, ?)`).run(reportId, taskId, checked);
  }

  function mirror(reportDate: string, rows: ReadonlyArray<{ ent_lan: number; product_code: string; product_desc: string; quantity: number; operator_name: string; movement_type?: 'E' | 'S' }>): void {
    const insert = db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, @ent_lan, @product_code, @product_desc, @quantity, ?, @operator_name, @movement_type, ?)`,
    );
    for (const row of rows) {
      insert.run(reportDate, { movement_type: 'E', ...row }, reportDate, '2026-07-01T18:00:00.000Z');
    }
  }

  before(() => {
    const result = setupDb();
    db = result.db;
  });

  after(() => {
    db.close();
  });

  it('prints the ERP supplies instead of the old checkboxes', () => {
    const id = newReport(401, '2026-11-03');
    receiptItem(id, 43, 1);
    mirror('2026-11-03', [
      { ent_lan: 35911, product_code: '001774', product_desc: 'CAIXA DE PAPELAO PEQUENA MISTA', quantity: 3606, operator_name: 'VIVIANE' },
      { ent_lan: 35911, product_code: '002108', product_desc: 'QUEIJO RALADO', quantity: 40, operator_name: 'VIVIANE' },
    ]);

    const text = generateWhatsAppText(db, id)!;

    assert.ok(text.includes('• CAIXA DE PAPELAO PEQUENA MISTA: 3606 un (Lançamento #35911 · VIVIANE)'));
    assert.ok(text.includes('• QUEIJO RALADO: 40 un (Lançamento #35911 · VIVIANE)'), 'both lines of the document');
    assert.ok(!text.includes('Recebimento de farinha'), 'a ticked task is not a receipt any more');
  });

  it('says a consulted day received nothing', () => {
    const id = newReport(402, '2026-11-02');
    receiptItem(id, 43, 0);
    mirror('2026-11-02', [
      { ent_lan: 35920, product_code: '000084', product_desc: 'MASSA PARA PASTEL DISCO 500G', quantity: 500, operator_name: 'VINICIUS R' },
    ]);

    const text = generateWhatsAppText(db, id)!;

    assert.ok(text.includes('_Nenhum insumo recebido no dia._'));
    assert.ok(!text.includes('Pendente de sincronização'));
  });

  it('asks for a sync when the report has the section but the day was never mirrored', () => {
    const id = newReport(403, '2026-11-01');
    receiptItem(id, 43, 0);

    const text = generateWhatsAppText(db, id)!;

    assert.ok(text.includes('_Pendente de sincronização ERP._'));
    assert.ok(!text.includes('Nenhum insumo recebido'));
  });

  it('falls back to the historical ticks when the report predates the mirror', () => {
    const id = newReport(404, '2026-07-04');
    receiptItem(id, 43, 1);

    const text = generateWhatsAppText(db, id)!;

    assert.ok(text.includes('✅ Recebimento de farinha'));
    assert.ok(!text.includes('Pendente de sincronização'));
  });

  it('stays silent when the report never carried the section', () => {
    const id = newReport(405, '2026-07-05');

    const text = generateWhatsAppText(db, id)!;

    assert.ok(!text.includes('RECEBIMENTO'), 'an untouched category must not add an empty heading');
  });
});
