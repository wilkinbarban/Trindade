package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun log(
        id: Int = 1,
        userId: Int? = 10,
        displayName: String? = "Operador Chefe",
        action: String = "login",
        entityType: String = "auth",
        entityId: Int? = 10,
        ipAddress: String? = "192.168.1.1",
        details: String? = "{\"method\":\"token\"}",
        createdAt: String = "2026-09-22T10:00:00.000Z",
    ) = AuditResponseLogsInner(
        id = id,
        userId = userId,
        displayName = displayName,
        action = action,
        entityType = entityType,
        entityId = entityId,
        ipAddress = ipAddress,
        details = details,
        createdAt = createdAt,
    )

    private fun render(
        state: AuditViewModel.UiState,
        onBack: () -> Unit = {},
        onActionInputChanged: (String) -> Unit = {},
        onEntityTypeInputChanged: (String) -> Unit = {},
        onUserIdInputChanged: (String) -> Unit = {},
        onApplyFilters: () -> Unit = {},
        onClearFilters: () -> Unit = {},
        onPreviousPage: () -> Unit = {},
        onNextPage: () -> Unit = {},
        onToggleDetails: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                AuditScreen(
                    state = state,
                    onBack = onBack,
                    onActionInputChanged = onActionInputChanged,
                    onEntityTypeInputChanged = onEntityTypeInputChanged,
                    onUserIdInputChanged = onUserIdInputChanged,
                    onApplyFilters = onApplyFilters,
                    onClearFilters = onClearFilters,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onToggleDetails = onToggleDetails,
                )
            }
        }
    }

    @Test
    fun `loading state renders progress indicator`() {
        val state = AuditViewModel.UiState(loading = true, role = RolePolicy.ADMIN)
        render(state = state)

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `access denied renders restricted access message when not admin`() {
        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.WORKER,
            message = "Acesso restrito a administradores.",
            isError = true,
        )
        render(state = state)

        composeRule.onNodeWithText(copy(R.string.audit_access_denied_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.audit_access_denied_desc)).assertIsDisplayed()
    }

    @Test
    fun `empty state renders empty message when logs list is empty`() {
        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.ADMIN,
            logs = emptyList(),
        )
        render(state = state)

        composeRule.onNodeWithText(copy(R.string.audit_empty)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `logs list renders audit items with user, action badge, entity, and details`() {
        val sampleLogs = listOf(
            log(id = 1, displayName = "Operador Chefe", action = "login", entityType = "auth"),
            log(id = 2, displayName = null, userId = 99, action = "delete", entityType = "report", entityId = 42),
        )
        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.ADMIN,
            logs = sampleLogs,
            total = 2,
            page = 1,
            limit = 20,
            totalPages = 1,
        )
        render(state = state)

        composeRule.onNodeWithText(copy(R.string.audit_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.audit_item_user, "Operador Chefe")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Login").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.audit_item_user, "#99")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Exclusão").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `filter controls allow user ID entry and trigger apply and clear callbacks`() {
        var applied = false
        var cleared = false
        var enteredUserId = ""

        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.ADMIN,
            logs = listOf(log()),
            userIdInput = "",
        )
        render(
            state = state,
            onUserIdInputChanged = { enteredUserId = it },
            onApplyFilters = { applied = true },
            onClearFilters = { cleared = true },
        )

        composeRule.onNodeWithText(copy(R.string.audit_filter_user_id)).performTextInput("42")
        assertEquals("42", enteredUserId)

        composeRule.onNodeWithText(copy(R.string.audit_filter_apply)).performClick()
        assertTrue(applied)

        composeRule.onNodeWithText(copy(R.string.audit_filter_clear)).performClick()
        assertTrue(cleared)
    }

    @Test
    fun `pagination controls render summary and invoke previous and next callbacks`() {
        var prevClicked = false
        var nextClicked = false

        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.ADMIN,
            logs = listOf(log()),
            total = 50,
            page = 2,
            limit = 20,
            totalPages = 3,
        )
        render(
            state = state,
            onPreviousPage = { prevClicked = true },
            onNextPage = { nextClicked = true },
        )

        val summaryText = copy(R.string.audit_pagination_summary, 50, 2, 3)
        composeRule.onNodeWithText(summaryText).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.audit_pagination_prev)).performScrollTo().performClick()
        assertTrue(prevClicked)

        composeRule.onNodeWithText(copy(R.string.audit_pagination_next)).performScrollTo().performClick()
        assertTrue(nextClicked)
    }

    @Test
    fun `back button invokes onBack callback`() {
        var backInvoked = false
        val state = AuditViewModel.UiState(
            loading = false,
            role = RolePolicy.ADMIN,
        )
        render(state = state, onBack = { backInvoked = true })

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertTrue(backInvoked)
    }
}
