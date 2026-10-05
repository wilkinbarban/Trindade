package com.trindade.app.network

import com.trindade.app.contract.models.CreateReportRequestTemperaturesInner
import com.trindade.app.contract.models.ReportDetailTemperaturesInner
import com.trindade.app.contract.models.ReportTemperatureDetail
import com.trindade.app.di.NetworkModule
import java.math.BigDecimal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct JSON serialization and deserialization contracts using the application's [Json] serializer.
 *
 * Exercises the serializer provided by [NetworkModule.provideJson] to verify:
 * 1. Temperature model decoding with [BigDecimal] values.
 */
class JsonSerializationTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test
    fun `ReportTemperatureDetail decodes value with BigDecimal using app json`() {
        val rawJson = """{"location":"Baú","location_es":"Caja","readingIndex":1,"value":4.5}"""
        val decoded = appJson.decodeFromString(ReportTemperatureDetail.serializer(), rawJson)

        assertEquals("Baú", decoded.location)
        assertEquals("Caja", decoded.locationEs)
        assertEquals(1, decoded.readingIndex)
        assertEquals(BigDecimal("4.5"), decoded.value)
    }

    @Test
    fun `ReportDetailTemperaturesInner decodes decimal temperature with BigDecimal using app json`() {
        val rawJson = """{"location":"Câmara Fria","location_es":"Cámara Fría","readingIndex":2,"value":-18.5}"""
        val decoded = appJson.decodeFromString(ReportDetailTemperaturesInner.serializer(), rawJson)

        assertEquals("Câmara Fria", decoded.location)
        assertEquals("Cámara Fría", decoded.locationEs)
        assertEquals(2, decoded.readingIndex)
        assertEquals(BigDecimal("-18.5"), decoded.value)
    }

    @Test
    fun `CreateReportRequestTemperaturesInner decodes value with BigDecimal using app json`() {
        val rawJson = """{"location":"Baú","readingIndex":1,"value":5.0}"""
        val decoded = appJson.decodeFromString(CreateReportRequestTemperaturesInner.serializer(), rawJson)

        assertEquals("Baú", decoded.location)
        assertEquals(1, decoded.readingIndex)
        assertEquals(BigDecimal("5.0"), decoded.value)
    }

    @Test
    fun `CreateReportRequestTemperaturesInner encodes BigDecimal as unquoted numeric JSON literal`() {
        val model = CreateReportRequestTemperaturesInner(location = "Baú", readingIndex = 2, value = BigDecimal("5.0"))
        val encoded = appJson.encodeToString(CreateReportRequestTemperaturesInner.serializer(), model)
        assertEquals("""{"location":"Baú","value":5.0,"readingIndex":2}""", encoded)
    }
}
