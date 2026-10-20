package com.trindade.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one-on-one chat link, kept pure so the screens that offer it hold no logic of their own.
 *
 * The link is WhatsApp's own published form -- `https://wa.me/<digits>?text=<encoded>` -- and not an
 * intent aimed at a package: which application answers a `wa.me` address is the system's decision, and
 * the same rule the web already applies.
 */
class WhatsAppLinkTest {

    @Test
    fun `the number keeps the digits a link can carry and nothing else`() {
        assertEquals("5511988887777", waMeNumber("+55 (11) 98888-7777"))
        assertEquals("11988887777", waMeNumber("11 98888.7777"))
        assertEquals("", waMeNumber(""))
    }

    @Test
    fun `only ascii digits survive, because a link cannot open a chat with the others`() {
        // Arabic-Indic digits are digits to `Char.isDigit`, and no link WhatsApp reads carries them. The
        // field is filtered for the field's own sake; the link is built only from what it can use, and a
        // number it cannot use offers no action at all rather than one that opens nothing.
        assertEquals("", waMeNumber("١١٩٨٨٨٧٧٧٧"))
        assertEquals("1198887777", waMeNumber("١١ 1198887777"))
    }

    @Test
    fun `the link carries the text encoded so its line breaks and spaces arrive intact`() {
        assertEquals(
            "https://api.whatsapp.com/send?phone=5511988887777&text=CRONOGRAMA%20da%20segunda",
            waMeLink("5511988887777", "CRONOGRAMA da segunda"),
        )
        // Brazilian phone without 55 gets 55 prepended automatically
        assertEquals(
            "https://api.whatsapp.com/send?phone=5511988887777&text=CRONOGRAMA%20da%20segunda",
            waMeLink("(11) 98888-7777", "CRONOGRAMA da segunda"),
        )
        // A space is a `+` in form encoding and a `%20` in a URL, and this is a URL: the export text
        // carries emoji and line breaks, and each has to survive the trip as the operator saw them.
        assertEquals(
            "https://api.whatsapp.com/send?phone=5511988887777&text=04%3A00%0A%F0%9F%93%8C%20Total%3A%203",
            waMeLink("5511988887777", "04:00\n📌 Total: 3"),
        )
    }

    @Test
    fun `the link names the endpoint that answers without redirecting`() {
        // `wa.me` answers with a 302 to `api.whatsapp.com/send`, and that hop is where a four-byte
        // character comes back as U+FFFD: the announcement carries 🚛 and 🕟, so the link names the
        // endpoint that answers directly, and the four-byte character survives as its own bytes.
        val link = waMeLink("5511988887777", "🚛 Cronograma\n🕟 04:00")

        assertEquals(
            "https://api.whatsapp.com/send?phone=5511988887777&text=%F0%9F%9A%9B%20Cronograma%0A%F0%9F%95%9F%2004%3A00",
            link,
        )
        assertEquals(false, link!!.contains("wa.me"))
        assertEquals(false, link.contains("%EF%BF%BD"))
    }

    @Test
    fun `a number with no digits offers no link, because an empty chat is not an offer`() {
        assertNull(waMeLink("", "texto"))
        assertNull(waMeLink("   ", "texto"))
        assertNull(waMeLink("abc", "texto"))
        assertNull(waMeLink("١١٩٨٨٨٧٧٧٧", "texto"))
    }
}
