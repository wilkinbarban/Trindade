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
import com.trindade.app.contract.models.AuditResponse
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The admin catalog management surface.
 *
 * Covers `/api/admin/tasks`, `/api/admin/categories`, `/api/admin/drivers`, `/api/admin/vehicles`,
 * and `/api/admin/time-slots`.
 *
 * Gated server-side by `catalogGuard` (for tasks and drivers)
 * and `adminGuard` (for category mutations, task deletion, activation toggles, vehicles, and time slots).
 */
interface AdminApi {

    @GET("api/admin/categories")
    suspend fun categories(): Response<AdminCategoriesResponse>

    @POST("api/admin/categories")
    suspend fun createCategory(@Body body: CreateAdminCategoryRequest): Response<AdminCategoryResponse>

    @PATCH("api/admin/categories/{id}")
    suspend fun updateCategory(
        @Path("id") id: Int,
        @Body body: JsonObject,
    ): Response<AdminCategoryResponse>

    suspend fun updateCategory(
        id: Int,
        body: UpdateAdminCategoryRequest,
    ): Response<AdminCategoryResponse> = updateCategory(
        id = id,
        body = Json.encodeToJsonElement(UpdateAdminCategoryRequestSerializer, body) as JsonObject,
    )

    @DELETE("api/admin/categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): Response<JsonObject>

    @GET("api/admin/tasks")
    suspend fun tasks(): Response<AdminTasksResponse>

    @POST("api/admin/tasks")
    suspend fun createTask(@Body body: CreateAdminTaskRequest): Response<AdminTaskResponse>

    @PATCH("api/admin/tasks/{id}")
    suspend fun updateTask(
        @Path("id") id: Int,
        @Body body: JsonObject,
    ): Response<AdminTaskResponse>

    suspend fun updateTask(
        id: Int,
        body: UpdateAdminTaskRequest,
    ): Response<AdminTaskResponse> = updateTask(
        id = id,
        body = Json.encodeToJsonElement(UpdateAdminTaskRequestSerializer, body) as JsonObject,
    )

    @DELETE("api/admin/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Int): Response<SuccessResponse>

    @GET("api/admin/drivers")
    suspend fun drivers(): Response<AdminDriversResponse>

    @POST("api/admin/drivers")
    suspend fun createDriver(@Body body: CreateAdminDriverRequest): Response<AdminDriverResponse>

    @PATCH("api/admin/drivers/{id}")
    suspend fun updateDriver(
        @Path("id") id: Int,
        @Body body: JsonObject,
    ): Response<AdminDriverResponse>

    suspend fun updateDriver(
        id: Int,
        body: UpdateAdminDriverRequest,
    ): Response<AdminDriverResponse> = updateDriver(
        id = id,
        body = Json.encodeToJsonElement(UpdateAdminDriverRequestSerializer, body) as JsonObject,
    )

    @GET("api/admin/vehicles")
    suspend fun vehicles(): Response<AdminVehiclesResponse>

    @POST("api/admin/vehicles")
    suspend fun createVehicle(@Body body: CreateAdminVehicleRequest): Response<AdminVehicleResponse>

    @PATCH("api/admin/vehicles/{id}")
    suspend fun updateVehicle(
        @Path("id") id: Int,
        @Body body: JsonObject,
    ): Response<AdminVehicleResponse>

    suspend fun updateVehicle(
        id: Int,
        body: UpdateAdminVehicleRequest,
    ): Response<AdminVehicleResponse> = updateVehicle(
        id = id,
        body = Json.encodeToJsonElement(UpdateAdminVehicleRequestSerializer, body) as JsonObject,
    )

    @DELETE("api/admin/vehicles/{id}")
    suspend fun deleteVehicle(@Path("id") id: Int): Response<JsonObject>

    @GET("api/admin/time-slots")
    suspend fun timeSlots(): Response<TimeSlotsResponse>

    @PUT("api/admin/time-slots")
    suspend fun updateTimeSlots(@Body body: UpdateTimeSlotsRequest): Response<TimeSlotsResponse>

    @GET("api/admin/users")
    suspend fun users(): Response<AdminUsersResponse>

    @POST("api/admin/users")
    suspend fun createUser(@Body body: JsonObject): Response<AdminUserResponse>

    suspend fun createUser(
        body: CreateAdminUserRequest,
    ): Response<AdminUserResponse> = createUser(
        body = Json.encodeToJsonElement(CreateAdminUserRequestSerializer, body) as JsonObject,
    )

    @PATCH("api/admin/users/{id}")
    suspend fun updateUser(
        @Path("id") id: Int,
        @Body body: JsonObject,
    ): Response<AdminUserResponse>

    suspend fun updateUser(
        id: Int,
        body: UpdateAdminUserRequest,
    ): Response<AdminUserResponse> = updateUser(
        id = id,
        body = Json.encodeToJsonElement(UpdateAdminUserRequestSerializer, body) as JsonObject,
    )

    @DELETE("api/admin/users/{id}")
    suspend fun deleteUser(@Path("id") id: Int): Response<JsonObject>

    @GET("api/admin/audit")
    suspend fun audit(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("action") action: String? = null,
        @Query("entityType") entityType: String? = null,
        @Query("userId") userId: Int? = null,
    ): Response<AuditResponse>

    suspend fun auditLogs(
        page: Int? = null,
        limit: Int? = null,
        action: String? = null,
        entityType: String? = null,
        userId: Int? = null,
    ): Response<AuditResponse> = audit(
        page = page,
        limit = limit,
        action = action,
        entityType = entityType,
        userId = userId,
    )
}

/**
 * Root [UpdateAdminDriverRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [UpdateAdminDriverRequest.IsActive] as an enum with values `"0"`
 * and `"1"`, which encodes as JSON strings by default. The backend expects numeric literals `0` or `1`.
 *
 * This serializer transforms `is_active` to a numeric [JsonPrimitive] on serialization and converts
 * incoming numeric tokens to string enum values on deserialization.
 */
object UpdateAdminDriverRequestSerializer : KSerializer<UpdateAdminDriverRequest> {
    private val delegate: KSerializer<UpdateAdminDriverRequest> = UpdateAdminDriverRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: UpdateAdminDriverRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1" -> JsonPrimitive(1)
                        "0" -> JsonPrimitive(0)
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateAdminDriverRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1", "true" -> JsonPrimitive("1")
                        "0", "false" -> JsonPrimitive("0")
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        }
        return delegate.deserialize(decoder)
    }
}

