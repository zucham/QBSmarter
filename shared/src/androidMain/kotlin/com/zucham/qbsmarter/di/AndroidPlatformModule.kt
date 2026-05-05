package com.zucham.qbsmarter.di

import android.content.Context
import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.data.db.DriverFactory
import com.zucham.qbsmarter.ui.i18n.AndroidLocaleApplier
import com.zucham.qbsmarter.ui.i18n.LocaleApplier
import com.zucham.qbsmarter.util.AndroidBluetoothSettings
import com.zucham.qbsmarter.util.AndroidFileExporter
import com.zucham.qbsmarter.util.AndroidScreenKeeper
import com.zucham.qbsmarter.util.AndroidUrlOpener
import com.zucham.qbsmarter.util.BluetoothSettings
import com.zucham.qbsmarter.util.FileExporter
import com.zucham.qbsmarter.util.ScreenKeeper
import com.zucham.qbsmarter.util.UrlOpener
import org.koin.dsl.module

/**
 * Android-specific Koin bindings. Pass `applicationContext` (NOT activity).
 */
fun androidPlatformModule(context: Context) = module {
    single<Context> { context }
    single { DriverFactory(context) }
    single { BleManager(context) }
    single<UrlOpener> { AndroidUrlOpener(context) }
    single<ScreenKeeper> { AndroidScreenKeeper() }
    single<FileExporter> { AndroidFileExporter(context) }
    single<LocaleApplier> { AndroidLocaleApplier() }
    single<BluetoothSettings> { AndroidBluetoothSettings(context) }
}
