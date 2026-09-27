package com.trindade.app.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehiclesScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun vehicle(
        id: Int,
        description: String = "Caminhão $id",
        licensePlate: String = "ABC$id",
        isActive: Int = 1,
        createdAt: String = "2026-01-01",
    ) = AdminVehicleResponseVehicle(
        id = id,
        description = description,
        licensePlate = licensePlate,
        isActive = isActive,
        createdAt = createdAt,
    )

    private fun state(
        role: String? = VehiclesViewModel.ADMIN,
        vehicles: List<AdminVehicleResponseVehicle> = listOf(
            vehicle(1, "Caminhão 1", "ABC1D23", isActive = 1),
            vehicle(2, "Van 2", "XYZ9W87", isActive = 0),
        ),
        loading: Boolean = false,
        saving: Boolean = false,
        error: String? = null,
        refusedStatus: Int? = null,
        editingId: Int? = null,
        description: String = "Novo Caminhão",
        licensePlate: String = "NEW1234",
        deleteTarget: AdminVehicleResponseVehicle? = null,
    ) = VehiclesViewModel.UiState(
        loading = loading,
        saving = saving,
        vehicles = vehicles,
        role = role,
        currentUserId = 1,
        editingId = editingId,
        description = description,
        licensePlate = licensePlate,
        deleteTarget = deleteTarget,
        error = error,
        refusedStatus = refusedStatus,
    )

    private fun render(
        state: VehiclesViewModel.UiState,
        onBack: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onDescriptionChange: (String) -> Unit = {},
        onLicensePlateChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onEdit: (AdminVehicleResponseVehicle) -> Unit = {},
        onToggle: (AdminVehicleResponseVehicle) -> Unit = {},
        onRequestDelete: (AdminVehicleResponseVehicle) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onDismissDelete: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            VehiclesScreen(
                state = state,
                onBack = onBack,
                onRefresh = onRefresh,
                onDescriptionChange = onDescriptionChange,
                onLicensePlateChange = onLicensePlateChange,
                onSave = onSave,
                onCancel = onCancel,
                onEdit = onEdit,
                onToggle = onToggle,
                onRequestDelete = onRequestDelete,
                onConfirmDelete = onConfirmDelete,
                onDismissDelete = onDismissDelete,
            )
        }
    }

    @Test fun `admin sees vehicle details and active toggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onNodeWithText("Veículos").assertIsDisplayed()
        composeRule.onNodeWithText("Caminhão 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ABC1D23").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ativo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Van 2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("XYZ9W87").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Inativo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Desativar").performScrollTo().performClick()
        assertEquals(1, toggled)
    }

    @Test fun `admin create form renders description and license plate fields and invokes save`() {
        var descChanged = ""
        var plateChanged = ""
        var saved = 0

        render(
            state(vehicles = emptyList(), description = "", licensePlate = ""),
            onDescriptionChange = { descChanged = it },
            onLicensePlateChange = { plateChanged = it },
            onSave = { saved++ },
        )

        composeRule.onNodeWithText("Novo veículo").performClick()
        composeRule.onNodeWithText("Descrição").assertIsDisplayed()
        composeRule.onNodeWithText("Placa").assertIsDisplayed()

        composeRule.onNodeWithText("Descrição").performTextInput("Truck A")
        assertEquals("Truck A", descChanged)

        composeRule.onNodeWithText("Placa").performTextInput("BRA1234")
        assertEquals("BRA1234", plateChanged)

        composeRule.onNodeWithText("Criar").performClick()
        assertEquals(1, saved)
    }

    @Test fun `admin edit form renders description and license plate with save and cancel`() {
        var saved = 0
        var cancelled = 0
        render(
            state(editingId = 1, description = "Caminhão 1", licensePlate = "ABC1D23"),
            onSave = { saved++ },
            onCancel = { cancelled++ },
        )

        composeRule.onNodeWithText("Descrição").assertIsDisplayed()
        composeRule.onNodeWithText("Placa").assertIsDisplayed()
        composeRule.onNodeWithText("Salvar").performClick()
        assertEquals(1, saved)
        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun `worker sees read-only catalog with somente leitura and no admin controls`() {
        var edits = 0
        render(
            state(role = VehiclesViewModel.WORKER),
            onEdit = { edits++ },
        )

        composeRule.onNodeWithText("Caminhão 1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Novo veículo").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Ativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
        assertEquals(0, edits)
    }

    @Test fun `unknown role fails closed with no admin controls`() {
        render(state(role = "Gerente"))

        composeRule.onNodeWithText("Caminhão 1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Novo veículo").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Ativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
    }

    @Test fun `confirmed delete dialog via state target forwards confirmation`() {
        var confirmed = 0
        var dismissed = 0
        var currentRole by mutableStateOf<String?>(VehiclesViewModel.WORKER)
        composeRule.setContent {
            TrindadeTheme {
                VehiclesScreen(
                    state = state(role = currentRole, deleteTarget = vehicle(1, "Caminhão 1")),
                    onBack = {},
                    onRefresh = {},
                    onConfirmDelete = { confirmed++ },
                    onDismissDelete = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
        composeRule.onNodeWithText("Confirmar").assertDoesNotExist()

        currentRole = VehiclesViewModel.ADMIN

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir Caminhão 1?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test fun `confirmed delete from list item invokes request once and confirm once`() {
        var requestCount = 0
        var confirmCount = 0
        var requestedVehicle: AdminVehicleResponseVehicle? = null
        render(
            state(deleteTarget = null),
            onRequestDelete = {
                requestCount++
                requestedVehicle = it
            },
            onConfirmDelete = { confirmCount++ },
        )

        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requestCount)
        assertEquals(1, requestedVehicle?.id)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()

        assertEquals(1, requestCount)
        assertEquals(1, confirmCount)
    }

    @Test fun `confirmed delete from list item forwards dismissal`() {
        var requested = 0
        var dismissed = 0
        var deletedTarget: AdminVehicleResponseVehicle? = null
        render(
            state(deleteTarget = null),
            onRequestDelete = { requested++; deletedTarget = it },
            onDismissDelete = { dismissed++ },
        )

        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requested)
        assertEquals(1, deletedTarget?.id)
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun `delete contract pins list request dialog confirm and dialog cancel without premature deletion`() {
        var requested = 0
        var confirmed = 0
        var dismissed = 0
        var target: AdminVehicleResponseVehicle? = null
        render(
            state(deleteTarget = null),
            onRequestDelete = {
                requested++
                target = it
            },
            onConfirmDelete = { confirmed++ },
            onDismissDelete = { dismissed++ },
        )

        assertEquals(0, requested)
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)

        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requested)
        assertEquals(1, target?.id)
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir Caminhão 1?").assertIsDisplayed()

        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()

        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(2, requested)
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
        assertEquals(2, requested)
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }

    @Test fun `loading error refusal and saving states are rendered`() {
        render(state(
            loading = true,
            saving = true,
            vehicles = emptyList(),
            error = VehiclesViewModel.REFUSED,
            refusedStatus = 400,
            editingId = 1,
        ))

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(VehiclesViewModel.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithText("Status: 400").assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }

    @Test fun `empty list renders empty notice`() {
        render(state(loading = false, vehicles = emptyList()))
        composeRule.onNodeWithText("Nenhum veículo").assertIsDisplayed()
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

    @Test fun `validation error display renders numeric-free message`() {
        render(state(error = VehiclesViewModel.DESCRIPTION))
        composeRule.onNodeWithText(VehiclesViewModel.DESCRIPTION).assertIsDisplayed()
        assertFalse(VehiclesViewModel.DESCRIPTION.any { it.isDigit() })
    }
}
