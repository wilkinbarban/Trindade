package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.contract.models.CreateAdminDriverRequest
import com.trindade.app.contract.models.UpdateAdminDriverRequest
import com.trindade.app.ui.cleanPhone
import com.trindade.app.ui.filterPhoneInput
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
        val phone: String = "",
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

    /**
     * The field keeps only what can belong to a number while the operator types; the tidying happens
     * once, on the save, so the spacing they are writing is not eaten keystroke by keystroke.
     */
    fun onPhoneChange(value: String) = changeForm { copy(phone = filterPhoneInput(value)) }
    fun onDriverTypeChange(value: String) = changeForm {
        copy(driverType = if (isAdmin) value else FLETERO)
    }

    fun edit(driver: AdminDriverResponseDriver) {
        val current = _state.value
        if (current.loading || !current.canEdit(driver) || current.saving) return
        _state.update { it.copy(editingId = driver.id, name = driver.name,
            licensePlate = driver.licensePlate.orEmpty(), phone = driver.phone.orEmpty(),
            driverType = driver.driverType.value, error = null) }
    }

    fun cancelEdit() = _state.update {
        it.copy(editingId = null, name = "", licensePlate = "", phone = "", driverType = FLETERO,
            error = null, refusedStatus = null)
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
        val plate = if (current.editingId != null) {
            current.licensePlate.trim()
        } else {
            current.licensePlate.trim().ifEmpty { null }
        }
        // An emptied field on an edit is the operator asking for the number to go, and the repository
        // turns that empty value into the explicit null the endpoint reads as removal. On a create,
        // empty is simply a number nobody has yet, so the key stays out of the body.
        val phone = if (current.editingId != null) {
            cleanPhone(current.phone)
        } else {
            cleanPhone(current.phone).ifEmpty { null }
        }
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = current.editingId?.let { id ->
                repository.update(id, UpdateAdminDriverRequest(
                    name = name, licensePlate = plate, phone = phone,
                    driverType = if (current.isAdmin) UpdateAdminDriverRequest.DriverType.valueOf(type) else null,
                ))
            } ?: repository.create(
                CreateAdminDriverRequest(
                    name = name,
                    licensePlate = plate,
                    phone = phone,
                    driverType = driverType,
                ),
            )
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
