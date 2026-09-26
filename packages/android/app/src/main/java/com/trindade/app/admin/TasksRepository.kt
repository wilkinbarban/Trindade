package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTaskResponseTask
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.Response

/** Failure is not an empty catalog; refusals on writes retain their HTTP status for the UI. */
@Singleton
class TasksRepository @Inject constructor(private val api: AdminApi) {
    suspend fun categories(): List<AdminCategoryResponseCategory>? =
        runCatchingCancellable { api.categories() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.categories

    suspend fun tasks(): List<AdminTasksResponseTasksInner>? =
        runCatchingCancellable { api.tasks() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.tasks

    suspend fun create(body: CreateAdminTaskRequest): TaskWriteResult = write { api.createTask(body) }

    suspend fun update(id: Int, body: UpdateAdminTaskRequest): TaskWriteResult =
        write {
            // The generated numeric enum serializes as a quoted string; Zod requires 0 or 1.
            val fields = Json.encodeToJsonElement(UpdateAdminTaskRequest.serializer(), body).jsonObject.toMutableMap()
            body.isActive?.let { fields["is_active"] = JsonPrimitive(it.value) }
            api.updateTask(id, JsonObject(fields))
        }

    suspend fun delete(id: Int): TaskWriteResult {
        val response = runCatchingCancellable { api.deleteTask(id) }.getOrNull()
            ?: return TaskWriteResult.Unreachable
        return if (response.isSuccessful) TaskWriteResult.Deleted else TaskWriteResult.Refused(response.code())
    }

    private suspend fun write(call: suspend () -> Response<com.trindade.app.contract.models.AdminTaskResponse>): TaskWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return TaskWriteResult.Unreachable
        if (!response.isSuccessful) return TaskWriteResult.Refused(response.code())
        return response.body()?.task?.let(TaskWriteResult::Saved) ?: TaskWriteResult.Unreachable
    }
}

sealed interface TaskWriteResult {
    data class Saved(val task: AdminTaskResponseTask) : TaskWriteResult
    data object Deleted : TaskWriteResult
    data class Refused(val status: Int) : TaskWriteResult
    data object Unreachable : TaskWriteResult
}
