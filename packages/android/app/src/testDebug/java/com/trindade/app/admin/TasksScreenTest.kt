package com.trindade.app.admin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
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
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    private val category = AdminCategoryResponseCategory(
        id = 3, parentCategoryId = null, namePt = "Temperatura", nameEs = "Temperatura",
        categoryType = AdminCategoryResponseCategory.CategoryType.temperature,
        sortOrder = 1, isActive = 1, createdAt = "2026-01-01",
    )

    private fun task(id: Int, owner: Int?, active: Int = 1) = AdminTasksResponseTasksInner(
        id = id, categoryId = category.id, namePt = "Câmara fria $id", nameEs = "Cámara fría $id",
        temperatureReadings = 2, isActive = active, createdByUserId = owner, createdAt = "2026-01-01",
        taskType = AdminTasksResponseTasksInner.TaskType.temperature, categoryName = category.namePt,
    )

    private fun state(
        role: String = TasksViewModel.ADMIN,
        tasks: List<AdminTasksResponseTasksInner> = listOf(task(1, 7), task(2, 8, active = 0)),
        saving: Boolean = false,
        error: UiText? = null,
        editingId: Int? = null,
        deleteTarget: AdminTasksResponseTasksInner? = null,
        namePt: String = "Câmara fria",
    ) = TasksViewModel.UiState(
        loading = false, saving = saving, categories = listOf(category), tasks = tasks,
        role = role, currentUserId = 7, editingId = editingId,
        categoryId = category.id.toString(), namePt = namePt, nameEs = "Cámara fría",
        temperatureReadings = "2", deleteTarget = deleteTarget, error = error,
    )

    private fun render(
        state: TasksViewModel.UiState,
        onEdit: (AdminTasksResponseTasksInner) -> Unit = {},
        onToggle: (AdminTasksResponseTasksInner) -> Unit = {},
        onRequestDelete: (AdminTasksResponseTasksInner) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onDismissDelete: () -> Unit = {},
        onBack: () -> Unit = {},
        onCategoryChange: (String) -> Unit = {},
        onNamePtChange: (String) -> Unit = {},
        onNameEsChange: (String) -> Unit = {},
        onReadingsChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onDismissOutcome: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            TasksScreen(
                state = state, onBack = onBack,
                onCategoryChange = onCategoryChange, onNamePtChange = onNamePtChange,
                onNameEsChange = onNameEsChange, onReadingsChange = onReadingsChange,
                onSave = onSave, onCancel = onCancel, onEdit = onEdit, onToggle = onToggle,
                onRequestDelete = onRequestDelete,
                onConfirmDelete = onConfirmDelete,
                onDismissDelete = onDismissDelete,
                onDismissOutcome = onDismissOutcome,
            )
        }
    }

    @Test
    fun `catalog draws task names readings and admin actions`() {
        var edited = 0
        var toggled = 0
        var requested = 0
        render(
            state(),
            onEdit = { edited++ },
            onToggle = { toggled++ },
            onRequestDelete = { requested++ },
        )

        composeRule.onNodeWithText("Câmara fria 1").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("2 leituras").assertCountEquals(2)
        composeRule.onAllNodesWithText("Editar")[0].performScrollTo().performClick()
        assertEquals(1, edited)
        composeRule.onNodeWithText("Desativar").performScrollTo().performClick()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, toggled)
        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requested)
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }

    @Test
    fun `worker may edit only own active task and sees no admin controls`() {
        var edits = 0
        render(
            state(role = TasksViewModel.WORKER, tasks = listOf(task(1, 7), task(2, 7, active = 0), task(3, 8))),
            onEdit = { edits++ },
        )

        composeRule.onNodeWithText("Editar").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, edits)
        composeRule.onNodeWithText("Câmara fria 2").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
        composeRule.onNodeWithText("Nova tarefa").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `task form exposes category names and readings and reports validation and saving`() {
        render(state(saving = true, error = TasksViewModel.TEMPERATURE_READINGS, editingId = 1))

        composeRule.onNodeWithText("Categoria").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Português").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Español").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Leituras de temperatura").performScrollTo().assertIsDisplayed()
        val expectedMsg = copy(R.string.tasks_error_readings)
        composeRule.onNodeWithText(expectedMsg).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `category picker names and reading selection forward operator input`() {
        var categoryId = ""
        var readings = ""
        render(
            state().copy(namePt = "", nameEs = "", tasks = emptyList(), editingId = 1),
            onCategoryChange = { categoryId = it },
            onReadingsChange = { readings = it },
        )

        composeRule.onNodeWithText("Temperatura").performClick()
        composeRule.onAllNodesWithText("Temperatura")[1].performClick()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("3").performClick()

        assertEquals("3", categoryId)
        assertEquals("3", readings)
    }

    @Test
    fun `back action is wired and refresh button is removed`() {
        var backs = 0
        render(state(), onBack = { backs++ })
        composeRule.onNodeWithText("Voltar").performClick()
        assertEquals(1, backs)
        composeRule.onNodeWithText("Atualizar").assertDoesNotExist()
    }

    @Test
    fun `confirmed delete dialog via state target forwards confirmation`() {
        var confirmed = 0
        var dismissed = 0
        render(
            state(deleteTarget = task(1, 7)),
            onConfirmDelete = { confirmed++ },
            onDismissDelete = { dismissed++ },
        )
        val expectedBody = copy(R.string.admin_dialog_delete_body, "Câmara fria 1")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()
        composeRule.onNode(hasAnyAncestor(isDialog()) and hasText(copy(R.string.admin_action_delete)) and hasClickAction()).performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `confirmed delete dialog dismissal forwards onDismissDelete`() {
        var dismissed = 0
        render(
            state(deleteTarget = task(1, 7)),
            onDismissDelete = { dismissed++ },
        )
        val expectedBody = copy(R.string.admin_dialog_delete_body, "Câmara fria 1")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `delete button is disabled during saving`() {
        render(state(saving = true))
        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `clicking Excluir invokes request callback but does not open dialog until state recomposes with deleteTarget`() {
        var requested: AdminTasksResponseTasksInner? = null
        var uiState by mutableStateOf(state(deleteTarget = null))
        composeRule.setContent {
            TrindadeTheme {
                TasksScreen(
                    state = uiState,
                    onBack = {},
                    onCategoryChange = {},
                    onNamePtChange = {},
                    onNameEsChange = {},
                    onReadingsChange = {},
                    onSave = {},
                    onCancel = {},
                    onEdit = {},
                    onToggle = {},
                    onRequestDelete = {
                        requested = it
                        uiState = uiState.copy(deleteTarget = it)
                    },
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        // Before click: dialog body does not exist
        val expectedBody = copy(R.string.admin_dialog_delete_body, "Câmara fria 1")
        composeRule.onNodeWithText(expectedBody).assertDoesNotExist()

        // Clicking Excluir triggers onRequestDelete callback which updates uiState.deleteTarget
        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requested?.id)

        // Now dialog appears because uiState recomposed with non-null deleteTarget
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()
    }

    @Test
    fun `failed reload clears deleteTarget so no lingering dialog survives`() {
        var uiState by mutableStateOf(state(deleteTarget = task(1, 7)))
        val expectedBody = copy(R.string.admin_dialog_delete_body, "Câmara fria 1")
        composeRule.setContent {
            TrindadeTheme {
                TasksScreen(
                    state = uiState,
                    onBack = {},
                    onCategoryChange = {},
                    onNamePtChange = {},
                    onNameEsChange = {},
                    onReadingsChange = {},
                    onSave = {},
                    onCancel = {},
                    onEdit = {},
                    onToggle = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        // Dialog is displayed initially because state.deleteTarget is set
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        // Failed reload clears deleteTarget in state
        uiState = uiState.copy(deleteTarget = null, error = TasksViewModel.UNREACHABLE)
        composeRule.waitForIdle()

        // Dialog must disappear immediately without lingering local modal
        composeRule.onNodeWithText(expectedBody).assertDoesNotExist()
    }

    @Test
    fun `critical task administration action controls meet 48dp touch target bounds`() {
        render(state(editingId = 1, deleteTarget = task(1, 7)))

        composeRule.onNodeWithText("Atualizar").assertDoesNotExist()

        val cancelNodes = composeRule.onAllNodesWithText("Cancelar")
        cancelNodes.assertCountEquals(2)
        val formCancelBounds = cancelNodes[0].getUnclippedBoundsInRoot()
        assertTrue("Form Cancelar height >= 48dp", formCancelBounds.bottom - formCancelBounds.top >= 48.dp)
        assertTrue("Form Cancelar width >= 48dp", formCancelBounds.right - formCancelBounds.left >= 48.dp)

        val dialogCancelBounds = cancelNodes[1].getUnclippedBoundsInRoot()
        assertTrue("Dialog Cancelar height >= 48dp", dialogCancelBounds.bottom - dialogCancelBounds.top >= 48.dp)
        assertTrue("Dialog Cancelar width >= 48dp", dialogCancelBounds.right - dialogCancelBounds.left >= 48.dp)
    }

    @Test
    fun `save shows confirmation dialog and confirm invokes onSave`() {
        var saved = 0
        render(state(editingId = 1, namePt = "Tarefa Teste"), onSave = { saved++ })

        composeRule.onNodeWithText("Salvar").performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_save_body, "Tarefa Teste")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `save confirmation cancel does not invoke onSave`() {
        var saved = 0
        render(state(editingId = 1, namePt = "Tarefa Teste"), onSave = { saved++ })

        composeRule.onNodeWithText("Salvar").performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_save_body, "Tarefa Teste")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onAllNodesWithText("Cancelar")[1].performClick()
        assertEquals(0, saved)
    }

    @Test
    fun `toggle shows confirmation dialog and confirm invokes onToggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onAllNodesWithText("Desativar")[0].performScrollTo().performClick()
        val expectedBody = copy(R.string.admin_dialog_toggle_off_body, "Câmara fria 1")
        composeRule.onNodeWithText(expectedBody).assertIsDisplayed()

        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun `outcome shows result dialog and close invokes onDismissOutcome`() {
        var dismissed = 0
        render(
            state().copy(outcome = AdminOutcome(isSuccess = true, message = UiText.of(R.string.admin_tasks_created))),
            onDismissOutcome = { dismissed++ },
        )

        val expectedSuccess = copy(R.string.admin_tasks_created)
        composeRule.onNodeWithText(expectedSuccess).assertIsDisplayed()
        composeRule.onNodeWithText("Fechar").performClick()
        assertEquals(1, dismissed)
    }
}
