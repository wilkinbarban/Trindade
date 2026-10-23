package com.trindade.app.stock

import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockDayProductsInner

/**
 * The day's collective summary: every counted product with the totals the server already computed.
 *
 * Deliberately the same list the message carries, and deliberately not recomputed here: a summary that
 * did its own arithmetic could disagree with the message about the same shelf. There is no per-operator
 * view either -- a count is of a shelf, not of who walked it -- so a day yields exactly one summary.
 */
fun stockCollectiveSummary(day: StockDay): List<StockDayProductsInner> =
    day.products.filter { it.units > 0 }
