package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoriesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    internal var localeProvider: () -> Locale = { Locale.getDefault() }

    constructor(
        repository: CategoriesRepository,
        authRepository: AuthRepository,
        localeProvider: () -> Locale,
    ) : this(repository, authRepository) {
        this.localeProvider = localeProvider
    }

    private val isSpanish: Boolean get() = localeProvider().language.startsWith("es")

    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val categories: List<AdminCategoryResponseCategory> = emptyList(),
        val role: String? = null,
        val currentUserId: Int? = null,
        val editingId: Int? = null,
        val namePt: String = "",
        val nameEs: String = "",
        val categoryType: String = CHECK,
        val sortOrder: String = "0",
        val deleteTarget: AdminCategoryResponseCategory? = null,
        val isSpanish: Boolean = false,
        val error: String? = null,
        val refusedStatus: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        val isEditing: Boolean get() = editingId != null
        fun canEdit(category: AdminCategoryResponseCategory): Boolean = isAdmin
        fun canToggle(category: AdminCategoryResponseCategory): Boolean = isAdmin
        fun canDelete(category: AdminCategoryResponseCategory): Boolean = isAdmin
    }

    private val _state = MutableStateFlow(UiState(isSpanish = isSpanish))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var reading: Job? = null

    /** Reload on arrival and after writes; cancelling prevents stale responses replacing the newest catalog. */
    fun load() {
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, refusedStatus = null, isSpanish = isSpanish) }
        reading = viewModelScope.launch {
            val profile = authRepository.profile()
            val categories = repository.categories()
            if (profile == null || categories == null) {
                _state.update { it.copy(loading = false, error = UNREACHABLE, categories = emptyList()) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    categories = categories,
                    role = profile.role,
                    currentUserId = profile.id,
                    error = null,
                    isSpanish = isSpanish,
                )
            }
        }
    }

    fun onNamePtChange(value: String) = changeForm { copy(namePt = value) }
    fun onNameEsChange(value: String) = changeForm { copy(nameEs = value) }
    fun onNameChange(value: String) = changeForm {
        if (isSpanish) copy(nameEs = value) else copy(namePt = value)
    }
    fun onCategoryTypeChange(value: String) = changeForm { copy(categoryType = value) }
    fun onSortOrderChange(value: String) = changeForm { copy(sortOrder = value) }

    fun edit(category: AdminCategoryResponseCategory) {
        val current = _state.value
        if (!current.canEdit(category) || current.saving) return
        _state.update {
            it.copy(
                editingId = category.id,
                namePt = category.namePt,
                nameEs = category.nameEs,
                categoryType = category.categoryType.value,
                sortOrder = category.sortOrder.toString(),
                error = null,
                refusedStatus = null,
            )
        }
    }

    fun cancelEdit() = _state.update {
        it.copy(
            editingId = null,
            namePt = "",
            nameEs = "",
            categoryType = CHECK,
            sortOrder = "0",
            error = null,
            refusedStatus = null,
        )
    }

    fun requestDelete(category: AdminCategoryResponseCategory) {
        val current = _state.value
        if (!current.canDelete(category) || current.saving) return
        _state.update { it.copy(deleteTarget = category, error = null, refusedStatus = null) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val current = _state.value
        val target = current.deleteTarget ?: return
        if (!current.canDelete(target) || current.saving) return
        _state.update { it.copy(deleteTarget = null) }
        delete(target)
    }

    fun delete(category: AdminCategoryResponseCategory) {
        if (!_state.value.canDelete(category)) return
        write { repository.delete(category.id) }
    }

    fun toggle(category: AdminCategoryResponseCategory) {
        if (!_state.value.canToggle(category)) return
        write {
            repository.update(
                category.id,
                UpdateAdminCategoryRequest(
                    isActive = if (category.isActive == 1) UpdateAdminCategoryRequest.IsActive._0 else UpdateAdminCategoryRequest.IsActive._1,
                ),
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.loading || current.saving || !current.isAdmin) return

        val namePt = current.namePt.trim().ifEmpty { null }
        val nameEs = current.nameEs.trim().ifEmpty { null }
        if (namePt == null && nameEs == null) return validation(NAME)

        val createType = when (current.categoryType) {
            CHECK -> CreateAdminCategoryRequest.CategoryType.check
            TEMPERATURE -> CreateAdminCategoryRequest.CategoryType.temperature
            CHECK_ASSAI -> CreateAdminCategoryRequest.CategoryType.check_assai
            CHECK_NORMAL -> CreateAdminCategoryRequest.CategoryType.check_normal
            else -> return validation(CATEGORY_TYPE)
        }
        val updateType = when (current.categoryType) {
            CHECK -> UpdateAdminCategoryRequest.CategoryType.check
            TEMPERATURE -> UpdateAdminCategoryRequest.CategoryType.temperature
            CHECK_ASSAI -> UpdateAdminCategoryRequest.CategoryType.check_assai
            CHECK_NORMAL -> UpdateAdminCategoryRequest.CategoryType.check_normal
            else -> return validation(CATEGORY_TYPE)
        }

        val sort = current.sortOrder.trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: return validation(SORT_ORDER)

        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = if (current.editingId == null) {
                val req = if (isSpanish) {
                    CreateAdminCategoryRequest(
                        parentCategoryId = null,
                        namePt = null,
                        nameEs = nameEs ?: namePt,
                        categoryType = createType,
                        sortOrder = sort,
                    )
                } else {
                    CreateAdminCategoryRequest(
                        parentCategoryId = null,
                        namePt = namePt ?: nameEs,
                        nameEs = null,
                        categoryType = createType,
                        sortOrder = sort,
                    )
                }
                repository.create(req)
            } else {
                repository.update(
                    current.editingId,
                    UpdateAdminCategoryRequest(
                        namePt = namePt,
                        nameEs = nameEs,
                        categoryType = updateType,
                        sortOrder = sort,
                    ),
                )
            }
            finishWrite(result)
        }
    }

    private fun write(call: suspend () -> CategoryWriteResult) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call()) }
    }

    private fun finishWrite(result: CategoryWriteResult) {
        when (result) {
            is CategoryWriteResult.Saved -> {
                _state.update {
                    it.copy(
                        saving = false,
                        editingId = null,
                        namePt = "",
                        nameEs = "",
                        categoryType = CHECK,
                        sortOrder = "0",
                        deleteTarget = null,
                    )
                }
                load()
            }
            is CategoryWriteResult.Refused -> _state.update {
                it.copy(saving = false, error = REFUSED, refusedStatus = result.status)
            }
            CategoryWriteResult.Unreachable -> _state.update {
                it.copy(saving = false, error = UNREACHABLE, refusedStatus = null)
            }
        }
    }

    private fun changeForm(change: UiState.() -> UiState) = _state.update { it.change().copy(error = null, refusedStatus = null) }
    private fun validation(message: String) = _state.update { it.copy(error = message, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val CHECK = "check"
        const val TEMPERATURE = "temperature"
        const val CHECK_ASSAI = "check_assai"
        const val CHECK_NORMAL = "check_normal"
        const val NAME = "Enter a category name in Portuguese or Spanish."
        const val CATEGORY_TYPE = "Select a valid category type."
        const val SORT_ORDER = "Sort order must be a non-negative integer."
        const val REFUSED = "The server refused this category operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
