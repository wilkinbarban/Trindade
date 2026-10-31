package com.trindade.app.aero

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * The company's WebService, as this app calls it.
 *
 * Every method takes an absolute `@Url` because the base address is a per-install setting, not a build
 * constant: one operator with the owner account can move it, and Retrofit resolves `@Url` without a base.
 *
 * The `Authorization` header is a parameter rather than an interceptor, and that is the security property
 * of this interface: these credentials are the factory's, they are not the app's session, and there is no
 * code path here that could attach a Trindade token to a plant request.
 */
interface AeroErpApi {

    /** `POST /usuarios`: proves the credentials and, in the same answer, the ERP's operator code. */
    @POST
    suspend fun postLogin(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): Response<JsonElement>

    /** `POST /entradasitem`: one stock entry. The ERP's answer decides whether the launch is reconciled. */
    @POST
    suspend fun postEntry(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: AeroEntryPayload,
    ): Response<JsonElement>

    /** `POST /movimentoitem?_method=PATCH`: the day as the ERP recorded it. */
    @POST
    suspend fun postMovements(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: AeroMovimentoPayload,
    ): Response<JsonElement>
}
