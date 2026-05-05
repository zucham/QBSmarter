package com.zucham.qbsmarter.util

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import co.touchlab.kermit.Logger
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SAF-based file save/open. Bound to a ComponentActivity in onCreate so
 * its result launchers are registered before onStart. We hold a
 * WeakReference to avoid leaks if the activity is destroyed mid-flight.
 *
 * The IO is on Dispatchers.IO via an internal SupervisorJob – keeps us
 * out of GlobalScope while still living through configuration changes.
 *
 * Hardening: every entry point that can throw – `launcher.launch()`,
 * `contentResolver.openInputStream`, `readBytes()` – is wrapped in
 * runCatching so failures (e.g. `ActivityNotFoundException` on a device
 * without a SAF picker) surface as null/false returns instead of
 * crashing the host activity.
 */
class AndroidFileExporter(private val applicationContext: Context) : FileExporter {

    private val log = Logger.withTag("AndroidFileExporter")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activityRef = WeakReference<ComponentActivity>(null)
    private var saveLauncher: ActivityResultLauncher<String>? = null
    private var openLauncher: ActivityResultLauncher<Array<String>>? = null

    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveResult: CompletableDeferred<Boolean>? = null
    private var pendingOpenResult: CompletableDeferred<ByteArray?>? = null

    fun bind(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        saveLauncher = activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? -> handleSaveResult(uri) }
        openLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? -> handleOpenResult(uri) }
    }

    fun unbind(activity: ComponentActivity) {
        if (activityRef.get() === activity) {
            activityRef = WeakReference<ComponentActivity>(null)
            saveLauncher = null
            openLauncher = null
        }
    }

    override suspend fun saveFile(
        suggestedName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Boolean {
        val launcher = saveLauncher ?: run {
            log.w { "saveFile: no launcher (not bound to an activity)" }
            return false
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingSaveBytes = bytes
        pendingSaveResult = deferred
        // launcher.launch() can throw ActivityNotFoundException on devices
        // without a SAF picker. Catch it so the call returns false instead
        // of crashing the activity.
        runCatching { launcher.launch(suggestedName) }
            .onFailure {
                log.e(it) { "saveFile: launcher.launch failed" }
                pendingSaveBytes = null
                pendingSaveResult = null
                deferred.complete(false)
            }
        return deferred.await()
    }

    override suspend fun openFile(mimeTypes: List<String>): ByteArray? {
        val launcher = openLauncher ?: run {
            log.w { "openFile: no launcher (not bound to an activity)" }
            return null
        }
        val deferred = CompletableDeferred<ByteArray?>()
        pendingOpenResult = deferred
        runCatching { launcher.launch(mimeTypes.toTypedArray()) }
            .onFailure {
                log.e(it) { "openFile: launcher.launch failed" }
                pendingOpenResult = null
                deferred.complete(null)
            }
        return deferred.await()
    }

    private fun handleSaveResult(uri: Uri?) {
        val bytes = pendingSaveBytes
        val deferred = pendingSaveResult
        pendingSaveBytes = null
        pendingSaveResult = null
        if (uri == null || bytes == null || deferred == null) {
            deferred?.complete(false); return
        }
        ioScope.launch {
            val ok = runCatching {
                applicationContext.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                }
            }.onFailure { log.e(it) { "saveFile: write failed" } }.isSuccess
            deferred.complete(ok)
        }
    }

    private fun handleOpenResult(uri: Uri?) {
        val deferred = pendingOpenResult ?: return
        pendingOpenResult = null
        if (uri == null) { deferred.complete(null); return }
        ioScope.launch {
            val bytes = runCatching {
                applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onFailure { log.e(it) { "openFile: read failed" } }.getOrNull()
            log.d { "openFile: read ${bytes?.size ?: 0} bytes from $uri" }
            deferred.complete(bytes)
        }
    }
}
