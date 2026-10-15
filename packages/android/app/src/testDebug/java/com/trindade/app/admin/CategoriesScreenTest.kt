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
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoriesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun category(
        id: Int,
        namePt: String = "Categoria $id",
        nameEs: String = "Categoría $id",
        categoryType: AdminCategoryResponseCategory.CategoryType = AdminCategoryResponseCategory.CategoryType.check,
        sortOrder: Int = 1,
        isActive: Int = 1,
    ) = AdminCategoryResponseCategory(
        id = id,
        parentCategoryId = null,
        namePt = namePt,
        nameEs = nameEs,
        categoryType = categoryType,
        sortOrder = sortOrder,
        isActive = isActive,
        createdAt = "2026-01-01T00:00:00Z",
    )

    private fun state(
        role: String = RolePolicy.ADMIN,
        categories: List<AdminCategoryResponseCategory> = listOf(category(1)),
        form: CategoriesViewModel.FormState? = null,
        message: String? = null,
        isError: Boolean = false,
        deleteConfirmCategory: AdminCategoryResponseCategory? = null,
    ) = CategoriesViewModel.UiState(
        loading = false,
        categories = categories,
        form = form,
        role = role,
        message = message,
        isError = isError,
        deleteConfirmCategory = deleteConfirmCategory,
    )

    private fun render(
        state: CategoriesViewModel.UiState,
        onBack: () -> Unit = {},
        onOpenCreate: () -> Unit = {},
        onOpenEdit: (AdminCategoryResponseCategory) -> Unit = {},
        onNamePtChanged: (String) -> Unit = {},
        onNameEsChanged: (String) -> Unit = {},
        onCategoryTypeChanged: (AdminCategoryResponseCategory.CategoryType) -> Unit = {},
        onSortOrderChanged: (String) -> Unit = {},
        onParentCategoryChanged: (Int?) -> Unit = {},
        onSave: () -> Unit = {},
        onCancelForm: () -> Unit = {},
        onToggleActive: (AdminCategoryResponseCategory) -> Unit = {},
        onRequestDelete: (AdminCategoryResponseCategory) -> Unit = {},
        onCancelDelete: () -> Unit = {},
        onConfirmDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                CategoriesScreen(
                    state = state,
                    onBack = onBack,
                    onOpenCreate = onOpenCreate,
                    onOpenEdit = onOpenEdit,
                    onNamePtChanged = onNamePtChanged,
                    onNameEsChanged = onNameEsChanged,
                    onCategoryTypeChanged = onCategoryTypeChanged,
                    onSortOrderChanged = onSortOrderChanged,
                    onParentCategoryChanged = onParentCategoryChanged,
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
        render(CategoriesViewModel.UiState(loading = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `category list renders name, type, order, and active status`() {
        var openedCreate = 0
        render(
            state = state(
                categories = listOf(
                    category(7, namePt = "Higienização", nameEs = "Higienización", categoryType = AdminCategoryResponseCategory.CategoryType.temperature, sortOrder = 3, isActive = 1),
                ),
            ),
            onOpenCreate = { openedCreate += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.categories_add_new)).performClick()
        composeRule.onNodeWithText("Higienização").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_name_es_value, "Higienización")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_type_value, copy(R.string.categories_type_temperature))).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_order_value, 3)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_status_active)).performScrollTo().assertIsDisplayed()
        assertEquals(1, openedCreate)
    }

    @Test
    fun `category form renders fields and wires save and cancel actions`() {
        var saved = 0
        var cancelled = 0
        render(
            state = state(
                form = CategoriesViewModel.FormState(
                    namePt = "Nova Categoria",
                    nameEs = "Nueva Categoría",
                    categoryType = AdminCategoryResponseCategory.CategoryType.check_assai,
                    sortOrder = "2",
                ),
            ),
            onSave = { saved += 1 },
            onCancelForm = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.categories_create_title)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_create)).performScrollTo().performClick()
        composeRule.onNodeWithText(copy(R.string.categories_cancel)).performScrollTo().performClick()
        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `admin controls trigger edit, toggle active, and delete`() {
        val cat = category(1, isActive = 1)
        var edited: AdminCategoryResponseCategory? = null
        var toggled: AdminCategoryResponseCategory? = null
        var requestedDelete: AdminCategoryResponseCategory? = null

        render(
            state = state(categories = listOf(cat)),
            onOpenEdit = { edited = it },
            onToggleActive = { toggled = it },
            onRequestDelete = { requestedDelete = it },
        )

        composeRule.onNodeWithText(copy(R.string.categories_edit)).performScrollTo().performClick()
        assertEquals(cat, edited)

        composeRule.onNodeWithContentDescription(copy(R.string.categories_deactivate)).performScrollTo().performClick()
        assertEquals(cat, toggled)

        composeRule.onNodeWithText(copy(R.string.categories_delete)).performScrollTo().performClick()
        assertEquals(cat, requestedDelete)
    }

    @Test
    fun `delete request renders confirmation dialog and confirms through callback`() {
        val cat = category(1, namePt = "Descartável")
        var confirmed = 0

        render(
            state = state(categories = emptyList(), deleteConfirmCategory = cat),
            onConfirmDelete = { confirmed += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.categories_delete_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.categories_delete_confirm_body, "Descartável")).assertIsDisplayed()

        composeRule.onNodeWithText(copy(R.string.categories_delete)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `delete confirmation dialog dismisses through cancel callback`() {
        val cat = category(1, namePt = "Descartável")
        var cancelled = 0

        render(
            state = state(categories = emptyList(), deleteConfirmCategory = cat),
            onCancelDelete = { cancelled += 1 },
        )

        composeRule.onNodeWithText(copy(R.string.categories_cancel)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun `error message is displayed when present in state`() {
        render(
            state = state(
                message = "Erro de teste",
                isError = true,
            ),
        )

        composeRule.onNodeWithText("Erro de teste").assertIsDisplayed()
    }
}
