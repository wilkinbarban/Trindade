package com.trindade.app.massas

import com.trindade.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.aero.AeroDispatchOutcome
import com.trindade.app.aero.AeroDispatchRequest
import com.trindade.app.aero.AeroDispatcher
import com.trindade.app.aero.AeroErpMovement
import com.trindade.app.aero.AeroSettings
import com.trindade.app.aero.AeroSettingsPort
import com.trindade.app.aero.AeroSyncOutcome
import com.trindade.app.aero.TabletAckPort
import com.trindade.app.aero.toLogic
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.network.CreateMassasLaunchItem
import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassasLaunchDayView
import com.trindade.app.ui.UiText
import com.trindade.app.ui.filterPhoneDigits
import com.trindade.app.ui.waMeLink
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MassaConfirmItem(
    val productCode: String,
    val productName: String?,
    val boxes: Int,
    val units: Int,
)

@HiltViewModel
class MassasLaunchViewModel @Inject constructor(
    private val repository: MassasRepository,
    private val authRepository: AuthRepository,
    private val aeroSettingsStore: AeroSettingsPort,
    private val aeroDispatcher: AeroDispatcher,
    private val tabletAckStore: TabletAckPort,
) : ViewModel() {

    companion object {
        /** The wall clock the operator reads on the sync line, in the plant's own timezone. */
        fun nowLabel(): String {
            val format = SimpleDateFormat("HH:mm", Locale.US)
            format.timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
            return format.format(java.util.Date())
        }

        fun saopauloToday(): String {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
            return format.format(java.util.Date())
        }
    }

    data class UiState(
        val date: String = saopauloToday(),
        val dayView: MassasLaunchDayView? = null,
        val quantities: Map<String, String> = emptyMap(),
        val isLoading: Boolean = false,
        val isSubmitting: Boolean = false,
        val isPollingActive: Boolean = false,
        val editingLaunchId: Int? = null,
        val editingBoxes: String = "",
        val deleteConfirmLaunchId: Int? = null,
        val currentUser: ProfileResponseUser? = null,
        val phone: String = "",
        val noticeMessage: UiText? = null,
        val errorMessage: UiText? = null,
        val showHelpDialog: Boolean = false,
        val showConfirmLaunchDialog: Boolean = false,
        val confirmItems: List<MassaConfirmItem> = emptyList(),
        val resultDialogMessage: UiText? = null,
        val resultDialogIsSuccess: Boolean = false,
        val selectedOperatorId: Int? = null,
        val isReadOnly: Boolean = false,
        /** The integration as this device holds it; blank credentials mean "not configured yet". */
        val aeroSettings: AeroSettings = AeroSettings(),
        /** The plant's day, as the last sync stored it on the backend. */
        val aeroMirror: List<AeroErpMovement> = emptyList(),
        /** Local time of the last successful sync, or null when this day was never synced. */
        val aeroSyncedAt: String? = null,
        val isSyncingAero: Boolean = false,
        /** The universal filter over the unified table: an operator key, empty for everyone. */
        val operatorFilter: String = "",
        /** The search box: a product code, a name or an operator, matched on both sides. */
        val unifiedQuery: String = "",
        /** Launch ids this device already marked as loaded on the company tablet. */
        val ackedLaunchIds: Set<Int> = emptySet(),
    ) {
        /** True when a launch can be sent to the plant without asking the operator anything else. */
        val aeroReady: Boolean get() = aeroSettings.ready

        /** The day in one table, with the three states the plant actually has. */
        val unifiedRows: List<MassasUnifiedRow>
            get() = massasUnifiedRows(
                input = MassasUnifiedInput(
                    launches = dayView?.launches.orEmpty(),
                    movements = aeroMirror,
                    products = dayView?.products.orEmpty().map {
                        AeroImportProduct(code = it.code, name = it.name, unitsPerBox = it.unitsPerBox)
                    },
                    ackedLaunchIds = ackedLaunchIds,
                ),
                operatorFilter = operatorFilter,
            ).filter { row ->
                val query = unifiedQuery.trim().lowercase()
                query.isEmpty() ||
                    row.productCode.lowercase().contains(query) ||
                    row.productName.lowercase().contains(query) ||
                    row.operatorKey.contains(query)
            }

        val unifiedBalance: MassasBalance get() = massasBalance(unifiedRows)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var isScreenActive: Boolean = false

    init {
        loadCurrentUser()
        loadAeroSettings()
        loadDay(state.value.date)
    }

    /**
     * Reads the integration and the stored mirror for the current day.
     *
     * The mirror is kept on the backend rather than on the phone, so two tablets looking at the same morning
     * see the same seats; this device only remembers when it last asked.
     */
    private fun loadAeroSettings() {
        viewModelScope.launch {
            val settings = aeroSettingsStore.load()
            _state.value = _state.value.copy(
                aeroSettings = settings,
                aeroSyncedAt = aeroSettingsStore.syncedAt(state.value.date),
                ackedLaunchIds = tabletAckStore.ackedIds(),
            )
            if (settings.ready) refreshMirror(state.value.date)
        }
    }

    private suspend fun refreshMirror(date: String) {
        val stored = repository.storedAeroMovements(date) ?: return
        _state.value = _state.value.copy(
            aeroMirror = stored.movements.map { it.toLogic() },
            aeroSyncedAt = stored.syncedAt ?: state.value.aeroSyncedAt,
        )
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val profile = authRepository.profile()
            _state.value = _state.value.copy(currentUser = profile)
        }
    }

    fun onScreenActive(active: Boolean) {
        isScreenActive = active
        val isToday = state.value.date == saopauloToday()
        if (active && isToday) {
            if (pollingJob == null || pollingJob?.isActive != true) {
                startPolling()
            }
        } else {
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val currentState = _state.value
                val isEditing = currentState.editingLaunchId != null
                val isSubmitting = currentState.isSubmitting
                val isToday = currentState.date == saopauloToday()
                if (shouldPollMassas(isScreenActive, isEditing, isSubmitting, isToday)) {
                    silentRefresh()
                }
            }
        }
    }

    fun loadDay(date: String) {
        val isToday = date == saopauloToday()
        val isReadOnly = !isToday
        if (isReadOnly) {
            pollingJob?.cancel()
            pollingJob = null
        } else if (isScreenActive && (pollingJob == null || pollingJob?.isActive != true)) {
            startPolling()
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                errorMessage = null,
                date = date,
                isReadOnly = isReadOnly,
                editingLaunchId = null,
                editingBoxes = "",
                deleteConfirmLaunchId = null,
                // The boxes typed for the previous day are not this day's: carrying them over would let a
                // closed day look launchable, and an open one launch what the operator meant for yesterday.
                quantities = emptyMap(),
                showConfirmLaunchDialog = false,
            )
            val result = repository.day(date)
            _state.value = _state.value.copy(
                isLoading = false,
                dayView = result,
                errorMessage = if (result == null) UiText.of(R.string.massas_error_load) else null,
            )
        }
    }

    fun refresh() {
        loadDay(state.value.date)
    }

    private suspend fun silentRefresh() {
        val result = repository.day(state.value.date)
        if (result != null) {
            _state.value = _state.value.copy(dayView = result)
        }
    }

    fun onQuantityChange(productCode: String, value: String) {
        if (state.value.isReadOnly) return
        val filtered = value.filter { it in '0'..'9' }
        val newQuantities = _state.value.quantities.toMutableMap()
        if (filtered.isEmpty() || filtered == "0") {
            newQuantities.remove(productCode)
        } else {
            newQuantities[productCode] = filtered
        }
        _state.value = _state.value.copy(quantities = newQuantities, noticeMessage = null)
    }

    fun clearQuantities() {
        _state.value = _state.value.copy(quantities = emptyMap(), noticeMessage = null)
    }

    /**
     * Turns a refusal into the sentence the operator reads.
     *
     * The code decides when this build knows it, whatever language the sentence arrived in; otherwise the
     * server's own text stands, and the status code is the last resort for a body nobody could read.
     */
    private fun refusalMessage(result: MassasWriteResult.Refused): UiText = when (result.errorCode) {
        "MASSAS_HISTORY_FROZEN" -> UiText.of(R.string.massas_error_history_frozen)
        "MASSAS_FORBIDDEN" -> UiText.of(R.string.massas_error_forbidden)
        "MASSAS_ALREADY_IMPORTED" -> UiText.of(R.string.massas_error_already_imported)
        "MASSAS_NOT_FOUND" -> UiText.of(R.string.massas_error_not_found)
        "MASSAS_RECONCILED" -> UiText.of(R.string.massas_error_reconciled)
        "MASSAS_INVALID_BOXES" -> UiText.of(R.string.massas_error_invalid_boxes)
        "MASSAS_FUTURE_DATE" -> UiText.of(R.string.massas_error_future_date)
        else -> result.message
            ?.takeIf { it.isNotBlank() }
            ?.let { UiText.literal(it) }
            ?: UiText.of(R.string.massas_error_create_refused, result.statusCode)
    }

    fun selectOperator(operatorId: Int?) {
        _state.value = _state.value.copy(selectedOperatorId = operatorId)
    }

    /**
     * The boxes on screen, with the name and the unit count the ERP needs.
     *
     * The catalogue is asked first and the built-in standard second: a product that disappeared from the API
     * yesterday still has a box size today, and a launch that divided by an invented one would post the wrong
     * quantity to the plant.
     */
    private fun confirmItems(quantities: Map<String, String>): List<MassaConfirmItem> {
        val dayProducts = _state.value.dayView?.products?.associateBy { it.code } ?: emptyMap()
        val standardMap = STANDARD_MASSAS.associateBy { it.code }
        return quantities.mapNotNull { (code, boxStr) ->
            val boxes = boxStr.toIntOrNull()
            if (boxes != null && boxes > 0) {
                val name = dayProducts[code]?.name ?: standardMap[code]?.name
                val unitsPerBox = dayProducts[code]?.unitsPerBox ?: standardMap[code]?.unitsPerBox ?: 10
                MassaConfirmItem(
                    productCode = code,
                    productName = name,
                    boxes = boxes,
                    units = boxes * unitsPerBox,
                )
            } else {
                null
            }
        }.sortedBy { it.productCode }
    }

    fun requestLaunchConfirmation() {
        if (state.value.isReadOnly) return
        val items = confirmItems(_state.value.quantities)
        if (items.isEmpty()) return
        _state.value = _state.value.copy(
            showConfirmLaunchDialog = true,
            confirmItems = items,
        )
    }

    fun dismissConfirmLaunch() {
        _state.value = _state.value.copy(
            showConfirmLaunchDialog = false,
            confirmItems = emptyList(),
        )
    }

    fun confirmLaunch() {
        if (state.value.isReadOnly) return
        val items = _state.value.confirmItems
        if (items.isEmpty()) return
        val apiItems = items.map { CreateMassasLaunchItem(it.productCode, it.boxes) }

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isSubmitting = true,
                showConfirmLaunchDialog = false,
                confirmItems = emptyList(),
                errorMessage = null,
                noticeMessage = null,
            )
            when (val result = repository.createLaunch(state.value.date, apiItems)) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        quantities = emptyMap(),
                        resultDialogMessage = UiText.of(R.string.massas_success),
                        resultDialogIsSuccess = true,
                    )
                    dispatchToErp(result.created, items)
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        resultDialogMessage = refusalMessage(result),
                        resultDialogIsSuccess = false,
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        resultDialogMessage = UiText.of(R.string.massas_error_offline),
                        resultDialogIsSuccess = false,
                    )
                }
            }
        }
    }

    /**
     * Sends the rows that were just stored to the plant.
     *
     * Called after the local write has already succeeded and never instead of it: a phone that loses the
     * factory Wi-Fi still records the launch, and the row stays `pending` until someone sends it again from
     * the table. The dialog is the one already on screen, so the outcome is appended to the success message
     * instead of covering it with a second one.
     */
    private suspend fun dispatchToErp(created: List<MassaIndividualLaunch>, items: List<MassaConfirmItem>) {
        val settings = state.value.aeroSettings
        if (!settings.ready || created.isEmpty()) return

        val unitsByCode = items.associate { it.productCode to it.units }
        val namesByCode = items.associate { it.productCode to it.productName }
        var refused = 0
        var unreachable = 0

        for (launch in created) {
            val request = AeroDispatchRequest(
                launchId = launch.id,
                productCode = launch.productCode,
                productName = namesByCode[launch.productCode] ?: launch.productCode,
                // The row carries units already; the confirm items carry units too, and the fallback keeps
                // a row whose product vanished from the catalogue from dividing by an unknown box size.
                units = (unitsByCode[launch.productCode] ?: launch.units).toDouble(),
                date = state.value.date,
            )
            when (aeroDispatcher.dispatch(settings, request)) {
                is AeroDispatchOutcome.Sent -> Unit
                is AeroDispatchOutcome.Refused -> refused++
                is AeroDispatchOutcome.Unreachable -> unreachable++
            }
        }

        if (refused + unreachable > 0) {
            _state.value = _state.value.copy(
                resultDialogMessage = UiText.of(
                    if (unreachable > 0) R.string.massas_aero_dispatch_unreachable else R.string.massas_aero_dispatch_refused,
                    refused + unreachable,
                ),
                resultDialogIsSuccess = false,
                noticeMessage = UiText.of(R.string.massas_aero_pending_hint, refused + unreachable),
            )
        }
        val refreshed = repository.day(state.value.date)
        if (refreshed != null) _state.value = _state.value.copy(dayView = refreshed)
    }

    /** Reads the plant's day and stores it on the backend, then reloads what was stored. */
    fun syncAeroDay() {
        val current = state.value
        if (current.isSyncingAero) return
        val settings = current.aeroSettings
        if (!settings.ready) {
            _state.value = _state.value.copy(
                errorMessage = UiText.of(R.string.massas_aero_not_configured),
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isSyncingAero = true, errorMessage = null, noticeMessage = null)
            when (val outcome = aeroDispatcher.syncDay(settings, current.date)) {
                is AeroSyncOutcome.Synced -> {
                    aeroSettingsStore.markSynced(current.date, nowLabel())
                    _state.value = _state.value.copy(
                        isSyncingAero = false,
                        aeroSyncedAt = nowLabel(),
                        noticeMessage = UiText.of(R.string.massas_aero_sync_done, outcome.saved.saved),
                    )
                    refreshMirror(current.date)
                }
                is AeroSyncOutcome.Unreachable -> {
                    // The previous snapshot stands: saying "sem conexão" is the whole message.
                    _state.value = _state.value.copy(
                        isSyncingAero = false,
                        errorMessage = UiText.of(R.string.massas_aero_unreachable),
                    )
                }
                is AeroSyncOutcome.Failed -> {
                    _state.value = _state.value.copy(
                        isSyncingAero = false,
                        errorMessage = UiText.of(R.string.massas_aero_sync_failed, outcome.reason ?: "-"),
                    )
                }
            }
        }
    }

    /**
     * Resends one pending row to the plant.
     *
     * The row already exists locally; what is at stake is only whether it becomes reconciled. The day is
     * reloaded afterwards because the state lives in the ERP, not in this table.
     */
    fun sendPendingLaunch(row: MassasUnifiedRow) {
        val settings = state.value.aeroSettings
        if (!settings.ready) {
            _state.value = _state.value.copy(errorMessage = UiText.of(R.string.massas_aero_not_configured))
            return
        }
        val launch = state.value.dayView?.launches?.firstOrNull { it.id in row.launchIds }
        if (launch == null) {
            _state.value = _state.value.copy(errorMessage = UiText.of(R.string.massas_aero_launch_missing))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(noticeMessage = null, errorMessage = null)
            val request = AeroDispatchRequest(
                launchId = launch.id,
                productCode = launch.productCode,
                productName = row.productName,
                units = row.units.toDouble(),
                date = state.value.date,
                // The row carries who the launch belongs to; the launch answers it when the owner is a
                // resolved account. Either way `ENT_USU` names the operator and not the phone's user.
                operatorName = row.operatorName.ifBlank { launch.displayName.ifBlank { launch.username } },
            )
            when (val outcome = aeroDispatcher.dispatch(settings, request)) {
                is AeroDispatchOutcome.Sent -> {
                    val refreshed = repository.day(state.value.date)
                    if (refreshed != null) _state.value = _state.value.copy(dayView = refreshed)
                    refreshMirror(state.value.date)
                    _state.value = _state.value.copy(noticeMessage = UiText.of(R.string.massas_aero_sent))
                }
                is AeroDispatchOutcome.Unreachable ->
                    _state.value = _state.value.copy(errorMessage = UiText.of(R.string.massas_aero_unreachable))
                is AeroDispatchOutcome.Refused ->
                    _state.value = _state.value.copy(
                        errorMessage = UiText.of(R.string.massas_aero_sync_failed, outcome.reason ?: "-"),
                    )
            }
        }
    }

    /**
     * Records that this row is already loaded on the company tablet.
     *
     * Kept on the device on purpose: it is what the operator standing next to the tablet knows, and it is not
     * something the ERP can tell us. It silences the button for this device without touching the row.
     */
    fun acknowledgeLaunch(row: MassasUnifiedRow) {
        val id = row.launchIds.firstOrNull() ?: return
        tabletAckStore.ack(id)
        // The rows are derived from the state, and the ack is part of that derivation: the copy is what
        // removes the button from this row without touching the launch itself.
        _state.value = _state.value.copy(ackedLaunchIds = _state.value.ackedLaunchIds + id)
    }

    /**
     * Imports a seat the tablet recorded, as a launch of this app.
     *
     * Only whole boxes arrive here: [aeroImportPlan] returns null for a quantity that does not fit the
     * catalogue's box size, and the row would not have offered the action.
     */
    fun importErpSeat(row: MassasUnifiedRow) {
        if (state.value.isReadOnly) return
        val known = state.value.unifiedRows.firstOrNull { it.key == row.key } ?: return
        val seat = state.value.aeroMirror.firstOrNull { it.entLan in known.entLans } ?: return
        val plan = aeroImportPlanFor(row) ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true, errorMessage = null, noticeMessage = null)
            val result = repository.createLaunch(
                date = state.value.date,
                items = listOf(CreateMassasLaunchItem(plan.productCode, plan.boxes)),
                // El asiento y el operario de la tableta viajan con las cajas: sin ellos el lanzamiento queda
                // a nombre de quien apretó el botón y sólo se puede emparejar por nombre.
                entLan = seat.entLan,
                operatorName = seat.operatorName,
            )
            when (result) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        noticeMessage = UiText.of(R.string.massas_aero_imported),
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = refusalMessage(result),
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = UiText.of(R.string.massas_error_offline),
                    )
                }
            }
        }
    }

    /** The import plan for a row, rebuilt from the seat it came from. */
    private fun aeroImportPlanFor(row: MassasUnifiedRow): com.trindade.app.massas.AeroImportPlan? {
        val products = state.value.dayView?.products.orEmpty().map {
            AeroImportProduct(code = it.code, name = it.name, unitsPerBox = it.unitsPerBox)
        }
        val seat = state.value.aeroMirror.firstOrNull { it.entLan in row.entLans } ?: return null
        return aeroImportPlan(seat, products)
    }

    /** The universal filter: an operator key, or empty for the whole plant. */
    fun setOperatorFilter(operatorKey: String) {
        _state.value = _state.value.copy(operatorFilter = operatorKey)
    }

    fun setUnifiedQuery(query: String) {
        _state.value = _state.value.copy(unifiedQuery = query)
    }

    /** Loads a stored day's mirror without touching the ERP. */
    fun reloadMirror(date: String) {
        viewModelScope.launch { refreshMirror(date) }
    }

    fun dismissResultDialog() {
        _state.value = _state.value.copy(
            resultDialogMessage = null,
            resultDialogIsSuccess = false,
        )
    }

    fun submitLaunch() {
        val items = confirmItems(_state.value.quantities)
        if (items.isEmpty()) return
        val body = items.map { CreateMassasLaunchItem(productCode = it.productCode, boxes = it.boxes) }

        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true, errorMessage = null, noticeMessage = null)
            when (val result = repository.createLaunch(state.value.date, body)) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        quantities = emptyMap(),
                        noticeMessage = UiText.of(R.string.massas_success),
                    )
                    dispatchToErp(result.created, items)
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = refusalMessage(result),
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = UiText.of(R.string.massas_error_offline),
                    )
                }
            }
        }
    }

    /** Los lanzamientos que el ERP ya asentó, tal como los ve la tabla del día. */
    private fun settledLaunchIds(): Set<Int> = reconciledLaunchIds(state.value.unifiedRows)

    fun canEdit(launch: MassaIndividualLaunch): Boolean {
        if (state.value.isReadOnly) return false
        val user = state.value.currentUser ?: return false
        return canEditOrDeleteLaunch(
            launchUserId = launch.userId,
            currentUserId = user.id,
            isToday = !state.value.isReadOnly,
            isReconciled = launch.id in settledLaunchIds(),
        )
    }

    fun startEdit(launch: MassaIndividualLaunch) {
        if (state.value.isReadOnly) return
        // La pantalla ya no dibuja el botón, pero el camino sigue cerrado acá: un lanzamiento conciliado está
        // en el ERP y moverle las cajas dejaría los dos registros del día en desacuerdo.
        if (launch.id in settledLaunchIds()) return
        _state.value = _state.value.copy(
            editingLaunchId = launch.id,
            editingBoxes = launch.boxes.toString(),
            noticeMessage = null,
        )
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(
            editingLaunchId = null,
            editingBoxes = "",
        )
    }

    fun onEditingBoxesChange(value: String) {
        val filtered = value.filter { it in '0'..'9' }
        _state.value = _state.value.copy(editingBoxes = filtered)
    }

    fun saveEdit() {
        if (state.value.isReadOnly) return
        val launchId = _state.value.editingLaunchId ?: return
        if (launchId in settledLaunchIds()) return
        val boxes = _state.value.editingBoxes.toIntOrNull() ?: return
        if (boxes <= 0) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true, errorMessage = null, noticeMessage = null)
            when (val result = repository.updateLaunch(launchId, boxes, state.value.date)) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        editingLaunchId = null,
                        editingBoxes = "",
                        noticeMessage = UiText.of(R.string.massas_notice_updated),
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = refusalMessage(result),
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = UiText.of(R.string.massas_error_offline),
                    )
                }
            }
        }
    }

    fun promptDelete(launchId: Int) {
        if (state.value.isReadOnly) return
        if (launchId in settledLaunchIds()) return
        _state.value = _state.value.copy(deleteConfirmLaunchId = launchId)
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(deleteConfirmLaunchId = null)
    }

    fun deleteLaunch() {
        if (state.value.isReadOnly) return
        val launchId = _state.value.deleteConfirmLaunchId ?: return
        if (launchId in settledLaunchIds()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isSubmitting = true,
                deleteConfirmLaunchId = null,
                errorMessage = null,
                noticeMessage = null,
            )
            when (val result = repository.deleteLaunch(launchId, state.value.date)) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        noticeMessage = UiText.of(R.string.massas_notice_deleted),
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = refusalMessage(result),
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = UiText.of(R.string.massas_error_offline),
                    )
                }
            }
        }
    }

    fun onPhoneChange(phone: String) {
        _state.value = _state.value.copy(phone = filterPhoneDigits(phone))
    }

    fun getWhatsAppLink(): String? {
        val view = _state.value.dayView ?: return null
        val message = formatMassasWhatsAppMessage(view)
        return waMeLink(_state.value.phone, message)
    }

    fun getShareText(): String {
        val view = _state.value.dayView ?: return ""
        return formatMassasWhatsAppMessage(view)
    }

    fun toggleHelpDialog(show: Boolean) {
        _state.value = _state.value.copy(showHelpDialog = show)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(noticeMessage = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
