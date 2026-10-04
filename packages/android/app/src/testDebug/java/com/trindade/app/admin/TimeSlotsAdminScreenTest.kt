package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `renders title and slots list`() {
        render(state(timeSlots = listOf("04:00", "06:00", "08:00")))

        composeRule.onNodeWithText(copy(R.string.time_slots_title)).assertIsDisplayed()
        composeRule.onNodeWithText("04:00").assertIsDisplayed()
        composeRule.onNodeWithText("06:00").assertIsDisplayed()
        composeRule.onNodeWithText("08:00").assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.time_slots_add_new)).assertIsDisplayed()
    }

    @Test
    fun `empty state shows empty message`() {
        render(state(timeSlots = emptyList()))

        composeRule.onNodeWithText(copy(R.string.time_slots_empty)).assertIsDisplayed()
    }

    @Test
    fun `click add button triggers onOpenCreate`() {
        var createClicked = false
        render(state(), onOpenCreate = { createClicked = true })

        composeRule.onNodeWithText(copy(R.string.time_slots_add_new)).performClick()
        assertEquals(true, createClicked)
    }

    @Test
    fun `form state renders input field and triggers save`() {
        var saveClicked = false
        var inputCaptured = ""

        render(
            state(
                form = TimeSlotsAdminViewModel.FormState(slotInput = "09:30"),
            ),
            onSlotInputChanged = { inputCaptured = it },
            onSave = { saveClicked = true },
        )

        composeRule.onNodeWithText(copy(R.string.time_slots_new_slot)).assertIsDisplayed()
        composeRule.onNodeWithText("09:30").assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.time_slots_add)).performClick()
        assertEquals(true, saveClicked)
    }

    @Test
    fun `cancel form triggers onCancelForm`() {
        var cancelClicked = false
        render(
            state(form = TimeSlotsAdminViewModel.FormState()),
            onCancelForm = { cancelClicked = true },
        )

        composeRule.onNodeWithText(copy(R.string.time_slots_cancel)).performClick()
        assertEquals(true, cancelClicked)
    }

    @Test
    fun `renders validation error when present`() {
        render(
            state(
                form = TimeSlotsAdminViewModel.FormState(
                    slotInput = "invalid",
                    validationError = "Formato inválido. Use HH:MM (ex: 08:00).",
                ),
            ),
        )

        composeRule.onNodeWithText("Formato inválido. Use HH:MM (ex: 08:00).").assertIsDisplayed()
    }

    @Test
    fun `remove button triggers onRequestDelete`() {
        var requestedSlot: String? = null
        render(
            state(timeSlots = listOf("06:00", "08:00")),
            onRequestDelete = { requestedSlot = it },
        )

        composeRule.onAllNodesWithText(copy(R.string.time_slots_remove))[0].performClick()
        assertEquals("06:00", requestedSlot)
    }

    @Test
    fun `confirmation dialog renders and triggers confirm or cancel`() {
        var confirmClicked = false
        var cancelClicked = false

        render(
            state(
                timeSlots = listOf("06:00", "08:00"),
                deleteConfirmSlot = "06:00",
            ),
            onConfirmDelete = { confirmClicked = true },
            onCancelDelete = { cancelClicked = true },
        )

        composeRule.onNodeWithText(copy(R.string.time_slots_delete_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.time_slots_delete_confirm_body, "06:00")).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.time_slots_cancel)).performClick()
        assertEquals(true, cancelClicked)
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
    fun `critical time slot administration action controls meet 48dp touch target bounds`() {
        render(
            state(
                timeSlots = listOf("06:00"),
                deleteConfirmSlot = "06:00",
                form = TimeSlotsAdminViewModel.FormState(),
            ),
        )

        val backNode = composeRule.onNodeWithText(copy(R.string.report_back))
        backNode.assertIsDisplayed()
        val backBounds = backNode.getUnclippedBoundsInRoot()
        assertTrue("Back height >= 48dp", backBounds.bottom - backBounds.top >= 48.dp)
        assertTrue("Back width >= 48dp", backBounds.right - backBounds.left >= 48.dp)

        val cancelNodes = composeRule.onAllNodesWithText(copy(R.string.time_slots_cancel))
        cancelNodes.assertCountEquals(2)
        val formCancelBounds = cancelNodes[0].getUnclippedBoundsInRoot()
        assertTrue("Form Cancel height >= 48dp", formCancelBounds.bottom - formCancelBounds.top >= 48.dp)
        assertTrue("Form Cancel width >= 48dp", formCancelBounds.right - formCancelBounds.left >= 48.dp)

        val dialogCancelBounds = cancelNodes[1].getUnclippedBoundsInRoot()
        assertTrue("Dialog Cancel height >= 48dp", dialogCancelBounds.bottom - dialogCancelBounds.top >= 48.dp)
        assertTrue("Dialog Cancel width >= 48dp", dialogCancelBounds.right - dialogCancelBounds.left >= 48.dp)

        val confirmNode = composeRule.onAllNodesWithText(copy(R.string.time_slots_remove))[0]
        confirmNode.assertIsDisplayed()
        val confirmBounds = confirmNode.getUnclippedBoundsInRoot()
        assertTrue("Confirm height >= 48dp", confirmBounds.bottom - confirmBounds.top >= 48.dp)
        assertTrue("Confirm width >= 48dp", confirmBounds.right - confirmBounds.left >= 48.dp)
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
        assertEquals(false, cancelDeleteCalled)
        assertEquals(false, backCalled)
    }

    @Test
    fun `back button while a form is saving keeps the lockout and never exits`() {
        var cancelFormCalled = false
        var backCalled = false
        render(
            state(form = TimeSlotsAdminViewModel.FormState(saving = true)),
            onCancelForm = { cancelFormCalled = true },
            onBack = { backCalled = true },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(false, cancelFormCalled)
        assertEquals(false, backCalled)
    }
}
