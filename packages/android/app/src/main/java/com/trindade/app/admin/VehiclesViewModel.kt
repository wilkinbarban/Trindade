package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the vehicle fleet state, inline form editing, and server synchronization.
 *
 * Implements permissions and business rules mirroring the web client (AdminDashboard.tsx):
 * - [isAdmin]: whether current session role is Administrador.
 * - Vehicles management is admin-only: only an Administrador can list, create, edit, toggle active, or delete vehicles.
 * - Workers are excluded via admin tab and VM guard.
 */
@HiltViewModel
class VehiclesViewModel @Inject constructor(
    private val repository: VehiclesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class FormState(
        val vehicleId: Int? = null,
        val description: String = "",
        val licensePlate: String = "",
        val saving: Boolean = false,
    ) {
        val isEditing: Boolean get() = vehicleId != null

        val canSubmit: Boolean
            get() = description.trim().isNotEmpty() && licensePlate.trim().isNotEmpty() && !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val vehicles: List<AdminVehicleResponseVehicle> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val deleteConfirmVehicle: AdminVehicleResponseVehicle? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN

        fun canEdit(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
        fun canDelete(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
        fun canToggleActive(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
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
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        role = role,
                        vehicles = emptyList(),
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }

            val vehicles = repository.vehicles()
            _uiState.update { current ->
                if (vehicles == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        message = "Não foi possível carregar os veículos. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        vehicles = vehicles,
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

    fun openEditForm(vehicle: AdminVehicleResponseVehicle) {
        if (!_uiState.value.canEdit(vehicle)) return
        _uiState.update {
            it.copy(
                form = FormState(
                    vehicleId = vehicle.id,
                    description = vehicle.description,
                    licensePlate = vehicle.licensePlate,
                ),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
    }

    fun onDescriptionChanged(description: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(description = description))
        }
    }

    fun onLicensePlateChanged(licensePlate: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(licensePlate = licensePlate))
        }
    }

    fun saveVehicle() {
        val form = _uiState.value.form ?: return
        if (!form.canSubmit || !_uiState.value.isAdmin) return

        _uiState.update { it.copy(form = form.copy(saving = true), message = null) }

        viewModelScope.launch {
            if (form.isEditing) {
                val vehicleId = form.vehicleId ?: return@launch
                val request = UpdateAdminVehicleRequest(
                    description = form.description.trim(),
                    licensePlate = form.licensePlate.trim(),
                )
                when (val result = repository.updateVehicle(vehicleId, request)) {
                    is VehicleWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Veículo atualizado com sucesso.", isError = false) }
                        refreshVehicles()
                    }
                    is VehicleWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível salvar o veículo (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is VehicleWriteResult.Unreachable -> {
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
                val request = CreateAdminVehicleRequest(
                    description = form.description.trim(),
                    licensePlate = form.licensePlate.trim(),
                )
                when (val result = repository.createVehicle(request)) {
                    is VehicleWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Veículo criado com sucesso.", isError = false) }
                        refreshVehicles()
                    }
                    is VehicleWriteResult.Refused -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Não foi possível criar o veículo (${result.statusCode}).",
                                isError = true,
                            )
                        }
                    }
                    is VehicleWriteResult.Unreachable -> {
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

    fun toggleVehicleActive(vehicle: AdminVehicleResponseVehicle) {
        if (!_uiState.value.canToggleActive(vehicle)) return
        val newActive = if (vehicle.isActive == 1) 0 else 1

        viewModelScope.launch {
            val request = UpdateAdminVehicleRequest(
                isActive = if (newActive == 1) UpdateAdminVehicleRequest.IsActive._1 else UpdateAdminVehicleRequest.IsActive._0,
            )
            when (repository.updateVehicle(vehicle.id, request)) {
                is VehicleWriteResult.Saved -> refreshVehicles()
                is VehicleWriteResult.Refused -> {
                    _uiState.update { it.copy(message = "Não foi possível alterar o status do veículo.", isError = true) }
                }
                is VehicleWriteResult.Unreachable -> {
                    _uiState.update { it.copy(message = "Sem conexão com o servidor. Tente de novo.", isError = true) }
                }
            }
        }
    }

    fun requestDelete(vehicle: AdminVehicleResponseVehicle) {
        if (!_uiState.value.canDelete(vehicle)) return
        _uiState.update { it.copy(deleteConfirmVehicle = vehicle, message = null) }
    }

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmVehicle = null) }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.deleteConfirmVehicle != null) {
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
        val vehicle = _uiState.value.deleteConfirmVehicle ?: return
        if (!_uiState.value.isAdmin) return
        _uiState.update { it.copy(deleting = true) }

        viewModelScope.launch {
            when (val result = repository.deleteVehicle(vehicle.id)) {
                is VehicleLifecycleResult.Changed -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmVehicle = null,
                            deleting = false,
                            message = "Veículo excluído com sucesso.",
                            isError = false,
                        )
                    }
                    refreshVehicles()
                }
                is VehicleLifecycleResult.Refused -> {
                    val msg = if (result.statusCode == 400) {
                        "Não é possível excluir este item pois ele está sendo referenciado em outro lugar. Recomendamos desativá-lo em vez de excluí-lo."
                    } else {
                        "Não foi possível excluir o veículo (${result.statusCode})."
                    }
                    _uiState.update {
                        it.copy(
                            deleteConfirmVehicle = null,
                            deleting = false,
                            message = msg,
                            isError = true,
                        )
                    }
                }
                is VehicleLifecycleResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmVehicle = null,
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

    private suspend fun refreshVehicles() {
        val vehicles = repository.vehicles()
        if (vehicles != null) {
            _uiState.update { it.copy(vehicles = vehicles) }
        }
    }
}
