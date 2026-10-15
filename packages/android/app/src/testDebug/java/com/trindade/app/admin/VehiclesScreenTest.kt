package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehiclesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun vehicle(
        id: Int,
        description: String = "Veículo $id",
        licensePlate: String = "ABC-$id",
        isActive: Int = 1,
    ) = AdminVehicleResponseVehicle(
        id = id,
        description = description,
        licensePlate = licensePlate,
        isActive = isActive,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun state(
        role: String = RolePolicy.ADMIN,
        vehicles: List<AdminVehicleResponseVehicle> = listOf(vehicle(1)),
        form: VehiclesViewModel.FormState? = null,
        message: String? = null,
        isError: Boolean = false,
        deleteConfirmVehicle: AdminVehicleResponseVehicle? = null,
    ) = VehiclesViewModel.UiState(
        loading = false,
        vehicles = vehicles,
        form = form,
        role = role,
        message = message,
        isError = isError,
        deleteConfirmVehicle = deleteConfirmVehicle,
    )

    private fun render(
        state: VehiclesViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onOpenEdit: (AdminVehicleResponseVehicle) -> Unit = {},
        onDescriptionChanged: (String) -> Unit = {},
        onLicensePlateChanged: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onToggleActive: (AdminVehicleResponseVehicle) -> Unit = {},
        onRequestDelete: (AdminVehicleResponseVehicle) -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                VehiclesScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onOpenEdit = onOpenEdit,
                    onDescriptionChanged = onDescriptionChanged,
                    onLicensePlateChanged = onLicensePlateChanged,
                    onSave = onSave,
                    onCancelForm = onCancelForm,
                    onToggleActive = onToggleActive,
                    onRequestDelete = onRequestDelete,
                    onCancelDelete = onCancelDelete,
                    onConfirmDelete = onConfirmDelete,
                )
            }
        }
    }

    @Test
    fun `loading state shows progress`() {
        render(VehiclesViewModel.UiState(loading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `vehicle list renders description, plate, and active status`() {
        var openedCreate = 0
        render(
            state = state(
                vehicles = listOf(
                    vehicle(7, description = "Caminhão 07", licensePlate = "XYZ-7777", isActive = 1),
                ),
            ),
            onOpenCreate = { openedCreate += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.vehicles_add_new)).performClick()
        composeRule.onNodeWithText("Caminhão 07").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.vehicles_plate_value, "XYZ-7777")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.vehicles_status_active)).performScrollTo().assertIsDisplayed()
        assertEquals(1, openedCreate)
    }

    @Test
    fun `vehicle form renders fields and wires save and cancel actions`() {
        var saved = 0
        var cancelled = 0
        render(
            state = state(
                form = VehiclesViewModel.FormState(
                    description = "Van Nova",
                    licensePlate = "VAN-1234",
                ),
            ),
            onSave = { saved += 1 },
            onCancelForm = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.vehicles_create_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.vehicles_create)).performScrollTo().performClick()
        composeRule.onNodeWithText(copy(R.string.vehicles_cancel)).performScrollTo().performClick()
        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `admin controls trigger edit, toggle active, and delete`() {
        val veh = vehicle(1, isActive = 1)
        var edited: AdminVehicleResponseVehicle? = null
        var toggled: AdminVehicleResponseVehicle? = null
        var requestedDelete: AdminVehicleResponseVehicle? = null

        render(
            state = state(vehicles = listOf(veh)),
            onOpenEdit = { edited = it },
            onToggleActive = { toggled = it },
            onRequestDelete = { requestedDelete = it },
        )

        composeRule.onNodeWithText(copy(R.string.vehicles_edit)).performScrollTo().performClick()
        assertEquals(veh, edited)

        composeRule.onNodeWithContentDescription(copy(R.string.vehicles_deactivate)).performScrollTo().performClick()
        assertEquals(veh, toggled)

        composeRule.onNodeWithText(copy(R.string.vehicles_delete)).performScrollTo().performClick()
        assertEquals(veh, requestedDelete)
    }

    @Test
    fun `delete request renders confirmation dialog and confirms through callback`() {
        val veh = vehicle(1, description = "Van Sucateada")
        var confirmed = 0

        render(
            state = state(vehicles = emptyList(), deleteConfirmVehicle = veh),
            onConfirmDelete = { confirmed += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.vehicles_delete_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.vehicles_delete_confirm_body, "Van Sucateada")).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.vehicles_delete)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `delete confirmation dialog dismisses through cancel callback`() {
        val veh = vehicle(1, description = "Van Sucateada")
        var cancelled = 0

        render(
            state = state(vehicles = emptyList(), deleteConfirmVehicle = veh),
            onCancelDelete = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.vehicles_cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `error message is displayed when present in state`() {
        render(
            state = state(
                message = "Erro de teste de veículos",
                isError = true,
            ),
        )

        composeRule.onNodeWithText("Erro de teste de veículos").assertIsDisplayed()
    }

    @Test
    fun `empty state renders when no vehicles present`() {
        render(state = state(vehicles = emptyList()))

        composeRule.onNodeWithText(copy(R.string.vehicles_empty)).assertIsDisplayed()
    }

    @Test
    fun `back button invokes onBack callback`() {
        var backInvoked = false
        render(state = state(), onBack = { backInvoked = true })

        composeRule.onNodeWithText(copy(R.string.report_back)).performClick()
        assertEquals(true, backInvoked)
    }
}
