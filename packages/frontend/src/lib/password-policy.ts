/**
 * The password rule, as the browser should apply it before a round trip.
 *
 * The server is the authority: it enforces the same three conditions in
 * `packages/backend/src/modules/auth/auth.schema.ts`. This module exists so the admin form stops
 * accepting a password the server is going to refuse, and so its hint describes the real rule
 * instead of the old "8 characters with an uppercase letter and a digit", which the server never
 * required. A test compares the weak list below against the backend file, because two copies of a
 * rule drift unless something checks.
 */
export type PasswordIssue = 'tooShort' | 'tooLong' | 'tooCommon' | null;

const MIN_GRAPHEMES = 15;
const MAX_BYTES = 72;

const segmenter = new Intl.Segmenter('und', { granularity: 'grapheme' });
const encoder = new TextEncoder();

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

// The set mirrors the backend exactly, which builds it from the list without normalizing the
// entries and lowercases only the incoming password. Lowercasing the entries here instead would
// silently diverge the day an entry is written with a capital letter.
const weakSet = new Set(WEAK_PASSWORDS);

/**
 * Which single rule the password breaks, or `null` when it satisfies all of them.
 *
 * Graphemes rather than UTF-16 units, so an accent composed of two code points or an emoji
 * sequence counts as one visible character. Bytes are measured on the original text because
 * bcrypt truncates at 72, and reporting one issue at a time keeps the message specific.
 */
export function passwordIssue(password: string): PasswordIssue {
  if (Array.from(segmenter.segment(password)).length < MIN_GRAPHEMES) return 'tooShort';
  if (encoder.encode(password).length > MAX_BYTES) return 'tooLong';
  if (weakSet.has(password.toLowerCase())) return 'tooCommon';
  return null;
}

/** Translation key for the issue, so the form never renders a raw key or an invented sentence. */
export function passwordIssueKey(issue: Exclude<PasswordIssue, null>): string {
  return `auth.password${issue.charAt(0).toUpperCase()}${issue.slice(1)}`;
}
