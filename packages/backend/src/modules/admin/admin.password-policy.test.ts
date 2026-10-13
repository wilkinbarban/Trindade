import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CreateUserSchema, UpdateUserSchema } from './admin.schema.js';

const emoji = '\u{1F469}\u200D\u{1F4BB}';

test('admin user creation applies the shared password policy', () => {
  const create = (password: string) => CreateUserSchema.safeParse({
    username: 'new-user', password, display_name: 'New User', role_id: 2,
  });
  assert.equal(create('a'.repeat(14)).success, false);
  assert.equal(create('turno-limpo2026').success, true);
  assert.equal(create('a'.repeat(73)).success, false);
  assert.equal(create('a'.repeat(71) + 'é').success, false);
  assert.equal(create(emoji.repeat(3) + 'a'.repeat(11)).success, false, '14 graphemes under 72 bytes');
  assert.equal(create(emoji.repeat(4) + 'a'.repeat(11)).success, true, '15 graphemes under 72 bytes');
  assert.equal(create('a'.repeat(14)).error?.issues[0].message, 'Password must be at least 15 visible graphemes');
  assert.equal(create('a'.repeat(73)).error?.issues[0].message, 'Password must not exceed 72 bytes');
});

test('admin user updates keep the password optional and bound it when present', () => {
  assert.equal(UpdateUserSchema.parse({ display_name: 'Renamed' }).password, undefined);
  assert.equal(UpdateUserSchema.safeParse({ password: 'a'.repeat(14) }).success, false);
  assert.equal(UpdateUserSchema.safeParse({ password: 'turno-limpo2026' }).data?.password, 'turno-limpo2026');
  assert.equal(UpdateUserSchema.safeParse({ password: 'a'.repeat(73) }).success, false);
});
