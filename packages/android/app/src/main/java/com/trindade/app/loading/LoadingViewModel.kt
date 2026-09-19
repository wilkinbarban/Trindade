package com.trindade.app.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriversResponseDriversInner
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.VehiclesResponseVehiclesInner
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LoadingViewModel @Inject constructor(
    private val repository: LoadingRepository,
) : ViewModel() {

    data class UiState(
        val date: String = saoPauloToday(),
        val loading: Boolean = true,
        val schedules: List<SchedulesResponseSchedulesInner> = emptyList(),
        val timeSlots: List<String> = emptyList(),
        val drivers: List<DriversResponseDriversInner> = emptyList(),
        val vehicles: List<VehiclesResponseVehiclesInner> = emptyList(),
        /** The slot whose add form is open, or null when none is. */
        val addingToSlot: String? = null,
        val selectedDriverId: Int? = null,
        val selectedVehicleId: Int? = null,
        val busy: Boolean = false,
        val message: String? = null,
    ) {
        /**
         * The slots to draw: the configured ones, plus any slot an entry already sits in.
         *
         * The union rather than the configured list alone, because a slot removed from settings after
         * entries were made would otherwise make those entries disappear from the screen while still
         * existing on the server -- the worst of both, since the operator cannot see or remove them.
         */
        val slots: List<String>
            get() = (timeSlots + schedules.map { it.timeSlot }).distinct().sorted()

        fun entriesIn(slot: String): List<SchedulesResponseSchedulesInner> =
            schedules.filter { it.timeSlot == slot }

        /** What the counter shows. A display: see [FleteroQuota] for why it may not refuse anything. */
        fun fleteroCountIn(slot: String): Int = FleteroQuota.countInWindow(schedules, slot)

        fun isExceeded(slot: String): Boolean = FleteroQuota.isExceeded(schedules, slot)

        val selectedDriver: DriversResponseDriversInner?
            get() = drivers.firstOrNull { it.id == selectedDriverId }

        /**
         * A company driver must be paired with a vehicle; an external one brings their own, and the
         * server rejects a `casa` assignment without one. The form follows the same rule rather than
         * letting the operator discover it as a 400.
         */
        val needsVehicle: Boolean
            get() = selectedDriver?.driverType == DriversResponseDriversInner.DriverType.casa

        val canConfirm: Boolean
            get() = !busy && selectedDriverId != null && (!needsVehicle || selectedVehicleId != null)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onDateChange(date: String) {
        _state.update { it.copy(date = date) }
        load()
    }

    fun load(date: String = state.value.date) {
        _state.update { it.copy(date = date, loading = true, message = null) }

        viewModelScope.launch {
            val schedules = repository.schedules(date)
            val slots = repository.timeSlots()
            val drivers = repository.drivers()
            val vehicles = repository.vehicles()

            _state.update {
                it.copy(
                    loading = false,
                    schedules = schedules.orEmpty(),
                    timeSlots = slots.orEmpty(),
                    drivers = drivers.orEmpty(),
                    vehicles = vehicles.orEmpty(),
                    // Only the schedules are worth refusing over: without them the grid is empty, while
                    // a missing slot list still leaves the day's entries visible in their own slots.
                    message = if (schedules == null) UNREACHABLE else null,
                )
            }
        }
    }

    fun startAdding(slot: String) = _state.update {
        it.copy(addingToSlot = slot, selectedDriverId = null, selectedVehicleId = null, message = null)
    }

    fun cancelAdding() = _state.update {
        it.copy(addingToSlot = null, selectedDriverId = null, selectedVehicleId = null)
    }

    fun onDriverSelected(driverId: Int) = _state.update { current ->
        val driver = current.drivers.firstOrNull { it.id == driverId }
        // Choosing an external driver clears the vehicle, because theirs is their own and the server
        // takes no vehicle for it; leaving a stale one would send a pairing the operator did not mean.
        val keepsVehicle = driver?.driverType == DriversResponseDriversInner.DriverType.casa
        current.copy(selectedDriverId = driverId, selectedVehicleId = if (keepsVehicle) current.selectedVehicleId else null)
    }

    fun onVehicleSelected(vehicleId: Int) = _state.update { it.copy(selectedVehicleId = vehicleId) }

    /**
     * Adds the entry the form describes.
     *
     * The driver type comes from the chosen driver rather than from a control, so the request cannot
     * contradict the driver it names. The date comes from the grid's own date, not from the form.
     */
    fun confirmAdd() {
        val current = state.value
        val slot = current.addingToSlot ?: return
        val driver = current.selectedDriver ?: return
        if (!current.canConfirm) return

        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            val request = CreateScheduleRequest(
                scheduleDate = current.date,
                timeSlot = slot,
                driverType = requestDriverType(driver),
                driverId = driver.id,
                vehicleId = if (current.needsVehicle) current.selectedVehicleId else null,
            )

            when (val result = repository.createSchedule(request)) {
                is ScheduleWriteResult.Saved -> {
                    _state.update { it.copy(busy = false, addingToSlot = null, selectedDriverId = null, selectedVehicleId = null) }
                    load()
                }
                is ScheduleWriteResult.Refused ->
                    _state.update { it.copy(busy = false, message = refusalMessage(result.statusCode)) }
                ScheduleWriteResult.Unreachable ->
                    _state.update { it.copy(busy = false, message = UNREACHABLE) }
            }
        }
    }

    fun deleteEntry(id: Int) {
        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            when (val result = repository.deleteSchedule(id)) {
                ScheduleDeleteResult.Deleted -> {
                    _state.update { it.copy(busy = false) }
                    load()
                }
                is ScheduleDeleteResult.Refused ->
                    _state.update { it.copy(busy = false, message = deleteRefusal(result.statusCode)) }
                ScheduleDeleteResult.Unreachable ->
                    _state.update { it.copy(busy = false, message = UNREACHABLE) }
            }
        }
    }

    /** Quick-add for a driver who is not in the list yet. Always a `fletero`, per the contract. */
    fun quickAddDriver(name: String, licensePlate: String?) {
        if (name.isBlank() || state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            val driver = repository.createDriver(name.trim(), licensePlate?.trim()?.takeIf { it.isNotEmpty() })
            _state.update {
                it.copy(
                    busy = false,
                    drivers = if (driver != null) it.drivers + driver else it.drivers,
                    selectedDriverId = driver?.id ?: it.selectedDriverId,
                    message = if (driver == null) UNREACHABLE else null,
                )
            }
        }
    }

    /**
     * The request's driver-type enum, matched by value.
     *
     * The generator emits a separate enum for every DTO that carries one, so the driver's own type and
     * the request's are different classes with the same values.
     */
    private fun requestDriverType(driver: DriversResponseDriversInner): CreateScheduleRequest.DriverType =
        CreateScheduleRequest.DriverType.entries.firstOrNull { it.value == driver.driverType.value }
            ?: CreateScheduleRequest.DriverType.fletero

    private fun refusalMessage(statusCode: Int): String = when (statusCode) {
        // The same driver, or the same vehicle, already on that date.
        409 -> "Esse motorista ou veículo já está nessa data."
        // Validation: a casa driver without a vehicle, an inactive driver or vehicle, a missing date.
        400 -> "Verifique os dados e tente de novo."
        404 -> "O motorista ou o veículo não existe mais."
        else -> GENERIC
    }

    private fun deleteRefusal(statusCode: Int): String = when (statusCode) {
        // A Trabalhador may only remove their own entry.
        403 -> "Só é possível excluir os próprios lançamentos."
        404 -> "O lançamento não existe mais."
        else -> GENERIC
    }

    companion object {
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
        const val GENERIC = "Não foi possível concluir. Tente de novo."

        /**
         * Today in São Paulo, not on the device.
         *
         * The schedule belongs to a day, and a phone with the wrong timezone would otherwise open the
         * previous or next day's grid -- showing an empty screen for a day that has entries, or worse,
         * inviting an operator to add one that already exists.
         *
         * Not covered by a test: asserting this needs a clock the class accepts as a parameter, which
         * is a change to the code rather than to the test. Recorded rather than papered over.
         */
        fun saoPauloToday(): String = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString()
    }
}
