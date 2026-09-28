package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTaskResponseTask
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val categories: List<AdminCategoryResponseCategory> = emptyList(),
        val tasks: List<AdminTasksResponseTasksInner> = emptyList(),
        val role: String? = null,
        val currentUserId: Int? = null,
        val editingId: Int? = null,
        val categoryId: String = "",
        val namePt: String = "",
        val nameEs: String = "",
        val temperatureReadings: String = "1",
        val deleteTarget: AdminTasksResponseTasksInner? = null,
        val error: String? = null,
        val refusedStatus: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        fun canEdit(task: AdminTasksResponseTasksInner): Boolean =
            isAdmin || (role == WORKER && currentUserId != null && task.isActive == 1 && task.createdByUserId == currentUserId)
        fun canToggle(task: AdminTasksResponseTasksInner): Boolean = isAdmin
        fun canDelete(task: AdminTasksResponseTasksInner): Boolean = isAdmin
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var reading: Job? = null

    /** Reload on arrival and after writes; cancelling prevents stale responses replacing the newest catalog. */
    fun load() {
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, refusedStatus = null) }
        reading = viewModelScope.launch {
            val profile = authRepository.profile()
            val categories = repository.categories()
            val tasks = repository.tasks()
            if (profile == null || categories == null || tasks == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = UNREACHABLE,
                        categories = emptyList(),
                        tasks = emptyList(),
                        role = null,
                        currentUserId = null,
                        deleteTarget = null,
                    )
                }
                return@launch
            }
            val currentTarget = _state.value.deleteTarget
            val retainedTarget = if (currentTarget != null && profile.role == ADMIN) {
                tasks.firstOrNull { it.id == currentTarget.id }
            } else null
            _state.update {
                it.copy(
                    loading = false,
                    categories = categories,
                    tasks = tasks,
                    role = profile.role,
                    currentUserId = profile.id,
                    deleteTarget = retainedTarget,
                    error = null,
                )
            }
        }
    }

    fun onCategoryChange(value: String) = changeForm { copy(categoryId = value) }
    fun onNamePtChange(value: String) = changeForm { copy(namePt = value) }
    fun onNameEsChange(value: String) = changeForm { copy(nameEs = value) }
    fun onTemperatureReadingsChange(value: String) = changeForm { copy(temperatureReadings = value) }

    fun edit(task: AdminTasksResponseTasksInner) {
        if (_state.value.loading || !_state.value.canEdit(task) || _state.value.saving) return
        _state.update { it.copy(editingId = task.id, categoryId = task.categoryId.toString(),
            namePt = task.namePt, nameEs = task.nameEs, temperatureReadings = task.temperatureReadings.toString(), error = null) }
    }

    fun cancelEdit() = _state.update {
        it.copy(editingId = null, categoryId = "", namePt = "", nameEs = "", temperatureReadings = "1", error = null)
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.loading) return
        if (current.role != ADMIN && current.role != WORKER) return
        if (current.role == WORKER && current.editingId != null) {
            val target = current.tasks.firstOrNull { it.id == current.editingId }
            if (target == null || !current.canEdit(target)) return
        }
        val categoryId = current.categoryId.toIntOrNull()
        val category = current.categories.firstOrNull { it.id == categoryId }
        if (category == null) return validation(CATEGORY)
        val namePt = current.namePt.takeIf(String::isNotBlank)?.trim()
        val nameEs = current.nameEs.takeIf(String::isNotBlank)?.trim()
        if (namePt == null && nameEs == null) return validation(NAME)
        val readings = if (category.categoryType == AdminCategoryResponseCategory.CategoryType.temperature) {
            current.temperatureReadings.toIntOrNull()?.takeIf { it in MIN_READINGS..MAX_READINGS }
                ?: return validation(TEMPERATURE_READINGS)
        } else 1
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = if (current.editingId == null) {
                repository.create(CreateAdminTaskRequest(category.id, namePt, nameEs, readings))
            } else {
                repository.update(current.editingId, UpdateAdminTaskRequest(
                    categoryId = category.id, namePt = namePt, nameEs = nameEs,
                    temperatureReadings = readings,
                ))
            }
            finishWrite(result)
        }
    }

    fun toggle(task: AdminTasksResponseTasksInner) {
        if (_state.value.loading || !_state.value.canToggle(task) || _state.value.saving) return
        write { repository.update(task.id, UpdateAdminTaskRequest(
            isActive = if (task.isActive == 1) UpdateAdminTaskRequest.IsActive._0 else UpdateAdminTaskRequest.IsActive._1,
        )) }
    }

    fun requestDelete(task: AdminTasksResponseTasksInner) {
        val current = _state.value
        if (current.loading || current.saving) return
        val currentTask = current.tasks.firstOrNull { it.id == task.id } ?: return
        if (!current.canDelete(currentTask)) return
        _state.update { it.copy(deleteTarget = currentTask, error = null, refusedStatus = null) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val current = _state.value
        val target = current.deleteTarget ?: return
        if (current.loading || current.saving) return
        val currentTask = current.tasks.firstOrNull { it.id == target.id }
        _state.update { it.copy(deleteTarget = null) }
        if (currentTask == null || !current.canDelete(currentTask)) return
        delete(currentTask)
    }

    private fun delete(task: AdminTasksResponseTasksInner) {
        if (!_state.value.canDelete(task)) return
        write { repository.delete(task.id) }
    }

    private fun write(call: suspend () -> TaskWriteResult) {
        if (_state.value.saving || _state.value.loading) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call()) }
    }

    private suspend fun finishWrite(result: TaskWriteResult) {
        when (result) {
            is TaskWriteResult.Saved, TaskWriteResult.Deleted -> {
                _state.update {
                    it.copy(
                        saving = false,
                        editingId = null,
                        categoryId = "",
                        namePt = "",
                        nameEs = "",
                        temperatureReadings = "1",
                        deleteTarget = null,
                    )
                }
                load()
            }
            is TaskWriteResult.Refused -> _state.update { it.copy(saving = false, error = REFUSED, refusedStatus = result.status) }
            TaskWriteResult.Unreachable -> _state.update { it.copy(saving = false, error = UNREACHABLE) }
        }
    }

    private fun changeForm(change: UiState.() -> UiState) = _state.update { it.changeForm(change) }
    private fun UiState.changeForm(change: UiState.() -> UiState) = change().copy(error = null, refusedStatus = null)
    private fun validation(message: String) = _state.update { it.copy(error = message, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val MIN_READINGS = 1
        const val MAX_READINGS = 3
        const val CATEGORY = "Select a valid category."
        const val NAME = "Enter a name in Portuguese or Spanish."
        const val TEMPERATURE_READINGS = "Temperature tasks require 1 to 3 readings."
        const val REFUSED = "The server refused this task operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
