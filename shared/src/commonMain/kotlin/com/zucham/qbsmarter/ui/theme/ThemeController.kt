package com.zucham.qbsmarter.ui.theme

import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Theme as a function of the active profile's settings. Reads from the
 * AppCache's per-profile settings map (which itself observes
 * SettingsRepository) – switching profiles automatically swaps the theme
 * to whatever was persisted for the new profile.
 *
 * Why an explicit StateFlow rather than `derivedStateOf` in AppTheme:
 * AppTheme is composed inside the composition root, but
 * [ApplySystemBarsTheme] runs as a SideEffect that we want to fire on
 * every theme change – keeping the StateFlow at the controller layer
 * makes the dataflow explicit and lets non-Compose callers observe.
 */
class ThemeController(
    private val cache: AppCache,
    private val settings: SettingsRepository,
    private val activeProfile: ActiveProfile,
    scope: CoroutineScope,
) {
    /**
     * Observed seed. Backed by [AppCache.settings] (which is the active
     * profile's settings snapshot). The initial value is `BLUE` for the
     * brief moment before warm-up; after warm-up it tracks the persisted
     * choice for the active profile.
     */
    val seed: StateFlow<ThemeSeed> = cache.settings
        .map { ThemeSeed.fromKey(it[SettingsRepository.Keys.THEME_SEED]) }
        .stateIn(scope, SharingStarted.Eagerly, ThemeSeed.BLUE)

    val mode: StateFlow<ThemeMode> = cache.settings
        .map { ThemeMode.fromKey(it[SettingsRepository.Keys.THEME_MODE]) }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /**
     * Persist a new seed. Writes against the *current* active profile.
     * If no profile is active (pre-bootstrap window – should never happen
     * in practice), the call is silently dropped.
     */
    fun setSeed(seed: ThemeSeed) {
        val uid = activeProfile.idSnapshot() ?: return
        settings.setString(uid, SettingsRepository.Keys.THEME_SEED, seed.key)
    }

    fun setMode(mode: ThemeMode) {
        val uid = activeProfile.idSnapshot() ?: return
        settings.setString(uid, SettingsRepository.Keys.THEME_MODE, mode.key)
    }
}
