package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.reports.ReportGeneratorRoute
import com.trindade.app.ui.theme.TrindadeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrindadeTheme {
                // The session is read synchronously rather than observed, because the decision is
                // already available: it is a SharedPreferences read, and awaiting it would mean a
                // frame of the wrong screen for a question that can be answered now. When sign-in
                // gains an asynchronous path this becomes a state flow like any other.
                var signedIn by remember { mutableStateOf(authRepository.hasSession()) }

                if (signedIn) {
                    ReportGeneratorRoute()
                } else {
                    LoginRoute(onSignedIn = { signedIn = true })
                }
            }
        }
    }
}
