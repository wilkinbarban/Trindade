package com.trindade.app.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.ReportsResponseReportsInner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The report history: one page at a time, filtered by a day, a month, or nothing.
 *
 * The two filters are mutually exclusive rather than additive, and choosing one clears the other. The
 * server would accept both in one request and match `date` with the month's first day, so a request
 * carrying the two would quietly answer a narrower question than the two controls on screen appear to
 * ask -- and the month button would be a control that does not do what it says.
 *
 * A filter that is not set is sent as null and never as the empty string. The server validates both
 * against a regex -- `^\d{4}-\d{2}-\d{2}$` for the day and `^\d{4}-\d{2}$` for the month -- so `date=`
 * is a 400 answering a client that meant "no filter". Retrofit omits a null query parameter, which is
 * the request the server reads as "the whole history".
 *
 * The page size is left to the server, which defaults to the 30 the SPA sends explicitly. A constant
 * here would be a second copy of a server rule that nothing on this client depends on, since the
 * pagination comes back in the answer either way.
 */
@HiltViewModel
class ReportsHistoryViewModel @Inject constructor(
    private val repository: ReportsRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val items: List<ReportsResponseReportsInner> = emptyList(),
        val page: Int = FIRST_PAGE,
        val totalPages: Int = 0,
        val total: Int = 0,
        /** The day filter as `YYYY-MM-DD`, or null when the history is not filtered by a day. */
        val date: String? = null,
        /** The month filter as `YYYY-MM`, or null when the history is not filtered by a month. */
        val month: String? = null,
        /** True while a deactivation or a deletion is in flight, which withholds both from every row. */
        val busy: Boolean = false,
        /**
         * True once a page of the history has actually been read from the server.
         *
         * The page count and the footer are claims about a page that exists, and on a failed first read
         * there is no page: the counts are this state's defaults. Left as it was when a later read fails,
         * because the page still on screen is the one that was read.
         */
        val loaded: Boolean = false,
        val message: String? = null,
    ) {
        /**
         * The pagination's own bounds, and nothing invented on top of them.
         *
         * The `!loading` half is deliberately not here: a page in flight is a rendering concern, and
         * the screen already has the flag. What this must not do is guess a bound the server did not
         * send -- there is no "page 1 of at least 1" here, only the page and the count it came with.
         */
        val canGoPrevious: Boolean get() = page > FIRST_PAGE

        /**
         * False on an empty history, where the server answers `totalPages` 0 and there is no page to
         * move to. Not clamped to 1 for the same reason as above: the only thing that reads this wants
         * to know whether another page exists, not to draw the count.
         */
        val canGoNext: Boolean get() = page < totalPages
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Filters by one day, which clears the month, and returns to the first page of the new answer. */
    fun onDateSelected(iso: String) {
        _state.update { it.copy(date = iso, month = null) }
        load(FIRST_PAGE)
    }

    /** Filters by one month, which clears the day, and returns to the first page of the new answer. */
    fun onMonthSelected(iso: String) {
        _state.update { it.copy(month = iso, date = null) }
        load(FIRST_PAGE)
    }

    fun clearFilters() {
        _state.update { it.copy(date = null, month = null) }
        load(FIRST_PAGE)
    }

    fun previous() {
        if (!state.value.canGoPrevious) return
        load(state.value.page - 1)
    }

    fun next() {
        if (!state.value.canGoNext) return
        load(state.value.page + 1)
    }

    /**
     * Loads one page with the filters currently set, the current page by default.
     *
     * The filters are read before the request is launched rather than from inside the coroutine, so the
     * page that arrives is always the page for the filter that was on screen when it was asked for.
     */
    fun load(page: Int = state.value.page) {
        val filters = state.value
        _state.update { it.copy(loading = true, message = null) }

        viewModelScope.launch {
            val history = repository.history(
                date = filters.date,
                month = filters.month,
                page = page,
                pageSize = null,
            )

            if (history == null) {
                // Null is "the server did not answer", not "there are no reports". An empty list drawn
                // over a failed read would report a network fault as a fact about the history.
                _state.update { it.copy(loading = false, message = UNREACHABLE) }
                return@launch
            }

            // A page past the end answers an empty list with a well-formed pagination instead of an
            // error, and above the first page that means the page just lost its last row -- which is
            // what a deletion does. Stepping back one page is what keeps the operator off a blank
            // screen still labelled "Página 2 de 2"; one page at a time is enough, because that is as
            // far as the single deletion that caused it could have moved the end.
            if (history.items.isEmpty() && page > FIRST_PAGE) {
                load(page - 1)
                return@launch
            }

            _state.update {
                it.copy(
                    loading = false,
                    items = history.items,
                    page = history.pagination.page,
                    totalPages = history.pagination.totalPages,
                    total = history.pagination.total,
                    loaded = true,
                )
            }
        }
    }

    /** Deactivates a report, keeping it in the history. See [lifecycle] for what may be refused. */
    fun deactivate(id: Int) = lifecycle(id, offered = ::offersDeactivate) { repository.deactivate(id) }

    /** Deletes a report. See [lifecycle] for what may be refused. */
    fun delete(id: Int) = lifecycle(id, offered = ::offersDelete) { repository.delete(id) }

    /**
     * Runs one lifecycle call, but only for a row the server offered it for.
     *
     * The offer is the item's own flag, read as `== true` by [offersDeactivate] and [offersDelete] and
     * never as `!= false`. The contract marks all five lifecycle flags optional even though the server
     * always emits them, so the generated type carries them as `Boolean? = null`, and `!= false` would
     * read an absent flag as permission -- drawing an action whose only possible answer is the 403 the
     * flag was there to report.
     *
     * Nothing else is refused here. Who may deactivate or delete is the server's decision, and the flag
     * is the server's own answer to it, which is why the gate is the flag and not a rule written here:
     * this client has no role of its own to weigh, and a second permission rule would be a second thing
     * to keep in agreement with the server's.
     */
    private fun lifecycle(
        id: Int,
        offered: (ReportsResponseReportsInner) -> Boolean,
        call: suspend () -> ReportLifecycleResult,
    ) {
        if (state.value.busy) return
        val item = state.value.items.firstOrNull { it.id == id } ?: return
        if (!offered(item)) return

        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            when (val result = call()) {
                ReportLifecycleResult.Changed -> {
                    _state.update { it.copy(busy = false) }
                    // The page on screen rather than the first one: the row that changed is on it, and a
                    // jump to page one would move the operator away from where they were reading. If the
                    // change emptied the last page, [load] steps back to the page that still has rows.
                    load()
                }

                is ReportLifecycleResult.Refused ->
                    _state.update { it.copy(busy = false, message = refusalMessage(result.statusCode)) }

                ReportLifecycleResult.Unreachable ->
                    _state.update { it.copy(busy = false, message = UNREACHABLE) }
            }
        }
    }

    private fun refusalMessage(statusCode: Int): String = when (statusCode) {
        // Both routes check for Administrador, and this is the pair where the server's own flag
        // overstates the caller: the history drew a delete action for a Trabalhador because `canDelete`
        // said so, and the route answers 403. Naming the role the server checks is the honest thing to
        // say -- the client cannot know the caller's role, only that it was refused here.
        403 -> ADMIN_ONLY
        404 -> GONE
        else -> GENERIC
    }

    companion object {
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
        const val GENERIC = "Não foi possível concluir. Tente de novo."
        const val ADMIN_ONLY = "Só um administrador pode fazer isso."
        const val GONE = "Este relatório não existe mais."
    }
}

/** The first page of the history, which is also what a missing `page` means to the server. */
private const val FIRST_PAGE = 1

/**
 * Whether the server offered to deactivate this report.
 *
 * `== true` and never `!= false`: see [ReportsHistoryViewModel.lifecycle] for why an absent flag is
 * not a permission. Named here rather than written at each call site so the screen that draws the
 * action and the view model that guards it cannot disagree about when it is offered.
 */
internal fun offersDeactivate(item: ReportsResponseReportsInner): Boolean = item.canDeactivate == true

/** Whether the server offered to delete this report. See [offersDeactivate]. */
internal fun offersDelete(item: ReportsResponseReportsInner): Boolean = item.canDelete == true
