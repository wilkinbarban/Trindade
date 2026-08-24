import { describe, it } from 'node:test';
import assert from 'node:assert';
import Database from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { verifyReferencePrerequisites } from './reference-data.js';

function seededDatabase() {
  const db = new Database(':memory:');
  db.exec(readFileSync(join(import.meta.dirname, 'schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, 'seed.sql'), 'utf8'));
  return db;
}

describe('Reference data contract', () => {
  it('accepts the versioned seed prerequisites', () => {
    const db = seededDatabase();
    assert.doesNotThrow(() => verifyReferencePrerequisites(db));
    db.close();
  });

  it('fails fast with the missing prerequisite field', () => {
    const db = seededDatabase();
    db.prepare("DELETE FROM roles WHERE name = 'Trabalhador'").run();
    assert.throws(() => verifyReferencePrerequisites(db), /role\.id=2/);
    db.close();
  });

  it('identifies version contract drift', () => {
    const db = seededDatabase();
    db.prepare("UPDATE settings SET value = '0' WHERE key = 'reference_dataset_version'").run();
    assert.throws(() => verifyReferencePrerequisites(db), /version expected 1, got 0/);
    db.close();
  });

  it('identifies category and task contract drift used by fixtures', () => {
    const db = seededDatabase();
    db.prepare("UPDATE report_categories SET name_pt = 'drift' WHERE id = 1").run();
    assert.throws(() => verifyReferencePrerequisites(db), /report_categories\.id=1\.name_pt/);
    db.prepare("UPDATE report_categories SET name_pt = 'Higiene e Organização' WHERE id = 1").run();
    db.prepare("UPDATE report_tasks SET category_id = 2 WHERE id = 1").run();
    assert.throws(() => verifyReferencePrerequisites(db), /report_tasks\.id=1\.category_id/);
    db.close();
  });
});
