package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoriesResponse
import com.trindade.app.contract.models.AdminCategoryResponse
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminDriverResponse
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.AdminDriversResponse
import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.contract.models.AdminTaskResponseTask
import com.trindade.app.contract.models.AdminTasksResponse
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.contract.models.AdminVehicleResponse
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.AdminVehiclesResponse
import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.CreateAdminUserRequestSerializer
import com.trindade.app.network.UpdateAdminCategoryRequestSerializer
import com.trindade.app.network.UpdateAdminDriverRequestSerializer
import com.trindade.app.network.UpdateAdminTaskRequestSerializer
import com.trindade.app.network.UpdateAdminUserRequestSerializer
import com.trindade.app.network.UpdateAdminVehicleRequestSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * A memory implementation of [AdminApi] for testing [TasksRepository], [DriversRepository],
 * [CategoriesRepository], [VehiclesRepository], [TasksViewModel], [DriversViewModel],
 * [CategoriesViewModel], and [VehiclesViewModel].
 */
class FakeAdminApi(
    private var categoriesToReturn: List<AdminCategoryResponseCategory>? = defaultCategories(),
    private var tasksToReturn: List<AdminTasksResponseTasksInner>? = defaultTasks(),
    private var driversToReturn: List<AdminDriverResponseDriver>? = defaultDrivers(),
    private var vehiclesToReturn: List<AdminVehicleResponseVehicle>? = defaultVehicles(),
    private var timeSlotsToReturn: List<String>? = defaultTimeSlots(),
    private var usersToReturn: List<AdminUserResponseUser>? = defaultUsers(),
    private var auditToReturn: AuditResponse? = defaultAuditResponse(),
    var auditResponse: Response<AuditResponse>? = null,
    var createResponse: Response<AdminTaskResponse>? = null,
    var updateResponse: Response<AdminTaskResponse>? = null,
    var deleteResponse: Response<SuccessResponse>? = null,
    var createDriverResponse: Response<AdminDriverResponse>? = null,
    var updateDriverResponse: Response<AdminDriverResponse>? = null,
    var createCategoryResponse: Response<AdminCategoryResponse>? = null,
    var updateCategoryResponse: Response<AdminCategoryResponse>? = null,
    var deleteCategoryResponse: Response<JsonObject>? = null,
    var createVehicleResponse: Response<AdminVehicleResponse>? = null,
    var updateVehicleResponse: Response<AdminVehicleResponse>? = null,
    var deleteVehicleResponse: Response<JsonObject>? = null,
    var updateTimeSlotsResponse: Response<TimeSlotsResponse>? = null,
    var createUserResponse: Response<AdminUserResponse>? = null,
    var updateUserResponse: Response<AdminUserResponse>? = null,
    var deleteUserResponse: Response<JsonObject>? = null,
    private val json: Json = NetworkModule.provideJson(),
) : AdminApi {

    val createdRequests = mutableListOf<CreateAdminTaskRequest>()
    val updatedRequests = mutableListOf<Pair<Int, UpdateAdminTaskRequest>>()
    val deletedIds = mutableListOf<Int>()
    val createdDriverRequests = mutableListOf<CreateAdminDriverRequest>()
    val updatedDriverRequests = mutableListOf<Pair<Int, UpdateAdminDriverRequest>>()
    val createdCategoryRequests = mutableListOf<CreateAdminCategoryRequest>()
    val updatedCategoryRequests = mutableListOf<Pair<Int, UpdateAdminCategoryRequest>>()
    val deletedCategoryIds = mutableListOf<Int>()
    val createdVehicleRequests = mutableListOf<CreateAdminVehicleRequest>()
    val updatedVehicleRequests = mutableListOf<Pair<Int, UpdateAdminVehicleRequest>>()
    val deletedVehicleIds = mutableListOf<Int>()
    val updatedTimeSlotsRequests = mutableListOf<UpdateTimeSlotsRequest>()
    val createdUserRequests = mutableListOf<CreateAdminUserRequest>()
    val updatedUserRequests = mutableListOf<Pair<Int, UpdateAdminUserRequest>>()
    val deletedUserIds = mutableListOf<Int>()
    data class AuditCall(
        val page: Int?,
        val limit: Int?,
        val action: String?,
        val entityType: String?,
        val userId: Int?,
    )
    val auditCalls = mutableListOf<AuditCall>()

    override suspend fun categories(): Response<AdminCategoriesResponse> {
        val list = categoriesToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(AdminCategoriesResponse(categories = list))
    }

    override suspend fun createCategory(body: CreateAdminCategoryRequest): Response<AdminCategoryResponse> {
        createdCategoryRequests.add(body)
        val response = createCategoryResponse ?: Response.success(
            AdminCategoryResponse(
                category = AdminCategoryResponseCategory(
                    id = 100 + createdCategoryRequests.size,
                    parentCategoryId = body.parentCategoryId,
                    namePt = body.namePt ?: "",
                    nameEs = body.nameEs ?: "",
                    categoryType = when (body.categoryType) {
                        CreateAdminCategoryRequest.CategoryType.check -> AdminCategoryResponseCategory.CategoryType.check
                        CreateAdminCategoryRequest.CategoryType.temperature -> AdminCategoryResponseCategory.CategoryType.temperature
                        CreateAdminCategoryRequest.CategoryType.check_assai -> AdminCategoryResponseCategory.CategoryType.check_assai
                        CreateAdminCategoryRequest.CategoryType.check_normal -> AdminCategoryResponseCategory.CategoryType.check_normal
                        null -> AdminCategoryResponseCategory.CategoryType.check
                    },
                    sortOrder = body.sortOrder ?: 0,
                    isActive = 1,
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
            ),
        )
        if (response.isSuccessful) {
            response.body()?.category?.let { created ->
                categoriesToReturn = (categoriesToReturn ?: emptyList()) + created
            }
        }
        return response
    }

    override suspend fun updateCategory(id: Int, body: JsonObject): Response<AdminCategoryResponse> {
        val request = json.decodeFromJsonElement(UpdateAdminCategoryRequestSerializer, body)
        updatedCategoryRequests.add(id to request)
        updateCategoryResponse?.let { return it }
        val existing = categoriesToReturn?.firstOrNull { it.id == id }
        val updated = AdminCategoryResponseCategory(
            id = id,
            parentCategoryId = request.parentCategoryId ?: existing?.parentCategoryId,
            namePt = request.namePt ?: existing?.namePt ?: "Updated Category",
            nameEs = request.nameEs ?: existing?.nameEs ?: "Categoría Actualizada",
            categoryType = when (request.categoryType) {
                UpdateAdminCategoryRequest.CategoryType.check -> AdminCategoryResponseCategory.CategoryType.check
                UpdateAdminCategoryRequest.CategoryType.temperature -> AdminCategoryResponseCategory.CategoryType.temperature
                UpdateAdminCategoryRequest.CategoryType.check_assai -> AdminCategoryResponseCategory.CategoryType.check_assai
                UpdateAdminCategoryRequest.CategoryType.check_normal -> AdminCategoryResponseCategory.CategoryType.check_normal
                null -> existing?.categoryType ?: AdminCategoryResponseCategory.CategoryType.check
            },
            sortOrder = request.sortOrder ?: existing?.sortOrder ?: 0,
            isActive = request.isActive?.value ?: existing?.isActive ?: 1,
            createdAt = existing?.createdAt ?: "2026-09-22T10:00:00.000Z",
        )
        categoriesToReturn = categoriesToReturn?.map { if (it.id == id) updated else it }
        return Response.success(AdminCategoryResponse(category = updated))
    }

    override suspend fun deleteCategory(id: Int): Response<JsonObject> {
        deletedCategoryIds.add(id)
        val response = deleteCategoryResponse ?: Response.success(JsonObject(mapOf("success" to JsonPrimitive(true))))
        if (response.isSuccessful) {
            categoriesToReturn = categoriesToReturn?.filterNot { it.id == id }
        }
        return response
    }

    override suspend fun tasks(): Response<AdminTasksResponse> {
        val list = tasksToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(AdminTasksResponse(tasks = list))
    }

    override suspend fun createTask(body: CreateAdminTaskRequest): Response<AdminTaskResponse> {
        createdRequests.add(body)
        val response = createResponse ?: Response.success(
            AdminTaskResponse(
                task = AdminTaskResponseTask(
                    id = 99,
                    categoryId = body.categoryId,
                    namePt = body.namePt ?: "",
                    nameEs = body.nameEs ?: "",
                    temperatureReadings = body.temperatureReadings ?: 1,
                    isActive = 1,
                    createdByUserId = 1,
                    createdAt = "2026-09-22T10:00:00.000Z",
                    taskType = AdminTaskResponseTask.TaskType.check,
                ),
            ),
        )
        if (response.isSuccessful) {
            response.body()?.task?.let { created ->
                tasksToReturn = tasksToReturn?.plus(
                    AdminTasksResponseTasksInner(
                        id = created.id,
                        categoryId = created.categoryId,
                        namePt = created.namePt,
                        nameEs = created.nameEs,
                        temperatureReadings = created.temperatureReadings,
                        isActive = created.isActive,
                        createdByUserId = created.createdByUserId,
                        createdAt = created.createdAt,
                        taskType = when (created.taskType) {
                            AdminTaskResponseTask.TaskType.check -> AdminTasksResponseTasksInner.TaskType.check
                            AdminTaskResponseTask.TaskType.temperature -> AdminTasksResponseTasksInner.TaskType.temperature
                            AdminTaskResponseTask.TaskType.check_assai -> AdminTasksResponseTasksInner.TaskType.check_assai
                            AdminTaskResponseTask.TaskType.check_normal -> AdminTasksResponseTasksInner.TaskType.check_normal
                        },
                        categoryName = categoriesToReturn
                            ?.firstOrNull { it.id == created.categoryId }
                            ?.namePt
                            ?: "Categoria ${created.categoryId}",
                    ),
                )
            }
        }
        return response
    }

    override suspend fun updateTask(id: Int, body: JsonObject): Response<AdminTaskResponse> {
        val request = json.decodeFromJsonElement(UpdateAdminTaskRequestSerializer, body)
        updatedRequests.add(id to request)
        updateResponse?.let { return it }
        val updated = AdminTaskResponseTask(
            id = id,
            categoryId = request.categoryId ?: 1,
            namePt = request.namePt ?: "Updated Task",
            nameEs = request.nameEs ?: "Tarea Actualizada",
            temperatureReadings = request.temperatureReadings ?: 1,
            isActive = request.isActive?.value ?: 1,
            createdByUserId = 1,
            createdAt = "2026-09-22T10:00:00.000Z",
            taskType = AdminTaskResponseTask.TaskType.check,
        )
        return Response.success(AdminTaskResponse(task = updated))
    }

    override suspend fun deleteTask(id: Int): Response<SuccessResponse> {
        deletedIds.add(id)
        val response = deleteResponse ?: Response.success(SuccessResponse(success = SuccessResponse.Success.`true`))
        if (response.isSuccessful) {
            tasksToReturn = tasksToReturn?.filterNot { it.id == id }
        }
        return response
    }

    override suspend fun drivers(): Response<AdminDriversResponse> {
        val list = driversToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(AdminDriversResponse(drivers = list))
    }

    override suspend fun createDriver(body: CreateAdminDriverRequest): Response<AdminDriverResponse> {
        createdDriverRequests.add(body)
        val response = createDriverResponse ?: Response.success(
            AdminDriverResponse(
                driver = AdminDriverResponseDriver(
                    id = 100 + createdDriverRequests.size,
                    name = body.name,
                    licensePlate = body.licensePlate,
                    driverType = when (body.driverType) {
                        CreateAdminDriverRequest.DriverType.casa -> AdminDriverResponseDriver.DriverType.casa
                        else -> AdminDriverResponseDriver.DriverType.fletero
                    },
                    isActive = 1,
                    createdByUserId = 42,
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
            ),
        )
        if (response.isSuccessful) {
            response.body()?.driver?.let { created ->
                driversToReturn = (driversToReturn ?: emptyList()) + created
            }
        }
        return response
    }

    override suspend fun updateDriver(id: Int, body: JsonObject): Response<AdminDriverResponse> {
        val request = json.decodeFromJsonElement(UpdateAdminDriverRequestSerializer, body)
        updatedDriverRequests.add(id to request)
        updateDriverResponse?.let { return it }
        val existing = driversToReturn?.firstOrNull { it.id == id }
        val updated = AdminDriverResponseDriver(
            id = id,
            name = request.name ?: existing?.name ?: "Updated Driver",
            licensePlate = request.licensePlate ?: existing?.licensePlate,
            driverType = when (request.driverType) {
                UpdateAdminDriverRequest.DriverType.casa -> AdminDriverResponseDriver.DriverType.casa
                UpdateAdminDriverRequest.DriverType.fletero -> AdminDriverResponseDriver.DriverType.fletero
                null -> existing?.driverType ?: AdminDriverResponseDriver.DriverType.fletero
            },
            isActive = request.isActive?.value ?: existing?.isActive ?: 1,
            createdByUserId = existing?.createdByUserId,
            createdAt = existing?.createdAt ?: "2026-09-22T10:00:00.000Z",
        )
        driversToReturn = driversToReturn?.map { if (it.id == id) updated else it }
        return Response.success(AdminDriverResponse(driver = updated))
    }

    override suspend fun vehicles(): Response<AdminVehiclesResponse> {
        val list = vehiclesToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(AdminVehiclesResponse(vehicles = list))
    }

    override suspend fun createVehicle(body: CreateAdminVehicleRequest): Response<AdminVehicleResponse> {
        createdVehicleRequests.add(body)
        val response = createVehicleResponse ?: Response.success(
            AdminVehicleResponse(
                vehicle = AdminVehicleResponseVehicle(
                    id = 100 + createdVehicleRequests.size,
                    description = body.description,
                    licensePlate = body.licensePlate,
                    isActive = 1,
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
            ),
        )
        if (response.isSuccessful) {
            response.body()?.vehicle?.let { created ->
                vehiclesToReturn = (vehiclesToReturn ?: emptyList()) + created
            }
        }
        return response
    }

    override suspend fun updateVehicle(id: Int, body: JsonObject): Response<AdminVehicleResponse> {
        val request = json.decodeFromJsonElement(UpdateAdminVehicleRequestSerializer, body)
        updatedVehicleRequests.add(id to request)
        updateVehicleResponse?.let { return it }
        val existing = vehiclesToReturn?.firstOrNull { it.id == id }
        val updated = AdminVehicleResponseVehicle(
            id = id,
            description = request.description ?: existing?.description ?: "Updated Vehicle",
            licensePlate = request.licensePlate ?: existing?.licensePlate ?: "ABC-0000",
            isActive = request.isActive?.value ?: existing?.isActive ?: 1,
            createdAt = existing?.createdAt ?: "2026-09-22T10:00:00.000Z",
        )
        vehiclesToReturn = vehiclesToReturn?.map { if (it.id == id) updated else it }
        return Response.success(AdminVehicleResponse(vehicle = updated))
    }

    override suspend fun deleteVehicle(id: Int): Response<JsonObject> {
        deletedVehicleIds.add(id)
        val response = deleteVehicleResponse ?: Response.success(JsonObject(mapOf("success" to JsonPrimitive(true))))
        if (response.isSuccessful) {
            vehiclesToReturn = vehiclesToReturn?.filterNot { it.id == id }
        }
        return response
    }

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

    override suspend fun users(): Response<AdminUsersResponse> {
        val list = usersToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(AdminUsersResponse(users = list))
    }

    override suspend fun createUser(body: JsonObject): Response<AdminUserResponse> {
        val request = json.decodeFromJsonElement(CreateAdminUserRequestSerializer, body)
        createdUserRequests.add(request)
        val response = createUserResponse ?: Response.success(
            AdminUserResponse(
                user = AdminUserResponseUser(
                    id = 100 + createdUserRequests.size,
                    username = request.username,
                    displayName = request.displayName,
                    roleId = request.roleId.value,
                    roleName = if (request.roleId == CreateAdminUserRequest.RoleId._1) "Administrador" else "Trabalhador",
                    isActive = 1,
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
            ),
        )
        if (response.isSuccessful) {
            response.body()?.user?.let { created ->
                usersToReturn = (usersToReturn ?: emptyList()) + created
            }
        }
        return response
    }

    override suspend fun updateUser(id: Int, body: JsonObject): Response<AdminUserResponse> {
        val request = json.decodeFromJsonElement(UpdateAdminUserRequestSerializer, body)
        updatedUserRequests.add(id to request)
        updateUserResponse?.let { return it }
        val existing = usersToReturn?.firstOrNull { it.id == id }
        val updatedRoleId = request.roleId?.value ?: existing?.roleId ?: 2
        val updated = AdminUserResponseUser(
            id = id,
            username = request.username ?: existing?.username ?: "user$id",
            displayName = request.displayName ?: existing?.displayName ?: "User $id",
            roleId = updatedRoleId,
            roleName = if (updatedRoleId == 1) "Administrador" else "Trabalhador",
            isActive = when (request.isActive) {
                UpdateAdminUserRequest.IsActive._1 -> 1
                UpdateAdminUserRequest.IsActive._0 -> 0
                null -> existing?.isActive ?: 1
            },
            createdAt = existing?.createdAt ?: "2026-01-01T00:00:00.000Z",
        )
        usersToReturn = usersToReturn?.map { if (it.id == id) updated else it }
        return Response.success(AdminUserResponse(user = updated))
    }

    override suspend fun deleteUser(id: Int): Response<JsonObject> {
        deletedUserIds.add(id)
        val response = deleteUserResponse ?: Response.success(JsonObject(mapOf("success" to JsonPrimitive(true))))
        if (response.isSuccessful) {
            usersToReturn = usersToReturn?.filterNot { it.id == id }
        }
        return response
    }

    override suspend fun audit(
        page: Int?,
        limit: Int?,
        action: String?,
        entityType: String?,
        userId: Int?,
    ): Response<AuditResponse> {
        auditCalls.add(AuditCall(page, limit, action, entityType, userId))
        val response = auditResponse
        if (response != null) return response
        val data = auditToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(data)
    }

    companion object {
        val EMPTY_BODY = "".toResponseBody("application/json".toMediaType())

        fun defaultCategories(): List<AdminCategoryResponseCategory> = listOf(
            AdminCategoryResponseCategory(
                id = 1,
                parentCategoryId = null,
                namePt = "Higienização",
                nameEs = "Higienización",
                categoryType = AdminCategoryResponseCategory.CategoryType.check,
                sortOrder = 1,
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminCategoryResponseCategory(
                id = 2,
                parentCategoryId = null,
                namePt = "Refrigeração",
                nameEs = "Refrigeración",
                categoryType = AdminCategoryResponseCategory.CategoryType.temperature,
                sortOrder = 2,
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
        )

        fun defaultTasks(): List<AdminTasksResponseTasksInner> = listOf(
            AdminTasksResponseTasksInner(
                id = 10,
                categoryId = 1,
                namePt = "Limpeza de Bancadas",
                nameEs = "Limpieza de Mesas",
                temperatureReadings = 1,
                isActive = 1,
                createdByUserId = null,
                createdAt = "2026-01-01T00:00:00.000Z",
                taskType = AdminTasksResponseTasksInner.TaskType.check,
                categoryName = "Higienização",
            ),
            AdminTasksResponseTasksInner(
                id = 20,
                categoryId = 2,
                namePt = "Câmara Fria",
                nameEs = "Cámara Fría",
                temperatureReadings = 2,
                isActive = 1,
                createdByUserId = 42,
                createdAt = "2026-01-01T00:00:00.000Z",
                taskType = AdminTasksResponseTasksInner.TaskType.temperature,
                categoryName = "Refrigeração",
            ),
        )

        fun defaultDrivers(): List<AdminDriverResponseDriver> = listOf(
            AdminDriverResponseDriver(
                id = 1,
                name = "Carlos Fletero",
                licensePlate = null,
                driverType = AdminDriverResponseDriver.DriverType.fletero,
                isActive = 1,
                createdByUserId = 42,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminDriverResponseDriver(
                id = 2,
                name = "João Casa",
                licensePlate = "ABC-1234",
                driverType = AdminDriverResponseDriver.DriverType.casa,
                isActive = 1,
                createdByUserId = null,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminDriverResponseDriver(
                id = 3,
                name = "Inativo Fletero",
                licensePlate = null,
                driverType = AdminDriverResponseDriver.DriverType.fletero,
                isActive = 0,
                createdByUserId = 99,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
        )

        fun defaultVehicles(): List<AdminVehicleResponseVehicle> = listOf(
            AdminVehicleResponseVehicle(
                id = 1,
                description = "Caminhão 01",
                licensePlate = "ABC-1234",
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminVehicleResponseVehicle(
                id = 2,
                description = "Van de Entrega",
                licensePlate = "VAN-5678",
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminVehicleResponseVehicle(
                id = 3,
                description = "Fiorino Reserva",
                licensePlate = "FIO-9012",
                isActive = 0,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
        )

        fun defaultTimeSlots(): List<String> = listOf(
            "04:00",
            "04:30",
            "05:00",
            "05:30",
            "06:00",
            "06:30",
            "07:00",
        )

        fun defaultUsers(): List<AdminUserResponseUser> = listOf(
            AdminUserResponseUser(
                id = 1,
                username = "admin",
                displayName = "Administrador Principal",
                roleId = 1,
                roleName = "Administrador",
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminUserResponseUser(
                id = 42,
                username = "worker",
                displayName = "Operador Trabalhador",
                roleId = 2,
                roleName = "Trabalhador",
                isActive = 1,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
            AdminUserResponseUser(
                id = 99,
                username = "inativo",
                displayName = "Usuário Desativado",
                roleId = 2,
                roleName = "Trabalhador",
                isActive = 0,
                createdAt = "2026-01-01T00:00:00.000Z",
            ),
        )

        fun defaultAuditResponse(): AuditResponse = AuditResponse(
            logs = listOf(
                AuditResponseLogsInner(
                    id = 1,
                    userId = 1,
                    displayName = "Administrador Principal",
                    action = "login",
                    entityType = "auth",
                    entityId = 1,
                    ipAddress = "127.0.0.1",
                    details = "{\"method\":\"password\"}",
                    createdAt = "2026-09-22T10:00:00.000Z",
                ),
                AuditResponseLogsInner(
                    id = 2,
                    userId = 1,
                    displayName = "Administrador Principal",
                    action = "create",
                    entityType = "category",
                    entityId = 1,
                    ipAddress = "127.0.0.1",
                    details = "{\"namePt\":\"Higienização\"}",
                    createdAt = "2026-09-22T10:05:00.000Z",
                ),
            ),
            total = 2,
            page = 1,
            limit = 20,
            totalPages = 1,
        )
    }
}
