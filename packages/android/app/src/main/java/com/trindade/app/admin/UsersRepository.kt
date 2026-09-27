package com.trindade.app.admin

import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
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
class UsersRepository @Inject constructor(private val api: AdminApi) {
    suspend fun users(): List<AdminUserResponseUser>? =
        runCatchingCancellable { api.users() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.users

    suspend fun create(body: CreateAdminUserRequest): UserWriteResult = write {
        // The generated numeric enum encodes as a string, but the backend schema requires a JSON number.
        val fields = Json.encodeToJsonElement(CreateAdminUserRequest.serializer(), body).jsonObject.toMutableMap()
        fields["role_id"] = JsonPrimitive(body.roleId.value)
        api.createUser(JsonObject(fields))
    }

    suspend fun update(id: Int, body: UpdateAdminUserRequest): UserWriteResult = write {
        // The generated numeric enum encodes as a string, but the backend schema requires a JSON number.
        // An unchanged password must be completely omitted from the PATCH body.
        val fields = Json.encodeToJsonElement(UpdateAdminUserRequest.serializer(), body).jsonObject.toMutableMap()
        if (body.password.isNullOrEmpty()) {
            fields.remove("password")
        }
        body.roleId?.let { fields["role_id"] = JsonPrimitive(it.value) }
        body.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
        api.updateUser(id, JsonObject(fields))
    }

    suspend fun delete(id: Int): UserWriteResult {
        val response = runCatchingCancellable { api.deleteUser(id) }.getOrNull()
            ?: return UserWriteResult.Unreachable
        return if (response.isSuccessful) UserWriteResult.Saved() else UserWriteResult.Refused(response.code())
    }

    private suspend fun write(call: suspend () -> Response<AdminUserResponse>): UserWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return UserWriteResult.Unreachable
        if (!response.isSuccessful) return UserWriteResult.Refused(response.code())
        return response.body()?.user?.let { UserWriteResult.Saved(it) } ?: UserWriteResult.Unreachable
    }
}

sealed interface UserWriteResult {
    data class Saved(val user: AdminUserResponseUser? = null) : UserWriteResult
    data class Refused(val status: Int) : UserWriteResult
    data object Unreachable : UserWriteResult
}

typealias UsersWriteResult = UserWriteResult
