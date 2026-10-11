package com.trindade.app.reports

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.trindade.app.R
import com.trindade.app.contract.models.ReportListItemUser
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native characterization only; never reads or restores pre-test clipboard contents. */
@RunWith(AndroidJUnit4::class)
class ReportDetailNativeGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val payload = "  Relatório — café 漢字\n\tsegunda linha  \n"
    private lateinit var clipboard: ClipboardManager

    private fun label(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun state() = ReportDetailViewModel.UiState(
        reportId = 42,
        loading = false,
        report = ReportResponseReport(
            id = 42,
            turno = ReportResponseReport.Turno.tarde,
            reportDate = "2026-09-18",
            notes = null,
            createdAt = "2026-09-18T10:00:00Z",
            updatedAt = "2026-09-18T10:00:00Z",
            user = ReportListItemUser(1, "synthetic"),
            items = emptyList(),
            temperatures = emptyList(),
            canEdit = true,
            readOnly = false,
        ),
        photos = emptyList(),
        uploadingPhoto = false,
        exportText = null,
        loadingExport = false,
        message = null,
    )

    // createComposeRule hosts the stateless screen, not MainActivity/the route/VM/backend.
    private fun render(state: ReportDetailViewModel.UiState, onLoadExport: () -> Unit = {}) {
        composeRule.setContent {
            clipboard = LocalClipboardManager.current
            TrindadeTheme {
                ReportDetailScreen(
                    state = state,
                    onAddPhoto = {},
                    onDeletePhoto = {},
                    onLoadExport = onLoadExport,
                    onEdit = {},
                    onBack = {},
                )
            }
        }
    }

    private fun seedClipboard(sentinel: String) {
        composeRule.runOnIdle {
            clipboard.setText(AnnotatedString(sentinel))
            assertEquals(AnnotatedString(sentinel), clipboard.getText())
        }
    }

    private fun assertPayload(text: String) {
        composeRule.onNodeWithText(text, substring = false)
            .performScrollTo().assertIsDisplayed().assertTextEquals(text)
    }

    private fun copyButton() = composeRule.onNodeWithText(label(R.string.export_copy))
        .performScrollTo().assertIsDisplayed().assertIsEnabled()

    @Test
    fun generalLoadingAllowsExportOnce() {
        var loads = 0
        render(state().copy(loading = true)) { loads++ }

        composeRule.onNodeWithText(label(R.string.export_load))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithText(label(R.string.export_copy)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun pendingWithoutPayloadDisablesExportAndHasNoCopy() {
        var loads = 0
        render(state().copy(loadingExport = true)) { loads++ }

        composeRule.onNodeWithText(label(R.string.export_load))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(label(R.string.export_copy)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, loads) }
    }

    @Test
    fun payloadIsVerbatimAndCopyEnabled() {
        render(state().copy(exportText = payload))

        assertPayload(payload)
        copyButton()
    }

    @Test
    fun copyReplacesSyntheticSentinelExactly() {
        render(state().copy(exportText = payload))
        seedClipboard("synthetic native gate sentinel")

        copyButton().performClick()
        composeRule.runOnIdle { assertEquals(AnnotatedString(payload), clipboard.getText()) }
    }

    @Test
    fun priorPayloadRemainsCopiableWhilePending() {
        val priorPayload = "  Export anterior — café\nsegunda linha  \n"
        var loads = 0
        render(state().copy(exportText = priorPayload, loadingExport = true)) { loads++ }
        seedClipboard("synthetic prior export sentinel")

        composeRule.onNodeWithText(label(R.string.export_load))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        assertPayload(priorPayload)
        copyButton().performClick()
        composeRule.runOnIdle {
            assertEquals(0, loads)
            assertEquals(AnnotatedString(priorPayload), clipboard.getText())
        }
    }
}
