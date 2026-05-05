package com.zucham.qbsmarter.ui.i18n

import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Supported UI languages. SYSTEM = follow the OS; explicit values force
 * the app's locale regardless.
 *
 * Adding a new language requires:
 *   1. A new entry here with the matching IETF tag.
 *   2. A composeResources/values-{tag}/strings.xml file.
 */
enum class AppLanguage(val key: String, val tag: String?) {
    SYSTEM(key = "system", tag = null),
    ENGLISH(key = "en", tag = "en"),
    CZECH(key = "cs", tag = "cs");

    companion object {
        fun fromKey(key: String?): AppLanguage =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Per-profile UI language. Switching profiles applies that profile's
 * persisted language. Unlike [ThemeController], the locale change has a
 * platform side-effect: on Android, [AppLanguage.tag] flows through
 * `AppCompatDelegate.setApplicationLocales(...)`, which internally
 * calls `Activity.recreate()` – and `recreate()` is a strict
 * **main-thread-only** call.
 *
 * **Threading contract.** Every call into [LocaleApplier.apply] MUST be
 * on the main dispatcher. The reactive observer below funnels its
 * emissions through `withContext(Dispatchers.Main)` so a setting
 * change written from any background dispatcher (DB I/O, import flow,
 * VM action) reaches the platform applier safely. Without this
 * funneling, a language change would crash with:
 *
 *     IllegalStateException: Must be called from main thread
 *         at android.app.Activity.recreate
 *         at AppCompatDelegateImpl.applyApplicationSpecificConfig
 *         at AppCompatDelegate.setApplicationLocales
 *
 * The `init`-time apply is allowed to be synchronous because Koin's
 * graph initialization runs on the main thread (Application.onCreate),
 * so we're already on Main when the controller is constructed.
 */
class LocaleController(
    cache: AppCache,
    private val settings: SettingsRepository,
    private val activeProfile: ActiveProfile,
    private val applier: LocaleApplier,
    scope: CoroutineScope,
) {
    val language: StateFlow<AppLanguage> = cache.settings
        .map { AppLanguage.fromKey(it[SettingsRepository.Keys.LANGUAGE]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AppLanguage.SYSTEM)

    init {
        // Apply the persisted choice once at construction. Koin's graph
        // initialisation happens on the main thread during the
        // Application.onCreate path, so calling apply() synchronously
        // here is on Main by construction. This sets the locale BEFORE
        // the first Activity is created so compose-resources picks up
        // the right string set on the very first frame.
        applier.apply(language.value)

        // React to subsequent changes. The eagerly-started StateFlow
        // re-emits its initial value to every subscriber – drop(1) to
        // skip the duplicate of what we just applied above.
        //
        // Each emission is forwarded to the platform applier on the
        // main dispatcher. `scope` is Default-backed (see AppModule),
        // so without the explicit `withContext(Main)` we'd reach
        // AppCompatDelegate from a background thread and crash. See
        // the kdoc above for the full stack.
        language
            .drop(1)
            .onEach { newLanguage ->
                withContext(Dispatchers.Main) { applier.apply(newLanguage) }
            }
            .launchIn(scope)
    }

    /**
     * Explicit user toggle. Persists, the StateFlow updates via the cache
     * observer chain, and the platform applier is fired through the
     * [language] subscription above (on the main dispatcher).
     */
    fun setLanguage(value: AppLanguage) {
        val uid = activeProfile.idSnapshot() ?: return
        settings.setString(uid, SettingsRepository.Keys.LANGUAGE, value.key)
    }

    /**
     * Push whatever the StateFlow currently holds through the platform
     * applier. The import flow calls this once it's safely off the SAF
     * result main-thread stack and on the main dispatcher (see
     * SettingsViewModel.importAll's `withContext(Dispatchers.Main) { ... }`)
     * so AppCompatDelegate's Activity-recreate doesn't fire mid-flight.
     *
     * Caller is responsible for invoking this on Main – this method
     * does not switch dispatchers. The reactive [language] observer
     * already handles the on-change apply on Main, so this method
     * exists only for the "force re-apply at a precise point in the
     * flow" use case (import).
     */
    fun flushApplied() {
        applier.apply(language.value)
    }
}

/**
 * Platform-specific locale machinery. Android uses AppCompat's per-app
 * locale API so the choice is reflected in the system Settings panel and
 * persists across process restarts. Other targets are no-ops because
 * compose-resources picks up the JVM default locale on its own.
 *
 * Implementations may call into platform APIs that require the main
 * thread – see the [LocaleController] kdoc.
 */
interface LocaleApplier {
    fun apply(language: AppLanguage)
}

/** No-op fallback for non-Android targets. */
class NoopLocaleApplier : LocaleApplier {
    override fun apply(language: AppLanguage) { /* no-op */ }
}
