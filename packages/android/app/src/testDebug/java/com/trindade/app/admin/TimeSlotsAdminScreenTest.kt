package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeSlotsAdminScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun state(
        role: String = RolePolicy.ADMIN,
        timeSlots: List<String> = listOf("06:00", "08:00"),
        form: TimeSlotsAdminViewModel.FormState? = null,
        message: String? = null,
        isError: Boolean = false,
        deleteConfirmSlot: String? = null,
        deleting: Boolean = false,
        loading: Boolean = false,
    ) = TimeSlotsAdminViewModel.UiState(
        loading = loading,
        timeSlots = timeSlots,
        form = form,
        role = role,
        message = message,
        isError = isError,
        deleteConfirmSlot = deleteConfirmSlot,
        deleting = deleting,
    )

    private fun render(
        state: TimeSlotsAdminViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onSlotInputChanged: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onRequestDelete: (String) -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                TimeSlotsAdminScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onSlotInputChanged = onSlotInputChanged,
                    onSave = onSave,
                    onCancelForm = onCancelForm,
                    onRequestDelete = onRequestDelete,
                    onCancelDelete = onCancelDelete,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }

    @Test
    fun `loading state shows progress indicator`() {
        render(state(loading = true))
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `loading state renders back button and allows navigation`() {
        var backClicked = false
        render(state(loading = true), onBack = { backClicked = true })

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()
        assertEquals(true, backClicked)
    }

    @Test
    fun `back button triggers onBack`() {
        var backClicked = false
        render(state(), onBack = { backClicked = true })

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, backClicked)
    }

    @Test
    fun `back button with active form cancels form rather than exiting screen`() {
        var cancelFormCalled = false
        var backCalled = false
        render(
            state(form = TimeSlotsAdminViewModel.FormState()),
            onCancelForm = { cancelFormCalled = true },
            onBack = { backCalled = true },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, cancelFormCalled)
        assertEquals(false, backCalled)
    }

    @Test
    fun `back button with active delete confirmation cancels dialog rather than exiting screen`() {
        var cancelDeleteCalled = false
        var backCalled = false
        render(
            state(deleteConfirmSlot = "08:00"),
            onCancelDelete = { cancelDeleteCalled = true },
            onBack = { backCalled = true },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, cancelDeleteCalled)
        assertEquals(false, backCalled)
    }

    @Test
    fun `back button during an active deletion keeps the lockout and never exits`() {
        var cancelDeleteCalled = false
        var backCalled = false
        render(
            state(deleteConfirmSlot = "08:00", deleting = true),
            onCancelDelete = { cancelDeleteCalled = true },
            onBack = { backCalled = true },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, cancelDeleteCalled)
        assertEquals(false, backCalled)
    }
}
