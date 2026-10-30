package com.trindade.app.network

import com.trindade.app.contract.models.MassasReportDayView
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
    @SerialName("launch_date") val launchDate: String = "",
    val date: String = "",
    @SerialName("product_code") val productCode: String,
    @SerialName("product_name") val productName: String = "",
    val boxes: Int,
    val units: Int,
    @SerialName("user_id") val userId: Int,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    /** El asiento del ERP del que salió este lanzamiento; nulo cuando se cargó acá a mano. */
    @SerialName("ent_lan") val entLan: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
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
    /**
     * El asiento del que sale esta importación y el operario que la tableta escribió. Los dos viajan
     * juntos o no viajan: sin ellos el lanzamiento queda a nombre de quien apretó el botón y no hay con
     * qué conciliarlo.
     */
    @SerialName("ent_lan") val entLan: Int? = null,
    @SerialName("operator_name") val operatorName: String? = null,
    val items: List<CreateMassasLaunchItem>,
)

@Serializable
data class CreateMassasLaunchResponse(
    val created: List<MassaIndividualLaunch> = emptyList(),
)

@Serializable
data class UpdateMassasLaunchRequest(
    val boxes: Int,
)

@Serializable
data class DeleteMassasLaunchResponse(
    val ok: Boolean = true,
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

/** One stored ERP seat, as the backend keeps the day's snapshot. */
@Serializable
data class AeroStoredMovement(
    @SerialName("ent_lan") val entLan: Int,
    @SerialName("product_code") val productCode: String,
    @SerialName("product_desc") val productDesc: String,
    val quantity: Double,
    @SerialName("entry_date") val entryDate: String,
    @SerialName("operator_name") val operatorName: String,
    @SerialName("movement_type") val movementType: String,
)

@Serializable
data class AeroMovementsSnapshotRequest(
    val date: String,
    val movements: List<AeroStoredMovement>,
)

/** What the backend counted with its two lenses: the same snapshot, read by two screens. */
@Serializable
data class AeroMovementsSaved(
    val saved: Int = 0,
    val massas: Int = 0,
    val insumos: Int = 0,
)

@Serializable
data class AeroMovementsStoredDay(
    val date: String,
    val movements: List<AeroStoredMovement> = emptyList(),
    @SerialName("syncedAt") val syncedAt: String? = null,
)

/** One audit line, in the shape the integration log accepts. */
@Serializable
data class AeroAuditLogRequest(
    val event: String,
    val destinationUrl: String,
    val phase: String? = null,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
    val note: String? = null,
)

/**
 * Endpoints for the massas launch module, plus the two calls that make the day's ERP mirror visible.
 *
 * The mirror lives on the backend on purpose: the phone reads the ERP once and stores the whole day there, so
 * the web and the tablet read the same snapshot instead of each keeping its own idea of the morning.
 */
interface MassasApi {
    @GET("api/massas-launch")
    suspend fun day(@Query("date") date: String): Response<MassasLaunchDayView>

    @POST("api/massas-launch")
    suspend fun createLaunch(@Body request: CreateMassasLaunchRequest): Response<CreateMassasLaunchResponse>

    @PUT("api/massas-launch/{id}")
    suspend fun updateLaunch(
        @Path("id") id: Int,
        @Body request: UpdateMassasLaunchRequest,
    ): Response<MassaIndividualLaunch>

    @DELETE("api/massas-launch/{id}")
    suspend fun deleteLaunch(@Path("id") id: Int): Response<DeleteMassasLaunchResponse>

    /** The day as the report reads it: launches here plus the tablet production that reconciles with them. */
    @GET("api/massas-launch/report")
    suspend fun reportDay(@Query("date") date: String): Response<MassasReportDayView>

    @GET("api/massas-launch/history")
    suspend fun history(): Response<MassasHistoryResponse>

    /** Stores the day exactly as the ERP answered it; the lenses are counted server-side. */
    @POST("api/integration/aero/movements")
    suspend fun saveMovements(
        @Body request: AeroMovementsSnapshotRequest,
    ): Response<AeroMovementsSaved>

    @GET("api/integration/aero/movements")
    suspend fun storedMovements(@Query("date") date: String): Response<AeroMovementsStoredDay>

    /** Every integration event, under this operator's own identity (S19). */
    @POST("api/integration/aero/log")
    suspend fun logEvent(@Body request: AeroAuditLogRequest): Response<Unit>
}
