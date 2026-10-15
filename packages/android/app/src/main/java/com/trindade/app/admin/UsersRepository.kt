package com.trindade.app.admin

import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.CreateAdminUserRequestSerializer
import com.trindade.app.network.UpdateAdminUserRequestSerializer
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The data layer for user and worker access management.
 *
 * Used by [UsersViewModel] to list, create, update, and delete system users.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy:
 * - [UserWriteResult.Saved] carrying the created or updated user
 * - [UserWriteResult.Refused] with server HTTP status code (400, 403, 404, etc.)
 * - [UserWriteResult.Unreachable] on network error
 *
 * Lifecycle operations answer:
 * - [UserLifecycleResult.Changed] on successful delete
 * - [UserLifecycleResult.Refused] with server HTTP status code
 * - [UserLifecycleResult.Unreachable] on network error
 */
@Singleton
class UsersRepository @Inject constructor(
    private val api: AdminApi,
    private val json: Json = NetworkModule.provideJson(),
) {

    /**
     * Lists all users. Gated server-side by adminGuard (Administrador only).
     */
    suspend fun users(): List<AdminUserResponseUser>? =
        runCatchingCancellable { api.users() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.users

    /**
     * Creates a new user. Gated server-side by adminGuard (Administrador only).
     * Serializes role_id as numeric literal via [CreateAdminUserRequestSerializer].
     */
    suspend fun createUser(body: CreateAdminUserRequest): UserWriteResult {
        val jsonObject = json.encodeToJsonElement(CreateAdminUserRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.createUser(jsonObject) }.getOrNull()
            ?: return UserWriteResult.Unreachable
        val user = response.body()?.user
        return if (response.isSuccessful && user != null) {
            UserWriteResult.Saved(user)
        } else {
            UserWriteResult.Refused(response.code())
        }
    }

    /**
     * Updates an existing user. Gated server-side by adminGuard (Administrador only).
     * Serializes role_id and is_active as numeric literals via [UpdateAdminUserRequestSerializer].
     */
    suspend fun updateUser(id: Int, body: UpdateAdminUserRequest): UserWriteResult {
        val jsonObject = json.encodeToJsonElement(UpdateAdminUserRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.updateUser(id, jsonObject) }.getOrNull()
            ?: return UserWriteResult.Unreachable
        val user = response.body()?.user
        return if (response.isSuccessful && user != null) {
            UserWriteResult.Saved(user)
        } else {
            UserWriteResult.Refused(response.code())
        }
    }

    /**
     * Deletes a user. Gated server-side by adminGuard (Administrador only).
     * Refuses self-deletion with 403.
     * 400 means foreign key reference constraint (has related records).
     */
    suspend fun deleteUser(id: Int): UserLifecycleResult {
        val response = runCatchingCancellable { api.deleteUser(id) }.getOrNull()
            ?: return UserLifecycleResult.Unreachable
        val successPrimitive = response.body()?.get("success") as? JsonPrimitive
        val isSuccess = response.isSuccessful &&
            successPrimitive != null &&
            !successPrimitive.isString &&
            successPrimitive.booleanOrNull == true
        return if (isSuccess) {
            UserLifecycleResult.Changed
        } else {
            UserLifecycleResult.Refused(response.code())
        }
    }
}

sealed interface UserWriteResult {
    data class Saved(val user: AdminUserResponseUser) : UserWriteResult
    data class Refused(val statusCode: Int) : UserWriteResult
    data object Unreachable : UserWriteResult
}

sealed interface UserLifecycleResult {
    data object Changed : UserLifecycleResult
    data class Refused(val statusCode: Int) : UserLifecycleResult
    data object Unreachable : UserLifecycleResult
}
