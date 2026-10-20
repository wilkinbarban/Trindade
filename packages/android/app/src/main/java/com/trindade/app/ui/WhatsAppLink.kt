package com.trindade.app.ui

import java.net.URLEncoder

/**
 * The one-on-one chat link, kept pure so the screens that offer it hold no logic of their own.
 *
 * The link names WhatsApp's canonical chat endpoint rather than the short form that redirects to it:
 * `wa.me` answers with a 302 to `api.whatsapp.com/send`, and that hop is where a four-byte UTF-8
 * character -- every emoji the announcement carries -- comes back as U+FFFD. The address below is the
 * one the device and the system resolve directly, so the payload arrives as the operator saw it, and it
 * is still an address rather than a package this application names.
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
    val number = toWhatsAppNumber(phone)
    if (number.isEmpty()) return null
    // URLEncoder writes a space as `+`, which is a form-encoding convention; a URL wants `%20`, and the
    // export text carries line breaks, emoji and colons that each have to arrive as the operator saw them.
    val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    // The canonical endpoint and not `wa.me`: the short form answers with a 302 to this address, and a
    // four-byte character does not survive that hop -- it comes back as U+FFFD, which is what turned the
    // announcement's emoji into blanks. One request, no redirect, the emoji whole.
    return "https://api.whatsapp.com/send?phone=$number&text=$encoded"
}
