package com.zucham.qbsmarter.di

import com.zucham.qbsmarter.app.AppLifecycle
import com.zucham.qbsmarter.data.ble.ConnectionOrchestrator
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.cache.CacheController
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.data.db.DriverFactory
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.db.SolvesRepository
import com.zucham.qbsmarter.data.db.UserRepository
import com.zucham.qbsmarter.data.db.createDatabase
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.domain.cube.RubiksCube
import com.zucham.qbsmarter.domain.driver.CubeDriverFacade
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import com.zucham.qbsmarter.domain.driver.gan.GanCubeDriver
import com.zucham.qbsmarter.ui.i18n.LocaleController
import com.zucham.qbsmarter.ui.screens.devices.DevicesViewModel
import com.zucham.qbsmarter.ui.screens.history.HistoryViewModel
import com.zucham.qbsmarter.ui.screens.settings.SettingsViewModel
import com.zucham.qbsmarter.ui.screens.solve.SolveViewModel
import com.zucham.qbsmarter.ui.screens.solve.stats.StatRegistry
import com.zucham.qbsmarter.ui.theme.ThemeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Common Koin module. Platform modules supply: DriverFactory, BleManager,
 * UrlOpener, ScreenKeeper, FileExporter, LocaleApplier.
 *
 * All toggles, theme, and
 * language are now stored in the per-profile `settings` table via
 * [SettingsRepository]. Hot reads go through [AppCache].
 */
val sharedModule = module {

    // -- Long-lived singletons -------------------------------------------
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { createDatabase(get<DriverFactory>()) }

    // -- Repositories ----------------------------------------------------
    // singleOf(::T) eagerly tries to resolve every constructor parameter
    // from the Koin graph – even ones with Kotlin default values. Each
    // repository has a `ioDispatcher` default we don't want to satisfy
    // from DI, so we use the explicit lambda form.
    single { UserRepository(get()) }
    single { SolvesRepository(get()) }
    single { DevicesRepository(get()) }
    single { SettingsRepository(get()) }

    // -- Profile / cache layer ------------------------------------------
    // ActiveProfile sits between the user repo and everything that needs
    // a "current user". AppCache holds the hot StateFlows; CacheController
    // bridges the per-profile cache.enabled setting → AppCache.setEnabled.
    single { ActiveProfile(userRepo = get(), scope = get()) }
    single {
        AppCache(
            userRepo = get(),
            devicesRepo = get(),
            solvesRepo = get(),
            settingsRepo = get(),
            activeProfile = get(),
            scope = get(),
        )
    }
    single {
        CacheController(
            cache = get(),
            settings = get(),
            activeProfile = get(),
            scope = get(),
        )
    }

    // -- Theme / locale -------------------------------------------------
    single {
        ThemeController(
            cache = get(),
            settings = get(),
            activeProfile = get(),
            scope = get(),
        )
    }
    single {
        LocaleController(
            cache = get(),
            settings = get(),
            activeProfile = get(),
            applier = get(),
            scope = get(),
        )
    }

    // -- Cube model & drivers -------------------------------------------
    // Each vendor's driver is its own Koin singleton, kept alive across
    // cube swaps. The [CubeDriverFacade] is the single binding for
    // [SmartCubeDriver] – it forwards `send` to whichever vendor driver
    // the [ConnectionOrchestrator] has currently activated and
    // re-publishes the active driver's events on its own stable
    // [SharedFlow]. Subscribers ([SolveViewModel], [AppLifecycle]) see a
    // single events flow regardless of which vendor is in use.
    //
    // [GanCubeDriver] holds Gen2/Gen3/Gen4 parsers internally and
    // selects one based on the [com.zucham.qbsmarter.domain.driver.gan.GanGeneration]
    // argument the orchestrator passes at connect time. A second vendor
    // would join here as its own singleton.
    single { RubiksCube() }
    single { GanCubeDriver(parserDispatcher = Dispatchers.Default) }
    single { CubeDriverFacade(scope = get()) }
    single<SmartCubeDriver> { get<CubeDriverFacade>() }

    // -- App lifecycle wiring -------------------------------------------
    single { AppLifecycle(ble = get(), driver = get()) }

    // -- Connection orchestrator ----------------------------------------
    single {
        ConnectionOrchestrator(
            ble = get(),
            ganDriver = get(),
            facade = get(),
            devicesRepo = get(),
            scope = get(),
        )
    }

    // -- Stat registry --------------------------------------------------
    single { StatRegistry() }

    // -- ViewModels -----------------------------------------------------
    viewModel {
        SolveViewModel(
            cube = get(),
            driver = get(),
            solvesRepo = get(),
            settingsRepo = get(),
            screenKeeper = get(),
            ble = get(),
            statRegistry = get(),
            activeProfile = get(),
            cache = get(),
            themeController = get(),
        )
    }
    viewModel {
        DevicesViewModel(
            ble = get(),
            orchestrator = get(),
            devicesRepo = get(),
            activeProfile = get(),
            bluetoothSettings = get(),
            cache = get(),
        )
    }
    viewModel {
        HistoryViewModel(
            solvesRepo = get(),
            activeProfile = get(),
            cache = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            settingsRepo = get(),
            solvesRepo = get(),
            userRepo = get(),
            devicesRepo = get(),
            themeController = get(),
            localeController = get(),
            fileExporter = get(),
            appScope = get(),
            activeProfile = get(),
            cache = get(),
            orchestrator = get(),
        )
    }
}
