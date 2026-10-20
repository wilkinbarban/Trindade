package com.trindade.app.ui

/**
 * The driver's phone number, as the fleet keeps it.
 *
 * The value is stored the way the operator typed it -- the driver list shows it back to them, and a
 * number that came back reformatted reads as the application having corrected them -- so cleaning only
 * removes what cannot be part of a number: letters, emoji, and any other punctuation, each of which
 * becomes a space, with runs of spaces collapsed and the ends trimmed. A number the operator wrote
 * inside a note keeps its digits: nothing here guesses which digit was meant as an extension, and the
 * field is theirs to keep clean.
 *
 * The number is optional everywhere it appears. An operator who does not have it yet leaves the field
 * empty, the driver is saved the same way, and the actions that need a number do not appear.
 */
private val NOT_A_NUMBER_CHARACTER = Regex("[^\\d+().\\-\\s]")
private val RUNS_OF_SPACES = Regex("\\s+")

/**
 * What the field keeps while the operator is still typing.
 *
 * The spacing is left exactly as typed: collapsing or trimming on every keystroke would eat the space
 * an operator is in the middle of writing. The tidying happens once the field is finished, in [cleanPhone].
 */
fun filterPhoneInput(raw: String): String = raw.replace(NOT_A_NUMBER_CHARACTER, "")

/**
 * The number as it is stored: the same rule applied to a field the operator has finished with.
 *
 * Everything [filterPhoneInput] keeps is kept, and on top of that runs of spaces collapse and the ends
 * trim, so a pasted number is saved without the padding around it.
 */
fun cleanPhone(raw: String): String = filterPhoneInput(raw).replace(RUNS_OF_SPACES, " ").trim()

/**
 * Formats a Brazilian phone number progressively into (XX) 9XXXX-XXXX or (XX) XXXX-XXXX.
 * Trims extraneous country codes or excessive digits past 11 digits.
 */
fun formatPhoneMask(raw: String): String {
    var digits = raw.filter { it in '0'..'9' }
    if (digits.startsWith("55") && digits.length > 11) {
        digits = digits.substring(2)
    }
    if (digits.length > 11) {
        digits = digits.substring(0, 11)
    }

    if (digits.isEmpty()) return ""
    if (digits.length <= 2) return "($digits" + if (digits.length == 2) ") " else ""
    if (digits.length <= 7) return "(${digits.substring(0, 2)}) ${digits.substring(2)}"
    return "(${digits.substring(0, 2)}) ${digits.substring(2, 7)}-${digits.substring(7)}"
}

/**
 * Normalizes a Brazilian phone number to pure digits with international country code (55).
 * If the number is 10 or 11 digits without 55, prepends 55 for WhatsApp API compatibility.
 */
fun toWhatsAppNumber(phone: String): String {
    val digits = phone.filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    if ((digits.length == 10 || digits.length == 11) && !digits.startsWith("55")) {
        return "55$digits"
    }
    return digits
}
