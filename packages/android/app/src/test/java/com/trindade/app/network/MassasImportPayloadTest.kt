package com.trindade.app.network

import com.trindade.app.di.NetworkModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * El cuerpo de la importación de un asiento, tal como viaja por la red.
 *
 * El backend valida con un esquema estricto: una clave de más es un 400, y `ent_lan: null` no es un número
 * positivo. Por eso el asiento se omite cuando no existe, en vez de viajar como nulo.
 */
class MassasImportPayloadTest {

    /** El mismo serializador que usa el cliente del backend: el que omite lo que nadie llenó. */
    private val appJson: Json = NetworkModule.provideBackendJson(NetworkModule.provideJson())

    @Test
    fun `an imported seat travels with its seat and the operator the tablet wrote`() {
        val body = appJson.encodeToString(
            CreateMassasLaunchRequest.serializer(),
            CreateMassasLaunchRequest(
                date = "2026-10-30",
                entLan = 35937,
                operatorName = "VICTOR",
                items = listOf(CreateMassasLaunchItem("5000", 27)),
            ),
        )

        val json = appJson.parseToJsonElement(body).jsonObject
        assertEquals("2026-10-30", json.getValue("date").jsonPrimitive.content)
        assertEquals("35937", json.getValue("ent_lan").jsonPrimitive.content)
        assertEquals("VICTOR", json.getValue("operator_name").jsonPrimitive.content)
        val item = json.getValue("items").jsonArray.single().jsonObject
        assertEquals("5000", item.getValue("product_code").jsonPrimitive.content)
        assertEquals("27", item.getValue("boxes").jsonPrimitive.content)
    }

    @Test
    fun `a launch typed here sends neither key, because the schema refuses what it does not expect`() {
        val body = appJson.encodeToString(
            CreateMassasLaunchRequest.serializer(),
            CreateMassasLaunchRequest(date = "2026-10-30", items = listOf(CreateMassasLaunchItem("22", 3))),
        )

        val json = appJson.parseToJsonElement(body).jsonObject
        assertFalse("ent_lan must be absent, not null", json.containsKey("ent_lan"))
        assertFalse("operator_name must be absent, not null", json.containsKey("operator_name"))
    }
}
