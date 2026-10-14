package com.trindade.app.admin

import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.Response

/** A failed read is null, not an empty list; write refusals keep their HTTP status. */
@Singleton
class DriversRepository @Inject constructor(private val api: AdminApi) {
    suspend fun drivers(): List<AdminDriverResponseDriver>? =
        runCatchingCancellable { api.drivers() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.drivers

    suspend fun create(body: CreateAdminDriverRequest): DriverWriteResult =
        write { api.createDriver(body) }

    suspend fun update(id: Int, body: UpdateAdminDriverRequest): DriverWriteResult = write {
        // The generated numeric enum encodes as a string, but the backend schema requires a JSON number.
        val fields = Json.encodeToJsonElement(UpdateAdminDriverRequest.serializer(), body).jsonObject.toMutableMap()
        body.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
        // The number is the one field a save may set to null on purpose: the endpoint reads an absent key
        // as "leave it alone" and a null as "remove it", and an operator who emptied the field is asking
        // for the second. The form sends that emptied field as an empty string, which no number is, so
        // the two intentions stay apart.
        body.phone?.let { fields["phone"] = if (it.isEmpty()) JsonNull else JsonPrimitive(it) }
        api.updateDriver(id, JsonObject(fields))
    }

    private suspend fun write(call: suspend () -> Response<AdminDriverResponse>): DriverWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return DriverWriteResult.Unreachable
        if (!response.isSuccessful) return DriverWriteResult.Refused(response.code())
        return response.body()?.driver?.let(DriverWriteResult::Saved) ?: DriverWriteResult.Unreachable
    }
}

sealed interface DriverWriteResult {
    data class Saved(val driver: AdminDriverResponseDriver) : DriverWriteResult
    data class Refused(val status: Int) : DriverWriteResult
    data object Unreachable : DriverWriteResult
}
