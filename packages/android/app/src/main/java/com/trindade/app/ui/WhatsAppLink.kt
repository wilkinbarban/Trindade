package com.trindade.app.ui

import java.net.URLEncoder

/**
 * The one-on-one chat link, kept pure so the screens that offer it hold no logic of their own.
 *
 * The link is WhatsApp's own published form -- `https://wa.me/<digits>?text=<encoded>` -- and not an
 * intent aimed at a package: which installed application answers a `wa.me` address is the system's
 * decision, and this is the same rule the web already applies.
 */

/**
 * The number in the shape a link needs: ASCII digits, and nothing else.
 *
 * `Char.isDigit` is true for the digits of any numeral system, and a link WhatsApp reads carries none of
 * the others. The field keeps whatever the operator typed for the field's own sake; what a link is built
 * from is the part it can use, and a number it cannot use offers no action rather than a broken one.
 */
fun waMeNumber(phone: String): String = phone.filter { it in '0'..'9' }

/**
 * The chat link for [phone] carrying [text], or null when there are no digits to open a chat with.
 *
 * An action that would open an empty conversation is worse than no action at all, so the caller draws
 * the offer only when this answers with a link.
 */
fun waMeLink(phone: String, text: String): String? {
    val number = waMeNumber(phone)
    if (number.isEmpty()) return null
    // URLEncoder writes a space as `+`, which is a form-encoding convention; a URL wants `%20`, and the
    // export text carries line breaks, emoji and colons that each have to arrive as the operator saw them.
    val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    return "https://wa.me/$number?text=$encoded"
}
