import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import type { FastifyInstance } from 'fastify';
import type Database from 'better-sqlite3';
import { buildTestApp } from './test-helper.js';
import { GUEST_USERNAME } from './guest-user.js';

/**
 * El invitado es de solo lectura por diseño: mira todo con la visibilidad del Administrador y no
 * puede tocar nada. La guarda vive en el backend, no en el cliente: es una lista de permitidos
 * central que bloquea toda mutación fuera de las rutas de autenticación, así que un endpoint nuevo
 * queda protegido por omisión en vez de por acordarse de decorarlo.
 *
 * Los tres casos existen para no escribir una prueba tramposa: una guarda que bloqueara TODO pasaría
 * el primero, así que el segundo exige que la lectura del invitado siga funcionando y el tercero que
 * el login siga funcionando. Que el Administrador pueda mutar lo prueba la suite entera, que muta
 * como Administrador en cada corrida.
 */

const MENSAJE_LITERAL = '\u00c9 necess\u00e1rio cadastrar um usu\u00e1rio na p\u00e1gina de login para realizar a\u00e7\u00f5es';

async function loginAs(app: FastifyInstance, username: string, password: string): Promise<string> {
  const res = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { username, password } });
  assert.equal(res.statusCode, 200, `el login de ${username} tiene que funcionar`);
  return JSON.parse(res.body).token as string;
}

describe('la guarda de mutaciones del invitado', () => {
  let app: FastifyInstance;
  let db: Database.Database;
  let guestToken: string;
  let adminToken: string;

  before(async () => {
    const built = await buildTestApp();
    app = built.app;
    db = built.db;
    guestToken = await loginAs(app, GUEST_USERNAME, GUEST_USERNAME);
    adminToken = await loginAs(app, built.fixtures.admin.username, built.fixtures.admin.password);
  });

  after(async () => {
    await app.close();
    db.close();
  });

  it('le responde 403 con el mensaje exacto cuando intenta mutar, y no toca la base', async () => {
    const antes = db.prepare('SELECT COUNT(*) FROM report_categories').pluck().get() as number;
    const res = await app.inject({
      method: 'POST',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${guestToken}` },
      payload: { name: 'no-deberia-crearse' },
    });
    assert.equal(res.statusCode, 403, `esperaba 403 y llegó ${res.statusCode}: ${res.body}`);
    assert.equal(JSON.parse(res.body).message, MENSAJE_LITERAL, 'el mensaje tiene que ser el literal de la directiva');
    assert.equal(db.prepare('SELECT COUNT(*) FROM report_categories').pluck().get() as number, antes, 'la mutación no puede haber tocado la base');
  });

  it('lo deja leer: puede mirar todo, solo no puede tocar', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/api/admin/categories',
      headers: { authorization: `Bearer ${guestToken}` },
    });
    assert.equal(res.statusCode, 200, `el invitado tiene que poder leer, y llegó ${res.statusCode}: ${res.body}`);
  });

  it('T6: ni siquiera el Administrador puede modificarlo ni desactivarlo', async () => {
    const guest = db.prepare('SELECT id, is_active FROM users WHERE username = ?').get(GUEST_USERNAME) as { id: number; is_active: number };

    const patch = await app.inject({
      method: 'PATCH',
      url: `/api/admin/users/${guest.id}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: { is_active: 0 },
    });
    assert.equal(patch.statusCode, 403, `desactivar al invitado tiene que rechazarse, y llegó ${patch.statusCode}: ${patch.body}`);

    const despues = db.prepare('SELECT username, is_active FROM users WHERE id = ?').get(guest.id) as { username: string; is_active: number } | undefined;
    assert.ok(despues, 'el invitado no puede desaparecer');
    assert.equal(despues.username, GUEST_USERNAME);
    assert.equal(despues.is_active, 1, 'el invitado tiene que seguir activo');
  });

  it('T6: ni siquiera el Administrador puede borrarlo', async () => {
    const guest = db.prepare('SELECT id FROM users WHERE username = ?').get(GUEST_USERNAME) as { id: number };

    const borrar = await app.inject({
      method: 'DELETE',
      url: `/api/admin/users/${guest.id}`,
      headers: { authorization: `Bearer ${adminToken}` },
    });
    assert.equal(borrar.statusCode, 403, `borrar al invitado tiene que rechazarse, y llegó ${borrar.statusCode}: ${borrar.body}`);

    const sigue = db.prepare('SELECT COUNT(*) FROM users WHERE username = ?').pluck().get(GUEST_USERNAME);
    assert.equal(sigue, 1, 'el invitado no se puede borrar');
  });
});
