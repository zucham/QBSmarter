package com.zucham.qbsmarter.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.data.db.SolveSort
import com.zucham.qbsmarter.data.db.SolvesRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One published window of loaded history rows, plus the identity of the
 * window they belong to.
 *
 * [generation] increments every time the window is *reset* – a sort
 * change, a profile switch, or a screen-entry refresh – and stays put
 * while [HistoryViewModel.maybeLoadMore] appends further pages to the
 * same window.
 *
 * Rows and generation travel together in a single value on purpose. The
 * screen resets its scroll position when the generation changes, and
 * bundling the two means the new generation becomes visible to
 * composition in the very same frame as the rows it describes. Publishing
 * them as two separate flows would let the screen observe one without the
 * other and scroll the wrong list to the top.
 */
data class HistoryWindow(
    val generation: Int = 0,
    val rows: List<SolveRow> = emptyList(),
)

/**
 * Solve history with progressive loading.
 *
 *   • A single [window: StateFlow<HistoryWindow>] holds whatever has been
 *     loaded so far for the current sort.
 *   • [maybeLoadMore] is called from the screen's `LaunchedEffect` block
 *     when the user scrolls within [PREFETCH_TRIGGER] items of the bottom.
 *     It appends the next [PAGE_SIZE] rows to the current window, keeping
 *     its generation.
 *   • A page-loading job is reused – repeated calls while a load is in
 *     flight are coalesced.
 *
 * Sort or profile change resets the window to the first page under a new
 * generation. Solve count comes from the cache.
 */
class HistoryViewModel(
    private val solvesRepo: SolvesRepository,
    private val activeProfile: ActiveProfile,
    cache: AppCache,
) : ViewModel() {

    private val _sort = MutableStateFlow(SolveSort.DATE_DESC)
    val sort: StateFlow<SolveSort> = _sort.asStateFlow()

    private val _window = MutableStateFlow(HistoryWindow())
    val window: StateFlow<HistoryWindow> = _window.asStateFlow()

    /**
     * Bumped by every window reset; stamped onto the published
     * [HistoryWindow] so the screen can tell "same list, more rows" from
     * "different list entirely".
     */
    private var generation = 0

    /** True while a window expansion is in flight. Used to disable repeated requests. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** True when the most recent page returned fewer than [PAGE_SIZE] rows. */
    private val _atEnd = MutableStateFlow(false)
    val atEnd: StateFlow<Boolean> = _atEnd.asStateFlow()

    /** Total solve count for the active profile, surfaced to the UI. */
    val totalCount: StateFlow<Long> = cache.solveCount

    private var loadJob: Job? = null

    /**
     * Monotonic id for the most recently *started* load. Only the load
     * that still owns the token is allowed to clear [_loading] when it
     * finishes.
     *
     * Without this, a page load cancelled by a reset would run its
     * `finally` block after the reset had already flipped `loading` to
     * true, publishing a spurious `false` in the middle of the reset's
     * own load – which both hides the spinner and re-opens the
     * [maybeLoadMore] gate mid-reset.
     */
    private var loadToken = 0

    init {
        // Reset & seed the first page whenever sort or active profile changes.
        viewModelScope.launch {
            combineSortAndUser().collect { (sort, uid) ->
                if (uid == null) {
                    // No profile → empty list, and a new generation so the
                    // screen doesn't hold a scroll offset from the profile
                    // we just left.
                    generation++
                    _window.value = HistoryWindow(generation, emptyList())
                    _atEnd.value = false
                    return@collect
                }
                resetAndLoadFirst(sort, uid, anchorTop = true)
            }
        }
    }

    private fun combineSortAndUser() =
        kotlinx.coroutines.flow.combine(_sort, activeProfile.id) { s, u -> s to u }

    fun setSort(value: SolveSort) {
        if (_sort.value == value) return
        _sort.value = value
    }

    /**
     * Called by the screen as the user scrolls. Idempotent and cheap –
     * coalesces concurrent calls and short-circuits when the cache is
     * already at the end.
     */
    fun maybeLoadMore() {
        if (_loading.value || _atEnd.value) return
        val uid = activeProfile.idSnapshot() ?: return
        loadJob?.cancel()
        val token = ++loadToken
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                val current = _window.value
                val rows = withContext(Dispatchers.Default) {
                    solvesRepo.page(
                        userId = uid,
                        sort = _sort.value,
                        limit = PAGE_SIZE.toLong(),
                        offset = current.rows.size.toLong(),
                    )
                }
                // A reset that landed while the query was in flight wins:
                // appending here would splice rows from the old sort onto
                // the new window. Cancellation normally gets us first, but
                // the check costs nothing and closes the gap.
                if (_window.value.generation == current.generation) {
                    _window.value = current.copy(rows = current.rows + rows)
                    _atEnd.value = rows.size < PAGE_SIZE
                }
            } finally {
                if (loadToken == token) _loading.value = false
            }
        }
    }

    /**
     * Re-fetch from offset 0.
     *
     * [anchorTop] decides whether this counts as a *new* window. Screen
     * entry passes `true`: the user is arriving at the list and expects
     * to land at the top. In-place reloads – [setPenalty], for instance –
     * pass `false`, so the generation is unchanged and the screen keeps
     * the user where they were rather than yanking them to the top for
     * an edit they made on a row halfway down.
     */
    fun refresh(anchorTop: Boolean = false) {
        val uid = activeProfile.idSnapshot() ?: return
        viewModelScope.launch { resetAndLoadFirst(_sort.value, uid, anchorTop) }
    }

    fun delete(id: Long) {
        solvesRepo.delete(id)
        // Optimistically remove from the local list so the row vanishes
        // immediately; refresh() will reconcile if anything else changed.
        // Same generation – removing one row doesn't invalidate the
        // user's scroll position.
        val current = _window.value
        _window.value = current.copy(rows = current.rows.filterNot { it.id == id })
    }

    /**
     * Update DNF / +2 flags on a solve. Called from the post-solve
     * buttons on the Solve screen and the History detail dialog.
     */
    fun setPenalty(id: Long, isDnf: Boolean, penaltyMs: Long) {
        solvesRepo.updatePenalty(id, isDnf, penaltyMs)
        // Refresh – the row may have moved positions if sorted by time.
        refresh()
    }

    /**
     * Drop the window back to a single page.
     *
     * The generation bump happens *with* the row publication, not before
     * it: the screen keys its scroll reset on the generation, so bumping
     * early would have it scroll a list it is about to replace.
     */
    private suspend fun resetAndLoadFirst(sort: SolveSort, uid: String, anchorTop: Boolean) {
        loadJob?.cancel()
        val token = ++loadToken
        _loading.value = true
        try {
            val rows = withContext(Dispatchers.Default) {
                solvesRepo.page(uid, sort, PAGE_SIZE.toLong(), 0L)
            }
            if (anchorTop) generation++
            _window.value = HistoryWindow(generation, rows)
            _atEnd.value = rows.size < PAGE_SIZE
        } finally {
            if (loadToken == token) _loading.value = false
        }
    }

    private companion object {
        /** Window expansion size. Each call to [maybeLoadMore] appends up to this many rows. */
        const val PAGE_SIZE = 50

        /**
         * Distance from the visible bottom (in items) at which the screen
         * pre-emptively requests the next page. Big enough that the next
         * batch is in memory before the user has scrolled to the spinner;
         * small enough that we don't load the whole DB up front.
         */
        const val PREFETCH_TRIGGER = 10
    }
}
