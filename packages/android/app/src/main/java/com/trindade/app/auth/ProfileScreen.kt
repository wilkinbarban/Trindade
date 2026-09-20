package com.trindade.app.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.BuildConfig
import com.trindade.app.R

/**
 * The account screen: who is signed in, the display name, the password, and the way out.
 *
 * Stateless, like the other screens: state in, events out, so the screen can be rendered without a view
 * model, an activity or a server. The two blocks are the two writes this screen has, and each has its
 * own action -- a name and a password are not the same kind of change, and the password change ends the
 * session.
 *
 * [appVersion] and [releasesUrl] are parameters rather than reads of `BuildConfig` inside the screen, for
 * the same reason the state is: this file renders what it is handed, and the one place that knows where
 * those values come from is [ProfileRoute].
 */
@Composable
fun ProfileScreen(
    state: ProfileViewModel.UiState,
    appVersion: String,
    releasesUrl: String,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Whether the last tap on the download action found no browser to hand the address to. Local state
    // rather than a field on the view model's state: no request is made and nothing on the server changes,
    // so this is the screen reporting on a call it made itself.
    var browserUnavailable by remember { mutableStateOf(false) }

    // The signed-out panel replaces the whole screen, back action included. The session is already gone
    // at that point, so "Voltar" would land on a surface that can read nothing, and the panel's single
    // action is the only thing left to do.
    if (state.signedOut) {
        SignedOutPanel(
            message = state.message,
            onBackToLogin = onBackToLogin,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.titleLarge)

        // One line for what went wrong, in the convention the other screens use.
        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Drawn in the confirmation tone rather than in the error one, which is why it is not the message
        // above: this is the answer to a tap that worked. The report generator's saved-report line makes
        // the same distinction for the same reason.
        if (state.nameSaved) {
            Text(
                text = stringResource(R.string.profile_name_saved),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val profile = state.profile
        when {
            state.loading -> CircularProgressIndicator()

            // Nothing to show and nothing to edit: a form drawn over a profile that was never read would
            // offer a save that would send a name the operator never chose. The message above says why.
            profile == null -> Unit

            else -> {
                Text(text = stringResource(R.string.profile_account), style = MaterialTheme.typography.titleMedium)

                // The username is the account's handle and the server's to change, so it is shown as text
                // rather than as a disabled field: there is nothing to type into it and a dead control
                // invites a tap.
                ReadOnlyField(label = stringResource(R.string.profile_username), value = profile.username)

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.profile_display_name)) },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.profile_save))
                    }
                }

                // The role as a word, from the string the server sent rather than from a control.
                ReadOnlyField(label = stringResource(R.string.profile_role), value = roleLabel(profile.role))

                Text(
                    text = stringResource(R.string.profile_change_password),
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedTextField(
                    value = state.currentPassword,
                    onValueChange = onCurrentPasswordChange,
                    label = { Text(stringResource(R.string.profile_current_password)) },
                    singleLine = true,
                    enabled = !state.changing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.newPassword,
                    onValueChange = onNewPasswordChange,
                    label = { Text(stringResource(R.string.profile_new_password)) },
                    singleLine = true,
                    enabled = !state.changing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // The rule is stated here, where the operator is typing, instead of being discovered as
                    // a 400 after typing the password twice. The number comes from the view model's own
                    // constant, which is the same one its gate uses, so the sentence and the rule cannot
                    // drift apart.
                    supportingText = {
                        Text(stringResource(R.string.profile_password_rule, ProfileViewModel.MIN_PASSWORD_LENGTH))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onChangePassword,
                    enabled = state.canChangePassword,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.changing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.profile_change_password))
                    }
                }

                // No confirmation on this one, and that is the difference between it and the report
                // deletion, which asks twice. Closing a session is undone by signing in again with the
                // credentials the operator already has; deleting a report is undone by nobody.
                TextButton(onClick = onSignOut) { Text(stringResource(R.string.profile_sign_out)) }
            }
        }

        // The version is drawn outside the branch that needs a loaded profile, and that placement is the
        // point: this is the line an operator reads back when they ask whether an update landed, and a
        // profile that failed to load is exactly when someone is looking for it. It is the same value the
        // APK was packaged with, not a copy that could drift -- see the version fields in build.gradle.kts.
        ReadOnlyField(label = stringResource(R.string.profile_app_version), value = appVersion)

        // What the check that runs on entry found, directly under the version it is about: that is the
        // line the operator compares it with.
        //
        // Three of the four states are drawn, and each speaks in its own voice. That is the design of
        // this line rather than a matter of taste:
        //
        // - "up to date" is quiet -- the muted tone the hint below uses -- because it is the answer that
        //   asks for nothing.
        // - "there is a newer version" is the one in the attention tone, because it is the only one of
        //   the three the operator has something to do about, and what to do is the button directly below
        //   this line. The tone is the button's own primary, so the sentence and its action read as one
        //   thing.
        // - "could not check" is quiet again, and that is the deliberate part: it does **not** go on the
        //   error line at the top of the screen. That line is this screen's channel for something that
        //   went wrong with the operator's account -- a refused name, an unreadable profile -- and it is
        //   drawn in the error colour. A failed update check is not about them: their account loaded, the
        //   session is fine, and GitHub had a bad moment or named a tag this client reads no version out
        //   of. Colouring the screen's error line for it would say something untrue about their session,
        //   and an operator who reads it that way starts looking for a fault at their end.
        //
        // The fourth state draws nothing at all. A "checking..." line would flicker for the length of one
        // round trip to GitHub and say nothing anyone needs: this line only matters once there is a
        // sentence to read.
        when (val update = state.update) {
            ProfileViewModel.UpdateStatus.Checking -> Unit

            ProfileViewModel.UpdateStatus.UpToDate -> UpdateNotice(
                text = stringResource(R.string.profile_update_up_to_date),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ProfileViewModel.UpdateStatus.Available -> UpdateNotice(
                text = stringResource(R.string.profile_update_available, update.version),
                color = MaterialTheme.colorScheme.primary,
            )

            ProfileViewModel.UpdateStatus.CouldNotCheck -> UpdateNotice(
                text = stringResource(R.string.profile_update_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The action, under the value it is about: the operator reads the version, decides it is old, and
        // this is the tap that takes them to where the new one is published. If both were not where a
        // failed profile load still leaves them, the two halves of that one decision would be split apart
        // on exactly the screen where the operator is looking for either.
        Button(
            onClick = { browserUnavailable = !openReleasesPage(context, releasesUrl) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.profile_download_update))
        }

        // The answer to a tap that found nothing able to open the address, drawn under the button that was
        // tapped rather than in the message line at the top: this is not a statement about the session, and
        // the operator's next move is right here. In the error tone, because unlike the hint below it this
        // one is a fault.
        if (browserUnavailable) {
            Text(
                text = stringResource(R.string.profile_download_update_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // The first install's prompt, described before it appears instead of after. Muted rather than in
        // the error tone or the confirmation one, because it is neither: nothing went wrong and nothing
        // was done yet.
        Text(
            text = stringResource(R.string.profile_download_update_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line of the update check, in whichever of the three voices [color] carries. */
@Composable
private fun UpdateNotice(text: String, color: Color) {
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Opens the page the APK is published on, in whichever browser the phone has.
 *
 * The call is wrapped in a catch rather than preceded by a check, and the difference is deliberate.
 * `resolveActivity` answers "can this app see anything that handles this intent?", and since Android 11
 * package visibility hides other apps unless a `<queries>` element declares them: it would answer null
 * here and disable a button that works, buying a manifest entry this app has no other use for. It would
 * also be a worse question than the one that matters -- whether a browser is installed is not the same
 * question as whether it answers at the moment of the tap. `startActivity` resolves when it is asked and
 * reports the only real failure, nothing able to open the URL, as [ActivityNotFoundException].
 *
 * That failure is not silent, and an earlier version of this comment was wrong about why it could be. It
 * argued that the phone must have a browser because the APK got there by hand from the release page, but an
 * APK also arrives by `adb install`, from an MDM or from a file manager, so "this app is installed" says
 * nothing about whether a browser exists. What a swallowed tap leaves behind is a button that does nothing:
 * the operator has no sentence to read and support has no line to look at. The catch answers both -- the
 * screen says what failed, and the log keeps the address that could not be opened.
 *
 * @return whether something took the intent, which is what the screen turns into that sentence when it did
 *   not.
 */
private fun openReleasesPage(context: Context, releasesUrl: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, releasesUrl.toUri()))
    true
} catch (notFound: ActivityNotFoundException) {
    Log.w(TAG, "No installed activity could open $releasesUrl", notFound)
    false
}

// The tag this file's one log line appears under, so a support call can be told what to grep for.
private const val TAG = "ProfileScreen"

/**
 * What is shown once the session has ended: why, and the one way forward.
 *
 * The sentence is the session's own account of itself -- a password change says so, because that is
 * something the operator did not ask to be signed out by -- and it falls back to this app's words for a
 * plain sign-out, which needs no explanation.
 */
@Composable
private fun SignedOutPanel(
    message: String?,
    onBackToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message ?: stringResource(R.string.profile_signed_out),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = onBackToLogin,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.profile_back_to_login))
        }
    }
}

/** One read-only fact about the account: its label above, its value below. */
@Composable
private fun ReadOnlyField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The role as a word the operator reads.
 *
 * The contract types `role` as a bare string while the database has exactly two values, which is the
 * same looseness `task_type` had before it became an enum. This does not invent an enum for it, and it
 * does not drop a value it does not recognise either: the two known roles get their own words from this
 * app's resources, and anything else is shown exactly as the server sent it. Printing an unknown role
 * as one of the two would be a claim about the operator's permissions that nothing supports.
 */
@Composable
private fun roleLabel(role: String): String = when (role) {
    ROLE_ADMINISTRATOR -> stringResource(R.string.profile_role_administrator)
    ROLE_WORKER -> stringResource(R.string.profile_role_worker)
    else -> role
}

private const val ROLE_ADMINISTRATOR = "Administrador"
private const val ROLE_WORKER = "Trabalhador"

/**
 * The screen with its view model attached.
 *
 * [onSignedOut] is the activity's, not this screen's: leaving the app is not something a view model
 * does, and it is reached from the panel's action rather than from an effect on the flag. The difference
 * matters here -- an effect would fire the instant the flag rose, and the sentence explaining the
 * sign-out would be on screen for a single frame before the login form replaced it.
 */
@Composable
fun ProfileRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.open() }

    ProfileScreen(
        state = state,
        appVersion = BuildConfig.APP_VERSION_NAME,
        releasesUrl = BuildConfig.RELEASES_URL,
        onBack = onBack,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onSave = viewModel::save,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onChangePassword = viewModel::changePassword,
        onSignOut = viewModel::signOut,
        onBackToLogin = onSignedOut,
    )
}
