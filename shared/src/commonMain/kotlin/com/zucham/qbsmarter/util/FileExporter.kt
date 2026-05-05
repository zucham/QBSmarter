package com.zucham.qbsmarter.util

/**
 * Save / open arbitrary blob data on disk. Implementations handle
 * platform-specific UI (SAF on Android, a file dialog on desktop, etc.)
 * so the caller doesn't need to care.
 *
 * Both methods suspend because the user is involved (a picker is shown).
 * The implementation must already be on the main dispatcher when it
 * shows UI; callers can safely call from any dispatcher.
 */
interface FileExporter {
    /** Returns true if the user picked a destination and the bytes were written. */
    suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean

    /** Returns the bytes the user picked, or null if they cancelled. */
    suspend fun openFile(mimeTypes: List<String>): ByteArray?
}
