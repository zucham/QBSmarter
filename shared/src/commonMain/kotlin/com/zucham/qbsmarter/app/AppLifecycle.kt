package com.zucham.qbsmarter.app

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide lifecycle hooks. Cancel scans on background; auto-disconnect
 * the cube after a stretch of background time to save its battery - a
 * smart cube holding a BLE link keeps its radio awake and will flatten
 * itself overnight. Wire-up is platform-specific (ProcessLifecycleOwner on
 * Android).
 *
 * **The period is a user setting**
 * ([SettingsRepository.Keys.AUTO_DISCONNECT_MINUTES], per profile,
 * defaulting to [SettingsRepository.Defaults.AUTO_DISCONNECT_MINUTES]
 * minutes), and **0 means never disconnect**.
 *
 * It is read once, at the moment of backgrounding, rather than observed.
 * That is not a shortcut - it is the correct shape for this setting. The
 * only instant the value matters is when the timer is armed; between then
 * and the disconnect the app is in the background, where the user cannot
 * be changing it. Observing it would mean holding a subscription open
 * across the whole background period to react to a change that cannot
 * happen. A change made while the app is open therefore governs the *next*
 * time it is backgrounded, which is also the only sequence a user can
 * actually observe.
 */
class AppLifecycle(
    private val ble: BleManager,
    private val driver: SmartCubeDriver,
    private val cache: AppCache,
) {
    private val log = Logger.withTag("AppLifecycle")

    private val scope = CoroutineScope(SupervisorJob())
    private var disconnectJob: Job? = null

    fun onForegrounded() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    fun onBackgrounded() {
        ble.stopScan()  // never leave a scan running in background
        disconnectJob?.cancel()
        disconnectJob = null

        val minutes = cache.intSetting(
            SettingsRepository.Keys.AUTO_DISCONNECT_MINUTES,
            SettingsRepository.Defaults.AUTO_DISCONNECT_MINUTES,
        )
        // Guard on <= 0 rather than == 0: "never" is the intent behind
        // any non-positive period, and a negative one could only ever
        // arrive from a hand-edited or imported value. Either way, arming
        // a zero-length timer would drop the cube the instant the user
        // glanced at another app, which is the opposite of what anyone
        // choosing this option wants.
        if (minutes <= 0) {
            log.d { "Backgrounded; auto-disconnect is off" }
            return
        }

        log.d { "Backgrounded; auto-disconnect in $minutes min" }
        disconnectJob = scope.launch {
            delay(minutes * MILLIS_PER_MINUTE)
            log.d { "Auto-disconnect period elapsed; dropping the cube link" }
            driver.disconnect()
            ble.disconnect()
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        /** Long so `minutes * this` produces the Long that [delay] wants. */
        const val MILLIS_PER_MINUTE = 60L * 1000L
    }
}
