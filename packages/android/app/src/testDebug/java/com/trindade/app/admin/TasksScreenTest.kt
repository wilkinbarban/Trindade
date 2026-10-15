package com.trindade.app.admin

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val checkCategory = AdminCategoryResponseCategory(
        id = 1,
        parentCategoryId = null,
        namePt = "Higienização",
        nameEs = "Higienización",
        categoryType = AdminCategoryResponseCategory.CategoryType.check,
        sortOrder = 1,
        isActive = 1,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private val temperatureCategory = AdminCategoryResponseCategory(
        id = 2,
        parentCategoryId = null,
        namePt = "Temperatura",
        nameEs = "Temperatura",
        categoryType = AdminCategoryResponseCategory.CategoryType.temperature,
        sortOrder = 2,
        isActive = 1,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun task(
        id: Int,
        createdByUserId: Int? = 10,
        isActive: Int = 1,
        categoryId: Int = checkCategory.id,
        categoryName: String = checkCategory.namePt,
        taskType: AdminTasksResponseTasksInner.TaskType = AdminTasksResponseTasksInner.TaskType.check,
    ) = AdminTasksResponseTasksInner(
        id = id,
        categoryId = categoryId,
        namePt = "Tarefa $id",
        nameEs = "Tarea $id",
        temperatureReadings = 2,
        isActive = isActive,
        createdByUserId = createdByUserId,
        createdAt = "2026-01-01T00:00:00Z",
        taskType = taskType,
        categoryName = categoryName,
    )

    private fun state(
        role: String = RolePolicy.ADMIN,
        currentUserId: Int? = 10,
        tasks: List<AdminTasksResponseTasksInner> = listOf(task(1)),
        form: TasksViewModel.FormState? = null,
        deleteConfirmTask: AdminTasksResponseTasksInner? = null,
    ) = TasksViewModel.UiState(
        loading = false,
        tasks = tasks,
        categories = listOf(checkCategory, temperatureCategory),
        form = form,
        role = role,
        currentUserId = currentUserId,
        deleteConfirmTask = deleteConfirmTask,
    )

    private fun render(
        state: TasksViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onOpenEdit: (AdminTasksResponseTasksInner) -> Unit = {},
        onCategoryChanged: (Int) -> Unit = {},
        onNamePtChanged: (String) -> Unit = {},
        onNameEsChanged: (String) -> Unit = {},
        onTemperatureReadingsChanged: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onToggleActive: (AdminTasksResponseTasksInner) -> Unit = {},
        onRequestDelete: (AdminTasksResponseTasksInner) -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                TasksScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onOpenEdit = onOpenEdit,
                    onCategoryChanged = onCategoryChanged,
                    onNamePtChanged = onNamePtChanged,
                    onNameEsChanged = onNameEsChanged,
                    onTemperatureReadingsChanged = onTemperatureReadingsChanged,
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
        render(TasksViewModel.UiState(loading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `task list renders category name type and active status`() {
        var openedCreate = 0
        render(
            state = state(tasks = listOf(task(7, isActive = 1))),
            onOpenCreate = { openedCreate += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.tasks_add_new)).performClick()
        composeRule.onNodeWithText("Tarefa 7").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_name_es_value, "Tarea 7")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_category_value, "Higienização")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_type_value, "Check")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_status_active)).performScrollTo().assertIsDisplayed()
        assertEquals(1, openedCreate)
    }

    @Test
    fun `temperature form renders fields readings control and wires actions`() {
        var saved = 0
        var cancelled = 0
        var changedCategory: Int? = null
        render(
            state = state(
                form = TasksViewModel.FormState(
                    categoryId = temperatureCategory.id,
                    namePt = "Massa",
                    nameEs = "Masa",
                    temperatureReadings = 2,
                ),
            ),
            onCategoryChanged = { changedCategory = it },
            onSave = { saved += 1 },
            onCancelForm = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.tasks_edit_title)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.tasks_create_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(temperatureCategory.namePt).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_name_pt)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_name_es)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(temperatureCategory.namePt).performClick()
        composeRule.onNodeWithText(checkCategory.namePt).performClick()
        composeRule.onNodeWithText(copy(R.string.tasks_temperature_readings)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_create)).performClick()
        composeRule.onNodeWithText(copy(R.string.tasks_cancel)).performClick()

        assertEquals(checkCategory.id, changedCategory)
        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `admin controls are rendered and callbacks are wired`() {
        val activeTask = task(1, isActive = 1)
        val inactiveTask = task(2, isActive = 0)
        var editedId: Int? = null
        var toggledId: Int? = null
        var deletedId: Int? = null
        render(
            state = state(tasks = listOf(activeTask, inactiveTask)),
            onOpenEdit = { editedId = it.id },
            onToggleActive = { toggledId = it.id },
            onRequestDelete = { deletedId = it.id },
        )

        composeRule.onAllNodesWithText(copy(R.string.tasks_edit)).assertCountEquals(2)
        composeRule.onAllNodesWithText(copy(R.string.tasks_delete)).assertCountEquals(2)
        composeRule.onNodeWithContentDescription(copy(R.string.tasks_deactivate)).performClick()
        composeRule.onAllNodesWithText(copy(R.string.tasks_edit)).get(0).performClick()
        composeRule.onAllNodesWithText(copy(R.string.tasks_delete)).get(0).performClick()

        assertEquals(1, toggledId)
        assertEquals(1, editedId)
        assertEquals(1, deletedId)
    }

    @Test
    fun `worker sees only own edit and delete controls and no active toggle`() {
        val ownTask = task(1, createdByUserId = 10)
        val otherTask = task(2, createdByUserId = 11)
        render(
            state = state(
                role = RolePolicy.WORKER,
                tasks = listOf(ownTask, otherTask),
            ),
        )

        composeRule.onAllNodesWithText(copy(R.string.tasks_edit)).assertCountEquals(1)
        composeRule.onAllNodesWithText(copy(R.string.tasks_delete)).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(copy(R.string.tasks_activate)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy(R.string.tasks_deactivate)).assertDoesNotExist()
    }

    @Test
    fun `delete request renders confirmation dialog and confirms through callback`() {
        val target = task(9)
        var confirmed = 0
        render(
            state = state(tasks = emptyList(), deleteConfirmTask = target),
            onConfirmDelete = { confirmed += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.tasks_delete_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_delete_confirm_body, target.namePt)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.tasks_delete)).performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `header back in root state invokes onBack callback`() {
        var backed = 0
        var cancelledForm = 0
        var cancelledDelete = 0

        render(
            state = state(tasks = listOf(task(1))),
            onBack = { backed += 1 },
            onCancelForm = { cancelledForm += 1 },
            onCancelDelete = { cancelledDelete += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()

        assertEquals(1, backed)
        assertEquals(0, cancelledForm)
        assertEquals(0, cancelledDelete)
    }

    @Test
    fun `header back in form state routes to onCancelForm rather than onBack`() {
        var backed = 0
        var cancelledForm = 0
        var cancelledDelete = 0

        render(
            state = state(
                tasks = listOf(task(1)),
                form = TasksViewModel.FormState(
                    categoryId = checkCategory.id,
                    namePt = "Nova Tarefa",
                ),
            ),
            onBack = { backed += 1 },
            onCancelForm = { cancelledForm += 1 },
            onCancelDelete = { cancelledDelete += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()

        assertEquals(1, cancelledForm)
        assertEquals(0, backed)
        assertEquals(0, cancelledDelete)
    }

    @Test
    fun `header back in delete state routes to onCancelDelete rather than onBack`() {
        val target = task(1)
        var backed = 0
        var cancelledForm = 0
        var cancelledDelete = 0

        render(
            state = state(
                tasks = listOf(target),
                deleteConfirmTask = target,
            ),
            onBack = { backed += 1 },
            onCancelForm = { cancelledForm += 1 },
            onCancelDelete = { cancelledDelete += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed().performClick()

        assertEquals(1, cancelledDelete)
        assertEquals(0, backed)
        assertEquals(0, cancelledForm)
    }
}

