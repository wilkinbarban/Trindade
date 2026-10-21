package com.trindade.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class MassaProductTotal(
    val code: String,
    val name: String,
    val unitsPerBox: Int,
    val totalBoxes: Int,
    val totalUnits: Int,
)

@Serializable
data class MassaIndividualLaunch(
    val id: Int,
    @SerialName("launch_date") val launchDate: String,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String,
    val boxes: Int,
    val units: Int,
    @SerialName("user_id") val userId: Int,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MassasLaunchDayView(
    val date: String,
    val products: List<MassaProductTotal> = emptyList(),
    val launches: List<MassaIndividualLaunch> = emptyList(),
    val totalBoxes: Int = 0,
    val totalUnits: Int = 0,
)

@Serializable
data class CreateMassasLaunchItem(
    @SerialName("product_code") val productCode: String,
    val boxes: Int,
)

@Serializable
data class CreateMassasLaunchRequest(
    val date: String,
    val items: List<CreateMassasLaunchItem>,
)

@Serializable
data class UpdateMassasLaunchRequest(
    val boxes: Int,
)

@Serializable
data class MassaHistoryDay(
    val date: String,
    val productsCount: Int,
    val totalBoxes: Int,
    val totalUnits: Int,
    val operatorsCount: Int,
)

@Serializable
data class MassasHistoryResponse(
    val days: List<MassaHistoryDay> = emptyList(),
)

/**
 * Endpoints for the massas launch module.
 */
interface MassasApi {
    @GET("api/massas-launch")
    suspend fun day(@Query("date") date: String): Response<MassasLaunchDayView>

    @POST("api/massas-launch")
    suspend fun createLaunch(@Body request: CreateMassasLaunchRequest): Response<MassasLaunchDayView>

    @PUT("api/massas-launch/{id}")
    suspend fun updateLaunch(
        @Path("id") id: Int,
        @Body request: UpdateMassasLaunchRequest,
    ): Response<MassasLaunchDayView>

    @DELETE("api/massas-launch/{id}")
    suspend fun deleteLaunch(@Path("id") id: Int): Response<MassasLaunchDayView>

    @GET("api/massas-launch/history")
    suspend fun history(): Response<MassasHistoryResponse>
}
