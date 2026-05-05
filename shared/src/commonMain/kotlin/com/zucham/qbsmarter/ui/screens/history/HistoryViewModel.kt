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
 * Solve history with progressive loading.
 *
 *   • A single [items: StateFlow<List<SolveRow>>] holds whatever has been
 *     loaded so far for the current sort.
 *   • [maybeLoadMore] is called from the screen's `LaunchedEffect` block
 *     when the user scrolls within [PREFETCH_TRIGGER] items of the bottom.
 *     It synchronously appends the next [PAGE_SIZE] rows to [items].
 *   • A page-loading job is reused – repeated calls while a load is in
 *     flight are coalesced.
 *
 * Sort or profile change resets the window to the first page. Solve count
 * comes from the cache.
 */
class HistoryViewModel(
    private val solvesRepo: SolvesRepository,
    private val activeProfile: ActiveProfile,
    cache: AppCache,
) : ViewModel() {

    private val _sort = MutableStateFlow(SolveSort.DATE_DESC)
    val sort: StateFlow<SolveSort> = _sort.asStateFlow()

    private val _items = MutableStateFlow<List<SolveRow>>(emptyList())
    val items: StateFlow<List<SolveRow>> = _items.asStateFlow()

    /** True while a window expansion is in flight. Used to disable repeated requests. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** True when the most recent page returned fewer than [PAGE_SIZE] rows. */
    private val _atEnd = MutableStateFlow(false)
    val atEnd: StateFlow<Boolean> = _atEnd.asStateFlow()

    /** Total solve count for the active profile, surfaced to the UI. */
    val totalCount: StateFlow<Long> = cache.solveCount

    private var loadJob: Job? = null

    init {
        // Reset & seed the first page whenever sort or active profile changes.
        viewModelScope.launch {
            combineSortAndUser().collect { (sort, uid) ->
                if (uid == null) {
                    _items.value = emptyList()
                    _atEnd.value = false
                    return@collect
                }
                resetAndLoadFirst(sort, uid)
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
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                val current = _items.value
                val rows = withContext(Dispatchers.Default) {
                    solvesRepo.page(
                        userId = uid,
                        sort = _sort.value,
                        limit = PAGE_SIZE.toLong(),
                        offset = current.size.toLong(),
                    )
                }
                _items.value = current + rows
                _atEnd.value = rows.size < PAGE_SIZE
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Re-fetch from offset 0. Called when the screen re-enters composition
     * (so a row added by the timer while we were elsewhere shows up) and
     * after [delete] commits.
     */
    fun refresh() {
        val uid = activeProfile.idSnapshot() ?: return
        viewModelScope.launch { resetAndLoadFirst(_sort.value, uid) }
    }

    fun delete(id: Long) {
        solvesRepo.delete(id)
        // Optimistically remove from the local list so the row vanishes
        // immediately; refresh() will reconcile if anything else changed.
        _items.value = _items.value.filterNot { it.id == id }
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

    private suspend fun resetAndLoadFirst(sort: SolveSort, uid: String) {
        loadJob?.cancel()
        _loading.value = true
        try {
            val rows = withContext(Dispatchers.Default) {
                solvesRepo.page(uid, sort, PAGE_SIZE.toLong(), 0L)
            }
            _items.value = rows
            _atEnd.value = rows.size < PAGE_SIZE
        } finally {
            _loading.value = false
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
