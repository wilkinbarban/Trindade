package com.trindade.app.auth

import java.text.BreakIterator
import java.util.Locale

/**
 * The one statement of the server's password rule inside the client.
 *
 * The API accepts a password only from [MIN_GRAPHEMES] visible characters on, and only up to
 * [MAX_BYTES] original UTF-8 bytes, which is the length bcrypt truncates silently at. Both numbers
 * are read from here and nowhere else: the registration form refuses a submission with [violationOf],
 * the profile screen enables its button from it, the admin panel refuses a save with it, and
 * `PasswordPolicyContractTest` compares both against the published contract so that a policy change
 * landing on the server without landing here fails a test instead of an operator.
 *
 * Visible characters, not code points and not UTF-16 units, because that is what the server counts
 * with `Intl.Segmenter`: a password typed as `e` followed by a combining acute accent is one visible
 * character, not two. Counting code points here would enable a form for a password the server
 * refuses; counting UTF-16 units would refuse passwords the server accepts.
 */
object PasswordPolicy {
    /** The server's minimum, in visible characters. */
    const val MIN_GRAPHEMES = 15

    /** The server's ceiling, in original UTF-8 bytes, matching the bcrypt truncation point. */
    const val MAX_BYTES = 72

    /** Which of the two rules a password breaks, when it breaks one. */
    enum class Violation { TOO_FEW_GRAPHEMES, TOO_MANY_BYTES }

    /**
     * The visible characters in [password], counted the way the server counts them.
     *
     * [BreakIterator] is asked for character boundaries, which merge a combining mark into the
     * character it decorates, so the count follows the same grapheme clusters the server segments.
     */
    fun graphemes(password: String): Int {
        if (password.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(password)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** The original UTF-8 bytes of [password], before any normalisation the server does not do. */
    fun utf8Bytes(password: String): Int = password.toByteArray(Charsets.UTF_8).size

    /**
     * Why the server would refuse [password], or null when it would accept it.
     *
     * The minimum is checked first on purpose: fifteen characters that also exceed the byte ceiling
     * are described to the operator as too short, which is the correction that does not ask them to
     * shorten a password they were told to lengthen.
     */
    fun violationOf(password: String): Violation? = when {
        graphemes(password) < MIN_GRAPHEMES -> Violation.TOO_FEW_GRAPHEMES
        utf8Bytes(password) > MAX_BYTES -> Violation.TOO_MANY_BYTES
        else -> null
    }
}
