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
class TimeSlotsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private fun state(
        role: String? = TimeSlotsViewModel.ADMIN,
        timeSlots: List<String> = listOf("08:00", "10:00", "12:00"),
        input: String = "",
        loading: Boolean = false,
        saving: Boolean = false,
        error: String? = null,
        refusedStatus: Int? = null,
    ) = TimeSlotsViewModel.UiState(
        loading = loading,
        saving = saving,
        timeSlots = timeSlots,
        input = input,
        role = role,
        currentUserId = 1,
        error = error,
        refusedStatus = refusedStatus,
    )

    private fun render(
        state: TimeSlotsViewModel.UiState,
        onBack: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onInputChange: (String) -> Unit = {},
        onAddTimeSlot: () -> Unit = {},
        onRemoveTimeSlot: (String) -> Unit = {},
    ) = composeRule.setContent {
        TrindadeTheme {
            TimeSlotsScreen(
                state = state,
                onBack = onBack,
                onRefresh = onRefresh,
                onInputChange = onInputChange,
                onAddTimeSlot = onAddTimeSlot,
                onRemoveTimeSlot = onRemoveTimeSlot,
            )
        }
    }

    @Test fun `admin sees time slots catalog add controls and remove controls`() {
        render(state())

        composeRule.onNodeWithText("Horários").assertIsDisplayed()
        composeRule.onNodeWithText("08:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("12:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Novo horário").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Adicionar").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Remover").assertCountEquals(3)
        composeRule.onNodeWithText("Somente leitura").assertDoesNotExist()
    }

    @Test fun `admin typing in input invokes onInputChange`() {
        var changed = ""
        render(state(), onInputChange = { changed = it })

        composeRule.onNodeWithText("Novo horário").performTextInput("14:30")
        assertEquals("14:30", changed)
    }

    @Test fun `admin clicking add invokes onAddTimeSlot`() {
        var added = 0
        render(state(input = "14:30"), onAddTimeSlot = { added++ })

        composeRule.onNodeWithText("Adicionar").performClick()
        assertEquals(1, added)
    }

    @Test fun `admin clicking remove on a slot invokes onRemoveTimeSlot with slot string`() {
        var removedSlot = ""
        render(
            state(timeSlots = listOf("08:00", "10:00")),
            onRemoveTimeSlot = { removedSlot = it },
        )

        composeRule.onAllNodesWithText("Remover")[1].performClick()
        assertEquals("10:00", removedSlot)
    }

    @Test fun `worker sees read-only catalog with somente leitura and no add or remove controls`() {
        render(state(role = TimeSlotsViewModel.WORKER, timeSlots = listOf("08:00", "10:00")))

        composeRule.onNodeWithText("08:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10:00").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Novo horário").assertDoesNotExist()
        composeRule.onNodeWithText("Adicionar").assertDoesNotExist()
        composeRule.onNodeWithText("Remover").assertDoesNotExist()
    }

    @Test fun `unknown role fails closed with no add or remove controls`() {
        render(state(role = "Operador", timeSlots = listOf("08:00", "10:00")))

        composeRule.onNodeWithText("08:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10:00").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Novo horário").assertDoesNotExist()
        composeRule.onNodeWithText("Adicionar").assertDoesNotExist()
        composeRule.onNodeWithText("Remover").assertDoesNotExist()
    }

    @Test fun `null role fails closed with no add or remove controls`() {
        render(state(role = null, timeSlots = listOf("08:00", "10:00")))

        composeRule.onNodeWithText("08:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10:00").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Somente leitura").assertCountEquals(2)
        composeRule.onNodeWithText("Novo horário").assertDoesNotExist()
        composeRule.onNodeWithText("Adicionar").assertDoesNotExist()
        composeRule.onNodeWithText("Remover").assertDoesNotExist()
    }

    @Test fun `loading state renders loading indicator and text and disables refresh`() {
        render(state(loading = true))

        composeRule.onNodeWithText("Carregando", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
    }

    @Test fun `saving state renders saving progress and disables controls`() {
        render(
            state(
                saving = true,
                timeSlots = listOf("08:00"),
                input = "14:00",
                error = TimeSlotsViewModel.REFUSED,
                refusedStatus = 400,
            ),
        )

        composeRule.onNodeWithText(TimeSlotsViewModel.REFUSED).assertIsDisplayed()
        composeRule.onNodeWithText("Status: 400").assertIsDisplayed()
        composeRule.onNodeWithText("Salvando…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Atualizar").assertIsNotEnabled()
        composeRule.onNodeWithText("Remover").assertIsNotEnabled()
    }

    @Test fun `empty catalog renders empty notice`() {
        render(state(loading = false, timeSlots = emptyList()))

        composeRule.onNodeWithText("Nenhum horário configurado").assertIsDisplayed()
    }

    @Test fun `error state renders error text`() {
        render(state(error = TimeSlotsViewModel.AT_LEAST_ONE_SLOT))

        composeRule.onNodeWithText(TimeSlotsViewModel.AT_LEAST_ONE_SLOT).assertIsDisplayed()
    }

    @Test fun `refused status renders refusal status text`() {
        render(state(refusedStatus = 403))

        composeRule.onNodeWithText("Status: 403").assertIsDisplayed()
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
