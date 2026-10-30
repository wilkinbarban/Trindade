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
  if (claim.isAdmin) return true;
  // Not `ownerUserId ?? userId`: an unknown owner is not the asker's to claim. Letting the request body
  // decide the owner is exactly the defect this rule closes.
  return claim.ownerUserId !== null && claim.ownerUserId === claim.userId;
}
