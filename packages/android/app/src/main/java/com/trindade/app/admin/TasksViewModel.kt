package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.contract.models.CreateAdminTaskRequest
import com.trindade.app.contract.models.UpdateAdminTaskRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the task catalog state, inline form editing, and server sync.
 *
 * Implements permissions and business rules mirroring the web client (AdminDashboard.tsx):
 * - [isAdmin]: whether current session role is Administrador.
 * - [currentUserId]: fetched from the signed-in profile to evaluate ownership.
 * - [canEditTask]: true if Administrador, or if Trabalhador who created the task (task.createdByUserId == currentUserId).
 * - Workers can only edit/delete their own tasks, cannot toggle active/inactive status (server refuses with 403).
 */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TasksRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class FormState(
        val taskId: Int? = null,
        val categoryId: Int? = null,
        val namePt: String = "",
        val nameEs: String = "",
        val temperatureReadings: Int = 1,
        val saving: Boolean = false,
    ) {
        val isEditing: Boolean get() = taskId != null

        val canSubmit: Boolean
            get() = categoryId != null &&
                (namePt.trim().isNotEmpty() || nameEs.trim().isNotEmpty()) &&
                !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val tasks: List<AdminTasksResponseTasksInner> = emptyList(),
        val categories: List<AdminCategoryResponseCategory> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val currentUserId: Int? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val deleteConfirmTask: AdminTasksResponseTasksInner? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN
        val isWorker: Boolean get() = role == RolePolicy.WORKER

        fun canEdit(task: AdminTasksResponseTasksInner): Boolean =
            isAdmin || (isWorker && currentUserId != null && task.createdByUserId == currentUserId)

        fun canDelete(task: AdminTasksResponseTasksInner): Boolean =
            isAdmin || (isWorker && currentUserId != null && task.createdByUserId == currentUserId)

        fun canToggleActive(task: AdminTasksResponseTasksInner): Boolean = isAdmin
    }

    private val _uiState = MutableStateFlow(UiState(role = authRepository.sessionRole()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        _uiState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val role = authRepository.sessionRole()
            val profile = authRepository.profile()
            val categories = repository.categories() ?: emptyList()
            val tasks = repository.tasks()

            _uiState.update { current ->
                if (tasks == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = profile?.id ?: current.currentUserId,
                        message = "Não foi possível carregar as tarefas. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = profile?.id ?: current.currentUserId,
                        categories = categories,
                        tasks = tasks,
                        message = null,
                        isError = false,
                    )
                }
            }
        }
    }

    fun openCreateForm() {
        val firstCatId = _uiState.value.categories.firstOrNull()?.id
        _uiState.update {
            it.copy(
                form = FormState(categoryId = firstCatId),
                message = null,
            )
        }
    }

    fun openEditForm(task: AdminTasksResponseTasksInner) {
        if (!_uiState.value.canEdit(task)) return
        _uiState.update {
            it.copy(
                form = FormState(
                    taskId = task.id,
                    categoryId = task.categoryId,
                    namePt = task.namePt,
                    nameEs = task.nameEs,
                    temperatureReadings = task.temperatureReadings.coerceIn(1, 3),
                ),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
    }

    fun onCategoryChanged(categoryId: Int) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(categoryId = categoryId))
        }
    }

    fun onNamePtChanged(namePt: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(namePt = namePt))
        }
    }

    fun onNameEsChanged(nameEs: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(nameEs = nameEs))
        }
    }

    fun onTemperatureReadingsChanged(readings: Int) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(temperatureReadings = readings.coerceIn(1, 3)))
        }
    }

    fun saveTask() {
        val form = _uiState.value.form ?: return
        if (!form.canSubmit) return

        val categoryId = form.categoryId ?: return
        _uiState.update { it.copy(form = form.copy(saving = true), message = null) }

        viewModelScope.launch {
            val category = _uiState.value.categories.find { it.id == categoryId }
            val readings = if (category?.categoryType == AdminCategoryResponseCategory.CategoryType.temperature) {
                form.temperatureReadings
            } else {
                null
            }

            if (form.isEditing) {
                val taskId = form.taskId ?: return@launch
                val request = UpdateAdminTaskRequest(
                    categoryId = categoryId,
                    namePt = form.namePt.trim().ifEmpty { null },
                    nameEs = form.nameEs.trim().ifEmpty { null },
                    temperatureReadings = readings,
                )
                when (val result = repository.updateTask(taskId, request)) {
                    is TaskWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Tarefa atualizada com sucesso.", isError = false) }
                        refreshTasks()
                    }
                    is TaskWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível salvar a tarefa (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is TaskWriteResult.Unreachable -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Sem conexão com o servidor. Tente de novo.",
                                isError = true,
                            )
                        }
                    }
                }
            } else {
                val request = CreateAdminTaskRequest(
                    categoryId = categoryId,
                    namePt = form.namePt.trim().ifEmpty { null },
                    nameEs = form.nameEs.trim().ifEmpty { null },
                    temperatureReadings = readings ?: 1,
                )
                when (val result = repository.createTask(request)) {
                    is TaskWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Tarefa criada com sucesso.", isError = false) }
                        refreshTasks()
                    }
                    is TaskWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível criar a tarefa (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is TaskWriteResult.Unreachable -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Sem conexão com o servidor. Tente de novo.",
                                isError = true,
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleTaskActive(task: AdminTasksResponseTasksInner) {
        if (!_uiState.value.canToggleActive(task)) return
        val newActive = if (task.isActive == 1) 0 else 1

        viewModelScope.launch {
            val request = UpdateAdminTaskRequest(
                isActive = if (newActive == 1) UpdateAdminTaskRequest.IsActive._1 else UpdateAdminTaskRequest.IsActive._0,
            )
            when (repository.updateTask(task.id, request)) {
                is TaskWriteResult.Saved -> refreshTasks()
                is TaskWriteResult.Refused -> {
                    _uiState.update { it.copy(message = "Não foi possível alterar o status da tarefa.", isError = true) }
                }
                is TaskWriteResult.Unreachable -> {
                    _uiState.update { it.copy(message = "Sem conexão com o servidor. Tente de novo.", isError = true) }
                }
            }
        }
    }

    fun requestDelete(task: AdminTasksResponseTasksInner) {
        if (!_uiState.value.canDelete(task)) return
        _uiState.update { it.copy(deleteConfirmTask = task, message = null) }
    }

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmTask = null) }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.deleteConfirmTask != null) {
            cancelDelete()
            return true
        }
        if (_uiState.value.form != null) {
            closeForm()
            return true
        }
        return false
    }

    fun confirmDelete() {
        val task = _uiState.value.deleteConfirmTask ?: return
        _uiState.update { it.copy(deleting = true) }

        viewModelScope.launch {
            when (val result = repository.deleteTask(task.id)) {
                is TaskLifecycleResult.Changed -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmTask = null,
                            deleting = false,
                            message = "Tarefa excluída com sucesso.",
                            isError = false,
                        )
                    }
                    refreshTasks()
                }
                is TaskLifecycleResult.Refused -> {
                    val msg = if (result.statusCode == 400) {
                        "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo."
                    } else {
                        "Não foi possível excluir a tarefa (${result.statusCode})."
                    }
                    _uiState.update {
                        it.copy(
                            deleteConfirmTask = null,
                            deleting = false,
                            message = msg,
                            isError = true,
                        )
                    }
                }
                is TaskLifecycleResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmTask = null,
                            deleting = false,
                            message = "Sem conexão com o servidor. Tente de novo.",
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun refreshTasks() {
        val tasks = repository.tasks()
        if (tasks != null) {
            _uiState.update { it.copy(tasks = tasks) }
        }
    }
}
