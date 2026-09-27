package com.trindade.app.network

import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Only the catalog read and task-type operations used by the task surface. */
interface AdminApi {
    @GET("api/admin/categories")
    suspend fun categories(): Response<AdminCategoriesResponse>

    @GET("api/admin/tasks")
    suspend fun tasks(): Response<AdminTasksResponse>

    @GET("api/admin/drivers")
    suspend fun drivers(): Response<AdminDriversResponse>

    @POST("api/admin/drivers")
    suspend fun createDriver(@Body body: CreateAdminDriverRequest): Response<AdminDriverResponse>

    @PATCH("api/admin/drivers/{id}")
    suspend fun updateDriver(@Path("id") id: Int, @Body body: JsonObject): Response<AdminDriverResponse>

    @POST("api/admin/tasks")
    suspend fun createTask(@Body body: CreateAdminTaskRequest): Response<AdminTaskResponse>

    @PATCH("api/admin/tasks/{id}")
    suspend fun updateTask(@Path("id") id: Int, @Body body: JsonObject): Response<AdminTaskResponse>

    @DELETE("api/admin/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Int): Response<Unit>
}
