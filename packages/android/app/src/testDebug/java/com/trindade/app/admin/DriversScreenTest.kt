package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriversScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun driver(
        id: Int,
        name: String = "Motorista $id",
        driverType: AdminDriverResponseDriver.DriverType = AdminDriverResponseDriver.DriverType.fletero,
        isActive: Int = 1,
        createdByUserId: Int? = 10,
    ) = AdminDriverResponseDriver(
        id = id,
        name = name,
        licensePlate = null,
        driverType = driverType,
        isActive = isActive,
        createdByUserId = createdByUserId,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun state(
        role: String = RolePolicy.ADMIN,
        currentUserId: Int? = 10,
        drivers: List<AdminDriverResponseDriver> = listOf(driver(1)),
        form: DriversViewModel.FormState? = null,
    ) = DriversViewModel.UiState(
        loading = false,
        drivers = drivers,
        form = form,
        role = role,
        currentUserId = currentUserId,
    )

    private fun render(
        state: DriversViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onOpenEdit: (AdminDriverResponseDriver) -> Unit = {},
        onNameChanged: (String) -> Unit = {},
        onDriverTypeChanged: (DriversViewModel.DriverTypeChoice) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onToggleActive: (AdminDriverResponseDriver) -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                DriversScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onOpenEdit = onOpenEdit,
                    onNameChanged = onNameChanged,
                    onDriverTypeChanged = onDriverTypeChanged,
                    onSave = onSave,
                    onCancelForm = onCancelForm,
                    onToggleActive = onToggleActive,
                )
            }
        }
    }

    @Test
    fun `loading state shows progress`() {
        render(DriversViewModel.UiState(loading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `driver list renders name type and active status`() {
        var openedCreate = 0
        render(
            state = state(drivers = listOf(driver(7, name = "Carlos Fletero", isActive = 1))),
            onOpenCreate = { openedCreate += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.drivers_add_new)).performClick()
        composeRule.onNodeWithText("Carlos Fletero").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.drivers_type_value, copy(R.string.drivers_type_fletero))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.drivers_status_active)).performScrollTo().assertIsDisplayed()
        assertEquals(1, openedCreate)
    }

    @Test
    fun `admin controls are rendered with edit and toggle active and no delete`() {
        val activeDriver = driver(1, isActive = 1)
        val inactiveDriver = driver(2, isActive = 0)
        var editedId: Int? = null
        var toggledId: Int? = null

        render(
            state = state(drivers = listOf(activeDriver, inactiveDriver)),
            onOpenEdit = { editedId = it.id },
            onToggleActive = { toggledId = it.id },
        )

        composeRule.onAllNodesWithText(copy(R.string.drivers_edit)).assertCountEquals(2)
        composeRule.onNodeWithContentDescription(copy(R.string.drivers_deactivate)).performClick()
        composeRule.onAllNodesWithText(copy(R.string.drivers_edit)).get(0).performClick()

        // Drivers have no delete button anywhere
        composeRule.onAllNodesWithText("Excluir").assertCountEquals(0)

        assertEquals(1, toggledId)
        assertEquals(1, editedId)
    }

    @Test
    fun `worker sees only own fletero edit control and no active toggle`() {
        val ownDriver = driver(1, createdByUserId = 10, driverType = AdminDriverResponseDriver.DriverType.fletero)
        val otherDriver = driver(2, createdByUserId = 11, driverType = AdminDriverResponseDriver.DriverType.fletero)

        render(
            state = state(
                role = RolePolicy.WORKER,
                drivers = listOf(ownDriver, otherDriver),
            ),
        )

        composeRule.onAllNodesWithText(copy(R.string.drivers_edit)).assertCountEquals(1)
        composeRule.onNodeWithText(copy(R.string.drivers_read_only)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copy(R.string.drivers_activate)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy(R.string.drivers_deactivate)).assertDoesNotExist()
    }

    @Test
    fun `driver form renders name and wires callbacks`() {
        var saved = 0
        var cancelled = 0
        var changedName = ""
        render(
            state = state(
                form = DriversViewModel.FormState(
                    name = "Novo Motorista",
                    driverType = DriversViewModel.DriverTypeChoice.FLETERO,
                ),
            ),
            onNameChanged = { changedName = it },
            onSave = { saved += 1 },
            onCancelForm = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.drivers_create_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.drivers_name)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.drivers_create)).performClick()
        composeRule.onNodeWithText(copy(R.string.drivers_cancel)).performClick()

        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `admin form allows selecting driver type`() {
        var selectedType: DriversViewModel.DriverTypeChoice? = null
        render(
            state = state(
                role = RolePolicy.ADMIN,
                form = DriversViewModel.FormState(
                    name = "Motorista",
                    driverType = DriversViewModel.DriverTypeChoice.FLETERO,
                ),
            ),
            onDriverTypeChanged = { selectedType = it },
        )

        composeRule.onNodeWithText(copy(R.string.drivers_type_fletero)).performClick()
        composeRule.onNodeWithText(copy(R.string.drivers_type_casa)).performClick()

        assertEquals(DriversViewModel.DriverTypeChoice.CASA, selectedType)
    }

    @Test
    fun `header back in root state invokes onBack callback`() {
        var backed = 0
        var cancelledForm = 0

        render(
            state = state(drivers = listOf(driver(1))),
            onBack = { backed += 1 },
            onCancelForm = { cancelledForm += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()

        assertEquals(1, backed)
        assertEquals(0, cancelledForm)
    }

    @Test
    fun `header back in form state routes to onCancelForm rather than onBack`() {
        var backed = 0
        var cancelledForm = 0

        render(
            state = state(
                drivers = listOf(driver(1)),
                form = DriversViewModel.FormState(name = "Novo Motorista"),
            ),
            onBack = { backed += 1 },
            onCancelForm = { cancelledForm += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()

        assertEquals(1, cancelledForm)
        assertEquals(0, backed)
    }
}
