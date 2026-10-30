package com.trindade.app.aero

import com.trindade.app.BuildConfig
import com.trindade.app.di.NetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The wire side of the native client, against a server this test controls.
 *
 * What is asserted here is only what leaves the phone and what comes back: the URL, the Authorization
 * header, the User-Agent, the body field by field, and how each answer is read. There is no plant to test
 * against, so a fake that records requests is the only honest witness of the payload.
 */
class AeroErpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AeroErpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The app's own Json, the same instance Dagger hands to every Retrofit converter: a copy of its
        // settings written here would agree with a change to the provider instead of catching it.
        client = AeroErpClient(NetworkModule.provideJson())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun wsUrl(): String = server.url("/api").toString().trimEnd('/')

    private fun body(request: RecordedRequest): String = request.body.readUtf8()

    @Test
    fun `posts the entry to entradasitem with basic auth and the exact payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))

        val result = client.postEntry(
            wsUrl = wsUrl(),
            username = "wilkin",
            password = "secret",
            payload = buildEntradaItemPayload(
                productCode = "1192",
                productName = "MASSA QUADRADA",
                quantity = 15.0,
                entryDate = "2026-10-30",
                userCode = "7",
                launchIds = listOf(12),
            ),
        )

        assertEquals(AeroWriteResult.Ok(null), result)

        val recorded = server.takeRequest()
        assertEquals("/api/entradasitem", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Basic d2lsa2luOnNlY3JldA==", recorded.getHeader("Authorization"))
        assertEquals(
            "Trindade-Android/${BuildConfig.APP_VERSION_NAME}",
            recorded.getHeader("User-Agent"),
        )
        assertEquals(
            """{"PRO_COD":"001192","PRO_DES":"MASSA QUADRADA","PRO_QTD":1500,"PRO_CST":"000",""" +
                """"ENT_DTA":"2026-10-30","ENT_USU":7,"ENT_SER":"APL","ENT_DTANOT":"2026-10-30",""" +
                """"ENT_DTMOVI":"2026-10-30","ENT_OBS":"Trindade #12","LOTES":[]}""",
            body(recorded),
        )
    }

    @Test
    fun `a refusal from the erp keeps its own sentence`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"success":false,"message":"Produto nao cadastrado"}"""),
        )

        val result = client.postEntry(
            wsUrl = wsUrl(),
            username = "wilkin",
            password = "secret",
            payload = buildEntradaItemPayload("1192", "MASSA QUADRADA", 15.0, "2026-10-30", "7"),
        )

        assertEquals(AeroWriteResult.Failed("Produto nao cadastrado"), result)
    }

    @Test
    fun `an unreachable host is a failed write with no sentence, not an exception`() = runBlocking {
        server.shutdown()

        val result = client.postEntry(
            wsUrl = wsUrl(),
            username = "wilkin",
            password = "secret",
            payload = buildEntradaItemPayload("1192", "MASSA QUADRADA", 15.0, "2026-10-30", "7"),
        )

        assertEquals(AeroWriteResult.Failed(null), result)
    }

    @Test
    fun `tests the connection against usuarios and reads the operator code`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"CODIGO":7,"NOME":"WILKIN"}}"""))

        val probe = client.testConnection(wsUrl(), "wilkin", "secret")

        assertEquals(AeroErpClient.Probe.Answered(AeroTestResult.Ok(7, "WILKIN")), probe)
        val recorded = server.takeRequest()
        assertEquals("/api/usuarios/", recorded.path)
        assertEquals("Basic d2lsa2luOnNlY3JldA==", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a wrong password is an answer, not an unreachable plant`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"Senha invalida"}"""))

        val probe = client.testConnection(wsUrl(), "wilkin", "wrong")

        assertEquals(AeroErpClient.Probe.Answered(AeroTestResult.Failed("Senha invalida")), probe)
    }

    @Test
    fun `reads the day from movimentoitem with the patch override in the query`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":[{"ENT_LAN":901,"PRO_COD":"001192","PRO_DES":"MASSA QUADRADA",""" +
                    """"PRO_QTD":15,"ENT_DTA":"2026-10-30T00:00:00","ENT_USU":"wilkin","ENTSAI":"E"}]}""",
            ),
        )

        val result = client.readMovements(wsUrl(), "wilkin", "secret", "2026-10-30")

        assertTrue(result is AeroMovementsResult.Ok)
        assertEquals(1, (result as AeroMovementsResult.Ok).movements.size)

        val recorded = server.takeRequest()
        assertEquals("/api/movimentoitem?_method=PATCH", recorded.path)
        assertEquals(
            """{"CODFOR":null,"NOMUSU":null,"CODPRO":null,"DTINI":"2026-10-30","DTFIM":"2026-10-30","ENTSAI":null}""",
            body(recorded),
        )
    }
}
