package com.trindade.app.admin

import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.UpdateAdminVehicleRequestSerializer
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The data layer for vehicle management.
 *
 * Used by [VehiclesViewModel] to list, create, update, and delete vehicles.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy so callers distinguish:
 * - A successful write carrying the created/updated vehicle
 * - A server refusal with its HTTP status code (400, 403, 404, etc.)
 * - An unreachable/network error to offer retry
 */
@Singleton
class VehiclesRepository @Inject constructor(
    private val api: AdminApi,
    private val json: Json = NetworkModule.provideJson(),
) {

    /**
     * Lists all vehicles. Gated server-side by adminGuard (Administrador only).
     */
    suspend fun vehicles(): List<AdminVehicleResponseVehicle>? =
        runCatchingCancellable { api.vehicles() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.vehicles

    /**
     * Creates a new vehicle. Gated server-side by adminGuard (Administrador only).
     */
    suspend fun createVehicle(body: CreateAdminVehicleRequest): VehicleWriteResult {
        val response = runCatchingCancellable { api.createVehicle(body) }.getOrNull()
            ?: return VehicleWriteResult.Unreachable
        val vehicle = response.body()?.vehicle
        return if (response.isSuccessful && vehicle != null) {
            VehicleWriteResult.Saved(vehicle)
        } else {
            VehicleWriteResult.Refused(response.code())
        }
    }

    /**
     * Updates an existing vehicle.
     * Encodes through [UpdateAdminVehicleRequestSerializer] to ensure is_active is numeric 0 or 1 on the wire.
     * Gated server-side by adminGuard (Administrador only).
     */
    suspend fun updateVehicle(id: Int, body: UpdateAdminVehicleRequest): VehicleWriteResult {
        val jsonObject = json.encodeToJsonElement(UpdateAdminVehicleRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.updateVehicle(id, jsonObject) }.getOrNull()
            ?: return VehicleWriteResult.Unreachable
        val vehicle = response.body()?.vehicle
        return if (response.isSuccessful && vehicle != null) {
            VehicleWriteResult.Saved(vehicle)
        } else {
            VehicleWriteResult.Refused(response.code())
        }
    }

    /**
     * Deletes a vehicle. Gated server-side by adminGuard (Administrador only).
     * 400 means foreign key reference constraint (referenced in loading schedules).
     * 404 means already deleted / not found.
     */
    suspend fun deleteVehicle(id: Int): VehicleLifecycleResult {
        val response = runCatchingCancellable { api.deleteVehicle(id) }.getOrNull()
            ?: return VehicleLifecycleResult.Unreachable
        val successPrimitive = response.body()?.get("success") as? JsonPrimitive
        val isSuccess = response.isSuccessful &&
            successPrimitive != null &&
            !successPrimitive.isString &&
            successPrimitive.booleanOrNull == true
        return if (isSuccess) {
            VehicleLifecycleResult.Changed
        } else {
            VehicleLifecycleResult.Refused(response.code())
        }
    }
}

sealed interface VehicleWriteResult {
    data class Saved(val vehicle: AdminVehicleResponseVehicle) : VehicleWriteResult
    data class Refused(val statusCode: Int) : VehicleWriteResult
    data object Unreachable : VehicleWriteResult
}

sealed interface VehicleLifecycleResult {
    data object Changed : VehicleLifecycleResult
    data class Refused(val statusCode: Int) : VehicleLifecycleResult
    data object Unreachable : VehicleLifecycleResult
}
