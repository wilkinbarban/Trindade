package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.components.AdminOutcome
import com.trindade.app.ui.UiText
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    private fun driver(
        id: Int,
        owner: Int?,
        type: AdminDriverResponseDriver.DriverType = AdminDriverResponseDriver.DriverType.fletero,
        active: Int = 1,
    ) = AdminDriverResponseDriver(id, "Motorista $id", "ABC$id", null, type, active, owner, "2026-01-01")

    private fun state(
        role: String = DriversViewModel.ADMIN,
        drivers: List<AdminDriverResponseDriver> = listOf(driver(1, 7), driver(2, 8, active = 0)),
        loading: Boolean = false,
        saving: Boolean = false,
        error: UiText? = null,
        editingId: Int? = null,
        name: String = "Motorista",
    ) = DriversViewModel.UiState(
        loading = loading, saving = saving, drivers = drivers, role = role,
        currentUserId = 7, editingId = editingId, name = name,
        phone = "+55 11 98888-7777", driverType = DriversViewModel.FLETERO,
        error = error,
    )

    private fun render(
        state: DriversViewModel.UiState,
        onEdit: (AdminDriverResponseDriver) -> Unit = {},
        onToggle: (AdminDriverResponseDriver) -> Unit = {},
        onNameChange: (String) -> Unit = {},
        onPhoneChange: (String) -> Unit = {},
        onDriverTypeChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onBack: () -> Unit = {},
        onDismissOutcome: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            DriversScreen(
                state = state, onBack = onBack, onRefresh = onRefresh,
                onNameChange = onNameChange,
                onPhoneChange = onPhoneChange,
                onDriverTypeChange = onDriverTypeChange, onSave = onSave, onCancel = onCancel,
                onEdit = onEdit, onToggle = onToggle,
                onDismissOutcome = onDismissOutcome,
            )
        }
    }

    @Test fun `admin sees driver details and active toggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onNodeWithText("Motorista 1").performScrollTo().assertIsDisplayed()
        // The fixture carries a plate and the card does not draw it: that field belongs to vehicles.
        composeRule.onNodeWithText("ABC1").assertDoesNotExist()
        composeRule.onAllNodesWithText("Fletero").assertCountEquals(2)
        composeRule.onNodeWithText("Desativar").performScrollTo().performClick()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, toggled)
    }

    /**
     * The number the fleet keeps, drawn in the list the way the plate is: the one an operator typed, and
     * nothing where nobody has one. A blank is the answer the list exists to give here -- it is how the
     * panel shows who has a number recorded and who does not -- so the case worth asserting is that the
     * driver without one is rendered at all (no error, no crash) and that no number appears for them.
     */
    @Test fun `a driver's number is drawn and a driver without one shows none`() {
        render(
            state(
                drivers = listOf(
                    driver(1, 7).copy(phone = "+55 (11) 98888-7777"),
                    driver(2, 8),
                ),
            ),
        )

        composeRule.onNodeWithText("+55 (11) 98888-7777").performScrollTo().assertIsDisplayed()

        // The second card renders its own lines, no number and no plate: the name says it was drawn.
        composeRule.onNodeWithText("Motorista 2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ABC2").assertDoesNotExist()
        composeRule.onAllNodesWithText("+55", substring = true).assertCountEquals(1)
    }

    @Test fun `admin create form exposes fields and forwards driver type selection`() {
        var type = ""
        render(state(drivers = emptyList()), onDriverTypeChange = { type = it })

        composeRule.onNodeWithText("Novo motorista").performClick()
        composeRule.onNodeWithText("Nome").assertIsDisplayed()
        // The web form has no plate for a driver, so this one does not offer it either.
        composeRule.onNodeWithText("Placa").assertDoesNotExist()
        // The number is optional: the field is there, and it starts from what the driver already has.
        composeRule.onNodeWithText("Telefone (WhatsApp)").assertIsDisplayed()
        composeRule.onNodeWithText("+55 11 98888-7777").assertIsDisplayed()
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
        render(state(loading = true, saving = true, error = DriversViewModel.UNREACHABLE, editingId = 1))

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        val expectedError = copy(R.string.admin_error_unreachable)
        composeRule.onNodeWithText(expectedError).assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }

    @Test fun `critical driver administration action controls meet 48dp touch target bounds`() {
        render(state(editingId = 1))

        val refreshNode = composeRule.onNodeWithText("Atualizar")
        refreshNode.assertIsDisplayed()
        val refreshBounds = refreshNode.getUnclippedBoundsInRoot()
        assertTrue("Atualizar height must be >= 48dp", refreshBounds.bottom - refreshBounds.top >= 48.dp)
        assertTrue("Atualizar width must be >= 48dp", refreshBounds.right - refreshBounds.left >= 48.dp)

        val cancelNode = composeRule.onNodeWithText("Cancelar")
        cancelNode.assertIsDisplayed()
        val cancelBounds = cancelNode.getUnclippedBoundsInRoot()
        assertTrue("Cancelar height >= 48dp", cancelBounds.bottom - cancelBounds.top >= 48.dp)
        assertTrue("Cancelar width >= 48dp", cancelBounds.right - cancelBounds.left >= 48.dp)

        val saveNode = composeRule.onNodeWithText("Salvar")
        saveNode.assertIsDisplayed()
        val saveBounds = saveNode.getUnclippedBoundsInRoot()
        assertTrue("Salvar height >= 48dp", saveBounds.bottom - saveBounds.top >= 48.dp)
        assertTrue("Salvar width >= 48dp", saveBounds.right - saveBounds.left >= 48.dp)
    }

    @Test
    fun `save shows confirmation dialog and confirm invokes onSave`() {
        var saved = 0
        render(state(editingId = 1, name = "Carlos"), onSave = { saved++ })

        composeRule.onNodeWithText("Salvar").performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_save_body, "Carlos")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `save confirmation cancel does not invoke onSave`() {
        var saved = 0
        render(state(editingId = 1, name = "Carlos"), onSave = { saved++ })

        composeRule.onNodeWithText("Salvar").performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_save_body, "Carlos")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onAllNodesWithText("Cancelar")[1].performClick()
        assertEquals(0, saved)
    }

    @Test
    fun `toggle shows confirmation dialog and confirm invokes onToggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onAllNodesWithText("Desativar")[0].performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_toggle_off_body, "Motorista 1")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `outcome shows result dialog and close invokes onDismissOutcome`() {
        var dismissed = 0
        render(
            state().copy(outcome = AdminOutcome(isSuccess = true, message = UiText.of(R.string.admin_drivers_created))),
            onDismissOutcome = { dismissed++ },
        )

        val expectedSuccess = copy(R.string.admin_drivers_created)
        composeRule.onNodeWithText(expectedSuccess).assertIsDisplayed()
        composeRule.onNodeWithText("Fechar").performClick()
        assertEquals(1, dismissed)
    }
}
