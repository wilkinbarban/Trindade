package com.trindade.app.network

import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockHistory
import com.trindade.app.contract.models.StockProduct
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The counting endpoints.
 *
 * Written here rather than generated, like every other client in this package: the published contract
 * ships the shapes and this project names its own calls, so a change on the server shows up as a compile
 * error in the models instead of as a silently different request.
 *
 * The two writes send a [JsonObject] with the keys the schema asks for, because the contract describes
 * the responses of those paths and not their bodies.
 */
interface StockApi {
    @GET("api/stock")
    suspend fun day(@Query("date") date: String): Response<StockDay>

    @POST("api/stock/add-entry")
    suspend fun addEntry(@Body body: JsonObject): Response<StockProduct>

    @POST("api/stock/reset-product")
    suspend fun resetProduct(@Body body: JsonObject): Response<StockProduct>

    @GET("api/stock/history")
    suspend fun history(): Response<StockHistory>
}
