package com.trindade.app.ui

import com.trindade.app.ui.components.HelpSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleHelpTest {

    @Test
    fun helpSection_createsExpectedModel() {
        val sectionWithoutSteps = HelpSection(
            title = "Passo 1",
            description = "Descrição detalhada do passo",
        )
        assertEquals("Passo 1", sectionWithoutSteps.title)
        assertEquals("Descrição detalhada do passo", sectionWithoutSteps.description)
        assertNull(sectionWithoutSteps.steps)

        val sectionWithSteps = HelpSection(
            title = "Passo 2",
            description = "Instruções",
            steps = listOf("Sub-etapa A", "Sub-etapa B"),
        )
        assertEquals("Passo 2", sectionWithSteps.title)
        assertNotNull(sectionWithSteps.steps)
        assertEquals(2, sectionWithSteps.steps?.size)
        assertEquals("Sub-etapa A", sectionWithSteps.steps?.get(0))
        assertEquals("Sub-etapa B", sectionWithSteps.steps?.get(1))
    }
}
