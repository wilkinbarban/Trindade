/**
 * Curated list of passwords that must never be accepted.
 *
 * This is deliberately short and hand-written rather than a downloaded corpus: the project
 * has no licence review for a third-party wordlist and no process yet to keep one updated,
 * so claiming broad coverage would be false. It exists to stop the choices a real operator
 * actually reaches for — the product name, the obvious sequences, the keyboard walks.
 *
 * Every entry is long enough to satisfy the 15-grapheme minimum, so it is reachable by the
 * screening rule instead of being shadowed by the length check. Shorter classics such as
 * `password` or `123456` are already refused for their length and would be dead weight here.
 * Entries are compared case-insensitively against the whole password; leet variants, digit
 * suffixes, usernames and language-specific lists are explicitly out of scope.
 */
export const WEAK_PASSWORDS: readonly string[] = [
  'password123456789', 'passwordpassword', 'passphrase123456',
  'administrador123', 'adminadmin12345',
  'trindademassas123', 'trindademassas2026', 'trindade12345678',
  'trabalhador12345', 'colaborador12345', 'relatorios123456',
  'temperaturas1234', 'abastecimento123', 'inventario123456',
  'higieneorganizacao', 'novasenha1234567', 'trocarsenha12345',
  'senhafacil123456', 'mudarsenha123456',
  'qwerty123456789', 'qwertyuiop123456', 'asdfghjkl1234567',
  '123456789012345', '1234567890123456', '111111111111111',
  '000000000000000', 'aaaaaaaaaaaaaaa',
  'welcome123456789', 'letmein123456789', 'iloveyou12345678',
];
