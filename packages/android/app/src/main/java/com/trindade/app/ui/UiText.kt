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
data class UiText(@StringRes val resId: Int, val args: List<Any> = emptyList()) {
    companion object {
        fun of(@StringRes resId: Int, vararg args: Any): UiText = UiText(resId, args.toList())
    }
}

/** Draws the message in the active locale, filling its placeholders. */
@Composable
fun UiText.resolve(): String = stringResource(resId, *args.toTypedArray())
