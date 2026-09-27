package com.trindade.app.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriversScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun driver(
        id: Int,
        owner: Int?,
        type: AdminDriverResponseDriver.DriverType = AdminDriverResponseDriver.DriverType.fletero,
        active: Int = 1,
    ) = AdminDriverResponseDriver(id, "Motorista $id", "ABC$id", type, active, owner, "2026-01-01")

    private fun state(
        role: String = DriversViewModel.ADMIN,
        drivers: List<AdminDriverResponseDriver> = listOf(driver(1, 7), driver(2, 8, active = 0)),
        loading: Boolean = false,
        saving: Boolean = false,
        error: String? = null,
        editingId: Int? = null,
    ) = DriversViewModel.UiState(
        loading = loading, saving = saving, drivers = drivers, role = role,
        currentUserId = 7, editingId = editingId, name = "Motorista",
        licensePlate = "ABC1", driverType = DriversViewModel.FLETERO, error = error,
    )

    private fun render(
        state: DriversViewModel.UiState,
        onEdit: (AdminDriverResponseDriver) -> Unit = {},
        onToggle: (AdminDriverResponseDriver) -> Unit = {},
        onNameChange: (String) -> Unit = {},
        onLicensePlateChange: (String) -> Unit = {},
        onDriverTypeChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onBack: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            DriversScreen(
                state = state, onBack = onBack, onRefresh = onRefresh,
                onNameChange = onNameChange, onLicensePlateChange = onLicensePlateChange,
                onDriverTypeChange = onDriverTypeChange, onSave = onSave, onCancel = onCancel,
                onEdit = onEdit, onToggle = onToggle,
            )
        }
    }

    @Test fun `admin sees driver details and active toggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onNodeWithText("Motorista 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ABC1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Fletero").assertCountEquals(2)
        composeRule.onNodeWithText("Desativar").performScrollTo().performClick()
        assertEquals(1, toggled)
    }

    @Test fun `admin create form exposes fields and forwards driver type selection`() {
        var type = ""
        render(state(drivers = emptyList()), onDriverTypeChange = { type = it })

        composeRule.onNodeWithText("Novo motorista").performClick()
        composeRule.onNodeWithText("Nome").assertIsDisplayed()
        composeRule.onNodeWithText("Placa").assertIsDisplayed()
        composeRule.onNodeWithText("Tipo do motorista").assertIsDisplayed()
        composeRule.onNodeWithText("Casa").performClick()

        assertEquals(DriversViewModel.CASA, type)
    }

    @Test fun `worker can create fletero and edit only own fletero without toggle`() {
        var edited = 0
        var typeChanges = 0
        render(state(
            role = DriversViewModel.WORKER,
            drivers = listOf(driver(1, 7), driver(2, 8), driver(3, 7, AdminDriverResponseDriver.DriverType.casa)),
        ), onEdit = { edited++ }, onDriverTypeChange = { typeChanges++ })

        composeRule.onNodeWithText("Novo motorista").assertIsDisplayed()
        composeRule.onAllNodesWithText("Editar").assertCountEquals(1)
        composeRule.onNodeWithText("Editar").performScrollTo().performClick()
        assertEquals(1, edited)
        composeRule.onNodeWithText("Tipo do motorista").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
        assertEquals(0, typeChanges)
    }

    @Test fun `back and refresh callbacks are wired`() {
        var backs = 0
        var refreshes = 0
        render(state(), onBack = { backs++ }, onRefresh = { refreshes++ })

        composeRule.onNodeWithText("Voltar").performClick()
        composeRule.onNodeWithText("Atualizar").performClick()
        assertEquals(1, backs)
        assertEquals(1, refreshes)
    }

    @Test fun `loading error and saving states are rendered`() {
        render(state(loading = true, saving = true, error = "Could not reach the server.", editingId = 1))

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Could not reach the server.").assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }
}
