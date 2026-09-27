package com.trindade.app.admin

import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.Response

/** A failed read is null, not an empty list; write refusals keep their HTTP status. */
@Singleton
class VehiclesRepository @Inject constructor(private val api: AdminApi) {
    suspend fun vehicles(): List<AdminVehicleResponseVehicle>? =
        runCatchingCancellable { api.vehicles() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.vehicles

    suspend fun create(body: CreateAdminVehicleRequest): VehicleWriteResult =
        write { api.createVehicle(body) }

    suspend fun update(id: Int, body: UpdateAdminVehicleRequest): VehicleWriteResult = write {
        // The generated numeric enum encodes as a string, but the backend schema requires a JSON number.
        val fields = Json.encodeToJsonElement(UpdateAdminVehicleRequest.serializer(), body).jsonObject.toMutableMap()
        body.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
        api.updateVehicle(id, JsonObject(fields))
    }

    suspend fun delete(id: Int): VehicleWriteResult {
        val response = runCatchingCancellable { api.deleteVehicle(id) }.getOrNull()
            ?: return VehicleWriteResult.Unreachable
        return if (response.isSuccessful) VehicleWriteResult.Saved() else VehicleWriteResult.Refused(response.code())
    }

    private suspend fun write(call: suspend () -> Response<AdminVehicleResponse>): VehicleWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return VehicleWriteResult.Unreachable
        if (!response.isSuccessful) return VehicleWriteResult.Refused(response.code())
        return response.body()?.vehicle?.let { VehicleWriteResult.Saved(it) } ?: VehicleWriteResult.Unreachable
    }
}

sealed interface VehicleWriteResult {
    data class Saved(val vehicle: AdminVehicleResponseVehicle? = null) : VehicleWriteResult
    data class Refused(val status: Int) : VehicleWriteResult
    data object Unreachable : VehicleWriteResult
}
