package com.trindade.app.massas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.network.CreateMassasLaunchItem
import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassasLaunchDayView
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
    val productName: String,
    val boxes: Int,
    val units: Int,
)

@HiltViewModel
class MassasLaunchViewModel @Inject constructor(
    private val repository: MassasRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    companion object {
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
        val noticeMessage: String? = null,
        val errorMessage: String? = null,
        val showHelpDialog: Boolean = false,
        val showConfirmLaunchDialog: Boolean = false,
        val confirmItems: List<MassaConfirmItem> = emptyList(),
        val resultDialogMessage: String? = null,
        val resultDialogIsSuccess: Boolean = false,
        val selectedOperatorId: Int? = null,
        val isReadOnly: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var isScreenActive: Boolean = false

    init {
        loadCurrentUser()
        loadDay(state.value.date)
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
            )
            val result = repository.day(date)
            _state.value = _state.value.copy(
                isLoading = false,
                dayView = result,
                errorMessage = if (result == null) "Não foi possível carregar os lançamentos de massas." else null,
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

    fun selectOperator(operatorId: Int?) {
        _state.value = _state.value.copy(selectedOperatorId = operatorId)
    }

    fun requestLaunchConfirmation() {
        if (state.value.isReadOnly) return
        val quantities = _state.value.quantities
        val dayProducts = _state.value.dayView?.products?.associateBy { it.code } ?: emptyMap()
        val standardMap = STANDARD_MASSAS.associateBy { it.code }

        val items = quantities.mapNotNull { (code, boxStr) ->
            val boxes = boxStr.toIntOrNull()
            if (boxes != null && boxes > 0) {
                val name = dayProducts[code]?.name ?: standardMap[code]?.name ?: "Produto $code"
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
                        resultDialogMessage = "Lançamento registrado com sucesso!",
                        resultDialogIsSuccess = true,
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        resultDialogMessage = "O servidor recusou o lançamento (código ${result.statusCode}).",
                        resultDialogIsSuccess = false,
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        resultDialogMessage = "Sem conexão com o servidor. Tente de novo.",
                        resultDialogIsSuccess = false,
                    )
                }
            }
        }
    }

    fun dismissResultDialog() {
        _state.value = _state.value.copy(
            resultDialogMessage = null,
            resultDialogIsSuccess = false,
        )
    }

    fun submitLaunch() {
        val quantities = _state.value.quantities
        val items = quantities.mapNotNull { (code, boxStr) ->
            val boxes = boxStr.toIntOrNull()
            if (boxes != null && boxes > 0) {
                CreateMassasLaunchItem(productCode = code, boxes = boxes)
            } else {
                null
            }
        }
        if (items.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true, errorMessage = null, noticeMessage = null)
            when (val result = repository.createLaunch(state.value.date, items)) {
                is MassasWriteResult.Saved -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        dayView = result.view,
                        quantities = emptyMap(),
                        noticeMessage = "Lançamento registrado com sucesso!",
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "O servidor recusou o lançamento (código ${result.statusCode}).",
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "Sem conexão com o servidor. Tente de novo.",
                    )
                }
            }
        }
    }

    fun canEdit(launch: MassaIndividualLaunch): Boolean {
        if (state.value.isReadOnly) return false
        val user = state.value.currentUser ?: return false
        val isAdmin = user.role == "admin"
        return canEditOrDeleteLaunch(launch.userId, user.id, isAdmin, !state.value.isReadOnly)
    }

    fun startEdit(launch: MassaIndividualLaunch) {
        if (state.value.isReadOnly) return
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
                        noticeMessage = "Lançamento alterado com sucesso!",
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "Não foi possível alterar este lançamento.",
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "Sem conexão com o servidor. Tente de novo.",
                    )
                }
            }
        }
    }

    fun promptDelete(launchId: Int) {
        if (state.value.isReadOnly) return
        _state.value = _state.value.copy(deleteConfirmLaunchId = launchId)
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(deleteConfirmLaunchId = null)
    }

    fun deleteLaunch() {
        if (state.value.isReadOnly) return
        val launchId = _state.value.deleteConfirmLaunchId ?: return
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
                        noticeMessage = "Lançamento excluído com sucesso!",
                    )
                }
                is MassasWriteResult.Refused -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "Não foi possível excluir este lançamento.",
                    )
                }
                is MassasWriteResult.Unreachable -> {
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = "Sem conexão com o servidor. Tente de novo.",
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
