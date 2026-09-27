package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.contract.models.CreateAdminVehicleRequest
import com.trindade.app.contract.models.UpdateAdminVehicleRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class VehiclesViewModel @Inject constructor(
    private val repository: VehiclesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val vehicles: List<AdminVehicleResponseVehicle> = emptyList(),
        val role: String? = null,
        val currentUserId: Int? = null,
        val editingId: Int? = null,
        val description: String = "",
        val licensePlate: String = "",
        val deleteTarget: AdminVehicleResponseVehicle? = null,
        val error: String? = null,
        val refusedStatus: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        val isEditing: Boolean get() = editingId != null
        fun canEdit(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
        fun canToggle(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
        fun canDelete(vehicle: AdminVehicleResponseVehicle): Boolean = isAdmin
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
            val vehicles = repository.vehicles()
            if (profile == null || vehicles == null) {
                _state.update { it.copy(loading = false, error = UNREACHABLE, vehicles = emptyList()) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    vehicles = vehicles,
                    role = profile.role,
                    currentUserId = profile.id,
                    error = null,
                )
            }
        }
    }

    fun onDescriptionChange(value: String) = changeForm { copy(description = value) }
    fun onLicensePlateChange(value: String) = changeForm { copy(licensePlate = value) }

    fun edit(vehicle: AdminVehicleResponseVehicle) {
        val current = _state.value
        if (!current.canEdit(vehicle) || current.saving) return
        _state.update {
            it.copy(
                editingId = vehicle.id,
                description = vehicle.description,
                licensePlate = vehicle.licensePlate,
                error = null,
                refusedStatus = null,
            )
        }
    }

    fun cancelEdit() = _state.update {
        it.copy(
            editingId = null,
            description = "",
            licensePlate = "",
            error = null,
            refusedStatus = null,
        )
    }

    fun requestDelete(vehicle: AdminVehicleResponseVehicle) {
        val current = _state.value
        if (!current.canDelete(vehicle) || current.saving) return
        _state.update { it.copy(deleteTarget = vehicle, error = null, refusedStatus = null) }
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

    fun delete(vehicle: AdminVehicleResponseVehicle) {
        if (!_state.value.canDelete(vehicle)) return
        write { repository.delete(vehicle.id) }
    }

    fun toggle(vehicle: AdminVehicleResponseVehicle) {
        if (!_state.value.canToggle(vehicle)) return
        write {
            repository.update(
                vehicle.id,
                UpdateAdminVehicleRequest(
                    isActive = if (vehicle.isActive == 1) UpdateAdminVehicleRequest.IsActive._0 else UpdateAdminVehicleRequest.IsActive._1,
                ),
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.loading || current.saving || !current.isAdmin) return

        val desc = current.description.trim().ifEmpty { null }
        val plate = current.licensePlate.trim().ifEmpty { null }

        if (current.editingId == null) {
            if (desc == null) return validation(DESCRIPTION)
            if (plate == null) return validation(LICENSE_PLATE)
        } else {
            if (desc == null && plate == null) return validation(DESCRIPTION)
        }

        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = current.editingId?.let { id ->
                repository.update(
                    id,
                    UpdateAdminVehicleRequest(
                        description = desc,
                        licensePlate = plate,
                    ),
                )
            } ?: repository.create(
                CreateAdminVehicleRequest(
                    description = desc!!,
                    licensePlate = plate!!,
                ),
            )
            finishWrite(result)
        }
    }

    private fun write(call: suspend () -> VehicleWriteResult) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call()) }
    }

    private fun finishWrite(result: VehicleWriteResult) {
        when (result) {
            is VehicleWriteResult.Saved -> {
                _state.update {
                    it.copy(
                        saving = false,
                        editingId = null,
                        description = "",
                        licensePlate = "",
                        deleteTarget = null,
                    )
                }
                load()
            }
            is VehicleWriteResult.Refused -> _state.update {
                it.copy(saving = false, error = REFUSED, refusedStatus = result.status)
            }
            VehicleWriteResult.Unreachable -> _state.update {
                it.copy(saving = false, error = UNREACHABLE, refusedStatus = null)
            }
        }
    }

    private fun changeForm(change: UiState.() -> UiState) = _state.update { it.change().copy(error = null, refusedStatus = null) }
    private fun validation(message: String) = _state.update { it.copy(error = message, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val DESCRIPTION = "Enter a vehicle description."
        const val LICENSE_PLATE = "Enter a license plate."
        const val REFUSED = "The server refused this vehicle operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
