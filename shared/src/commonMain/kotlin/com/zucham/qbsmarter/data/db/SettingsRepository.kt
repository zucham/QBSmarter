package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.zucham.qbsmarter.db.QbsmarterDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-profile key/value settings. All settings travel with the profile –
 * switching profiles swaps themes, language, inspection toggle, etc.
 *
 * **Performance**: settings reads happen at most a handful of times per
 * recomposition, so plain DB calls are fine. The optional [AppCache]
 * layer sits in front of this repository for hot reads.
 *
 * **Typed APIs**: [getBool] / [getInt] / [getString] decode from the TEXT
 * column; [setBool] / [setInt] / [setString] encode. The per-key value
 * format is documented near each [Keys] entry.
 */
class SettingsRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    // -- Raw string accessors ----------------------------------------------

    fun get(userId: String, key: String): String? =
        db.settingsQueries.get(userId, key).executeAsOneOrNull()

    fun put(userId: String, key: String, value: String) {
        db.settingsQueries.put(userId, key, value)
    }

    /**
     * Reactive view of a single key. Implemented coarsely (observing the
     * whole table for the user, then projecting) – fine because the
     * settings table per profile holds only a handful of rows. With the
     * AppCache layer in place, most callers don't reach this directly
     * anyway.
     */
    fun observe(userId: String, key: String): Flow<String?> =
        db.settingsQueries.selectAllForUser(userId).asFlow().mapToList(ioDispatcher)
            .map { all -> all.firstOrNull { it.key == key }?.value_ }
            .distinctUntilChanged()

    /** Whole-snapshot map for a profile – used by export and by AppCache warm-up. */
    fun snapshot(userId: String): Map<String, String> =
        db.settingsQueries.selectAllForUser(userId).executeAsList()
            .associate { it.key to it.value_ }

    /** Reactive snapshot of all settings for a profile. */
    fun observeAll(userId: String): Flow<Map<String, String>> =
        db.settingsQueries.selectAllForUser(userId).asFlow().mapToList(ioDispatcher)
            .map { all -> all.associate { it.key to it.value_ } }
            .distinctUntilChanged()

    /** Bulk-apply a map (used by import). Atomic: one transaction. */
    fun applyAll(userId: String, values: Map<String, String>) {
        db.transaction {
            for ((k, v) in values) db.settingsQueries.put(userId, k, v)
        }
    }

    // -- Typed accessors ---------------------------------------------------

    fun getBool(userId: String, key: String, default: Boolean): Boolean =
        get(userId, key)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: default

    fun setBool(userId: String, key: String, value: Boolean) {
        put(userId, key, if (value) "1" else "0")
    }

    fun observeBool(userId: String, key: String, default: Boolean): Flow<Boolean> =
        observe(userId, key)
            .map { raw -> raw?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: default }
            .distinctUntilChanged()

    fun getInt(userId: String, key: String, default: Int): Int =
        get(userId, key)?.toIntOrNull() ?: default

    fun setInt(userId: String, key: String, value: Int) {
        put(userId, key, value.toString())
    }

    fun getString(userId: String, key: String, default: String? = null): String? =
        get(userId, key) ?: default

    fun setString(userId: String, key: String, value: String) {
        put(userId, key, value)
    }

    /**
     * Setting keys, centralised so a typo at one call site can't drift
     * away from another. Each comment documents the value format.
     */
    object Keys {
        // Solving
        /** "1" / "0". Default true. */
        const val INSPECTION_ENABLED = "solving.inspectionEnabled"

        // /** "1" / "0". Default false. */
        // const val SOUND_ENABLED = "solving.soundEnabled"
        // ^^^ Sound-effects toggle disabled until cube-event sound design
        // lands. Commented out (rather than deleted) so re-enabling it is
        // a one-line revert when we return to the feature. The string
        // resource (`settings_sound`) is also preserved.

        /**
         * "1" / "0". Default false. Whether the 3D cube follows the
         * physical cube's gyroscope.
         *
         * Off by default because it only does anything on the subset of
         * cubes that carry the sensor, and because a cube that moves on
         * its own is a surprise for a user who didn't ask for it. Stored
         * per profile like every other preference, so it survives a
         * restart and travels with the profile.
         */
        const val GYRO_ENABLED = "solving.gyroEnabled"

        // Display
        /** "1" / "0". Default true. */
        const val KEEP_SCREEN_ON = "solving.keepScreenOn"

        // Theme & locale
        /** ThemeSeed.key – "blue", "green", … */
        const val THEME_SEED = "display.theme.seed"
        /** ThemeMode.key – "system", "light", "dark". */
        const val THEME_MODE = "display.theme.mode"
        /** AppLanguage.key – "system", "en", "cs". */
        const val LANGUAGE = "display.ui.language"

        // App-wide tuning (per profile so individual users can opt out)
        /**
         * "1" / "0". Default true. When true, the app warms an in-memory
         * cache of hot DB reads (paired cubes, recent solves, settings, …)
         * for snappier UI. Turning off frees the cache immediately.
         */
        const val CACHE_ENABLED = "app.cacheEnabled"
    }
}
