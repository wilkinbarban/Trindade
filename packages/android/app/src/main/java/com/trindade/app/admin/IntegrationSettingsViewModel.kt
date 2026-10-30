package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.aero.AeroErpClient
import com.trindade.app.aero.AeroReachability
import com.trindade.app.aero.AeroSettings
import com.trindade.app.aero.AeroSettingsPort
import com.trindade.app.aero.AeroSettingsPermissions
import com.trindade.app.aero.aeroSettingsEditable
import com.trindade.app.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The integration, as a screen the panel opens.
 *
 * The address is the only field with a rule of its own: it decides where every launch of the shift goes, so it
 * stays with the owner account, while the credentials belong to whoever is holding the phone. That split is
 * decided by [aeroSettingsEditable] and enforced here, not by hiding the field -- the operator sees the address
 * and cannot move it, which is the honest version of "you are not allowed to change this".
 */
@HiltViewModel
class IntegrationSettingsViewModel @Inject constructor(
    private val store: AeroSettingsPort,
    private val client: AeroErpClient,
    private val reachability: AeroReachability,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val wsUrl: String = "",
        val username: String = "",
        val password: String = "",
        val userCode: String = "",
        val currentUser: String = "",
        val isConfigured: Boolean = false,
        val isSaving: Boolean = false,
        /** What the last action answered, already resolved to the operator's language. */
        val message: String? = null,
        val messageIsError: Boolean = false,
        /** True while a probe is in flight, so the button can say so. */
        val isTesting: Boolean = false,
    ) {
        /** Both fields the ERP needs, plus the address: the three the client actually uses. */
        val ready: Boolean get() = AeroSettings(wsUrl, username, password, userCode).ready

        val permissions: AeroSettingsPermissions get() = aeroSettingsEditable(currentUser)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Fills the form from the device and from who is holding it.
     *
     * The name comes from the session and not from the APK: the address lock is per operator, so the wrong
     * name here would either hand the address to a worker or hide it from the owner.
     */
    fun load() {
        val stored = store.load()
        _state.value = _state.value.copy(
            wsUrl = stored.wsUrl,
            username = stored.username,
            password = stored.password,
            userCode = stored.userCode,
            isConfigured = store.isConfigured(),
        )
        viewModelScope.launch {
            val profile = authRepository.profile()
            val name = profile?.username.orEmpty()
            _state.update {
                it.copy(
                    currentUser = name,
                    // The operator's own ERP login is pre-filled with their name once and only while the
                    // field is still empty: overwriting a saved one would sign the plant's books for them.
                    username = if (it.username.isBlank()) name.uppercase() else it.username,
                )
            }
        }
    }

    fun setWsUrl(value: String) = _state.update { it.copy(wsUrl = value) }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun setUserCode(value: String) = _state.update { it.copy(userCode = value) }

    fun clearMessage() = _state.update { it.copy(message = null, messageIsError = false) }

    /**
     * Stores what the form holds.
     *
     * A locked address keeps the stored one instead of the typed one: the field is disabled, so the only way a
     * different value could arrive here is a stale composition, and saving it would move every future launch.
     */
    fun save() {
        val current = _state.value
        val stored = store.load()
        val target = AeroSettings(
            wsUrl = if (current.permissions.canEditWsUrl) current.wsUrl else stored.wsUrl,
            username = current.username.trim(),
            password = current.password,
            userCode = current.userCode.trim(),
        )
        store.save(target)
        _state.value = current.copy(
            wsUrl = target.wsUrl,
            username = target.username,
            userCode = target.userCode,
            isConfigured = true,
            message = null,
            messageIsError = false,
        )
    }

    /**
     * Probes the plant with what the form holds, not with what is stored.
     *
     * Otherwise the operator would have to save an address they are still unsure about before finding out
     * whether it is right, and would then have to remember to undo it.
     */
    fun test() {
        val current = _state.value
        if (current.isTesting) return
        val settings = AeroSettings(
            wsUrl = current.wsUrl,
            username = current.username.trim(),
            password = current.password,
            userCode = current.userCode.trim(),
        )
        val normalized = settings.normalizedWsUrl

        viewModelScope.launch {
            _state.value = _state.value.copy(isTesting = true, message = null, messageIsError = false)
            val diagnosis = reachability.diagnose(settings.wsUrl)
            // The network is asked first, and only a reachable plant is probed: an address that does not
            // answer costs the operator 2.5 seconds either way, but the sentence they get is different and
            // only one of them tells them to go look at the Wi-Fi.
            val answer = if (normalized == null || !settings.ready || !diagnosis.plantReachable) {
                null
            } else {
                client.testConnection(normalized, settings.username, settings.password)
            }
            _state.value = _state.value.copy(
                isTesting = false,
                message = describe(diagnosis, answer),
                messageIsError = answer !is AeroErpClient.Probe.Answered,
            )
        }
    }
}

/**
 * The one sentence a probe earns, in the operator's language.
 *
 * Resolved here as a plain string because this view model has no `UiText` seam and the section is admin-only:
 * the strings it returns are the ones the screen shows verbatim.
 */
private fun describe(
    diagnosis: AeroReachability.Diagnosis,
    answer: AeroErpClient.Probe?,
): String = when {
    !diagnosis.wifiConnected -> "Sem conexão com a rede da fábrica."
    diagnosis.hostReachable != true -> "O ERP não respondeu no endereço configurado."
    answer == null -> "Preencha endereço, usuário e senha."
    answer is AeroErpClient.Probe.Unreachable -> when (answer.reason) {
        AeroErpClient.ProbeFailure.TIMEOUT -> "O ERP demorou demais para responder."
        AeroErpClient.ProbeFailure.NO_ROUTE -> "Não há caminho até o ERP."
        AeroErpClient.ProbeFailure.REFUSED -> "O ERP recusou a conexão."
        AeroErpClient.ProbeFailure.UNKNOWN -> "Não foi possível falar com o ERP."
    }
    answer is AeroErpClient.Probe.Answered -> when (val test = answer.test) {
        is com.trindade.app.aero.AeroTestResult.Ok -> "Conectado ao ERP: ${test.userName.ifBlank { test.userCode.toString() }}."
        is com.trindade.app.aero.AeroTestResult.Failed -> test.message ?: "O ERP recusou o usuário ou a senha."
    }
    else -> "Conectado ao ERP."
}

/** `MutableStateFlow.update` without the import gymnastics in every setter. */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
