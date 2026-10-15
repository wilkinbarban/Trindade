package com.trindade.app.stock

/**
 * What the counter decides without asking the server.
 *
 * The arithmetic of a count is the server's -- it owns the accumulated boxes and the units derived from
 * them -- but two things happen before anything travels: whether what was typed can be a number of boxes
 * at all, and whether saying so belongs on screen right now. Both are rules and not rendering details,
 * so they live here, apart from the screen that draws them and apart from the repository that sends them.
 *
 * The rule is the same one the web applies, case for case, because a count typed on a phone and a count
 * typed on a desk are the same count: a box holds whole units, so what is not a positive whole number is
 * not a quantity.
 */

/** True when what was typed cannot become a number of boxes. An empty field is not a mistake. */
fun boxesInvalid(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty()) return false
    val value = text.toIntOrNull() ?: return true
    return value <= 0
}

/** The add button is off when there is nothing to add, or what is there cannot be added. */
fun addDisabled(raw: String): Boolean = raw.trim().isEmpty() || boxesInvalid(raw)

/**
 * The products whose warning should be shown right now.
 *
 * Whether a quantity can be counted is a fact about the number; whether to say so is a fact about the
 * moment. While the operator is typing, half a number is allowed to be half written, and coming back into
 * a field has to clear the warning it left behind, or the first keystroke of a correction accuses the
 * operator of the mistake they are in the middle of fixing.
 */
fun visibleBoxErrors(quantities: Map<String, String>, blurred: Set<String>, focused: String?): List<String> =
    quantities.keys
        .filter { blurred.contains(it) && it != focused && boxesInvalid(quantities[it] ?: "") }
        .sorted()
