package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.CreateAdminCategoryRequest
import com.trindade.app.contract.models.UpdateAdminCategoryRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the category catalog state, inline form editing, and server synchronization.
 *
 * Implements permissions and business rules mirroring the web client (AdminDashboard.tsx):
 * - [isAdmin]: whether current session role is Administrador.
 * - Categories management is admin-only: only an Administrador can create, edit, toggle active, or delete categories.
 * - Workers can read categories through report generator/edit forms but must not access admin category manager.
 */
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoriesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class FormState(
        val categoryId: Int? = null,
        val parentCategoryId: Int? = null,
        val namePt: String = "",
        val nameEs: String = "",
        val categoryType: AdminCategoryResponseCategory.CategoryType = AdminCategoryResponseCategory.CategoryType.check,
        val sortOrder: String = "0",
        val saving: Boolean = false,
    ) {
        val isEditing: Boolean get() = categoryId != null

        val canSubmit: Boolean
            get() = (namePt.trim().isNotEmpty() || nameEs.trim().isNotEmpty()) && !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val categories: List<AdminCategoryResponseCategory> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val deleteConfirmCategory: AdminCategoryResponseCategory? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN

        fun canEdit(category: AdminCategoryResponseCategory): Boolean = isAdmin
        fun canDelete(category: AdminCategoryResponseCategory): Boolean = isAdmin
        fun canToggleActive(category: AdminCategoryResponseCategory): Boolean = isAdmin
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
            val categories = repository.categories()

            _uiState.update { current ->
                if (categories == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        message = "Não foi possível carregar as categorias. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        categories = categories,
                        message = null,
                        isError = false,
                    )
                }
            }
        }
    }

    fun openCreateForm() {
        if (!_uiState.value.isAdmin) return
        _uiState.update {
            it.copy(
                form = FormState(),
                message = null,
            )
        }
    }

    fun openEditForm(category: AdminCategoryResponseCategory) {
        if (!_uiState.value.canEdit(category)) return
        _uiState.update {
            it.copy(
                form = FormState(
                    categoryId = category.id,
                    parentCategoryId = category.parentCategoryId,
                    namePt = category.namePt,
                    nameEs = category.nameEs,
                    categoryType = category.categoryType,
                    sortOrder = category.sortOrder.toString(),
                ),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
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

    fun onCategoryTypeChanged(categoryType: AdminCategoryResponseCategory.CategoryType) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(categoryType = categoryType))
        }
    }

    fun onSortOrderChanged(sortOrder: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(sortOrder = sortOrder))
        }
    }

    fun onParentCategoryChanged(parentCategoryId: Int?) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(parentCategoryId = parentCategoryId))
        }
    }

    fun saveCategory() {
        val form = _uiState.value.form ?: return
        if (!form.canSubmit) return

        if (!_uiState.value.isAdmin || authRepository.sessionRole() != RolePolicy.ADMIN) {
            _uiState.update {
                it.copy(
                    role = authRepository.sessionRole(),
                    form = form.copy(saving = false),
                    message = "Acesso restrito a administradores.",
                    isError = true,
                )
            }
            return
        }

        _uiState.update { it.copy(form = form.copy(saving = true), message = null) }

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        form = form.copy(saving = false),
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }

            val order = form.sortOrder.trim().toIntOrNull() ?: 0
            val mappedType = when (form.categoryType) {
                AdminCategoryResponseCategory.CategoryType.check -> CreateAdminCategoryRequest.CategoryType.check
                AdminCategoryResponseCategory.CategoryType.temperature -> CreateAdminCategoryRequest.CategoryType.temperature
                AdminCategoryResponseCategory.CategoryType.check_assai -> CreateAdminCategoryRequest.CategoryType.check_assai
                AdminCategoryResponseCategory.CategoryType.check_normal -> CreateAdminCategoryRequest.CategoryType.check_normal
            }

            if (form.isEditing) {
                val categoryId = form.categoryId ?: return@launch
                val updateType = when (form.categoryType) {
                    AdminCategoryResponseCategory.CategoryType.check -> UpdateAdminCategoryRequest.CategoryType.check
                    AdminCategoryResponseCategory.CategoryType.temperature -> UpdateAdminCategoryRequest.CategoryType.temperature
                    AdminCategoryResponseCategory.CategoryType.check_assai -> UpdateAdminCategoryRequest.CategoryType.check_assai
                    AdminCategoryResponseCategory.CategoryType.check_normal -> UpdateAdminCategoryRequest.CategoryType.check_normal
                }
                val request = UpdateAdminCategoryRequest(
                    parentCategoryId = form.parentCategoryId,
                    namePt = form.namePt.trim().ifEmpty { null },
                    nameEs = form.nameEs.trim().ifEmpty { null },
                    categoryType = updateType,
                    sortOrder = order,
                )
                when (val result = repository.updateCategory(categoryId, request)) {
                    is CategoryWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Categoria atualizada com sucesso.", isError = false) }
                        refreshCategories()
                    }
                    is CategoryWriteResult.Refused -> {
                        val msg = when (result.statusCode) {
                            403 -> "Acesso negado: permissão insuficiente."
                            else -> "Não foi possível salvar a categoria (${result.statusCode})."
                        }
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = msg,
                                isError = true,
                            )
                        }
                    }
                    is CategoryWriteResult.Unreachable -> {
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
                val request = CreateAdminCategoryRequest(
                    parentCategoryId = form.parentCategoryId,
                    namePt = form.namePt.trim().ifEmpty { null },
                    nameEs = form.nameEs.trim().ifEmpty { null },
                    categoryType = mappedType,
                    sortOrder = order,
                )
                when (val result = repository.createCategory(request)) {
                    is CategoryWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Categoria criada com sucesso.", isError = false) }
                        refreshCategories()
                    }
                    is CategoryWriteResult.Refused -> {
                        val msg = when (result.statusCode) {
                            403 -> "Acesso negado: permissão insuficiente."
                            else -> "Não foi possível criar a categoria (${result.statusCode})."
                        }
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = msg,
                                isError = true,
                            )
                        }
                    }
                    is CategoryWriteResult.Unreachable -> {
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

    fun toggleCategoryActive(category: AdminCategoryResponseCategory) {
        if (!_uiState.value.canToggleActive(category) || authRepository.sessionRole() != RolePolicy.ADMIN) return
        val newActive = if (category.isActive == 1) 0 else 1

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }
            val request = UpdateAdminCategoryRequest(
                isActive = if (newActive == 1) UpdateAdminCategoryRequest.IsActive._1 else UpdateAdminCategoryRequest.IsActive._0,
            )
            when (val result = repository.updateCategory(category.id, request)) {
                is CategoryWriteResult.Saved -> refreshCategories()
                is CategoryWriteResult.Refused -> {
                    val msg = when (result.statusCode) {
                        403 -> "Acesso negado: permissão insuficiente."
                        else -> "Não foi possível alterar o status da categoria."
                    }
                    _uiState.update { it.copy(message = msg, isError = true) }
                }
                is CategoryWriteResult.Unreachable -> {
                    _uiState.update { it.copy(message = "Sem conexão com o servidor. Tente de novo.", isError = true) }
                }
            }
        }
    }

    fun requestDelete(category: AdminCategoryResponseCategory) {
        if (!_uiState.value.canDelete(category)) return
        _uiState.update { it.copy(deleteConfirmCategory = category, message = null) }
    }

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmCategory = null) }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.deleteConfirmCategory != null) {
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
        val category = _uiState.value.deleteConfirmCategory ?: return
        if (!_uiState.value.canDelete(category) || authRepository.sessionRole() != RolePolicy.ADMIN) return
        _uiState.update { it.copy(deleting = true) }

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        deleteConfirmCategory = null,
                        deleting = false,
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }
            when (val result = repository.deleteCategory(category.id)) {
                is CategoryLifecycleResult.Changed -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmCategory = null,
                            deleting = false,
                            message = "Categoria excluída com sucesso.",
                            isError = false,
                        )
                    }
                    refreshCategories()
                }
                is CategoryLifecycleResult.Refused -> {
                    val msg = when (result.statusCode) {
                        400 -> "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo."
                        403 -> "Acesso negado: permissão insuficiente."
                        else -> "Não foi possível excluir a categoria (${result.statusCode})."
                    }
                    _uiState.update {
                        it.copy(
                            deleteConfirmCategory = null,
                            deleting = false,
                            message = msg,
                            isError = true,
                        )
                    }
                }
                is CategoryLifecycleResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmCategory = null,
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

    private suspend fun refreshCategories() {
        val categories = repository.categories()
        if (categories != null) {
            _uiState.update { it.copy(categories = categories) }
        }
    }
}
