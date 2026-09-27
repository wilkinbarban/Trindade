package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
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
class CategoriesRepository @Inject constructor(private val api: AdminApi) {
    suspend fun categories(): List<AdminCategoryResponseCategory>? =
        runCatchingCancellable { api.categories() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.categories

    suspend fun create(body: CreateAdminCategoryRequest): CategoryWriteResult =
        write { api.createCategory(body) }

    suspend fun update(id: Int, body: UpdateAdminCategoryRequest): CategoryWriteResult = write {
        // The generated numeric enum encodes as a string, but the backend schema requires a JSON number.
        val fields = Json.encodeToJsonElement(UpdateAdminCategoryRequest.serializer(), body).jsonObject.toMutableMap()
        body.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
        api.updateCategory(id, JsonObject(fields))
    }

    suspend fun delete(id: Int): CategoryWriteResult {
        val response = runCatchingCancellable { api.deleteCategory(id) }.getOrNull()
            ?: return CategoryWriteResult.Unreachable
        return if (response.isSuccessful) CategoryWriteResult.Saved() else CategoryWriteResult.Refused(response.code())
    }

    private suspend fun write(call: suspend () -> Response<AdminCategoryResponse>): CategoryWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return CategoryWriteResult.Unreachable
        if (!response.isSuccessful) return CategoryWriteResult.Refused(response.code())
        return response.body()?.category?.let { CategoryWriteResult.Saved(it) } ?: CategoryWriteResult.Unreachable
    }
}

sealed interface CategoryWriteResult {
    data class Saved(val category: AdminCategoryResponseCategory? = null) : CategoryWriteResult
    data class Refused(val status: Int) : CategoryWriteResult
    data object Unreachable : CategoryWriteResult
}
