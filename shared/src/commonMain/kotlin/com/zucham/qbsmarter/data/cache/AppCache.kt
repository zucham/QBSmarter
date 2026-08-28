package com.zucham.qbsmarter.data.cache

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.data.db.PairedCube
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.data.db.SolvesRepository
import com.zucham.qbsmarter.data.db.UserRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.domain.user.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Centralised in-memory cache for hot-path DB reads. When enabled, holds a
 * StateFlow snapshot of:
 *   • all profiles
 *   • paired cubes for the active profile
 *   • the recent-100 solves for stats
 *   • full solve count for the active profile
 *   • best (effective) duration for the active profile
 *   • settings map for the active profile (always observed – see [settings])
 *
 * **Why this layer exists.** Every screen that reads any of the above used
 * to open its own SQLDelight observer through a Repository. With the cache
 * enabled, all of them share a single observer per data type – opening a
 * screen no longer pays the cost of warming up a new DB query, and stat
 * cards don't go briefly empty during initial composition.
 *
 * **History solves are NOT cached.** The full History list can be tens of
 * thousands of rows; caching all of them in a single list would defeat the
 * whole point. The History screen uses a windowed StateFlow in
 * [com.zucham.qbsmarter.ui.screens.history.HistoryViewModel] that loads
 * 50 rows at a time as the user scrolls.
 *
 * **Disable semantics.** When the user toggles the "Use caching" setting
 * off, [setEnabled(false)] is called. The cached StateFlows immediately
 * emit empty/null, the underlying observers are cancelled, and reads that
 * still arrive at the cache delegate straight to the repository. The
 * [settings] flow is the documented exception – see its own note.
 *
 * **Profile switch behaviour.** Every cache flow keys off
 * [ActiveProfile.id] via `flatMapLatest`. Switching profiles cancels the
 * previous observer and starts fresh ones for the new profile – no manual
 * invalidation needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCache(
    userRepo: UserRepository,
    private val devicesRepo: DevicesRepository,
    private val solvesRepo: SolvesRepository,
    private val settingsRepo: SettingsRepository,
    private val activeProfile: ActiveProfile,
    scope: CoroutineScope,
) {
    private val log = Logger.withTag("AppCache")

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // -- Cached flows ------------------------------------------------------
    //
    // Each is gated by [enabled] AND by the active profile id. When
    // disabled, the gated flow emits an empty default (or null for
    // "current profile") and stays subscribed cheaply (no DB observer).

    val allProfiles: StateFlow<List<UserProfile>> =
        userRepo.observeAll()
            .gated(emptyList())
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pairedCubes: StateFlow<List<PairedCube>> =
        forActive { uid -> devicesRepo.observeForUser(uid) }
            .gated(emptyList())
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentSolves: StateFlow<List<SolveRow>> =
        forActive { uid -> solvesRepo.recentForStats(uid, RECENT_SOLVES_LIMIT) }
            .gated(emptyList())
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val solveCount: StateFlow<Long> =
        forActive { uid -> solvesRepo.observeCount(uid) }
            .gated(0L)
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * Best effective duration (durationMs + penaltyMs, excluding DNFs) for
     * the active profile. Re-derived whenever the recent-solves list or the
     * enabled flag changes; uses the indexed MIN query directly so we don't
     * need to scan the cached list (which only holds the most-recent 100).
     *
     * Emits null when caching is disabled – UI then falls through to a
     * direct repo call if it really wants the value.
     */
    val bestDurationMs: StateFlow<Long?> =
        recentSolves.map { rows ->
            if (!_enabled.value) return@map null
            // The recent list is bounded; an all-time best may be older,
            // so we always go to the DB rather than min'ing the list.
            val uid = activeProfile.idSnapshot() ?: return@map null
            solvesRepo.bestDuration(uid)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Settings map for the active profile. Empty only when there is no
     * active profile.
     *
     * **Deliberately NOT [gated] on [enabled]**, unlike every other flow
     * here. It is the one exception, for two reasons.
     *
     * The cost of keeping it is the smallest of the lot: a profile's
     * settings are a handful of rows, and the flag's purpose is to stop
     * the app holding *hot bulk* data (cube lists, solve windows) in
     * memory.
     *
     * The cost of gating it was a broken Settings screen. Every control
     * there renders `settings[key] ?: <default>` off this map, so with
     * caching off the map went empty and every control displayed its
     * default instead of the stored value. That included the caching
     * toggle itself: switch it off and it sprang back to showing "on",
     * so tapping it again wrote `false` a second time and the setting
     * could never be turned back on from the UI. A flow whose emptiness
     * is indistinguishable from "everything is at its default" cannot be
     * gated behind a user-visible flag.
     *
     * The synchronous accessors below still honour [enabled] and go to
     * the repository when it is off — those serve callers who asked not
     * to be served out of memory. This flow serves the screen that shows
     * the user what is actually in the database, which is a different
     * question.
     */
    val settings: StateFlow<Map<String, String>> =
        forActive { uid -> settingsRepo.observeAll(uid) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Tell the world we're alive. The Eagerly start strategy on each
        // StateFlow above means observers are spinning the moment AppCache
        // is constructed. ConnectionOrchestrator and AppLifecycle are also
        // eagerly resolved by Koin, so by the time the UI mounts, all the
        // flows are warm.
        log.d { "AppCache constructed; warming up flows" }
    }

    /**
     * Toggle caching at runtime. When set to false, observers stop and the
     * StateFlows emit empty/null. Calls to [boolSetting], [snapshotPairedCubes],
     * etc. fall through to the repository.
     *
     * When toggled back on, each flow's gating restarts the underlying
     * observers automatically – no manual rewarm needed.
     */
    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        log.d { "Cache enabled = $value" }
        _enabled.value = value
    }

    // -- Synchronous typed reads ------------------------------------------
    //
    // When the cache is on, prefer the [StateFlow]s above. These methods
    // exist for places that need a value RIGHT NOW (event handlers, init
    // blocks). They fall through to the repo when disabled, and to the
    // cached snapshot otherwise.

    fun boolSetting(key: String, default: Boolean): Boolean {
        val uid = activeProfile.idSnapshot() ?: return default
        if (_enabled.value) {
            settings.value[key]?.let { raw ->
                return raw == "1" || raw.equals("true", ignoreCase = true)
            }
        }
        return settingsRepo.getBool(uid, key, default)
    }

    fun snapshotPairedCubes(): List<PairedCube> {
        val uid = activeProfile.idSnapshot() ?: return emptyList()
        return if (_enabled.value) pairedCubes.value
        else devicesRepo.snapshotAllForUser(uid)
    }

    // -- Helpers ----------------------------------------------------------

    /**
     * Build a flow that re-runs [block] each time the active profile id
     * changes. Emits an empty default flow for the brief null-id window.
     */
    private fun <T> forActive(block: (String) -> Flow<T>): Flow<T> =
        activeProfile.id.flatMapLatest { uid ->
            if (uid == null) emptyFlow<T>() else block(uid)
        }

    /**
     * Gate a flow on [_enabled]: when off, the flow emits [whenDisabled]
     * indefinitely and the upstream observer is suspended. When the user
     * flips caching back on, observers automatically resubscribe.
     */
    @Suppress("NOTHING_TO_INLINE")
    private fun <T> Flow<T>.gated(whenDisabled: T): Flow<T> =
        _enabled.flatMapLatest { on -> if (on) this else flowOf(whenDisabled) }

    private companion object {
        /** How many recent solves we keep in memory for the stats cards. */
        const val RECENT_SOLVES_LIMIT = 100L
    }
}
