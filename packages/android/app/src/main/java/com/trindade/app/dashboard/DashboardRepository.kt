package com.trindade.app.dashboard

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.network.LoadingApi
import com.trindade.app.network.SystemApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dashboard's one read.
 *
 * Null means the server could not be asked, and the screen has to tell that apart from a summary of
 * zeroes: every count in this answer is an `Int`, so a failed read that answered with defaults would
 * be a screen reporting an empty day, which is the one thing a summary must never invent.
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val api: SystemApi,
    private val loadingApi: LoadingApi,
) {

    suspend fun summary(): DashboardSummary? =
        runCatchingCancellable { api.dashboardSummary() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    /**
     * El texto de WhatsApp de una fecha, tal como lo renderiza el servidor.
     *
     * No se arma acá y no es una decisión de estilo: el texto es la salida del producto, no una vista de
     * los datos, y una segunda implementación sería una segunda cosa que mantener de acuerdo con la
     * primera. Es la misma llamada que ya envuelve `LoadingRepository.exportText`.
     */
    suspend fun scheduleText(date: String): String? =
        runCatchingCancellable { loadingApi.export(date) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.text
}
