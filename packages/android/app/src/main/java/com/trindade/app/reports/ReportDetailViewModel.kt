package com.trindade.app.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.PhotosResponsePhotosInner
import com.trindade.app.contract.models.ReportResponseReport
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
) : ViewModel() {

    data class UiState(
        val reportId: Int = 0,
        val loading: Boolean = true,
        val report: ReportResponseReport? = null,
        val photos: List<PhotosResponsePhotosInner> = emptyList(),
        val uploadingPhoto: Boolean = false,
        val exportText: String? = null,
        val loadingExport: Boolean = false,
        val message: String? = null,
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
            val text = repository.exportText(state.value.reportId)
            _state.update {
                it.copy(
                    loadingExport = false,
                    exportText = text,
                    message = if (text == null) INCOMPLETE_REPORT else null,
                )
            }
        }
    }

    private fun deleteRefusal(state: UiState): String =
        if (state.report?.canEdit == false) OUTSIDE_EDIT_WINDOW else GENERIC

    private fun attachRefusal(statusCode: Int): String = when (statusCode) {
        413 -> "A foto ficou grande demais. Tente novamente."
        415 -> "O arquivo não é uma imagem aceita."
        409 -> "O relatório já tem $MAX_PHOTOS fotos. Remova uma antes."
        403 -> OUTSIDE_EDIT_WINDOW
        else -> GENERIC
    }

    private companion object {
        const val MAX_PHOTOS = 5
        const val NOT_FOUND = "Relatório não encontrado."
        const val UNREADABLE_PHOTO = "Não foi possível ler a foto."
        const val OUTSIDE_EDIT_WINDOW = "Fora do prazo de edição: este relatório é somente leitura."
        const val INCOMPLETE_REPORT = "Falta preencher todas as temperaturas para gerar o texto."
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
        const val GENERIC = "Não foi possível concluir. Tente de novo."
    }
}
