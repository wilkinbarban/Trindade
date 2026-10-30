package com.trindade.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A sentence the view model hands to the screen without deciding its language.
 *
 * A Portuguese constant baked into a view model reaches a Spanish operator untranslated, and the
 * mistake is invisible from the code: the state field is a `String` and the screen draws it. What
 * travels instead is the resource the screen resolves, plus the values that fill its holes when the
 * sentence has any. The value stays a plain data class, so a unit test asserts which message a
 * failure produced without a Context.
 */
data class UiText(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
    /**
     * A sentence that is already text, such as the one the backend wrote.
     *
     * It exists for the case where the server's refusal has no resource of ours to name it: an unknown code
     * still has a Portuguese sentence, and dropping it for a generic "code 400" would throw away the only
     * explanation the operator has.
     */
    val literal: String? = null,
) {
    companion object {
        fun of(@StringRes resId: Int, vararg args: Any): UiText = UiText(resId, args.toList())

        fun literal(text: String): UiText = UiText(0, emptyList(), text)
    }
}

/** Draws the message in the active locale, filling its placeholders. */
@Composable
fun UiText.resolve(): String = literal ?: stringResource(resId, *args.toTypedArray())
