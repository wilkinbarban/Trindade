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
        /**
         * The WhatsApp text the server rendered for [date], or null when there is nothing to copy.
         *
         * Null covers both "not asked for yet" and "asked for and the server could not render it", and
         * that is deliberate: the operator sees the same thing either way -- the action, ready to be
         * tried -- while the reason lives in [message] for the second case. A separate flag would exist
         * only to distinguish two states nothing on the screen treats differently.
         */
        val exportText: String? = null,
        val loadingExport: Boolean = false,
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

    /**
     * Identifies the newest load, so an answer to an older one cannot overwrite it.
     *
     * Two loads really can be in the air at once now that a day can be requested: the constructor's
     * own load of today starts before the requested day is applied, and both answer. Without this the
     * earlier answer landing last would draw today's entries under the requested day's name -- the
     * schedule of one day shown under the date of another, which reads exactly like the server
     * answering with the wrong rows. The same token the history screens use, for the same reason.
     */
    private var newestLoad = 0

    init {
        load()
    }

    fun onDateChange(date: String) {
        _state.update { it.copy(date = date) }
        load()
    }

    fun load(date: String = state.value.date) {
        // The export text is dropped on every reload, and that includes the one a successful mutation
        // triggers: the text describes the day's entries, so leaving it on screen after they changed
        // would hand the operator a message about a day that no longer exists. It is one tap away.
        val load = ++newestLoad
        _state.update { it.copy(date = date, loading = true, message = null, exportText = null) }

        viewModelScope.launch {
            val schedules = repository.schedules(date)
            val slots = repository.timeSlots()
            val drivers = repository.drivers()
            val vehicles = repository.vehicles()

            // A superseded answer is dropped whole: not the entries, not the date, not the message.
            // Half of it written over a newer answer would be worse than none, because the grid would
            // then describe one day under the header of another.
            if (load != newestLoad) return@launch

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
                ScheduleLifecycleResult.Changed -> {
                    _state.update { it.copy(busy = false) }
                    load()
                }
                is ScheduleLifecycleResult.Refused ->
                    _state.update { it.copy(busy = false, message = deleteRefusal(result.statusCode)) }
                ScheduleLifecycleResult.Unreachable ->
                    _state.update { it.copy(busy = false, message = UNREACHABLE) }
            }
        }
    }

    /**
     * Fetches the server-rendered WhatsApp text for the day on screen. Never assembled here.
     *
     * The date is the grid's own, not a second control, so the text and the entries below it cannot
     * describe different days. Fetched on demand rather than with the grid, because it is one more call
     * the operator may not want and the grid is the point of the screen.
     */
    fun loadExport() {
        if (state.value.loadingExport) return
        _state.update { it.copy(loadingExport = true, message = null) }

        viewModelScope.launch {
            val text = repository.exportText(state.value.date)
            _state.update {
                it.copy(
                    loadingExport = false,
                    exportText = text,
                    // 400 is the only refusal the server has here and it answers a malformed date, which
                    // this client cannot send; what is left is an absent answer, and saying so is honest.
                    message = if (text == null) EXPORT_FAILED else null,
                )
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
        const val EXPORT_FAILED = "Não foi possível gerar o texto agora. Tente de novo."

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
