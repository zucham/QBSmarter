package com.zucham.qbsmarter

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zucham.qbsmarter.app.AppLifecycle
import com.zucham.qbsmarter.data.ble.ConnectionOrchestrator
import com.zucham.qbsmarter.data.cache.CacheController
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.di.androidPlatformModule
import com.zucham.qbsmarter.di.sharedModule
import com.zucham.qbsmarter.ui.i18n.LocaleController
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application class. Koin starts here (before any Activity/ContentProvider
 * touches DI), and the process-wide ProcessLifecycleOwner observer drives
 * AppLifecycle so foreground/background transitions land at exactly one
 * point in the app – not duplicated across activities.
 *
 * **Boot ordering matters here.** Several singletons depend on the active
 * profile having been bootstrapped, so we resolve them in this order:
 *   1. ActiveProfile + bootstrap → ensures users.id + app_state row exist.
 *   2. CacheController → wires the cache.enabled setting → AppCache.
 *   3. LocaleController → reads the active profile's language and applies
 *      it to AppCompatDelegate before any Activity is created.
 *   4. ConnectionOrchestrator → starts collecting driver events so the
 *      first pair attempt's Hardware response is captured.
 *   5. AppLifecycle → ProcessLifecycleOwner observer.
 */
class QbsmarterApp : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@QbsmarterApp)
            modules(
                androidPlatformModule(applicationContext),
                sharedModule,
            )
        }

        // 1. Active profile: idempotent bootstrap. After this, every
        // ViewModel that resolves activeProfile.idSnapshot() sees a real
        // userId. Cache + locale need this to resolve a non-null profile.
        val activeProfile: ActiveProfile = get()
        activeProfile.ensureBootstrapped()

        // 2. Cache controller: bridges the per-profile cache.enabled
        // setting → AppCache. Resolved before LocaleController so that the
        // locale's settings read goes through a fully-wired cache.
        get<CacheController>()

        // 3. Locale controller: applies the persisted language to
        // AppCompatDelegate before any Activity is created. If we waited
        // until SettingsScreen is first composed, the very first Activity
        // launch would render with the wrong locale.
        get<LocaleController>()

        // 4. Connection orchestrator: starts its driver-event listener
        // (Hardware → DB, Battery → in-memory map) before any pair attempt.
        get<ConnectionOrchestrator>()

        // 5. ProcessLifecycleOwner: ON_START/ON_STOP fire for the *whole*
        // process, not per-activity. Auto-disconnect on background uses this.
        val appLifecycle: AppLifecycle = get()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = appLifecycle.onForegrounded()
                override fun onStop(owner: LifecycleOwner) = appLifecycle.onBackgrounded()
            },
        )
    }
}
