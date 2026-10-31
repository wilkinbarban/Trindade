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

/** What the seat's operator means for Trindade: a person to credit, a person to reactivate, or nobody. */
export type OperatorLink =
  | { status: 'linked'; userId: number }
  | { status: 'inactive'; userId: number; username: string }
  | { status: 'missing' };

/**
 * Resolves a seat's operator without letting "not found" and "found but deactivated" look the same.
 *
 * Active accounts are matched first, exactly as `resolveOperatorUserId` does, so a working person is never
 * shadowed by a namesake who was deactivated. Only then the inactive ones answer, and the caller learns that
 * the account exists: offering to create it again would collide with the unique login instead of reactivating.
 */
export function resolveOperatorLink(db: Database.Database, operatorName: string | null | undefined): OperatorLink {
  const active = resolveOperatorUserId(db, operatorName);
  if (active !== null) return { status: 'linked', userId: active };

  const wanted = normalizeOperatorName(operatorName);
  if (wanted === '') return { status: 'missing' };

  const inactive = db
    .prepare('SELECT id, username, display_name FROM users WHERE is_active = 0 ORDER BY id')
    .all() as OperatorCandidate[];
  const match = matchOperatorUserId(inactive, wanted);
  if (match === null) return { status: 'missing' };
  const person = inactive.find((candidate) => candidate.id === match)!;
  return { status: 'inactive', userId: match, username: person.username };
}

/**
 * The person a mirrored seat belongs to, taken from what the ERP itself wrote on that seat.
 *
 * The request body also carries an operator name, and reading the owner from there would let a crafted
 * request claim somebody else's boxes -- the very thing the import rule exists to prevent. `aero_movements`
 * is what the ERP answered, so it is the fact both the rule and the row the UI hides are built on.
 *
 * An `ent_lan` the mirror has not synced, or a database whose mirror table the migration never created,
 * answers `null`: unknown owner, which only an administrator may import.
 */
export function resolveSeatOwnerUserId(db: Database.Database, entLan: number | null | undefined): number | null {
  if (entLan === null || entLan === undefined) return null;
  const table = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'aero_movements'")
    .pluck()
    .get();
  if (table === undefined) return null;
  const operatorName = db
    .prepare('SELECT operator_name FROM aero_movements WHERE ent_lan = ? ORDER BY movement_date DESC LIMIT 1')
    .pluck()
    .get(entLan) as string | undefined;
  return resolveOperatorUserId(db, operatorName ?? null);
}
