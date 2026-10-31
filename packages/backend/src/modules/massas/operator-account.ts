/**
 * The Trindade account an ERP seat's operator needs before the seat can be imported.
 *
 * Three copies of this rule already exist -- the web's `share-app.ts`, the phone's `ShareModels.kt`, and the
 * screen that previews the login -- and this is the one the correction script uses to create the account for
 * a seat that nobody answers to. They agree on the same rule: the login is the name with everything that is
 * not a letter or a digit removed, and the password is that login padded to the safety floor.
 */

/** The floor every generated password meets. */
export const PASSWORD_MIN_LENGTH = 15;

const PASSWORD_SEQUENCE = '1234567890123456789012345678901234567890';

/**
 * `JOAO H` becomes `joaoh`: no dot, no space, no surname initial, because the plant's convention for telling
 * two people apart is not part of the login anybody types.
 */
export function deriveOperatorUsername(displayName: string): string {
  return String(displayName ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
}

/** The login, padded with a digit sequence when it is shorter than a safe password. */
export function quickWorkerPassword(username: string): string {
  const base = String(username ?? '')
    .trim()
    .toLowerCase()
    .slice(0, PASSWORD_MIN_LENGTH);
  if (base.length >= PASSWORD_MIN_LENGTH) return base;
  return base + PASSWORD_SEQUENCE.slice(0, PASSWORD_MIN_LENGTH - base.length);
}

/** The seat shouts `JOAO H`; the person is `Joao H`, and that is what every screen will show. */
export function operatorDisplayName(seatName: string): string {
  return String(seatName ?? '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLowerCase()
    .replace(/(^|\s)(\p{L})/gu, (_match, space: string, letter: string) => space + letter.toUpperCase());
}
