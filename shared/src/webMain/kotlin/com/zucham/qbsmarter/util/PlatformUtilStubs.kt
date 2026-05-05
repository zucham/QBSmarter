package com.zucham.qbsmarter.util

class StubUrlOpener : UrlOpener {
    override fun open(url: String) {
        throw NotImplementedError("TODO: webMain UrlOpener")
    }
}

class StubScreenKeeper : ScreenKeeper {
    override fun setKeepScreenOn(enabled: Boolean) = Unit
}

class StubBluetoothSettings : BluetoothSettings {
    override fun openSettings() = Unit
}

class StubFileExporter : FileExporter {
    override suspend fun saveFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean =
        throw NotImplementedError("TODO: webMain FileExporter")
    override suspend fun openFile(mimeTypes: List<String>): ByteArray? =
        throw NotImplementedError("TODO: webMain FileExporter")
}
