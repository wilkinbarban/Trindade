package com.trindade.app.reports

/**
 * A temperature reading, as the field is allowed to hold it.
 *
 * The keypad on a numeric field offers no minus key, so the sign arrives from the `±` action -- but on a
 * keyboard that does have one, an operator reaches for it in the middle of the number, or presses it after
 * the digits because they noticed the chamber was cold. A field that accepted `4.5-` would hand the parser a
 * string it cannot read, and the reading would be lost with nothing on screen saying why.
 *
 * So the string is normalized as it is typed: `-` anywhere means negative and moves to the front, a second
 * one is not a second sign, and only the first `.` or `,` survives as the decimal separator. Everything that
 * is not a digit or that separator is dropped, which also covers pasted text.
 */
internal fun sanitizeTemperatureInput(raw: String): String {
    val negative = raw.contains('-')
    val body = raw.filter { it.isDigit() || it == '.' || it == ',' }
    val separator = body.indexOfFirst { it == '.' || it == ',' }
    val kept = if (separator < 0) {
        body
    } else {
        body.substring(0, separator + 1) + body.substring(separator + 1).filter(Char::isDigit)
    }
    return if (negative) "-$kept" else kept
}

/**
 * The same reading with its sign flipped, which is what a field's `±` action writes back.
 *
 * String arithmetic rather than a round trip through the parser, so it works on a half-typed field: an
 * operator who typed `2,` and then remembers the chamber was cold gets `-2,` and keeps the separator they
 * had already pressed, where parsing and re-rendering would have rewritten the number without it. An empty
 * field becomes a lone `-`, which is the sign the next digit lands behind. A `-` found anywhere else is
 * cleared before the sign is applied, so the result never carries two.
 */
internal fun toggledSign(value: String): String {
    val body = value.replace("-", "")
    return if (value.startsWith("-")) body else "-$body"
}
