package com.trindade.app.loading

import com.trindade.app.contract.models.ScheduleResponse
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.LoadingApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * What an edit puts on the wire, which is the half of a partial update no screen can see.
 *
 * The server merges a schedule update **field by field** -- `data.X !== undefined ? data.X : existing.X`
 * in `loading.service.ts` -- so the fields a caller leaves out are the fields it keeps. An explicit
 * `null` is not a missing field to that expression: it is a value, and it is written. The web's own edit
 * page relies on the difference and sends `{ time_slot }` and nothing else, and the operator's screen
 * here makes the same kind of change -- one slot, one driver, one vehicle.
 *
 * The risk this file exists for is therefore not a wrong value but an extra one.
 * `UpdateScheduleRequest` is generated with every field nullable and defaulting to `null`, so a body
 * built with only the slot is two very different bodies depending on one setting of the `Json` the app
 * hands to Retrofit: `{"time_slot":"04:30"}`, which renames the slot and touches nothing else, or the
 * same object with `"driver_id":null` and `"vehicle_id":null` beside it, which clears the driver and the
 * vehicle the operator never opened. Nothing about that difference is visible in a Kotlin type, in the
 * generated DTO, or at the call site, and the failure is silent in the worst direction: the server
 * answers 200 either way, so the client reports a save and the following read is the first place the
 * missing driver appears.
 *
 * Both requests below are serialized by the app's **own** `Json` -- `NetworkModule.provideJson()`, the
 * single instance Dagger hands to the Retrofit converter -- rather than by a copy of its settings
 * written here. A copy would be a second statement of the same belief, so it would agree with a change
 * to the provider instead of catching it, and the setting that decides this file's whole question is
 * exactly the one such a copy would get wrong.
 *
 * The body is asserted whole rather than key by key, because "no null of any kind" is a claim about the
 * body and a key-by-key assertion can only ever pin the keys somebody remembered to name. The failure
 * message then reads as the two bodies side by side, which is the thing an operator's missing driver
 * looks like before it reaches the server.
 */
class ScheduleUpdatePayloadTest {

    /**
     * The app's own serializer, called on the provider rather than through Dagger.
     *
     * `NetworkModule` is an object and the provider is a plain function, so a test can have the real
     * configuration without standing up a component; this is the same call the generated component makes
     * to build the converter the app sends through.
     */
    private val appJson: Json = NetworkModule.provideJson()

    /**
     * An update that changes one slot, on a real socket and through the real Retrofit converter.
     *
     * A socket rather than `appJson.encodeToString` alone, because the client does not serialize a
     * request body itself: it hands the object to Retrofit and the kotlinx converter serializes it with
     * the `Json` it was built from. The two are the same serializer only as long as that converter is
     * the one configured, so the claim worth making is about the bytes that left the process -- the
     * bytes `MockWebServer` records and the server would have read.
     *
     * The route is asserted before the body so the body below is known to belong to the call under test
     * and not to some other request this app made.
     */
    @Test
    fun `an update that changes only the slot sends only the slot, and no null for the fields nobody touched`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(scheduleAnswer()))

            // `runBlocking` rather than `runTest`: this call crosses a real socket, and there is no
            // virtual time to advance that would make it finish any sooner.
            runBlocking {
                wireApi(server).updateSchedule(
                    id = SCHEDULE_ID,
                    body = UpdateScheduleRequest(timeSlot = NEW_SLOT),
                )
            }

            val sent = server.takeRequest()

            assertEquals("PATCH", sent.method)
            assertEquals("/api/loading/schedules/$SCHEDULE_ID", sent.path)

            // The whole body, character for character, for the reason in the class comment: the three
            // fields that must not appear are `schedule_date`, `driver_id` and `vehicle_id`, and the
            // shape that carries a field nobody changed is this one with `:null` after each of them.
            // A body that grew one of those is a body that clears the date, the driver or the vehicle
            // the operator never touched, and the server merges it without complaining.
            assertEquals("""{"time_slot":"$NEW_SLOT"}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    /**
     * The same request one configuration setting away from the app's own, which is what makes the
     * assertion above worth having.
     *
     * `encodeDefaults = false` is kotlinx's own default and the only reason a field left at its default
     * `null` stays off the wire; `provideJson()` sets `ignoreUnknownKeys` and nothing else, so it keeps
     * that default. This test is the failure mode the first one refuses, produced on purpose: with
     * `encodeDefaults = true` the very same object carries every one of its five fields, and the three
     * the edit did not touch go as `null`.
     *
     * It is written as an expected body rather than as a comparison against the app's output because the
     * point is **which** field appears and with what value. A flipped `encodeDefaults` on the provider
     * fails the test above with this body as the difference, and that is the whole chain: the setting,
     * the three nulls, and the merge on the server that would read each of them as an instruction to
     * delete what the operator still wants.
     */
    @Test
    fun `a serializer that encoded defaults would put a null on the wire for every field the edit left alone`() {
        val request = UpdateScheduleRequest(timeSlot = NEW_SLOT)

        assertEquals(
            """{"time_slot":"$NEW_SLOT"}""",
            appJson.encodeToString(UpdateScheduleRequest.serializer(), request),
        )

        assertEquals(
            """{"schedule_date":null,"time_slot":"$NEW_SLOT","driver_type":null,"driver_id":null,"vehicle_id":null}""",
            encodingDefaults.encodeToString(UpdateScheduleRequest.serializer(), request),
        )
    }

    /** A real Retrofit over a real socket, carrying the app's own `Json` through the app's own converter. */
    private fun wireApi(server: MockWebServer): LoadingApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LoadingApi::class.java)

    /**
     * The answer a real PATCH sends back: the entry as it now stands.
     *
     * Built by serializing the contract's own type instead of by pasting a JSON literal, so a field the
     * contract adds later cannot leave this fixture describing a body the server no longer sends, and so
     * the assertion above stays the only hand-written JSON in the file.
     */
    private fun scheduleAnswer(): String =
        appJson.encodeToString(
            ScheduleResponse.serializer(),
            ScheduleResponse(schedule = FakeLoadingApi.entry(id = SCHEDULE_ID, slot = NEW_SLOT)),
        )

    private companion object {
        /** The entry the edit targets, and the slot it is moved to. Both distinct from the fixture's own. */
        const val SCHEDULE_ID = 7
        const val NEW_SLOT = "06:30"

        /**
         * The app's `Json` with the one setting that decides this file flipped, and nothing else changed.
         *
         * `ignoreUnknownKeys` is repeated because it is what the provider sets; leaving it out would make
         * the two differ in two ways and turn a failure here into a puzzle about which one did it.
         */
        val encodingDefaults = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
