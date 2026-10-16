import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, it } from 'node:test';
import type { Database } from 'better-sqlite3';
import { openDatabase } from './index.js';

/**
 * El invitado es la cuenta de solo lectura que la directiva pide: existe siempre, mira todo con la
 * visibilidad del Administrador y no puede tocar nada. Estas pruebas cuidan lo primero: que la fila
 * ESTÉ, en los dos escenarios que importan. El caso de la base que ya existía es el de producción:
 * ahí no corre ninguna siembra, así que es el único que habría cazado el error de poner al invitado
 * en seed.sql —donde solo lo vería una base recién creada.
 */

const GUEST_USERNAME = 'invitado';

let root = '';
afterEach(() => {
  if (root) rmSync(root, { recursive: true, force: true });
  root = '';
});

function freshDatabasePath(): string {
  root = mkdtempSync(join(tmpdir(), 'trindade-guest-'));
  return join(root, 'trindade.sqlite');
}

function guestRow(db: Database): { username: string; display_name: string; is_active: number; role_id: number } | undefined {
  return db.prepare('SELECT username, display_name, is_active, role_id FROM users WHERE username = ?').get(GUEST_USERNAME) as
    | { username: string; display_name: string; is_active: number; role_id: number }
    | undefined;
}

describe('el usuario invitado', () => {
  it('existe en una base recién creada, activo y con la visibilidad del Administrador', () => {
    const db = openDatabase(freshDatabasePath());
    try {
      const row = guestRow(db);
      assert.ok(row, 'el invitado tiene que existir en una base nueva');
      assert.equal(row.is_active, 1, 'el invitado tiene que estar activo');
      const role = db.prepare('SELECT name FROM roles WHERE id = ?').get(row.role_id) as { name: string } | undefined;
      assert.equal(role?.name, 'Administrador', 'el invitado mira con la visibilidad del Administrador');
    } finally {
      db.close();
    }
  });

  it('existe también en una base que ya existía, que es el caso de producción', () => {
    const path = freshDatabasePath();
    openDatabase(path).close();
    // Se reabre una base ya creada y sellada: por diseño, acá NO corre ninguna siembra.
    const db = openDatabase(path);
    try {
      assert.ok(guestRow(db), 'una base existente también tiene que tener al invitado');
    } finally {
      db.close();
    }
  });

  it('sigue habiendo uno solo después de abrir la base dos veces', () => {
    const path = freshDatabasePath();
    openDatabase(path).close();
    const db = openDatabase(path);
    try {
      const count = db.prepare('SELECT COUNT(*) FROM users WHERE username = ?').pluck().get(GUEST_USERNAME);
      assert.equal(count, 1, 'la semilla tiene que ser idempotente: una sola fila');
    } finally {
      db.close();
    }
  });
});
