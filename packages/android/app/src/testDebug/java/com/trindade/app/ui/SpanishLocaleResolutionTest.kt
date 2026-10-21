package com.trindade.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SpanishLocaleResolutionTest {

    @Test
    @Config(qualifiers = "es")
    fun `spanish locale resolves strings correctly from values-es`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertEquals("Usuario", context.getString(R.string.login_username))
        assertEquals("Contraseña", context.getString(R.string.login_password))
        assertEquals("Ingresar", context.getString(R.string.login_submit))
        assertEquals("Lanzamiento de Masas", context.getString(R.string.massas_title))
        assertEquals("Panel de Control", context.getString(R.string.nav_dashboard))
        assertEquals("Reportes", context.getString(R.string.nav_reports))
        assertEquals("Horarios", context.getString(R.string.nav_loading))
        assertEquals("Guardar reporte", context.getString(R.string.report_submit))
        assertEquals("Incluir Lanzamiento de Masas", context.getString(R.string.report_include_massas))
        assertEquals("Entendido", context.getString(R.string.help_dialog_understood))
        assertEquals("Ayuda", context.getString(R.string.help_button_label))

        // Format string test
        val formattedUnits = context.getString(R.string.massas_calculated_units_format, 100, 10)
        assertEquals("= 100 unidades (10 un/cx)", formattedUnits)
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun `default or pt-BR locale resolves strings from values`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertEquals("Usuário", context.getString(R.string.login_username))
        assertEquals("Senha", context.getString(R.string.login_password))
        assertEquals("Entrar", context.getString(R.string.login_submit))
        assertEquals("Lançamento de Massas", context.getString(R.string.massas_title))
        assertEquals("Dashboard", context.getString(R.string.nav_dashboard))
        assertEquals("Relatórios", context.getString(R.string.nav_reports))
        assertEquals("Horários", context.getString(R.string.nav_loading))
        assertEquals("Salvar relatório", context.getString(R.string.report_submit))
        assertEquals("Incluir Lançamento de Massas", context.getString(R.string.report_include_massas))
        assertEquals("Entendido", context.getString(R.string.help_dialog_understood))
        assertEquals("Ajuda", context.getString(R.string.help_button_label))

        // Format string test
        val formattedUnits = context.getString(R.string.massas_calculated_units_format, 100, 10)
        assertEquals("= 100 unidades (10 un/cx)", formattedUnits)
    }
}
