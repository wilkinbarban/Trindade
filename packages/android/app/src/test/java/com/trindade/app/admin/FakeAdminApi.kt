package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.AdminVehiclesResponse
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class FakeAdminApi(
    private var timeSlotsToReturn: List<String>? = defaultTimeSlots(),
    var updateTimeSlotsResponse: Response<TimeSlotsResponse>? = null,
) : AdminApi {

    val updatedTimeSlotsRequests = mutableListOf<UpdateTimeSlotsRequest>()

    override suspend fun categories(): Response<AdminCategoriesResponse> =
        Response.success(AdminCategoriesResponse(categories = emptyList()))

    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun deleteCategory(id: Int): Response<Unit> =
        Response.success(Unit)

    override suspend fun tasks(): Response<AdminTasksResponse> =
        Response.success(AdminTasksResponse(tasks = emptyList()))

    override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun deleteTask(id: Int): Response<Unit> =
        Response.success(Unit)

    override suspend fun drivers(): Response<AdminDriversResponse> =
        Response.success(AdminDriversResponse(drivers = emptyList()))

    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun vehicles(): Response<AdminVehiclesResponse> =
        Response.success(AdminVehiclesResponse(vehicles = emptyList()))

    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun deleteVehicle(id: Int): Response<Unit> =
        Response.success(Unit)

    override suspend fun timeSlots(): Response<TimeSlotsResponse> {
        val list = timeSlotsToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TimeSlotsResponse(timeSlots = list))
    }

    override suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse> {
        updatedTimeSlotsRequests.add(body)
        updateTimeSlotsResponse?.let { return it }
        if (body.timeSlots.isEmpty()) {
            return Response.error(400, "{\"error\":\"At least one time slot is required\"}".toResponseBody(null))
        }
        timeSlotsToReturn = body.timeSlots
        return Response.success(TimeSlotsResponse(timeSlots = body.timeSlots))
    }

    override suspend fun users(): Response<AdminUsersResponse> =
        Response.success(AdminUsersResponse(users = emptyList()))

    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> =
        Response.error(501, EMPTY_BODY)

    override suspend fun deleteUser(id: Int): Response<Unit> =
        Response.success(Unit)

    companion object {
        val EMPTY_BODY = "".toResponseBody("application/json".toMediaType())

        fun defaultTimeSlots(): List<String> = listOf(
            "06:00",
            "08:00",
            "10:00",
            "12:00",
            "14:00",
            "16:00",
        )
    }
}
