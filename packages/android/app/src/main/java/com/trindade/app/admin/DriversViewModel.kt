package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DriversViewModel @Inject constructor(
    private val repository: DriversRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val drivers: List<AdminDriverResponseDriver> = emptyList(),
        val role: String? = null,
        val currentUserId: Int? = null,
        val editingId: Int? = null,
        val name: String = "",
        val licensePlate: String = "",
        val driverType: String = "fletero",
        val error: String? = null,
        val refusedStatus: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        fun canEdit(driver: AdminDriverResponseDriver): Boolean = isAdmin ||
            (role == WORKER && driver.driverType == AdminDriverResponseDriver.DriverType.fletero &&
                currentUserId != null && driver.createdByUserId == currentUserId)
        fun canToggle(driver: AdminDriverResponseDriver): Boolean = isAdmin
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var reading: Job? = null

    /** Refresh on entry/write; an older request must not replace the latest driver list. */
    fun load() {
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, refusedStatus = null) }
        reading = viewModelScope.launch {
            val profile = authRepository.profile()
            val drivers = repository.drivers()
            if (profile == null || drivers == null) {
                _state.update { it.copy(loading = false, drivers = emptyList(), role = null, currentUserId = null, error = UNREACHABLE) }
                return@launch
            }
            _state.update { it.copy(loading = false, drivers = drivers, role = profile.role,
                currentUserId = profile.id, error = null) }
        }
    }

    fun onNameChange(value: String) = changeForm { copy(name = value) }
    fun onLicensePlateChange(value: String) = changeForm { copy(licensePlate = value) }
    fun onDriverTypeChange(value: String) = changeForm {
        copy(driverType = if (isAdmin) value else FLETERO)
    }

    fun edit(driver: AdminDriverResponseDriver) {
        val current = _state.value
        if (current.loading || !current.canEdit(driver) || current.saving) return
        _state.update { it.copy(editingId = driver.id, name = driver.name,
            licensePlate = driver.licensePlate.orEmpty(), driverType = driver.driverType.value, error = null) }
    }

    fun cancelEdit() = _state.update {
        it.copy(editingId = null, name = "", licensePlate = "", driverType = FLETERO, error = null, refusedStatus = null)
    }

    fun save() {
        val current = _state.value
        if (current.loading || current.saving || (current.role != ADMIN && current.role != WORKER)) return
        val name = current.name.trim().takeIf(String::isNotEmpty) ?: return validation(NAME)
        val type = if (current.isAdmin) current.driverType else FLETERO
        if (type != CASA && type != FLETERO) return validation(DRIVER_TYPE)
        val driverType = when (type) {
            CASA -> CreateAdminDriverRequest.DriverType.casa
            else -> CreateAdminDriverRequest.DriverType.fletero
        }
        val plate = current.licensePlate.trim().ifEmpty { null }
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = current.editingId?.let { id ->
                repository.update(id, UpdateAdminDriverRequest(
                    name = name, licensePlate = plate,
                    driverType = if (current.isAdmin) UpdateAdminDriverRequest.DriverType.valueOf(type) else null,
                ))
            } ?: repository.create(CreateAdminDriverRequest(name, plate, driverType))
            finishWrite(result)
        }
    }

    fun toggle(driver: AdminDriverResponseDriver) {
        val current = _state.value
        if (current.loading || !current.canToggle(driver) || current.saving) return
        write {
            repository.update(driver.id, UpdateAdminDriverRequest(
                isActive = if (driver.isActive == 1) UpdateAdminDriverRequest.IsActive._0 else UpdateAdminDriverRequest.IsActive._1,
            ))
        }
    }

    private fun write(call: suspend () -> DriverWriteResult) {
        val current = _state.value
        if (current.loading || current.saving) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call()) }
    }

    private suspend fun finishWrite(result: DriverWriteResult) {
        when (result) {
            is DriverWriteResult.Saved -> {
                _state.update { it.copy(saving = false, editingId = null, name = "", licensePlate = "", driverType = FLETERO) }
                load()
            }
            is DriverWriteResult.Refused -> _state.update {
                it.copy(saving = false, error = REFUSED, refusedStatus = result.status)
            }
            DriverWriteResult.Unreachable -> _state.update { it.copy(saving = false, error = UNREACHABLE) }
        }
    }

    private fun changeForm(change: UiState.() -> UiState) = _state.update { it.change().copy(error = null, refusedStatus = null) }
    private fun validation(message: String) = _state.update { it.copy(error = message, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val CASA = "casa"
        const val FLETERO = "fletero"
        const val NAME = "Enter a driver name."
        const val DRIVER_TYPE = "Select a valid driver type."
        const val REFUSED = "The server refused this driver operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
