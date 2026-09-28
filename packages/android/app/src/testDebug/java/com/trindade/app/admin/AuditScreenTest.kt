package com.trindade.app.admin

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun logEntry(
        id: Int = 1,
        userId: Int? = 10,
        displayName: String? = "Admin User",
        action: String = "create",
        entityType: String = "report",
        entityId: Int? = 42,
        ipAddress: String? = "192.168.1.1",
        details: String? = """{"shift":"tarde","notes":"ok"}""",
        createdAt: String = "2026-09-28T12:00:00Z",
    ) = AuditResponseLogsInner(
        id = id,
        userId = userId,
        displayName = displayName,
        action = action,
        entityType = entityType,
        entityId = entityId,
        ipAddress = ipAddress,
        details = details,
        createdAt = createdAt,
    )

    private fun state(
        loading: Boolean = false,
        logs: List<AuditResponseLogsInner> = listOf(logEntry()),
        page: Int = 1,
        totalPages: Int = 1,
        total: Int = logs.size,
        role: String? = AuditViewModel.ADMIN,
        error: String? = null,
        actionInput: String = "",
        entityTypeInput: String = "",
        userIdInput: String = "",
        appliedAction: String? = null,
        appliedEntityType: String? = null,
        appliedUserId: Int? = null,
    ) = AuditViewModel.UiState(
        loading = loading,
        logs = logs,
        page = page,
        totalPages = totalPages,
        total = total,
        role = role,
        error = error,
        actionInput = actionInput,
        entityTypeInput = entityTypeInput,
        userIdInput = userIdInput,
        appliedAction = appliedAction,
        appliedEntityType = appliedEntityType,
        appliedUserId = appliedUserId,
    )

    private fun render(
        state: AuditViewModel.UiState,
        onBack: () -> Unit = {},
        onActionChange: (String) -> Unit = {},
        onEntityTypeChange: (String) -> Unit = {},
        onUserIdChange: (String) -> Unit = {},
        onApplyFilters: () -> Unit = {},
        onClearFilters: () -> Unit = {},
        onPreviousPage: () -> Unit = {},
        onNextPage: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            AuditScreen(
                state = state,
                onBack = onBack,
                onActionChange = onActionChange,
                onEntityTypeChange = onEntityTypeChange,
                onUserIdChange = onUserIdChange,
                onApplyFilters = onApplyFilters,
                onClearFilters = onClearFilters,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onRefresh = onRefresh,
            )
        }
    }

    @Test
    fun `no legacy alias parameters exist on AuditScreen signature`() {
        val auditScreenMethod = Class.forName("com.trindade.app.admin.AuditScreenKt")
            .declaredMethods
            .first { it.name == "AuditScreen" }
        val parameterCount = auditScreenMethod.parameterCount
        assertTrue(
            "Expected at most 15 parameters on AuditScreen (including composer and metadata), but found $parameterCount indicating legacy aliases remain",
            parameterCount <= 15,
        )
    }

    @Test
    fun `admin sees title filter controls and audit log entry`() {
        val entry = logEntry(
            id = 101,
            userId = 7,
            displayName = "Carlos Operador",
            action = "update",
            entityType = "task",
            entityId = 15,
            details = """{"status":"completed"}""",
            createdAt = "2026-09-28T14:30:00Z",
        )
        render(state(logs = listOf(entry), total = 1))

        composeRule.onNodeWithText("Auditoria").assertIsDisplayed()
        composeRule.onNodeWithText("Voltar").assertIsDisplayed()
        composeRule.onNodeWithText("Atualizar").assertIsDisplayed()
        composeRule.onNodeWithText("Ação: Todos").assertIsDisplayed()
        composeRule.onNodeWithText("Entidade: Todos").assertIsDisplayed()
        composeRule.onNodeWithText("ID Usuário").assertIsDisplayed()
        composeRule.onNodeWithText("Filtrar").assertIsDisplayed()
        composeRule.onNodeWithText("Limpar").assertIsDisplayed()

        composeRule.onNodeWithText("Carlos Operador").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Atualização").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tarefa #15").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Detalhes: status: completed").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no rows displayed for worker`() {
        val entry = logEntry(displayName = "Secret Admin Action")
        render(state(role = "Trabalhador", logs = listOf(entry)))

        composeRule.onNodeWithText("Acesso Restrito").assertIsDisplayed()
        composeRule.onNodeWithText("Esta área é exclusiva para administradores.").assertIsDisplayed()
        composeRule.onNodeWithText("Secret Admin Action").assertDoesNotExist()
        composeRule.onNodeWithText("Filtrar").assertDoesNotExist()
    }

    @Test
    fun `no rows displayed for no session`() {
        val entry = logEntry(displayName = "Secret Unauth Action")
        render(state(role = null, logs = listOf(entry)))

        composeRule.onNodeWithText("Acesso Restrito").assertIsDisplayed()
        composeRule.onNodeWithText("Secret Unauth Action").assertDoesNotExist()
        composeRule.onNodeWithText("Filtrar").assertDoesNotExist()
    }

    @Test
    fun `no rows displayed during in-flight load`() {
        val oldEntry = logEntry(displayName = "Old Stale Log")
        render(state(loading = true, logs = listOf(oldEntry)))

        composeRule.onNodeWithText("Carregando…").assertIsDisplayed()
        composeRule.onNodeWithText("Old Stale Log").assertDoesNotExist()
        composeRule.onNodeWithText("Filtrar").assertDoesNotExist()
    }

    @Test
    fun `staging action selector invokes onActionChange`() {
        var selectedAction = ""
        render(state(), onActionChange = { selectedAction = it })

        composeRule.onNodeWithText("Ação: Todos").performClick()
        composeRule.onNodeWithText("Login").performClick()

        assertEquals("login", selectedAction)
    }

    @Test
    fun `staging entity selector invokes onEntityTypeChange`() {
        var selectedEntity = ""
        render(state(), onEntityTypeChange = { selectedEntity = it })

        composeRule.onNodeWithText("Entidade: Todos").performClick()
        composeRule.onNodeWithText("Relatório").performClick()

        assertEquals("report", selectedEntity)
    }

    @Test
    fun `typing user id invokes onUserIdChange`() {
        var typedUserId = ""
        render(state(), onUserIdChange = { typedUserId = it })

        composeRule.onNodeWithText("ID Usuário").performTextInput("42")

        assertEquals("42", typedUserId)
    }

    @Test
    fun `apply and clear buttons invoke their respective callbacks exactly once`() {
        var applied = 0
        var cleared = 0
        render(state(), onApplyFilters = { applied++ }, onClearFilters = { cleared++ })

        composeRule.onNodeWithText("Filtrar").performClick()
        assertEquals("onApplyFilters must be called exactly once per click", 1, applied)

        composeRule.onNodeWithText("Limpar").performClick()
        assertEquals("onClearFilters must be called exactly once per click", 1, cleared)
    }

    @Test
    fun `paging buttons follow state bounds and invoke callbacks`() {
        var prevClicks = 0
        var nextClicks = 0

        // Single page (page 1 of 1): both disabled
        render(
            state(page = 1, totalPages = 1, total = 5),
            onPreviousPage = { prevClicks++ },
            onNextPage = { nextClicks++ },
        )
        composeRule.onNodeWithText("Anterior").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Próximo").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("5 registros — Página 1 de 1").assertIsDisplayed()
    }

    @Test
    fun `next page is enabled on page 1 of 3 and clicking it invokes nextPage exactly once`() {
        var nextClicks = 0
        render(
            state(page = 1, totalPages = 3, total = 50),
            onNextPage = { nextClicks++ },
        )

        composeRule.onNodeWithText("Anterior").performScrollTo().assertIsNotEnabled()
        val nextBtn = composeRule.onNodeWithText("Próximo").performScrollTo()
        nextBtn.assertIsEnabled()
        nextBtn.performClick()

        assertEquals("onNextPage must be called exactly once per click", 1, nextClicks)
    }

    @Test
    fun `previous page is enabled on page 3 of 3 and clicking it invokes previousPage exactly once`() {
        var prevClicks = 0
        render(
            state(page = 3, totalPages = 3, total = 50),
            onPreviousPage = { prevClicks++ },
        )

        val prevBtn = composeRule.onNodeWithText("Anterior").performScrollTo()
        prevBtn.assertIsEnabled()
        prevBtn.performClick()
        composeRule.onNodeWithText("Próximo").performScrollTo().assertIsNotEnabled()

        assertEquals("onPreviousPage must be called exactly once per click", 1, prevClicks)
    }

    @Test
    fun `navigation back and refresh callbacks are wired and invoked exactly once`() {
        var backs = 0
        var refreshed = 0
        render(state(), onBack = { backs++ }, onRefresh = { refreshed++ })

        composeRule.onNodeWithText("Voltar").performClick()
        assertEquals("onBack must be called exactly once per click", 1, backs)

        composeRule.onNodeWithText("Atualizar").performClick()
        assertEquals("onRefresh must be called exactly once per click", 1, refreshed)
    }

    @Test
    fun `null fields render gracefully with fallback placeholders`() {
        val entry = logEntry(
            id = 999,
            userId = null,
            displayName = null,
            action = "unknown_action",
            entityType = "unknown_entity",
            entityId = null,
            ipAddress = null,
            details = null,
            createdAt = "2026-09-28T08:00:00Z",
        )
        render(state(logs = listOf(entry), total = 1))

        composeRule.onNodeWithText("Sem usuário").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("unknown_action").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("unknown_entity").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Detalhes: —").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `displays user id with hash when display name is null`() {
        val entry = logEntry(
            id = 55,
            userId = 77,
            displayName = null,
        )
        render(state(logs = listOf(entry), total = 1))

        composeRule.onNodeWithText("#77").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `details are summarized without full json syntax and truncated when long`() {
        val jsonDetails = """{"field_a":"value_one","field_b":"value_two","extra":"hidden_field"}"""
        val entryJson = logEntry(id = 1, details = jsonDetails)

        val longText = "A".repeat(120)
        val entryLong = logEntry(id = 2, details = longText)

        render(state(logs = listOf(entryJson, entryLong), total = 2))

        // First log should show 2 key-value pairs without JSON syntax braces
        composeRule.onNodeWithText("Detalhes: field_a: value_one, field_b: value_two").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(jsonDetails).assertDoesNotExist()

        // Second log should be truncated with ellipsis
        val expectedTruncated = "Detalhes: " + "A".repeat(57) + "..."
        composeRule.onNodeWithText(expectedTruncated).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(longText).assertDoesNotExist()
    }

    @Test
    fun `explicit empty state displayed when admin has no logs`() {
        render(state(logs = emptyList(), total = 0))

        composeRule.onNodeWithText("Nenhum log de auditoria encontrado.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `explicit error state displayed when state carries error`() {
        render(state(error = "User ID must be a positive integer."))

        composeRule.onNodeWithText("User ID must be a positive integer.").assertIsDisplayed()
    }
}
