import type { Database } from 'better-sqlite3';

/**
 * El invitado: la cuenta de solo lectura que la directiva pide. Existe siempre, mira todo con la
 * visibilidad del Administrador y —por la guarda de mutaciones— no puede tocar nada.
 *
 * Por qué no vive en seed.sql: esa semilla corre solo cuando la base es nueva (ver openDatabase), así
 * que un invitado puesto ahí existiría en una base recién creada y NO en la que ya está sirviendo en
 * producción. Esta función corre en cada arranque y se asegura de que la fila esté, sin duplicarla.
 *
 * La contraseña es pública por diseño ("invitado"), así que el hash va precalculado: un hash fijo no
 * revela nada nuevo y evita pagar bcrypt en cada arranque.
 */

import { GUEST_USERNAME, GUEST_DISPLAY_NAME } from '../guest-user.js';

/** bcrypt.hashSync('invitado', 10), calculado con el mismo bcrypt que verifica el login. */
const GUEST_PASSWORD_HASH = '$2a$10$WC0aN.PIZqOJkJoGb/51NO8gZYjXh16hGVwLqWyn7lCeRaLWyYqty';

export function ensureGuestUser(db: Database): void {
  const ya_esta = db.prepare('SELECT 1 FROM users WHERE username = ?').get(GUEST_USERNAME);
  if (ya_esta) return;

  const role = db.prepare("SELECT id FROM roles WHERE name = 'Administrador'").get() as { id: number } | undefined;
  if (!role) throw new Error('Guest user seeding refused: the Administrador role is missing');

  // username es UNIQUE: si dos procesos arrancan a la vez, uno de los dos falla en vez de duplicar.
  db.prepare('INSERT INTO users (username, password_hash, display_name, role_id, is_active) VALUES (?, ?, ?, ?, 1)')
    .run(GUEST_USERNAME, GUEST_PASSWORD_HASH, GUEST_DISPLAY_NAME, role.id);
}
