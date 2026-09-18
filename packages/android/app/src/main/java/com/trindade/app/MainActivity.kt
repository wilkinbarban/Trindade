package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.loading.LoadingRoute
import com.trindade.app.reports.ReportDetailRoute
import com.trindade.app.reports.ReportGeneratorRoute
import com.trindade.app.ui.theme.TrindadeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The two top-level surfaces. Nothing else is a destination yet. */
    private enum class Tab { REPORTS, LOADING }

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
                // Which report the operator is looking at, if any. Null means the generator.
                var openReportId by remember { mutableStateOf<Int?>(null) }
                // Which of the two top-level surfaces is showing. Reports is where a shift starts.
                var tab by remember { mutableStateOf(Tab.REPORTS) }

                when {
                    !signedIn -> LoginRoute(onSignedIn = { signedIn = true })
                    openReportId != null -> ReportDetailRoute(
                        reportId = openReportId!!,
                        onBack = { openReportId = null },
                    )
                    tab == Tab.LOADING -> LoadingRoute(onBack = { tab = Tab.REPORTS })
                    else -> Column {
                        // Two surfaces, one row of tabs. A navigation library would be more than this
                        // app has places to go, and the back action each screen already owns is the
                        // whole of the routing it needs.
                        Row {
                            TextButton(onClick = { tab = Tab.REPORTS }) { Text(stringResource(R.string.nav_reports)) }
                            TextButton(onClick = { tab = Tab.LOADING }) { Text(stringResource(R.string.nav_loading)) }
                        }
                        ReportGeneratorRoute(onCreated = { openReportId = it })
                    }
                }
            }
        }
    }
}