/**
 * Root [UpdateAdminCategoryRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [UpdateAdminCategoryRequest.IsActive] as an enum with values `"0"`
 * and `"1"`, which encodes as JSON strings by default. The backend expects numeric literals `0` or `1`.
 *
 * This serializer transforms `is_active` to a numeric [JsonPrimitive] on serialization and converts
 * incoming numeric tokens to string enum values on deserialization.
 */
object UpdateAdminCategoryRequestSerializer : KSerializer<UpdateAdminCategoryRequest> {
    private val delegate: KSerializer<UpdateAdminCategoryRequest> = UpdateAdminCategoryRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: UpdateAdminCategoryRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1" -> JsonPrimitive(1)
                        "0" -> JsonPrimitive(0)
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateAdminCategoryRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1", "true" -> JsonPrimitive("1")
                        "0", "false" -> JsonPrimitive("0")
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        }
        return delegate.deserialize(decoder)
    }
}

/**
 * Root [UpdateAdminVehicleRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [UpdateAdminVehicleRequest.IsActive] as an enum with values `"0"`
 * and `"1"`, which encodes as JSON strings by default. The backend expects numeric literals `0` or `1`.
 *
 * This serializer transforms `is_active` to a numeric [JsonPrimitive] on serialization and converts
 * incoming numeric tokens to string enum values on deserialization.
 */
object UpdateAdminVehicleRequestSerializer : KSerializer<UpdateAdminVehicleRequest> {
    private val delegate: KSerializer<UpdateAdminVehicleRequest> = UpdateAdminVehicleRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: UpdateAdminVehicleRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1" -> JsonPrimitive(1)
                        "0" -> JsonPrimitive(0)
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateAdminVehicleRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1", "true" -> JsonPrimitive("1")
                        "0", "false" -> JsonPrimitive("0")
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        }
        return delegate.deserialize(decoder)
    }
}

