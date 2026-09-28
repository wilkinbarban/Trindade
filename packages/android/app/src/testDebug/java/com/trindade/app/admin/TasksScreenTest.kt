package com.trindade.app.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.ui.theme.TrindadeTheme
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
        error: String? = null,
        editingId: Int? = null,
        deleteTarget: AdminTasksResponseTasksInner? = null,
    ) = TasksViewModel.UiState(
        loading = false, saving = saving, categories = listOf(category), tasks = tasks,
        role = role, currentUserId = 7, editingId = editingId,
        categoryId = category.id.toString(), namePt = "Câmara fria", nameEs = "Cámara fría",
        temperatureReadings = "2", deleteTarget = deleteTarget, error = error,
    )

    private fun render(
        state: TasksViewModel.UiState,
        onEdit: (AdminTasksResponseTasksInner) -> Unit = {},
        onToggle: (AdminTasksResponseTasksInner) -> Unit = {},
        onRequestDelete: (AdminTasksResponseTasksInner) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onDismissDelete: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onBack: () -> Unit = {},
        onCategoryChange: (String) -> Unit = {},
        onNamePtChange: (String) -> Unit = {},
        onNameEsChange: (String) -> Unit = {},
        onReadingsChange: (String) -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            TasksScreen(
                state = state, onBack = onBack, onRefresh = onRefresh,
                onCategoryChange = onCategoryChange, onNamePtChange = onNamePtChange,
                onNameEsChange = onNameEsChange, onReadingsChange = onReadingsChange,
                onSave = {}, onCancel = {}, onEdit = onEdit, onToggle = onToggle,
                onRequestDelete = onRequestDelete,
                onConfirmDelete = onConfirmDelete,
                onDismissDelete = onDismissDelete,
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
        composeRule.onNodeWithText(TasksViewModel.TEMPERATURE_READINGS).performScrollTo().assertIsDisplayed()
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
    fun `back and refresh actions are wired`() {
        var backs = 0
        var refreshes = 0
        render(state(), onBack = { backs++ }, onRefresh = { refreshes++ })
        composeRule.onNodeWithText("Voltar").performClick()
        composeRule.onNodeWithText("Atualizar").performClick()
        assertEquals(1, backs)
        assertEquals(1, refreshes)
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
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir Câmara fria 1?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()
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
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
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
                    onRefresh = {},
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

        // Before click: dialog does not exist
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()

        // Clicking Excluir triggers onRequestDelete callback which updates uiState.deleteTarget
        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requested?.id)

        // Now dialog appears because uiState recomposed with non-null deleteTarget
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir Câmara fria 1?").assertIsDisplayed()
    }

    @Test
    fun `failed reload clears deleteTarget so no lingering dialog survives`() {
        var uiState by mutableStateOf(state(deleteTarget = task(1, 7)))
        composeRule.setContent {
            TrindadeTheme {
                TasksScreen(
                    state = uiState,
                    onBack = {},
                    onRefresh = {},
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
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()

        // Failed reload clears deleteTarget in state
        uiState = uiState.copy(deleteTarget = null, error = TasksViewModel.UNREACHABLE)
        composeRule.waitForIdle()

        // Dialog must disappear immediately without lingering local modal
        composeRule.onNodeWithText("Confirmar exclusão").assertDoesNotExist()
    }
}
