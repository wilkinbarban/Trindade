package com.trindade.app.admin

import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTaskResponseTask
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import com.trindade.app.network.UpdateAdminTaskRequestSerializer
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The data layer for catalog tasks and categories.
 *
 * Used by [TasksViewModel] to list categories and manage report tasks.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy so the caller can distinguish:
 * - A successful write carrying the updated/created task
 * - A server refusal with its HTTP status code (400, 403, 404, etc.)
 * - An unreachable/network error to offer retry
 */
@Singleton
class TasksRepository @Inject constructor(
    private val api: AdminApi,
    private val json: Json = NetworkModule.provideJson(),
) {

    /**
     * Lists all categories available for task assignment.
     */
    suspend fun categories(): List<AdminCategoryResponseCategory>? =
        runCatchingCancellable { api.categories() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.categories

    /**
     * Lists tasks. Server-side, an Administrador sees all tasks (active and inactive),
     * while a Trabalhador sees only active tasks.
     */
    suspend fun tasks(): List<AdminTasksResponseTasksInner>? =
        runCatchingCancellable { api.tasks() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.tasks

    /**
     * Creates a new report task under a category.
     */
    suspend fun createTask(body: CreateAdminTaskRequest): TaskWriteResult {
        val response = runCatchingCancellable { api.createTask(body) }.getOrNull()
            ?: return TaskWriteResult.Unreachable
        val task = response.body()?.task
        return if (response.isSuccessful && task != null) {
            TaskWriteResult.Saved(task)
        } else {
            TaskWriteResult.Refused(response.code())
        }
    }

    /**
     * Updates an existing report task.
     */
    suspend fun updateTask(id: Int, body: UpdateAdminTaskRequest): TaskWriteResult {
        val jsonObject = json.encodeToJsonElement(UpdateAdminTaskRequestSerializer, body) as JsonObject
        val response = runCatchingCancellable { api.updateTask(id, jsonObject) }.getOrNull()
            ?: return TaskWriteResult.Unreachable
        val task = response.body()?.task
        return if (response.isSuccessful && task != null) {
            TaskWriteResult.Saved(task)
        } else {
            TaskWriteResult.Refused(response.code())
        }
    }

    /**
     * Deletes a report task.
     * 400 means foreign key reference constraint (in use by reports).
     * 403 means permission denied (worker attempting to delete a task created by another user).
     * 404 means already deleted.
     */
    suspend fun deleteTask(id: Int): TaskLifecycleResult {
        val response = runCatchingCancellable { api.deleteTask(id) }.getOrNull()
            ?: return TaskLifecycleResult.Unreachable
        return if (response.isSuccessful) {
            TaskLifecycleResult.Changed
        } else {
            TaskLifecycleResult.Refused(response.code())
        }
    }
}

sealed interface TaskWriteResult {
    data class Saved(val task: AdminTaskResponseTask) : TaskWriteResult
    data class Refused(val statusCode: Int) : TaskWriteResult
    data object Unreachable : TaskWriteResult
}

sealed interface TaskLifecycleResult {
    data object Changed : TaskLifecycleResult
    data class Refused(val statusCode: Int) : TaskLifecycleResult
    data object Unreachable : TaskLifecycleResult
}
