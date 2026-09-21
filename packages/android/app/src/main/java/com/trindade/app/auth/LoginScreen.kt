package com.trindade.app.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R

/**
 * The login form, stateless.
 *
 * State comes in and events go out, which is what lets this be rendered from a test without a
 * ViewModel, an Activity or a server. The alternative -- reading the ViewModel here -- would put the
 * screen's behaviour out of reach of anything but an instrumented test.
 *
 * The branding block at the top is the web login's own block, in the web's order -- mark, then name,
 * then tagline -- and the mark is the brand's own asset rather than a redraw of it, so the two
 * clients present the same logo at the same size: the 280.dp ceiling here is the web's `max-w-[280px]`.
 * The source asset is 300x131, so it is upscaled on a high-density screen; the web shares that limit,
 * because the repository holds no larger export.
 */
@Composable
fun LoginScreen(
    state: LoginViewModel.UiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The mark, then the name, then the tagline: the web's order for this block. The heading stays
        // even though the mark carries the name, because the web deliberately draws both.
        Image(
            painter = painterResource(R.drawable.logomarca),
            // The web needs its `alt` because there the image is the only machine-readable name. Here,
            // the heading directly below already reads "Trindade Massas" to a screen reader, so a
            // description would make that name arrive twice.
            contentDescription = null,
            // Full width up to the web's `max-w-[280px]`, with the box pinned to the source's own
            // 300x131 ratio. The ratio is written out rather than left to the painter's intrinsic
            // height because this drawable lives in `drawable-nodpi`, where one pixel is one dp: an
            // `Image` measured only by `fillMaxWidth` would take the intrinsic 131dp as its height,
            // and `Fit` would then letterbox the mark inside a box taller than the mark. With the
            // ratio set, the laid-out box *is* the mark's shape and the spacers below measure against
            // the mark itself.
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(300f / 131f),
            contentScale = ContentScale.Fit,
        )
        // Room above the heading, so the column does not start flush against the top padding.
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        // The tagline under the name, in the muted tone this app already uses for its own lines rather
        // than a new colour.
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        // The failure messages are rendered differently on purpose. Text from the server is shown as
        // it arrives, because it describes what happened; a timeout gets this app's words for a slow
        // server ("não respondeu a tempo"); TLS failures and unparseable responses get dedicated messages;
        // and missing routes or unknown failures get the generic unreachable sentence. Which case is which
        // is decided by the view model, not here: this screen renders what it is handed and never looks at a cause.
        state.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (message) {
                    is LoginMessage.FromServer -> message.text
                    LoginMessage.Timeout -> stringResource(R.string.login_timeout)
                    LoginMessage.Tls -> stringResource(R.string.login_tls)
                    LoginMessage.UnreadableBody -> stringResource(R.string.login_unreadable_body)
                    LoginMessage.Unreachable -> stringResource(R.string.login_unreachable)
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.login_submit))
            }
        }
    }
}

/**
 * The screen with its ViewModel attached and its one navigation event handled.
 *
 * [onSignedIn] fires once per successful sign-in. `signedIn` is an event the view model hands over rather
 * than a state the caller reads, and this effect consumes it as soon as it has reported it, so the effect
 * can never be handed the same sign-in twice and the next one is a false -> true change again.
 */
@Composable
fun LoginRoute(
    onSignedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.signedIn) {
        // The guard is what makes the hand-over below safe. Consuming restarts this effect with a false
        // key, and the restarted body returns here, before the callback, instead of calling the caller
        // a second time. Neither call suspends, so there is no point at which this screen tearing down
        // could strand the flag as true on the way to the next sign-in.
        if (!state.signedIn) return@LaunchedEffect
        onSignedIn()
        viewModel.consumeSignIn()
    }

    LoginScreen(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
    )
}
