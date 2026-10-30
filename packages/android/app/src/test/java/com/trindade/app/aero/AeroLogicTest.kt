package com.trindade.app.aero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port of `packages/frontend/src/lib/aero-integration.ts`, asserted on the values the ERP reads.
 *
 * These are parity tests, not invention: each case is one the web suite already pins, so a divergence
 * between the two clients shows up here as a failing field rather than as a seat Firebird cannot reconcile.
 */
class AeroLogicTest {

    private val json = Json

    @Test
    fun `normalizes the plant url and appends api when the path is missing`() {
        assertEquals("http://192.168.25.250:1453/api", normalizeAeroWsUrl("http://192.168.25.250:1453"))
        assertEquals("http://192.168.25.250:1453/api", normalizeAeroWsUrl("  http://192.168.25.250:1453/api///  "))
        assertNull(normalizeAeroWsUrl("ftp://192.168.25.250/api"))
        assertNull(normalizeAeroWsUrl(""))
        assertNull(normalizeAeroWsUrl(null))
    }

    @Test
    fun `joins the base and the endpoint with one slash`() {
        assertEquals(
            "http://192.168.25.250:1453/api/entradasitem",
            aeroEndpoint("http://192.168.25.250:1453/api/", "/entradasitem"),
        )
        assertEquals(
            "http://192.168.25.250:1453/api/movimentoitem?_method=PATCH",
            aeroMovementsUrl("http://192.168.25.250:1453/api"),
        )
    }

    @Test
    fun `scales units by one hundred and pads the product code to six digits`() {
        assertEquals(9000, formatAeroQuantity(90.0))
        assertEquals(1500, formatAeroQuantity(15.0))
        assertEquals(0, formatAeroQuantity(null))
        assertEquals("001192", formatAeroProductCode("1192"))
        assertEquals("001045", formatAeroProductCode("001045"))
        assertEquals("", formatAeroProductCode("  "))
        assertEquals("000", formatAeroCst(null))
        assertEquals("060", formatAeroCst("060"))
    }

    @Test
    fun `builds the exact entry payload the webservice expects`() {
        val payload = buildEntradaItemPayload(
            productCode = "1192",
            productName = "MASSA QUADRADA",
            quantity = 15.0,
            entryDate = "2026-10-30T11:22:33.000Z",
            userCode = "7",
            launchIds = listOf(12),
        )

        val written = json.encodeToJsonElement(AeroEntryPayload.serializer(), payload).jsonObject.toString()
        assertEquals(
            "{\"PRO_COD\":\"001192\",\"PRO_DES\":\"MASSA QUADRADA\",\"PRO_QTD\":1500,\"PRO_CST\":\"000\"," +
                "\"ENT_DTA\":\"2026-10-30\",\"ENT_USU\":7,\"ENT_SER\":\"APL\",\"ENT_DTANOT\":\"2026-10-30\"," +
                "\"ENT_DTMOVI\":\"2026-10-30\",\"ENT_OBS\":\"Trindade #12\",\"LOTES\":[]}",
            written,
        )
    }

    @Test
    fun `carries an uppercase username when the operator has no numeric code`() {
        val payload = buildEntradaItemPayload(
            productCode = "1192",
            productName = "MASSA QUADRADA",
            quantity = 30.0,
            entryDate = "2026-10-30",
            userCode = "jefferson",
        )
        assertEquals(JsonPrimitive("JEFFERSON"), payload.userCode)
        assertEquals("Lancamento de massas via Trindade", payload.observation)
        assertEquals(3000, payload.quantity)
    }

    @Test
    fun `falls back to the erp default user code when nothing usable is configured`() {
        assertEquals(JsonPrimitive(AERO_FALLBACK_USER_CODE), aeroUserCode(null))
        assertEquals(JsonPrimitive(AERO_FALLBACK_USER_CODE), aeroUserCode("   "))
        assertEquals(JsonPrimitive(42), aeroUserCode("42"))
        // A numeric string with noise in it is a name the ERP writes in capitals, not a number.
        assertEquals(JsonPrimitive("42A"), aeroUserCode("42a"))
    }

    @Test
    fun `builds the movements query with explicit nulls for the filters it leaves open`() {
        val payload = buildMovimentoItemPayload(
            userName = "all",
            startDate = "2026-10-30",
        )
        val written = json.encodeToJsonElement(AeroMovimentoPayload.serializer(), payload).jsonObject.toString()
        assertEquals(
            "{\"CODFOR\":null,\"NOMUSU\":null,\"CODPRO\":null,\"DTINI\":\"2026-10-30\"," +
                "\"DTFIM\":\"2026-10-30\",\"ENTSAI\":null}",
            written,
        )
    }

