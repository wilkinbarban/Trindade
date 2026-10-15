package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the driver catalog state, inline form editing, and server sync.
 *
 * Implements permissions and business rules mirroring the web client (AdminDashboard.tsx):
 * - [isAdmin]: whether current session role is Administrador.
 * - [currentUserId]: fetched from the signed-in profile to evaluate ownership.
 * - [canEdit]: true if Administrador, or if Trabalhador who created this fletero driver
 *   (driver.driverType == fletero && driver.createdByUserId == currentUserId).
 * - Workers can only create and edit fletero drivers (never casa), and cannot toggle active status.
 * - Drivers cannot be deleted (no DELETE endpoint exists).
 */
@HiltViewModel
class DriversViewModel @Inject constructor(
    private val repository: DriversRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    enum class DriverTypeChoice {
        CASA,
        FLETERO,
    }

    data class FormState(
        val driverId: Int? = null,
        val name: String = "",
        val driverType: DriverTypeChoice = DriverTypeChoice.FLETERO,
        val saving: Boolean = false,
    ) {
        val isEditing: Boolean get() = driverId != null
        val canSubmit: Boolean get() = name.trim().isNotEmpty() && !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val drivers: List<AdminDriverResponseDriver> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val currentUserId: Int? = null,
        val message: String? = null,
        val isError: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN
        val isWorker: Boolean get() = role == RolePolicy.WORKER

        fun canEdit(driver: AdminDriverResponseDriver): Boolean =
            isAdmin || (isWorker && currentUserId != null &&
                driver.driverType == AdminDriverResponseDriver.DriverType.fletero &&
                driver.createdByUserId == currentUserId)

        fun canToggleActive(driver: AdminDriverResponseDriver): Boolean = isAdmin
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
            val drivers = repository.drivers()

            _uiState.update { current ->
                if (drivers == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = profile?.id ?: current.currentUserId,
                        message = "Não foi possível carregar os motoristas. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = profile?.id ?: current.currentUserId,
                        drivers = drivers,
                        message = null,
                        isError = false,
                    )
                }
            }
        }
    }

    fun openCreateForm() {
        _uiState.update {
            it.copy(
                form = FormState(driverType = DriverTypeChoice.FLETERO),
                message = null,
            )
        }
    }

    fun openEditForm(driver: AdminDriverResponseDriver) {
        if (!_uiState.value.canEdit(driver)) return
        val typeChoice = if (driver.driverType == AdminDriverResponseDriver.DriverType.casa) {
            DriverTypeChoice.CASA
        } else {
            DriverTypeChoice.FLETERO
        }
        _uiState.update {
            it.copy(
                form = FormState(
                    driverId = driver.id,
                    name = driver.name,
                    driverType = typeChoice,
                ),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
    }

    fun onNameChanged(name: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(name = name))
        }
    }

    fun onDriverTypeChanged(type: DriverTypeChoice) {
        if (!_uiState.value.isAdmin) return
        _uiState.update { current ->
            current.copy(form = current.form?.copy(driverType = type))
        }
    }

    fun saveDriver() {
        val form = _uiState.value.form ?: return
        if (!form.canSubmit) return

        val trimmedName = form.name.trim()
        val chosenType = if (_uiState.value.isAdmin) {
            form.driverType
        } else {
            DriverTypeChoice.FLETERO
        }

        _uiState.update { it.copy(form = form.copy(saving = true), message = null) }

        viewModelScope.launch {
            if (form.isEditing) {
                val driverId = form.driverId ?: return@launch
                val request = UpdateAdminDriverRequest(
                    name = trimmedName,
                    driverType = when (chosenType) {
                        DriverTypeChoice.CASA -> UpdateAdminDriverRequest.DriverType.casa
                        DriverTypeChoice.FLETERO -> UpdateAdminDriverRequest.DriverType.fletero
                    },
                )
                when (val result = repository.updateDriver(driverId, request)) {
                    is DriverWriteResult.Saved -> {
                        _uiState.update {
                            it.copy(
                                form = null,
                                message = "Motorista atualizado com sucesso.",
                                isError = false,
                            )
                        }
                        refreshDrivers()
                    }
                    is DriverWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível salvar o motorista (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is DriverWriteResult.Unreachable -> {
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
                val request = CreateAdminDriverRequest(
                    name = trimmedName,
                    driverType = when (chosenType) {
                        DriverTypeChoice.CASA -> CreateAdminDriverRequest.DriverType.casa
                        DriverTypeChoice.FLETERO -> CreateAdminDriverRequest.DriverType.fletero
                    },
                )
                when (val result = repository.createDriver(request)) {
                    is DriverWriteResult.Saved -> {
                        _uiState.update {
                            it.copy(
                                form = null,
                                message = "Motorista criado com sucesso.",
                                isError = false,
                            )
                        }
                        refreshDrivers()
                    }
                    is DriverWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível criar o motorista (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is DriverWriteResult.Unreachable -> {
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

    fun toggleDriverActive(driver: AdminDriverResponseDriver) {
        if (!_uiState.value.canToggleActive(driver)) return
        val newActive = if (driver.isActive == 1) 0 else 1

        viewModelScope.launch {
            val request = UpdateAdminDriverRequest(
                isActive = if (newActive == 1) UpdateAdminDriverRequest.IsActive._1 else UpdateAdminDriverRequest.IsActive._0,
            )
            when (repository.updateDriver(driver.id, request)) {
                is DriverWriteResult.Saved -> refreshDrivers()
                is DriverWriteResult.Refused -> {
                    _uiState.update {
                        it.copy(message = "Não foi possível alterar o status do motorista.", isError = true)
                    }
                }
                is DriverWriteResult.Unreachable -> {
                    _uiState.update {
                        it.copy(message = "Sem conexão com o servidor. Tente de novo.", isError = true)
                    }
                }
            }
        }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.form != null) {
            closeForm()
            return true
        }
        return false
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun refreshDrivers() {
        val drivers = repository.drivers()
        if (drivers != null) {
            _uiState.update { it.copy(drivers = drivers) }
        }
    }
}
