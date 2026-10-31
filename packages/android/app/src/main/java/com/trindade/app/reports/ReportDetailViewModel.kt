package com.trindade.app.reports

import com.trindade.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.aero.AeroDispatcher
import com.trindade.app.aero.AeroSettingsPort
import com.trindade.app.aero.AeroSyncOutcome
import com.trindade.app.contract.models.MassasReportDayView
import com.trindade.app.contract.models.ReportReceipts
import com.trindade.app.massas.MassasDaySource
import com.trindade.app.massas.MassasLaunchViewModel
import com.trindade.app.contract.models.PhotosResponsePhotosInner
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.export.NoOpShareManager
import com.trindade.app.export.ShareManager
import com.trindade.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One report: what it contains, its photos, and its WhatsApp text.
 *
 * The report is loaded once and the photos separately, because the photos change while the report
 * does not: attaching and deleting reload only the list, so a slow photo upload never blanks the
 * contents behind it.
 *
 * The export text is fetched on demand rather than with the rest. It is the one thing on this screen
 * the server computes by formatting, and a report missing a temperature makes it fail; fetching it
 * with the load would turn a missing reading into a screen that does not open at all.
 */
@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    private val repository: ReportsRepository,
    private val compressor: PhotoCompressor,
    // The platform seam for sharing, injected rather than reached for: the screen offers the action and
    // the default is the no-op the tests get, so a view model built without one shares nothing.
    private val shareManager: ShareManager = NoOpShareManager,
    // Optional on purpose: without a source the card simply does not draw, which is what a test that is
    // only about the report's own answers asks for.
    private val massasRepository: MassasDaySource? = null,
    private val aeroDispatcher: AeroDispatcher? = null,
    private val aeroSettingsStore: AeroSettingsPort? = null,
) : ViewModel() {

    data class UiState(
        val reportId: Int = 0,
        val loading: Boolean = true,
        val report: ReportResponseReport? = null,
        val photos: List<PhotosResponsePhotosInner> = emptyList(),
        val uploadingPhoto: Boolean = false,
        val exportText: String? = null,
        val loadingExport: Boolean = false,
        val message: UiText? = null,
        /** The day the massas card draws, read from the report's own endpoint. */
        val massasDay: MassasReportDayView? = null,
        /** The incoming supplies registered in the ERP for this date. */
        val receipts: ReportReceipts? = null,
        /** Whether an ERP sync via bridge is currently in progress. */
        val isSyncingErp: Boolean = false,
    ) {
        /** Five is the server's limit, and the add action is withheld rather than refused at the door. */
        val canAddPhoto: Boolean get() = !uploadingPhoto && photos.size < MAX_PHOTOS
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Reads the report, or answers from what this instance already holds -- unless [force] says not to.
     *
     * The early return is an optimisation for repeated calls within one arrival, and [force] exists to
     * say so rather than to add a feature. This view model is scoped to the activity's store, so it
     * outlives the composition that draws the report and survives a trip through the edit surface: the
     * `ReportResponseReport` it is holding is then the one that screen has just changed, and serving it
     * again would show the operator the values from before their own save -- the exact failure a reload
     * after a write exists to prevent. The report is not this screen's to cache in the first place: the
     * server owns the window and the values, so the arrival is the only moment either can be trusted,
     * which is what the route asks for by forcing the read there.
     *
     * The default keeps every existing caller -- and every test written against the old signature -- on
     * the behaviour it had, and it is the right default for a caller that only wants the report drawn
     * once.
     */
    fun load(reportId: Int, force: Boolean = false) {
        if (!force && state.value.reportId == reportId && state.value.report != null) return
        _state.update { it.copy(reportId = reportId, loading = true, message = null) }

        viewModelScope.launch {
            val report = repository.report(reportId)
            _state.update { current ->
                current.copy(
                    loading = false,
                    report = report,
                    message = if (report == null) NOT_FOUND else null,
                )
            }
            refreshPhotos(reportId)
            refreshMassas(report)
            refreshReceipts(report)
        }
    }

    /**
     * Reads the day's massas for this report.
     *
     * A report has a date, and the card is about that date's production, not about this report's own answers:
     * the two are related by the day, which is why the card is permanent and why a day with nothing recorded
     * still draws.
     */
    private fun refreshMassas(report: ReportResponseReport?) {
        // The source is optional, so the absence is checked here rather than at every call site.
        val source = massasRepository ?: return
        val date = report?.reportDate?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val day = source.reportDay(date)
            _state.update { it.copy(massasDay = day) }
        }
    }

    private fun refreshReceipts(report: ReportResponseReport?) {
        val date = report?.reportDate?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val receipts = repository.receipts(date)
            _state.update { it.copy(receipts = receipts) }
        }
    }

    /**
     * Reads the plant's day from the ERP via bridge and updates both massas and receipts (S6, S7).
     */
    fun syncErp() {
        val currentReport = state.value.report ?: return
        val date = currentReport.reportDate.takeIf { it.isNotBlank() } ?: return
        if (state.value.isSyncingErp) return
        val dispatcher = aeroDispatcher
        val store = aeroSettingsStore
        if (dispatcher == null || store == null) {
            _state.update { it.copy(message = UiText.of(R.string.report_erp_not_configured)) }
            return
        }

        viewModelScope.launch {
            val settings = store.load()
            if (!settings.ready) {
                _state.update { it.copy(message = UiText.of(R.string.report_erp_not_configured)) }
                return@launch
            }
            _state.update { it.copy(isSyncingErp = true, message = null) }
            when (val outcome = dispatcher.syncDay(settings, date)) {
                is AeroSyncOutcome.Synced -> {
                    store.markSynced(date, MassasLaunchViewModel.nowLabel())
                    val massas = massasRepository?.reportDay(date)
                    val recs = repository.receipts(date)
                    val export = repository.exportText(currentReport.id)
                    _state.update {
                        it.copy(
                            isSyncingErp = false,
                            massasDay = massas,
                            receipts = recs,
                            exportText = export ?: it.exportText,
                            message = UiText.of(R.string.report_erp_sync_success),
                        )
                    }
                }
                is AeroSyncOutcome.Unreachable -> {
                    _state.update {
                        it.copy(isSyncingErp = false, message = UiText.of(R.string.report_error_offline))
                    }
                }
                is AeroSyncOutcome.Failed -> {
                    _state.update {
                        it.copy(
                            isSyncingErp = false,
                            message = UiText.of(R.string.report_erp_sync_failed, outcome.reason ?: "-"),
                        )
                    }
                }
            }
        }
    }

    fun refreshPhotos(reportId: Int = state.value.reportId) {
        viewModelScope.launch {
            val photos = repository.photos(reportId)
            if (photos != null) _state.update { it.copy(photos = photos) }
        }
    }

    /**
     * Compresses the captured photo and sends it.
     *
     * Compression happens here rather than in the screen so the failure that matters is visible: a
     * photo that cannot be read is reported as such, instead of uploading the original and letting the
     * server answer 413 for a limit the client could have respected.
     */
    fun attachPhoto(path: String) {
        if (state.value.uploadingPhoto) return
        _state.update { it.copy(uploadingPhoto = true, message = null) }

        viewModelScope.launch {
            val reportId = state.value.reportId
            val bytes = compressor.compressToJpeg(path)

            if (bytes == null) {
                _state.update { it.copy(uploadingPhoto = false, message = UNREADABLE_PHOTO) }
                return@launch
            }

            when (val result = repository.attachPhoto(reportId, bytes)) {
                is PhotoAttachResult.Saved -> {
                    _state.update { it.copy(uploadingPhoto = false) }
                    refreshPhotos(reportId)
                }
                is PhotoAttachResult.Refused ->
                    _state.update { it.copy(uploadingPhoto = false, message = attachRefusal(result.statusCode)) }
                PhotoAttachResult.Unreachable ->
                    _state.update { it.copy(uploadingPhoto = false, message = UNREACHABLE) }
            }
        }
    }

    fun deletePhoto(photoId: Int) {
        viewModelScope.launch {
            when (repository.deletePhoto(photoId)) {
                PhotoDeleteResult.Deleted -> refreshPhotos()
                is PhotoDeleteResult.Refused ->
                    _state.update { it.copy(message = deleteRefusal(it)) }
                PhotoDeleteResult.Unreachable -> _state.update { it.copy(message = UNREACHABLE) }
            }
        }
    }

    /** Fetches the server-rendered WhatsApp text. Never assembled here. */
    fun loadExport() {
        if (state.value.loadingExport) return
        _state.update { it.copy(loadingExport = true, message = null) }

        viewModelScope.launch {
            // The server decides what a complete report contains; the app asks once and shows it.
            val text = repository.exportText(id = state.value.reportId)
            _state.update {
                it.copy(
                    loadingExport = false,
                    exportText = text,
                    message = if (text == null) INCOMPLETE_REPORT else null,
                )
            }
        }
    }


    /**
     * Shares the report's own server-rendered text.
     *
     * Offered beside that text rather than before it: a share fired with nothing loaded would put an empty
     * message in front of the operator and call it the report.
     */
    fun shareExport() {
        val text = state.value.exportText ?: return
        shareManager.shareText(text)
    }

    private fun deleteRefusal(state: UiState): UiText =
        if (state.report?.canEdit == false) OUTSIDE_EDIT_WINDOW else GENERIC

    private fun attachRefusal(statusCode: Int): UiText = when (statusCode) {
        413 -> PHOTO_TOO_LARGE
        415 -> PHOTO_NOT_IMAGE
        409 -> UiText.of(R.string.report_error_photo_limit, MAX_PHOTOS)
        403 -> OUTSIDE_EDIT_WINDOW
        else -> GENERIC
    }

    private companion object {
        const val MAX_PHOTOS = 5
        val NOT_FOUND = UiText.of(R.string.report_error_not_found)
        val UNREADABLE_PHOTO = UiText.of(R.string.report_error_unreadable_photo)
        val OUTSIDE_EDIT_WINDOW = UiText.of(R.string.report_error_outside_window)
        val INCOMPLETE_REPORT = UiText.of(R.string.report_error_incomplete)
        val UNREACHABLE = UiText.of(R.string.report_error_offline)
        val GENERIC = UiText.of(R.string.report_error_generic)
        val PHOTO_TOO_LARGE = UiText.of(R.string.report_error_photo_too_large)
        val PHOTO_NOT_IMAGE = UiText.of(R.string.report_error_photo_type)
    }
}