    @Test
    fun `reads the authentication answer into the code and the name`() {
        val ok = readAeroTestResult(
            json.parseToJsonElement("""{"success":true,"data":{"CODIGO":7,"NOME":"WILKIN"}}"""),
        )
        assertTrue(ok is AeroTestResult.Ok)
        assertEquals(7, (ok as AeroTestResult.Ok).userCode)
        assertEquals("WILKIN", ok.userName)

        val refused = readAeroTestResult(
            json.parseToJsonElement("""{"success":false,"message":"Usuario ou senha invalidos"}"""),
        )
        assertEquals(AeroTestResult.Failed("Usuario ou senha invalidos"), refused)

        // No indicator at all is a failure the client explains itself, not a success and not the ERP's word.
        assertEquals(
            AeroTestResult.Failed(null),
            readAeroTestResult(json.parseToJsonElement("""{"data":{}}""")),
        )
    }

    @Test
    fun `reads the day and counts the seats it cannot describe instead of failing the whole query`() {
        val body = json.parseToJsonElement(
            """
            {"success":true,"data":[
              {"ENT_LAN":901,"PRO_COD":"001192","PRO_DES":"MASSA QUADRADA","PRO_QTD":15,
               "ENT_DTA":"2026-10-30T00:00:00","ENT_USU":"wilkin","ENTSAI":"E"},
              {"ENT_LAN":0,"PRO_COD":"001192","PRO_DES":"MASSA QUADRADA","PRO_QTD":15,
               "ENT_DTA":"2026-10-30","ENT_USU":"wilkin","ENTSAI":"E"}
            ]}
            """.trimIndent(),
        )

        val read = readAeroMovements(body)
        assertTrue(read is AeroMovementsResult.Ok)
        read as AeroMovementsResult.Ok
        assertEquals(1, read.movements.size)
        assertEquals(1, read.skipped)
        assertEquals("WILKIN", read.movements.single().operatorName)
        assertEquals("2026-10-30", read.movements.single().entryDate)
        assertEquals(15.0, read.movements.single().quantity, 0.0001)
    }

    @Test
    fun `a failed answer is an error even when it carries seats`() {
        val read = readAeroMovements(
            json.parseToJsonElement("""{"success":false,"message":"sem conexao","data":[{"ENT_LAN":1}]}"""),
        )
        assertEquals(AeroMovementsResult.Failed("sem conexao"), read)
    }

    @Test
    fun `reconciles by day, code without padding, quantity and operator`() {
        val movements = listOf(
            seat(entLan = 901, code = "001192", quantity = 15.0, date = "2026-10-30", operator = "WILKIN"),
            seat(entLan = 902, code = "001192", quantity = 15.0, date = "2026-10-30", operator = "JEFFERSON"),
            seat(entLan = 903, code = "001192", quantity = 15.0, date = "2026-10-30", operator = "WILKIN", type = "S"),
        )
        val launches = listOf(
            AeroLocalLaunch(1, "2026-10-30", "1192", 15.0, "wilkin"),
            AeroLocalLaunch(2, "2026-10-30", "1192", 15.0, "jefferson"),
            AeroLocalLaunch(3, "2026-10-30", "1192", 15.0, "wilkin"),
        )

        val reconciliation = reconcileAeroMovements(movements, launches)

        assertEquals(listOf(901, 902), reconciliation.reconciled)
        assertEquals(listOf(1, 2), reconciliation.matchedLaunchIds)
        assertEquals(listOf(3), reconciliation.unmatchedLaunchIds)
    }

    /**
     * El caso real de la tableta: el asiento lo cargó VICTOR y la importación quedó a nombre de wilkin. Antes
     * de guardar el asiento, el nombre era el único puente y lo rechazaba para siempre.
     */
    @Test
    fun `a launch imported from a seat reconciles with it whatever the operator names say`() {
        val movements = listOf(
            seat(entLan = 35937, code = "005000", quantity = 270.0, date = "2026-10-30", operator = "VICTOR"),
        )
        val launches = listOf(
            AeroLocalLaunch(90, "2026-10-30", "5000", 270.0, "wilkin", entLan = 35937),
        )

        val reconciliation = reconcileAeroMovements(movements, launches)

        assertEquals(listOf(35937), reconciliation.reconciled)
        assertEquals(listOf(90), reconciliation.matchedLaunchIds)
        assertEquals(emptyList<Int>(), reconciliation.unmatchedLaunchIds)
    }

