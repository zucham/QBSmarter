package com.zucham.qbsmarter.app

import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-wide lifecycle hooks. Cancel scans on background; auto-disconnect
 * after [DISCONNECT_AFTER_BG_MS] of background time to save the cube's
 * battery. Wire-up is platform-specific (ProcessLifecycleOwner on Android).
 */
class AppLifecycle(
    private val ble: BleManager,
    private val driver: SmartCubeDriver,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var disconnectJob: Job? = null

    fun onForegrounded() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    fun onBackgrounded() {
        ble.stopScan()  // never leave a scan running in background
        disconnectJob?.cancel()
        disconnectJob = scope.launch {
            delay(DISCONNECT_AFTER_BG_MS)
            driver.disconnect()
            ble.disconnect()
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        const val DISCONNECT_AFTER_BG_MS = 5L * 60L * 1000L
    }
}
