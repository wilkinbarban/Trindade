package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.UpdateAdminCategoryRequestSerializer
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The data layer for category management.
 *
 * Used by [CategoriesViewModel] to list, create, update, and delete categories.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy so callers distinguish:
 * - A successful write carrying the created/updated category
 * - A server refusal with its HTTP status code (400, 403, 404, etc.)
 * - An unreachable/network error to offer retry
 */
@Singleton
class CategoriesRepository @Inject constructor(
    private val api: AdminApi,
    private val json: Json = NetworkModule.provideJson(),
) {

    /**
     * Lists all categories. Server-side, both Administrador and Trabalhador can read.
     */
    suspend fun categories(): List<AdminCategoryResponseCategory>? =
        runCatchingCancellable { api.categories() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.categories

    /**
     * Creates a new category. Gated server-side by adminGuard (Administrador only).
     */
    suspend fun createCategory(body: CreateAdminCategoryRequest): CategoryWriteResult {
        val response = runCatchingCancellable { api.createCategory(body) }.getOrNull()
            ?: return CategoryWriteResult.Unreachable
        val category = response.body()?.category
        return if (response.isSuccessful && category != null) {
            CategoryWriteResult.Saved(category)
        } else {
            CategoryWriteResult.Refused(response.code())
        }
    }

    /**
     * Updates an existing category.
     * Encodes through [UpdateAdminCategoryRequestSerializer] to ensure is_active is numeric 0 or 1 on the wire.
     * Gated server-side by adminGuard (Administrador only).
     */
    suspend fun updateCategory(id: Int, body: UpdateAdminCategoryRequest): CategoryWriteResult {
        val jsonObject = json.encodeToJsonElement(UpdateAdminCategoryRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.updateCategory(id, jsonObject) }.getOrNull()
            ?: return CategoryWriteResult.Unreachable
        val category = response.body()?.category
        return if (response.isSuccessful && category != null) {
            CategoryWriteResult.Saved(category)
        } else {
            CategoryWriteResult.Refused(response.code())
        }
    }

    /**
     * Deletes a category. Gated server-side by adminGuard (Administrador only).
     * 400 means foreign key reference constraint (referenced in tasks or reports).
     * 404 means already deleted / not found.
     */
    suspend fun deleteCategory(id: Int): CategoryLifecycleResult {
        val response = runCatchingCancellable { api.deleteCategory(id) }.getOrNull()
            ?: return CategoryLifecycleResult.Unreachable
        val successPrimitive = response.body()?.get("success") as? JsonPrimitive
        val isSuccess = response.isSuccessful &&
            successPrimitive != null &&
            !successPrimitive.isString &&
            successPrimitive.booleanOrNull == true
        return if (isSuccess) {
            CategoryLifecycleResult.Changed
        } else {
            CategoryLifecycleResult.Refused(response.code())
        }
    }
}

sealed interface CategoryWriteResult {
    data class Saved(val category: AdminCategoryResponseCategory) : CategoryWriteResult
    data class Refused(val statusCode: Int) : CategoryWriteResult
    data object Unreachable : CategoryWriteResult
}

sealed interface CategoryLifecycleResult {
    data object Changed : CategoryLifecycleResult
    data class Refused(val statusCode: Int) : CategoryLifecycleResult
    data object Unreachable : CategoryLifecycleResult
}