    @Test
    fun `the seat decides before the name, so a typed launch keeps waiting for its own seat`() {
        val movements = listOf(
            seat(entLan = 35937, code = "005000", quantity = 270.0, date = "2026-10-30", operator = "VICTOR"),
        )
        val launches = listOf(
            AeroLocalLaunch(90, "2026-10-30", "5000", 270.0, "victor", entLan = 35937),
            AeroLocalLaunch(91, "2026-10-30", "5000", 270.0, "victor"),
        )

        val reconciliation = reconcileAeroMovements(movements, launches)

        assertEquals(listOf(90), reconciliation.matchedLaunchIds)
        assertEquals(listOf(91), reconciliation.unmatchedLaunchIds)
    }

    @Test
    fun `each seat reconciles at most one launch`() {
        val movements = listOf(
            seat(entLan = 901, code = "1192", quantity = 15.0, date = "2026-10-30", operator = "WILKIN"),
        )
        val launches = listOf(
            AeroLocalLaunch(1, "2026-10-30", "001192", 15.0, "wilkin"),
            AeroLocalLaunch(2, "2026-10-30", "001192", 15.0, "wilkin"),
        )

        val reconciliation = reconcileAeroMovements(movements, launches)

        assertEquals(listOf(901), reconciliation.reconciled)
        assertEquals(listOf(1), reconciliation.matchedLaunchIds)
        assertEquals(listOf(2), reconciliation.unmatchedLaunchIds)
    }

    @Test
    fun `a seat whose operator is unknown reconciles with nothing`() {
        val movements = listOf(
            seat(entLan = 901, code = "1192", quantity = 15.0, date = "2026-10-30", operator = "  "),
        )
        val reconciliation = reconcileAeroMovements(movements, listOf(AeroLocalLaunch(1, "2026-10-30", "1192", 15.0, "")))
        assertEquals(emptyList<Int>(), reconciliation.reconciled)
        assertEquals(listOf(1), reconciliation.unmatchedLaunchIds)
    }

    @Test
    fun `builds the snapshot in the names the backend route validates`() {
        val snapshot = buildAeroMovementsSnapshot(
            date = "2026-10-30T10:00:00.000Z",
            movements = listOf(seat(901, "001192", 15.0, "2026-10-30", "WILKIN")),
        )
        val written = json.encodeToJsonElement(AeroMovementsSnapshot.serializer(), snapshot).jsonObject
        assertEquals("2026-10-30", written["date"]!!.jsonPrimitive.content)

        val seat = written["movements"]!!.jsonArray.single().jsonObject
        assertEquals(901, seat["ent_lan"]!!.jsonPrimitive.int)
        assertEquals("001192", seat["product_code"]!!.jsonPrimitive.content)
        assertEquals("WILKIN", seat["operator_name"]!!.jsonPrimitive.content)
        assertEquals("E", seat["movement_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the plant address stays with the owner while credentials belong to every operator`() {
        assertEquals(AeroSettingsPermissions(true, true), aeroSettingsEditable("Wilkin"))
        assertEquals(AeroSettingsPermissions(false, true), aeroSettingsEditable("jefferson"))
        assertEquals(AeroSettingsPermissions(false, false), aeroSettingsEditable(null))
    }

    @Test
    fun `is ready only with a usable url, a username and a password`() {
        assertTrue(aeroSettingsReady("http://192.168.25.250:1453/api", "wilkin", "secret"))
        assertTrue(!aeroSettingsReady("", "wilkin", "secret"))
        assertTrue(!aeroSettingsReady("http://192.168.25.250:1453/api", " ", "secret"))
        assertTrue(!aeroSettingsReady("http://192.168.25.250:1453/api", "wilkin", ""))
    }

    @Test
    fun `the basic header encodes utf8 credentials once`() {
        assertEquals("Basic d2lsa2luOnNlY3JldA==", basicAuthHeader("wilkin", "secret"))
        assertEquals(
            "Basic " + java.util.Base64.getEncoder().encodeToString("jefferson:ação".toByteArray(Charsets.UTF_8)),
            basicAuthHeader("jefferson", "ação"),
        )
    }

    private fun seat(
        entLan: Int,
        code: String,
        quantity: Double,
        date: String,
        operator: String,
        type: String = "E",
    ) = AeroErpMovement(
        entLan = entLan,
        productCode = code,
        productDesc = "MASSA QUADRADA",
        quantity = quantity,
        entryDate = date,
        operatorName = operator,
        movementType = type,
    )
}
