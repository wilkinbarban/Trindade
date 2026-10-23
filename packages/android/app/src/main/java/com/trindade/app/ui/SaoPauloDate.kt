package com.trindade.app.ui

import java.time.LocalDate
import java.time.ZoneId

/**
 * The plant's zone, and the only one a date is read in.
 *
 * A phone set to another zone would otherwise open its date picker on tomorrow or yesterday and let
 * the operator file a count, a report or a launch under a day the plant is not living. The web reads
 * the same zone for the same reason; the two must agree on what "today" means.
 */
val SAO_PAULO_ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")

/** The current day in São Paulo, with no hour attached. */
fun saoPauloLocalDate(): LocalDate = LocalDate.now(SAO_PAULO_ZONE)
