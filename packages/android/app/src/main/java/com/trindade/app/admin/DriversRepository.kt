package com.trindade.app.admin

import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.UpdateAdminDriverRequestSerializer
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The data layer for catalog drivers and fleteros.
 *
 * Used by [DriversViewModel] to list, create, and update drivers.
 * Drivers cannot be deleted; the backend provides no DELETE endpoint for drivers.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy so the caller can distinguish:
 * - A successful write carrying the updated/created driver
 * - A server refusal with its HTTP status code (400, 403, 404, etc.)
 * - An unreachable/network error to offer retry
 */
@Singleton
class DriversRepository @Inject constructor(
    private val api: AdminApi,
    private val json: Json = NetworkModule.provideJson(),
) {

    /**
     * Lists drivers. Server-side, an Administrador sees all drivers (active and inactive),
     * while a Trabalhador sees only active drivers.
     */
    suspend fun drivers(): List<AdminDriverResponseDriver>? =
        runCatchingCancellable { api.drivers() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.drivers

    /**
     * Creates a new driver.
     * Administrador can create either casa or fletero.
     * Trabalhador can only create fletero (server enforces 403 for casa).
     */
    suspend fun createDriver(body: CreateAdminDriverRequest): DriverWriteResult {
        val response = runCatchingCancellable { api.createDriver(body) }.getOrNull()
            ?: return DriverWriteResult.Unreachable
        val driver = response.body()?.driver
        return if (response.isSuccessful && driver != null) {
            DriverWriteResult.Saved(driver)
        } else {
            DriverWriteResult.Refused(response.code())
        }
    }

    /**
     * Updates an existing driver.
     * Encodes through [UpdateAdminDriverRequestSerializer] to ensure is_active is numeric 0 or 1 on the wire.
     * Administrador can update any driver, change driver_type, and toggle is_active.
     * Trabalhador can only update their own fletero drivers, and cannot toggle is_active or set type to casa.
     */
    suspend fun updateDriver(id: Int, body: UpdateAdminDriverRequest): DriverWriteResult {
        val jsonObject = json.encodeToJsonElement(UpdateAdminDriverRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.updateDriver(id, jsonObject) }.getOrNull()
            ?: return DriverWriteResult.Unreachable
        val driver = response.body()?.driver
        return if (response.isSuccessful && driver != null) {
            DriverWriteResult.Saved(driver)
        } else {
            DriverWriteResult.Refused(response.code())
        }
    }
}

sealed interface DriverWriteResult {
    data class Saved(val driver: AdminDriverResponseDriver) : DriverWriteResult
    data class Refused(val statusCode: Int) : DriverWriteResult
    data object Unreachable : DriverWriteResult
}
