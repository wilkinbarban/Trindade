package com.trindade.app.admin

/**
 * The hour an operator is typing, as the field shows it.
 *
 * A free-text field invited `9:5`, `9h5` and `0900`, and the validation then had to explain that none of
 * them is a time. The field now takes four digits and places the colon itself, so the only thing left to get
 * wrong is the numbers -- which is what `isValidTimeSlot` is still there for.
 *
 * The colon appears when the first minute digit arrives, not after the second hour digit. That is the
 * difference between a mask that can be edited and one that traps the cursor: `14:` with the colon re-added
 * on every keystroke cannot be shortened back to `14`, and the operator ends up fighting the field.
 * Anything that is not a digit is dropped, so a pasted `14:30` and a typed `1430` land on the same string.
 */
internal fun formatTimeSlotMask(raw: String): String {
    val digits = raw.filter(Char::isDigit).take(4)
    return if (digits.length <= 2) digits else "${digits.take(2)}:${digits.drop(2)}"
}
