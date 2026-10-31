import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import bcrypt from 'bcryptjs';
import { adoptErpSeats } from './adopt-erp-seats.js';

/**
 * The two seats the plant signed as `JOAO H` and `JEFFERSON` were imported under the owner's account because
 * the old import fell back to whoever pressed Importar. This is the correction: create the operator the seat
 * names and give that person their boxes, without duplicating anything if it runs twice.
 */
describe('adopting the seats the plant could not attribute', () => {
  let db: Database.Database;
  let adminId: number;
  let aliceId: number;

  function seedUser(username: string, displayName: string, isActive = 1): number {
    const roleId = db.prepare('SELECT id FROM roles WHERE name = ?').pluck().get('Trabalhador') as number;
    const result = db
      .prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?,?,?,?,?)')
      .run(username, 'hash', displayName, roleId, isActive);
    return result.lastInsertRowid as number;
  }

  function seedSeat(entLan: number, operatorName: string, date = '2026-06-03'): void {
    db.prepare(
      `INSERT INTO aero_movements
         (movement_date, ent_lan, product_code, product_desc, quantity, entry_date, operator_name, movement_type, synced_at)
       VALUES (?, ?, '91', 'PAO DE QUEIJO', 40, ?, ?, 'E', ?)`,
    ).run(date, entLan, date, operatorName, `${date}T10:00:00Z`);
  }

  function seedLaunch(entLan: number, userId: number, date = '2026-06-03'): number {
    const result = db
      .prepare(
        `INSERT INTO massa_launches (launch_date, product_code, boxes, user_id, created_at, updated_at, ent_lan)
         VALUES (?, '91', 4, ?, ?, ?, ?)`,
      )
      .run(date, userId, `${date}T10:00:00Z`, `${date}T10:00:00Z`, entLan);
    return result.lastInsertRowid as number;
  }

  function launchOwner(entLan: number): number {
    return db.prepare('SELECT user_id FROM massa_launches WHERE ent_lan = ?').pluck().get(entLan) as number;
  }

  function userIdOf(username: string): number | undefined {
    return db.prepare('SELECT id FROM users WHERE username = ?').pluck().get(username) as number | undefined;
  }

  beforeEach(() => {
    db = new Database(':memory:');
    db.pragma('foreign_keys = ON');
    db.exec(readFileSync(join(import.meta.dirname, '../../db/schema.sql'), 'utf8'));
    db.exec("INSERT INTO roles (name) VALUES ('Administrador'), ('Trabalhador')");
    adminId = seedUser('alice', 'Alice');
    aliceId = adminId;
  });

  it('creates the operator the seat names and moves only that seat to them', () => {
    seedSeat(35947, 'JOAO H');
    seedLaunch(35947, aliceId);
    seedSeat(35952, 'JEFFERSON');
    seedLaunch(35952, aliceId);

    const report = adoptErpSeats(db, [35947, 35952]);

    assert.deepEqual(
      report.map((seat) => seat.status),
      ['created', 'created'],
    );

    const joao = userIdOf('joaoh')!;
    const jefferson = userIdOf('jefferson')!;
    assert.equal(launchOwner(35947), joao);
    assert.equal(launchOwner(35952), jefferson);

    const joaoRow = db
      .prepare('SELECT display_name, role_id, is_active, password_hash FROM users WHERE id = ?')
      .get(joao) as { display_name: string; role_id: number; is_active: number; password_hash: string };
    assert.equal(joaoRow.display_name, 'Joao H');
    assert.equal(joaoRow.role_id, 2);
    assert.equal(joaoRow.is_active, 1);
    assert.ok(bcrypt.compareSync('joaoh1234567890', joaoRow.password_hash), 'the generated password signs in');

    // The owner's own launches are untouched: the correction credits instead of blaming.
    seedLaunch(35900, aliceId);
    assert.equal(launchOwner(35900), aliceId);
  });

  it('does nothing on a second run', () => {
    seedSeat(35947, 'JOAO H');
    seedLaunch(35947, aliceId);
    adoptErpSeats(db, [35947]);

    const again = adoptErpSeats(db, [35947]);

    assert.deepEqual(
      again.map((seat) => seat.status),
      ['already-owned'],
    );
    assert.equal(db.prepare("SELECT COUNT(*) FROM users WHERE username = 'joaoh'").pluck().get(), 1);
  });

  it('refuses to invent a person for an account somebody deactivated', () => {
    seedUser('jefferson', 'Jefferson', 0);
    seedSeat(35952, 'JEFFERSON');
    seedLaunch(35952, aliceId);

    const report = adoptErpSeats(db, [35952]);

    assert.equal(report[0].status, 'inactive');
    assert.equal(report[0].username, 'jefferson');
    assert.equal(launchOwner(35952), aliceId, 'nothing moves while the account is off');
  });

  it('reports a seat the mirror never saw instead of guessing', () => {
    const report = adoptErpSeats(db, [35999]);
    assert.equal(report[0].status, 'missing-seat');
  });

  it('writes nothing in a dry run', () => {
    seedSeat(35947, 'JOAO H');
    seedLaunch(35947, aliceId);

    const report = adoptErpSeats(db, [35947], { dryRun: true });

    assert.equal(report[0].status, 'created');
    assert.equal(userIdOf('joaoh'), undefined);
    assert.equal(launchOwner(35947), aliceId);
  });

  it('leaves a trace of who credited whom, and from where', () => {
    seedSeat(35947, 'JOAO H');
    seedLaunch(35947, aliceId);

    adoptErpSeats(db, [35947]);

    const log = db.prepare("SELECT details FROM audit_logs WHERE entity_type = 'massa_launch'").pluck().get() as string;
    const details = JSON.parse(log) as { ent_lan: number; from_user_id: number; to_user_id: number };
    assert.equal(details.ent_lan, 35947);
    assert.equal(details.from_user_id, aliceId);
    assert.equal(details.to_user_id, userIdOf('joaoh'));
  });
});
