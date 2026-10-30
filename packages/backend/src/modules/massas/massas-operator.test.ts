import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { matchOperatorUserId, normalizeOperatorName, resolveSeatOwnerUserId } from './massas-operator.js';

/**
 * The tablet writes the operator the way a person types their own name — `VICTOR`, `GUSTAVO H` — while
 * Trindade keys people by a short login. These cases are the bridge between the two, and the only one that
 * matters operationally is that a name which names exactly one person resolves to that person.
 */
describe('massas operator resolution', () => {
  interface User {
    id: number;
    username: string;
    display_name: string;
  }

  const users: User[] = [
    { id: 1, username: 'wilkin', display_name: 'Wilkin' },
    { id: 12, username: 'victor', display_name: 'Victor' },
    { id: 17, username: 'gustavo', display_name: 'Gustavo' },
    { id: 18, username: 'jaime', display_name: 'Jaime' },
  ];

  it('reads the tablet name the way a person reads it', () => {
    assert.equal(normalizeOperatorName('  victim '), 'VICTIM');
    assert.equal(normalizeOperatorName('Gustavo   H'), 'GUSTAVO H');
    assert.equal(normalizeOperatorName(null), '');
  });

  it('resolves an exact name in either case', () => {
    assert.equal(matchOperatorUserId(users, 'VICTOR'), 12);
    assert.equal(matchOperatorUserId(users, 'victor'), 12);
    assert.equal(matchOperatorUserId(users, 'Wilkin'), 1);
  });

  it('resolves a surname initial the tablet appends to the first name', () => {
    // The seat says GUSTAVO H; the person is Gustavo in Trindade. This is the pair the agreement in the
    // ODD log describes, and the one an exact comparison cannot make.
    assert.equal(matchOperatorUserId(users, 'GUSTAVO H'), 17);
  });

  it('refuses to guess when the name fits more than one person', () => {
    // Both readings of `GUSTAVO H` are plausible when the plant really has two: the initial may be noise or
    // the second person's surname. One candidate is an answer; two are a guess, so nobody is chosen.
    const twoGustavos: User[] = [
      { id: 17, username: 'gustavo', display_name: 'Gustavo' },
      { id: 21, username: 'gustavoh', display_name: 'Gustavo Henrique' },
    ];
    assert.equal(matchOperatorUserId(twoGustavos, 'GUSTAVO H'), null);
  });

  it('answers null for a name nobody has', () => {
    assert.equal(matchOperatorUserId(users, 'MARIA'), null);
    assert.equal(matchOperatorUserId(users, ''), null);
    assert.equal(matchOperatorUserId(users, '   '), null);
  });

  it('does not resolve a name the tablet truncated below a whole word', () => {
    // `GUST` is not how anyone signed, and a prefix shorter than a token is a substring search in disguise.
    assert.equal(matchOperatorUserId(users, 'GUST'), null);
  });
});

describe('massas operator resolution against the database', () => {
  let db: Database.Database;
  let victorId: number;
  let gustavoId: number;

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
    const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
    const insert = db.prepare(
      'INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)',
    );
    victorId = Number(insert.run('victor', 'hash', 'Victor', roleId).lastInsertRowid);
    gustavoId = Number(insert.run('gustavo', 'hash', 'Gustavo', roleId).lastInsertRowid);
  });

  it('only answers users who can still sign in', async () => {
    const { resolveOperatorUserId } = await import('./massas-operator.js');
    db.prepare('UPDATE users SET is_active = 0 WHERE id = ?').run(gustavoId);
    assert.equal(resolveOperatorUserId(db, 'VICTOR'), victorId);
    assert.equal(resolveOperatorUserId(db, 'GUSTAVO H'), null);
  });
});

// --- Who may import a seat ---------------------------------------------------
//
// The plant's rule is one sentence: a worker imports what the ERP already attributes to them, and an
// administrator imports anything. These are the answers that sentence has, and the source of the owner
// fact -- the mirror row, not the request body -- is what keeps a crafted request from claiming a seat.

function seatLedger(): Database.Database {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
  db.exec(readFileSync(join(import.meta.dirname, '../../db/seed.sql'), 'utf8'));
  return db;
}

function addWorker(db: Database.Database, username: string, displayName: string): number {
  const roleId = db.prepare("SELECT id FROM roles WHERE name = 'Trabalhador'").pluck().get() as number;
  return Number(
    db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,1)')
      .run(username, 'hash', displayName, roleId).lastInsertRowid,
  );
}

function addSeat(db: Database.Database, entLan: number, operatorName: string): void {
  db.prepare(
    `INSERT INTO aero_movements
       (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
     VALUES ('2026-10-30', ?, '5000', 'MASSA DE PASTEL', 270, '2026-10-30', ?, 'E', '2026-10-30T10:00:00.000Z')`,
  ).run(entLan, operatorName);
}

describe('massas import ownership', () => {
  let db: Database.Database;
  let victorId: number;
  let gustavoId: number;

  beforeEach(() => {
    db = seatLedger();
    victorId = addWorker(db, 'victor', 'Victor');
    gustavoId = addWorker(db, 'gustavo', 'Gustavo');
  });

  it('lets an administrator import any seat, known owner or not', async () => {
    const { canImportSeat } = await import('./massas-import-policy.js');
    addSeat(db, 35937, 'VICTOR');
    const ownerUserId = resolveSeatOwnerUserId(db, 35937);
    assert.equal(canImportSeat({ ownerUserId, userId: 1, isAdmin: true }), true);
    assert.equal(canImportSeat({ ownerUserId: null, userId: 1, isAdmin: true }), true);
  });

  it('lets a worker import the seat the ERP attributes to them', async () => {
    const { canImportSeat } = await import('./massas-import-policy.js');
    addSeat(db, 35937, 'VICTOR');
    const ownerUserId = resolveSeatOwnerUserId(db, 35937);
    assert.equal(ownerUserId, victorId);
    assert.equal(canImportSeat({ ownerUserId, userId: victorId, isAdmin: false }), true);
  });

  it("refuses a worker importing somebody else's seat", async () => {
    const { canImportSeat } = await import('./massas-import-policy.js');
    addSeat(db, 35937, 'VICTOR');
    const ownerUserId = resolveSeatOwnerUserId(db, 35937);
    assert.equal(canImportSeat({ ownerUserId, userId: gustavoId, isAdmin: false }), false);
  });

  it('refuses a seat whose operator names nobody here, unless the asker is an administrator', async () => {
    const { canImportSeat } = await import('./massas-import-policy.js');
    addSeat(db, 35939, 'MARIA');
    const ownerUserId = resolveSeatOwnerUserId(db, 35939);
    assert.equal(ownerUserId, null);
    assert.equal(canImportSeat({ ownerUserId, userId: victorId, isAdmin: false }), false);
  });

  it('leaves a seat the mirror never heard of without an owner', () => {
    assert.equal(resolveSeatOwnerUserId(db, 99999), null);
  });

  it('reads the owner from the seat the ERP wrote, initials included', () => {
    addSeat(db, 35937, 'VICTOR');
    addSeat(db, 35939, 'GUSTAVO H');
    assert.equal(resolveSeatOwnerUserId(db, 35937), victorId);
    assert.equal(resolveSeatOwnerUserId(db, 35939), gustavoId);
  });
});
