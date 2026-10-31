/**
 * Who may import a seat the ERP already recorded.
 *
 * The plant asked for one rule in one sentence: a worker imports the seats the ERP attributes to them,
 * and an administrator imports anybody's. This is that sentence, with no database and no request in it,
 * so the answer is the same whether the route asks, a test asks, or somebody reads it.
 */
export interface SeatImportClaim {
  /**
   * The person the seat belongs to, as the ERP named them and Trindade resolved them.
   *
   * `null` is not "nobody": it is "this company cannot say". A seat whose operator names no account here,
   * and a seat the mirror never heard of, both land here, and neither one belongs to a worker.
   */
  ownerUserId: number | null;
  /** Who is asking to import it. */
  userId: number;
  isAdmin: boolean;
}

export function canImportSeat(claim: SeatImportClaim): boolean {
  // A seat whose operator is not linked here is nobody's to import: the launch would have to invent an
  // owner, and the only honest way forward is creating the operator from the seat first -- which is what
  // the screen offers an administrator instead of an Importar button.
  if (claim.ownerUserId === null) return false;
  if (claim.isAdmin) return true;
  return claim.ownerUserId === claim.userId;
}

/**
 * Whether the asker may create the operator a seat names, so the seat can be imported by its real owner.
 *
 * Only an administrator: creating accounts is an administrative act, and the screen shows this as the
 * inline button on a seat nobody in Trindade answers to.
 */
export function canCreateSeatOperator(claim: { ownerUserId: number | null; isAdmin: boolean }): boolean {
  return claim.isAdmin && claim.ownerUserId === null;
}
