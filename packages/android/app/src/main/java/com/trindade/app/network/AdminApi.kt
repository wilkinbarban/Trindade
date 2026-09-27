package com.trindade.app.network

import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehiclesResponse
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Admin catalog operations used by the task and category surfaces. */
interface AdminApi {
    @GET("api/admin/categories")
    suspend fun categories(): Response<AdminCategoriesResponse>

    @POST("api/admin/categories")
    suspend fun createCategory(@Body body: CreateAdminCategoryRequest): Response<AdminCategoryResponse>

    @PATCH("api/admin/categories/{id}")
    suspend fun updateCategory(@Path("id") id: Int, @Body body: JsonObject): Response<AdminCategoryResponse>

    @DELETE("api/admin/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): Response<Unit>

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

    @GET("api/admin/vehicles")
    suspend fun vehicles(): Response<AdminVehiclesResponse>

    @POST("api/admin/vehicles")
    suspend fun createVehicle(@Body body: CreateAdminVehicleRequest): Response<AdminVehicleResponse>

    @PATCH("api/admin/vehicles/{id}")
    suspend fun updateVehicle(@Path("id") id: Int, @Body body: JsonObject): Response<AdminVehicleResponse>

    @DELETE("api/admin/vehicles/{id}")
    suspend fun deleteVehicle(@Path("id") id: Int): Response<Unit>

    @GET("api/admin/time-slots")
    suspend fun timeSlots(): Response<TimeSlotsResponse>

    @PUT("api/admin/time-slots")
    suspend fun updateTimeSlots(@Body body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse>

    @GET("api/admin/users")
    suspend fun users(): Response<AdminUsersResponse>

    @POST("api/admin/users")
    suspend fun createUser(@Body body: JsonObject): Response<AdminUserResponse>

    @PATCH("api/admin/users/{id}")
    suspend fun updateUser(@Path("id") id: Int, @Body body: JsonObject): Response<AdminUserResponse>

    @DELETE("api/admin/users/{id}")
    suspend fun deleteUser(@Path("id") id: Int): Response<Unit>
}
