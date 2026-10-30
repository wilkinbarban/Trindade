import type Database from 'better-sqlite3';

/**
 * Who the tablet says made the boxes, translated into a person Trindade knows.
 *
 * The ERP's `ENT_USU` is typed on a Delphi screen where the operator writes their own name, so the same
 * person appears as `VICTOR` there and as `victor` here, and sometimes as `GUSTAVO H` because the plant has
 * two people called Gustavo and the company's convention is to add the initial. Trindade's login never
 * carries that initial.
 *
 * The rule is deliberately narrow, because guessing an owner is worse than leaving the importer as one:
 * - case and padding are noise;
 * - a name resolves when one person's username or display name is exactly it, or when one person's name is
 *   a whole-token prefix of it (`GUSTAVO` -> `GUSTAVO H`);
 * - two candidates mean no answer, and so does none.
 *
 * A failed resolution costs attribution, never reconciliation: the seat is paired by `ENT_LAN`.
 */

/** Uppercased, single-spaced, trimmed: how a name is compared across the two systems. */
export function normalizeOperatorName(value: string | null | undefined): string {
  return String(value ?? '')
    .trim()
    .replace(/\s+/g, ' ')
    .toUpperCase();
}

export interface OperatorCandidate {
  id: number;
  username: string;
  display_name: string;
}

function tokens(value: string): string[] {
  const normalized = normalizeOperatorName(value);
  return normalized === '' ? [] : normalized.split(' ');
}

/** An initial abbreviates the word it starts, and nothing else does. */
function tokenAgrees(seatToken: string, userToken: string): boolean {
  return seatToken === userToken || (seatToken.length === 1 && userToken.startsWith(seatToken));
}

/**
 * True when the seat can be this very person.
 *
 * Position by position, the seat either writes the same token or abbreviates it with an initial -- the
 * plant's convention for telling two people with the same first name apart. Whatever one side writes beyond
 * the other has to be an initial too: a word of its own is a different name, and a fragment like `GUST` is a
 * substring search in disguise, which is how the wrong person gets credited.
 */
function namesTheSamePerson(userTokens: string[], seatTokens: string[]): boolean {
  if (userTokens.length === 0 || seatTokens.length === 0) return false;
  const shared = Math.min(userTokens.length, seatTokens.length);
  for (let index = 0; index < shared; index += 1) {
    if (!tokenAgrees(seatTokens[index], userTokens[index])) return false;
  }
  const longer = userTokens.length > seatTokens.length ? userTokens : seatTokens;
  return longer.slice(shared).every((token) => token.length === 1);
}

/**
 * The one person the tablet name names, or `null` when it names nobody or more than one.
 *
 * Exact matches win over prefixes, so a plant with `Gustavo` and `Gustavo Henrique` still resolves a seat
 * that signed `GUSTAVO HENRIQUE` exactly, and only the ambiguous `GUSTAVO H` is left unanswered.
 */
export function matchOperatorUserId(users: readonly OperatorCandidate[], operatorName: string): number | null {
  const wanted = tokens(operatorName);
  if (wanted.length === 0) return null;

  const seat = wanted.join(' ');
  const exact = users.filter(
    (user) => tokens(user.username).join(' ') === seat || tokens(user.display_name).join(' ') === seat,
  );
  if (exact.length === 1) return exact[0].id;
  if (exact.length > 1) return null;

  const candidates = users.filter(
    (user) => namesTheSamePerson(tokens(user.username), wanted) || namesTheSamePerson(tokens(user.display_name), wanted),
  );
  return candidates.length === 1 ? candidates[0].id : null;
}

/**
 * Resolves against the people who can still sign in: a deactivated account cannot have pressed anything on
 * the tablet today, and attributing boxes to it would hide them from every operator filter.
 */
export function resolveOperatorUserId(db: Database.Database, operatorName: string | null | undefined): number | null {
  const wanted = normalizeOperatorName(operatorName);
  if (wanted === '') return null;
  const users = db
    .prepare('SELECT id, username, display_name FROM users WHERE is_active = 1 ORDER BY id')
    .all() as OperatorCandidate[];
  return matchOperatorUserId(users, wanted);
}
