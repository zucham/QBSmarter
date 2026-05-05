package com.zucham.qbsmarter.data.cache

import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Bridges the per-profile `app.cache.enabled` setting to [AppCache.setEnabled].
 *
 * Why this is its own class: [AppCache] doesn't know about
 * [SettingsRepository] (avoiding a dependency cycle – settings reads themselves
 * go through the cache). [CacheController] sits one level up and listens
 * for changes, calling into AppCache.
 *
 * Eagerly-resolved by Koin (see Application.onCreate) so the cache flips
 * to whatever the persisted setting says before any UI renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheController(
    private val cache: AppCache,
    private val settings: SettingsRepository,
    activeProfile: ActiveProfile,
    scope: CoroutineScope,
) {

    init {
        // Re-evaluate the cache flag every time the active profile changes
        // OR the setting itself changes. The composed flow uses observe()
        // (not the cache) deliberately: at this layer we ARE the cache
        // controller, so reading our own cached value would be circular
        // before the first toggle has even fired.
        activeProfile.id
            .flatMapLatest { uid ->
                if (uid == null) flowOf(true)  // pre-bootstrap
                else settings.observeBool(uid, SettingsRepository.Keys.CACHE_ENABLED, default = true)
            }
            .onEach { enabled -> cache.setEnabled(enabled) }
            .launchIn(scope)
    }
}
