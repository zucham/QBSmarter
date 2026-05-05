package com.zucham.qbsmarter.di

import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.data.db.DriverFactory
import com.zucham.qbsmarter.ui.i18n.LocaleApplier
import com.zucham.qbsmarter.ui.i18n.NoopLocaleApplier
import com.zucham.qbsmarter.util.BluetoothSettings
import com.zucham.qbsmarter.util.FileExporter
import com.zucham.qbsmarter.util.ScreenKeeper
import com.zucham.qbsmarter.util.StubBluetoothSettings
import com.zucham.qbsmarter.util.StubFileExporter
import com.zucham.qbsmarter.util.StubScreenKeeper
import com.zucham.qbsmarter.util.StubUrlOpener
import com.zucham.qbsmarter.util.UrlOpener
import org.koin.dsl.module

val platformModule = module {
    single { DriverFactory() }
    single { BleManager() }
    single<UrlOpener> { StubUrlOpener() }
    single<ScreenKeeper> { StubScreenKeeper() }
    single<FileExporter> { StubFileExporter() }
    single<LocaleApplier> { NoopLocaleApplier() }
    single<BluetoothSettings> { StubBluetoothSettings() }
}
