package com.trindade.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.DashboardSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The dashboard's read, the two states it can end in, and the reason it is not done once.
 *
 * There is no `failed` flag, and its absence is the point: *not loading and no summary* **is** the failure,
 * so a flag beside the data would be a second thing saying what the first already says -- and a flag and
 * the data it describes can disagree, one of them set and the other not. The way to stop two things
 * disagreeing is not to have two, so the state carries the answer and nothing else.
 *
 * The failure has no sentence here either, which is the same decision one level down. The loading and
 * history view models carry a `message: String` because a *write* can be refused by the server and the
 * refusal's own words have to reach the operator; this screen only reads, so the only failure there is is
 * "the read did not arrive", and the sentence for it belongs with the screen that draws it, not copied
 * into a view model as a fifth copy of a string the app already repeats.
 *
 * Nothing here distinguishes a timeout from a refused connection from an unparsable answer, and that is
 * deliberate: the screen must not claim a cause the read has not been shown to have. The login line of work
 * spent a whole slice on exactly that mistake.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: DashboardRepository,
) : ViewModel() {

    /**
     * Loading, or the answer once it arrived. A state that is not loading and carries no summary is the
     * failure -- there is no flag to consult and nothing that could contradict the summary beside it.
     */
    data class UiState(
        val loading: Boolean = true,
        val summary: DashboardSummary? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The read in flight, if there is one. Held so the next [refresh] can cancel it: this app's view models
     * are activity-scoped, so refreshes arrive from a screen that can be left and re-entered at any moment,
     * and two live reads are two answers racing to write one state.
     */
    private var reading: Job? = null

    /**
     * Reads the summary. Called on every arrival at the screen rather than once from `init`.
     *
     * Once is what this class did first, and it cannot work here for a reason that is a property of this
     * app rather than of this screen: `hiltViewModel()` resolves `LocalViewModelStoreOwner` to the activity,
     * which has no `NavHost` anywhere in `main`, so this instance and its state outlive both the tab that
     * drew them and the session that filled them. Two things follow, and both are why the read belongs to
     * the arrival:
     *
     *  * a read that failed would otherwise stay failed for the life of the activity, with nothing on the
     *    screen able to try again;
     *  * a number is about *this* session's day, and the next operator to sign in on the same activity would
     *    be shown the previous operator's summary.
     *
     * The previous answer is dropped before the read for the second reason: showing it while the new one is
     * in flight is exactly the moment the wrong operator's numbers would be on screen. It is also what the web
     * page does -- it fetches on mount and draws its spinner until the answer lands.
     *
     * **The read already in flight is cancelled first.** Two reads in the air are two answers that can land in
     * either order, and the older one landing last would overwrite the newer with what the last refresh had
     * already replaced -- which is how a previous session's summary could come back after this session had read
     * its own. The history view models in this app keep a request token for the same guarantee; for a single
     * read, cancelling the one in flight is that guarantee with less machinery.
     */
    fun refresh() {
        reading?.cancel()
        _state.update { UiState(loading = true) }
        reading = viewModelScope.launch {
            val summary = repository.summary()
            _state.update { it.copy(loading = false, summary = summary) }
        }
    }
}
