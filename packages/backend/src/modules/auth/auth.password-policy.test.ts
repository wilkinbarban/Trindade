import { test } from 'node:test';
import assert from 'node:assert/strict';
import { registerSchema, loginSchema, changePasswordSchema, setupSchema } from './auth.schema.js';
import { CreateUserSchema } from '../admin/admin.schema.js';
import { WEAK_PASSWORDS } from './weak-passwords.js';

const input = (password: string) => ({ username: 'worker', display_name: 'Worker', password });

test('registration requires 15 visible graphemes, including combining marks and emoji', () => {
  for (const password of ['a'.repeat(14), 'e\u0301'.repeat(14), '👍'.repeat(14), '👩‍💻'.repeat(3) + 'a'.repeat(11)]) {
    assert.ok(Buffer.byteLength(password, 'utf8') <= 72);
    const result = registerSchema.safeParse(input(password));
    assert.equal(result.success, false);
    if (!result.success) assert.deepEqual(result.error.flatten().fieldErrors.password, ['Password must be at least 15 visible graphemes']);
  }
  for (const password of ['turno-limpo2026', 'e\u0301'.repeat(15), '👍'.repeat(15), '👩‍💻'.repeat(4) + 'a'.repeat(11)]) {
    assert.equal(registerSchema.parse(input(password)).password, password);
  }
});

test('registration rejects more than 72 original UTF-8 bytes without truncation', () => {
  for (const password of ['a'.repeat(72), 'a'.repeat(70) + 'é']) {
    assert.equal(Buffer.byteLength(password, 'utf8'), 72);
    assert.equal(registerSchema.parse(input(password)).password, password);
  }
  for (const password of ['a'.repeat(73), 'a'.repeat(71) + 'é']) {
    assert.equal(Buffer.byteLength(password, 'utf8'), 73);
    const result = registerSchema.safeParse(input(password));
    assert.equal(result.success, false);
    if (!result.success) assert.deepEqual(result.error.flatten().fieldErrors.password, ['Password must not exceed 72 bytes']);
  }
});

test('passphrases retain original spaces, Unicode representation and case', () => {
  for (const password of ['  Clave con espacios 15!  ', 'e\u0301'.repeat(15)]) {
    const parsed = registerSchema.parse(input(password)).password;
    assert.equal(parsed, password);
    assert.deepEqual(Buffer.from(parsed, 'utf8'), Buffer.from(password, 'utf8'));
  }
  assert.equal(loginSchema.parse({ username: 'worker', password: 'old' }).password, 'old');
  assert.equal(changePasswordSchema.parse({ currentPassword: 'old', newPassword: 'new long passphrase' }).currentPassword, 'old');
});

test('changing a password and provisioning an operator apply the same policy', () => {
  const change = (newPassword: string) => changePasswordSchema.safeParse({
    currentPassword: 'legacy-current-password', newPassword,
  });
  const setup = (password: string) => setupSchema.safeParse({ username: 'owner', displayName: 'Owner', password });
  for (const invalid of ['a'.repeat(14), 'e\u0301'.repeat(14), 'a'.repeat(73), 'a'.repeat(71) + 'é']) {
    for (const result of [change(invalid), setup(invalid)]) {
      assert.equal(result.success, false);
      assert.equal(result.error?.issues.length, 1);
    }
  }
  for (const valid of ['turno-limpo2026', 'e\u0301'.repeat(15), 'a'.repeat(72), 'a'.repeat(70) + 'é']) {
    assert.equal(change(valid).data?.newPassword, valid);
    assert.equal(setup(valid).data?.password, valid);
  }
  assert.equal(change('a'.repeat(14)).error?.issues[0].message, 'Password must be at least 15 visible graphemes');
  assert.equal(setup('a'.repeat(73)).error?.issues[0].message, 'Password must not exceed 72 bytes');
  assert.equal(setup('legacy').success, false, 'operator provisioning no longer accepts short passwords');
});

test('every curated weak entry is reachable by the policy rather than shadowed', () => {
  for (const weak of WEAK_PASSWORDS) {
    assert.ok(weak.length >= 15, `${weak} must pass the grapheme minimum to be screened`);
    assert.ok(Buffer.byteLength(weak, 'utf8') <= 72, `${weak} must fit the byte bound`);
    assert.equal(registerSchema.safeParse(input(weak)).error?.issues.length, 1, `${weak} must report exactly one reason`);
  }
});

test('screening rejects the curated weak list case-insensitively, by whole value', () => {
  const rejected = (password: string) => registerSchema.safeParse(input(password));
  for (const weak of ['qwerty123456789', 'QWERTY123456789', 'aaaaaaaaaaaaaaa', 'trindademassas2026']) {
    assert.ok(weak.length >= 15, weak);
    const result = rejected(weak);
    assert.equal(result.success, false, weak);
    assert.deepEqual(result.error?.flatten().fieldErrors.password, ['Password is too common; choose another passphrase']);
  }
  // The rule compares the whole password, so a phrase that merely contains a listed word passes.
  assert.equal(rejected('Higiene limpa hoje 2026').success, true);
  assert.equal(rejected('Trindade limpa 2026').success, true);
  assert.equal(changePasswordSchema.safeParse({ currentPassword: 'old', newPassword: 'qwerty123456789' }).success, false);
  assert.equal(CreateUserSchema.safeParse({ username: 'u', displayName: 'U', role_id: 2, password: 'qwerty123456789' }).success, false);
});
