package com.trindade.app.admin

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
import com.trindade.app.contract.models.AdminCategoryResponseCategory
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
class CategoriesScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun category(
        id: Int,
        namePt: String = "Categoria $id",
        nameEs: String = "Categoría $id",
        categoryType: AdminCategoryResponseCategory.CategoryType = AdminCategoryResponseCategory.CategoryType.temperature,
        sortOrder: Int = id,
        isActive: Int = 1,
    ) = AdminCategoryResponseCategory(
        id = id,
        parentCategoryId = null,
        namePt = namePt,
        nameEs = nameEs,
        categoryType = categoryType,
        sortOrder = sortOrder,
        isActive = isActive,
        createdAt = "2026-01-01",
    )

    private fun state(
        role: String? = CategoriesViewModel.ADMIN,
        categories: List<AdminCategoryResponseCategory> = listOf(
            category(1, "Temperatura Câmara", "Temperatura Cámara", AdminCategoryResponseCategory.CategoryType.temperature, 1),
            category(2, "Higiene Check", "Higiene Check", AdminCategoryResponseCategory.CategoryType.check, 2, isActive = 0),
        ),
        loading: Boolean = false,
        saving: Boolean = false,
        error: String? = null,
        refusedStatus: Int? = null,
        editingId: Int? = null,
        isSpanish: Boolean = false,
        deleteTarget: AdminCategoryResponseCategory? = null,
    ) = CategoriesViewModel.UiState(
        loading = loading,
        saving = saving,
        categories = categories,
        role = role,
        currentUserId = 1,
        editingId = editingId,
        namePt = "Nova Categoria",
        nameEs = "Nueva Categoría",
        categoryType = CategoriesViewModel.CHECK,
        sortOrder = "0",
        deleteTarget = deleteTarget,
        isSpanish = isSpanish,
        error = error,
        refusedStatus = refusedStatus,
    )

    private fun render(
        state: CategoriesViewModel.UiState,
        onBack: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onNameChange: (String) -> Unit = {},
        onNamePtChange: (String) -> Unit = {},
        onNameEsChange: (String) -> Unit = {},
        onCategoryTypeChange: (String) -> Unit = {},
        onSortOrderChange: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onEdit: (AdminCategoryResponseCategory) -> Unit = {},
        onToggle: (AdminCategoryResponseCategory) -> Unit = {},
        onRequestDelete: (AdminCategoryResponseCategory) -> Unit = {},
        onConfirmDelete: () -> Unit = {},
        onDismissDelete: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            CategoriesScreen(
                state = state,
                onBack = onBack,
                onRefresh = onRefresh,
                onNameChange = onNameChange,
                onNamePtChange = onNamePtChange,
                onNameEsChange = onNameEsChange,
                onCategoryTypeChange = onCategoryTypeChange,
                onSortOrderChange = onSortOrderChange,
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

    @Test fun `admin sees category details type order and active toggle`() {
        var toggled = 0
        render(state(), onToggle = { toggled++ })

        composeRule.onNodeWithText("Temperatura Câmara").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Temperatura").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ordem: 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ativa").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Inativa").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Desativar").performScrollTo().performClick()
        assertEquals(1, toggled)
    }

    @Test fun `admin create form is locale-sensitive and captures category type and order`() {
        var typeChanged = ""
        var saved = 0

        render(
            state(categories = emptyList()),
            onCategoryTypeChange = { typeChanged = it },
            onSave = { saved++ },
        )

        composeRule.onNodeWithText("Nova categoria").performClick()
        composeRule.onNodeWithText("Português").assertIsDisplayed()
        composeRule.onNodeWithText("Español").assertDoesNotExist()
        composeRule.onNodeWithText("Tipo").assertIsDisplayed()
        composeRule.onNodeWithText("Ordem").assertIsDisplayed()

        composeRule.onNodeWithText("Temperatura").performClick()
        assertEquals(CategoriesViewModel.TEMPERATURE, typeChanged)

        composeRule.onNodeWithText("Criar").performClick()
        assertEquals(1, saved)
    }

    @Test fun `create name field invokes only onNameChange and not edit callbacks`() {
        var createChanges = 0
        var ptChanges = 0
        var esChanges = 0
        render(
            state(categories = emptyList()),
            onNameChange = { createChanges++ },
            onNamePtChange = { ptChanges++ },
            onNameEsChange = { esChanges++ },
        )
        composeRule.onNodeWithText("Nova categoria").performClick()
        composeRule.onNodeWithText("Português").performTextInput("X")

        assertEquals(1, createChanges)
        assertEquals(0, ptChanges)
        assertEquals(0, esChanges)
    }

    @Test fun `admin create form in spanish exposes spanish name`() {
        render(state(isSpanish = true, categories = emptyList()))
        composeRule.onNodeWithText("Nova categoria").performClick()
        composeRule.onNodeWithText("Español").assertIsDisplayed()
        composeRule.onNodeWithText("Português").assertDoesNotExist()
    }

    @Test fun `admin edit form exposes both names and wires actions`() {
        var saved = 0
        var cancelled = 0
        render(
            state(editingId = 1, isSpanish = false),
            onSave = { saved++ },
            onCancel = { cancelled++ },
        )
        composeRule.onNodeWithText("Português").assertIsDisplayed()
        composeRule.onNodeWithText("Español").assertIsDisplayed()
        composeRule.onNodeWithText("Salvar").performClick()
        assertEquals(1, saved)
        composeRule.onNodeWithText("Cancelar").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun `worker sees read-only catalog with somente leitura and no admin controls`() {
        var edits = 0
        render(
            state(role = CategoriesViewModel.WORKER),
            onEdit = { edits++ },
        )

        composeRule.onNodeWithText("Temperatura Câmara").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Nova categoria").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Ativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
        assertEquals(0, edits)
    }

    @Test fun `unknown role fails closed with no admin controls`() {
        render(state(role = "Gerente"))

        composeRule.onNodeWithText("Temperatura Câmara").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Nova categoria").assertDoesNotExist()
        composeRule.onNodeWithText("Editar").assertDoesNotExist()
        composeRule.onNodeWithText("Desativar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
    }

    @Test fun `confirmed delete dialog via state target forwards confirmation`() {
        var confirmed = 0
        var dismissed = 0
        render(
            state(deleteTarget = category(1, "Temperatura Câmara")),
            onConfirmDelete = { confirmed++ },
            onDismissDelete = { dismissed++ },
        )
        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir Temperatura Câmara?").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test fun `confirmed delete from list item forwards dismissal`() {
        var requested = 0
        var dismissed = 0
        var deletedTarget: AdminCategoryResponseCategory? = null
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

    @Test fun `confirmed delete from list item invokes request once and confirm once`() {
        var requestCount = 0
        var confirmCount = 0
        var requestedCategory: AdminCategoryResponseCategory? = null
        render(
            state(deleteTarget = null),
            onRequestDelete = {
                requestCount++
                requestedCategory = it
            },
            onConfirmDelete = { confirmCount++ },
        )

        composeRule.onAllNodesWithText("Excluir")[0].performScrollTo().performClick()
        assertEquals(1, requestCount)
        assertEquals(1, requestedCategory?.id)

        composeRule.onNodeWithText("Confirmar exclusão").assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar").performClick()

        assertEquals(1, requestCount)
        assertEquals(1, confirmCount)
    }

    @Test fun `loading error refusal and saving states are rendered`() {
        render(state(
            loading = true,
            saving = true,
            categories = emptyList(),
            error = CategoriesViewModel.REFUSED,
            refusedStatus = 400,
            editingId = 1,
        ))

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(CategoriesViewModel.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithText("Status: 400").assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }

    @Test fun `empty list renders empty notice`() {
        render(state(loading = false, categories = emptyList()))
        composeRule.onNodeWithText("Nenhuma categoria").assertIsDisplayed()
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
}