/**
 * Root [CreateAdminUserRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [CreateAdminUserRequest.RoleId] as an enum with values `"1"`
 * and `"2"`, which encodes as JSON strings by default. The backend expects numeric literals `1` or `2`.
 *
 * This serializer transforms `role_id` to a numeric [JsonPrimitive] on serialization and converts
 * incoming numeric tokens to string enum values on deserialization.
 */
object CreateAdminUserRequestSerializer : KSerializer<CreateAdminUserRequest> {
    private val delegate: KSerializer<CreateAdminUserRequest> = CreateAdminUserRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: CreateAdminUserRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject && "role_id" in element) {
                val roleElement = element["role_id"]
                if (roleElement is JsonPrimitive && roleElement !is JsonNull) {
                    val transformed = when (roleElement.content) {
                        "1" -> JsonPrimitive(1)
                        "2" -> JsonPrimitive(2)
                        else -> roleElement
                    }
                    val map = element.toMutableMap()
                    map["role_id"] = transformed
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): CreateAdminUserRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject && "role_id" in element) {
                val roleElement = element["role_id"]
                if (roleElement is JsonPrimitive && roleElement !is JsonNull) {
                    val transformed = when (roleElement.content) {
                        "1" -> JsonPrimitive("1")
                        "2" -> JsonPrimitive("2")
                        else -> roleElement
                    }
                    val map = element.toMutableMap()
                    map["role_id"] = transformed
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        }
        return delegate.deserialize(decoder)
    }
}

/**
 * Root [UpdateAdminUserRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [UpdateAdminUserRequest.RoleId] and [UpdateAdminUserRequest.IsActive]
 * as enums with string serial names (`"1"`, `"2"` and `"0"`, `"1"`), which encode as JSON strings by default.
 * The backend expects numeric literals `1`/`2` and `0`/`1`.
 *
 * This serializer transforms `role_id` and `is_active` to numeric [JsonPrimitive]s on serialization and converts
 * incoming numeric tokens to string enum values on deserialization.
 */
object UpdateAdminUserRequestSerializer : KSerializer<UpdateAdminUserRequest> {
    private val delegate: KSerializer<UpdateAdminUserRequest> = UpdateAdminUserRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: UpdateAdminUserRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject) {
                var modified = false
                val map = element.toMutableMap()
                if ("role_id" in element) {
                    val roleElement = element["role_id"]
                    if (roleElement is JsonPrimitive && roleElement !is JsonNull) {
                        val transformed = when (roleElement.content) {
                            "1" -> JsonPrimitive(1)
                            "2" -> JsonPrimitive(2)
                            else -> roleElement
                        }
                        map["role_id"] = transformed
                        modified = true
                    }
                }
                if ("is_active" in element) {
                    val activeElement = element["is_active"]
                    if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                        val transformed = when (activeElement.content) {
                            "1" -> JsonPrimitive(1)
                            "0" -> JsonPrimitive(0)
                            else -> activeElement
                        }
                        map["is_active"] = transformed
                        modified = true
                    }
                }
                if (modified) {
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateAdminUserRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject) {
                var modified = false
                val map = element.toMutableMap()
                if ("role_id" in element) {
                    val roleElement = element["role_id"]
                    if (roleElement is JsonPrimitive && roleElement !is JsonNull) {
                        val transformed = when (roleElement.content) {
                            "1" -> JsonPrimitive("1")
                            "2" -> JsonPrimitive("2")
                            else -> roleElement
                        }
                        map["role_id"] = transformed
                        modified = true
                    }
                }
                if ("is_active" in element) {
                    val activeElement = element["is_active"]
                    if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                        val transformed = when (activeElement.content) {
                            "1", "true" -> JsonPrimitive("1")
                            "0", "false" -> JsonPrimitive("0")
                            else -> activeElement
                        }
                        map["is_active"] = transformed
                        modified = true
                    }
                }
                if (modified) {
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        }
        return delegate.deserialize(decoder)
    }
}
