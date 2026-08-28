# QBSmarter – Project Knowledge

This document captures everything a developer should know to work on QBSmarter productively without re-deriving it from the source. It covers architecture, decisions, conventions, and the protocol details that aren't obvious from reading individual files.

> **Audience.** A new contributor who knows Kotlin and Compose Multiplatform and wants to learn about this codebase. Read top-to-bottom once; after that, the inline comments in the source assume you've seen the big picture here.

---

## Table of contents

1. [What QBSmarter is](#what-qbsmarter-is)
2. [Technology stack](#technology-stack)
3. [Module layout](#module-layout)
4. [Package structure (`shared`)](#package-structure-shared)
5. [Architecture overview](#architecture-overview)
6. [Layered breakdown](#layered-breakdown)
   - [BLE layer](#ble-layer)
   - [Smart-cube driver layer](#smart-cube-driver-layer)
   - [Cube domain (logical + visual)](#cube-domain-logical--visual)
   - [Solve timing](#solve-timing)
   - [Database & repositories](#database--repositories)
   - [Profile, cache, controllers](#profile-cache-controllers)
   - [UI: scaffold, drawer, theme, i18n](#ui-scaffold-drawer-theme-i18n)
   - [Screens & ViewModels](#screens--viewmodels)
   - [Solve stats](#solve-stats)
7. [Cross-cutting concerns](#cross-cutting-concerns)
8. [GAN Gen2 protocol notes](#gan-gen2-protocol-notes)
9. [Database schema](#database-schema)
   - [Per-profile cube names](#per-profile-cube-names)
   - [Migrations](#migrations)
   - [Record queries and the ranking index](#record-queries-and-the-ranking-index)
   - [Ao5 as a maintained column](#ao5-as-a-maintained-column)
   - [Solve reconstruction: `solve_moves` and `solve_gyro`](#solve-reconstruction-solve_moves-and-solve_gyro)
   - [Foreign keys](#foreign-keys)
10. [Permissions, edge-to-edge & system bars](#permissions-edge-to-edge--system-bars)
11. [Internationalisation](#internationalisation)
12. [Theming](#theming)
13. [Build, dependencies, versions](#build-dependencies-versions)
14. [Multiplatform stubs](#multiplatform-stubs)
15. [Conventions & gotchas](#conventions--gotchas)
16. [Known issues & future work](#known-issues--future-work)

---

## What QBSmarter is

QBSmarter is an **Android-first Compose Multiplatform** companion app for **smart cubes**. It connects via **Bluetooth Low Energy (BLE)** using the **GAN Gen2** &ndash; **Gen4** protocols (GAN i Carry, i Carry 2, GAN12 ui Maglev, etc.) and the **MoYu WeiLong V10 AI** protocol, renders the cube in 3D with the **korender** engine, and provides:

- a **Solve** screen (live cube view, scramble generator, scramble progress with deviation correction, inspection countdown, timer, post-solve penalty/DNF, stat tiles, personal-best celebration),
- a **Devices** screen (scan, pair, reconnect, forget, per-cube battery, cube info dialog),
- a **History** screen (paged solve list, sort, swipe-to-delete, detail dialog),
- a **Settings** screen (per-profile toggles, theme seed/mode, language, profile management, JSON import/export),
- a **Guide** screen (renders the bundled localised usage guide as Markdown),
- multi-profile support, theming (8 seeds × light/dark/system), and i18n (English + Czech).

The codebase is structured as Compose Multiplatform with **Android, JVM-desktop, and JS/WASM** targets declared, but **only Android is shipped**. The other platforms exist as stubs so the build keeps compiling; running them throws `NotImplementedError` in the platform-specific bits.

---

## Technology stack

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform `1.10.1` + Material3 `1.10.0-alpha05` | Single composition tree across screens. |
| Language | Kotlin `2.3.10` | Multiplatform, with `kotlin.time.ExperimentalTime` and `ExperimentalCoroutinesApi` opted in project-wide. |
| 3D rendering | korender `0.6.1` | Renders into a native `SurfaceView` on Android. Constrains the source-set hierarchy (see *Module layout*). |
| Concurrency | kotlinx.coroutines `1.10.2` | `StateFlow`, `SharedFlow`, structured scoping per layer. |
| DI | Koin `4.1.1` (BOM) | `androidPlatformModule(context)` + `sharedModule`, started in `Application.onCreate`. |
| Persistence | SQLDelight `2.2.1`, dialect `sqlite-3-25-dialect` | Bumped from 3.18 default for `ON CONFLICT … DO UPDATE`. |
| Navigation | `androidx.navigation:navigation-compose` `2.9.0-alpha14` | Multiplatform variant. Single `NavHost` rooted in `AppScaffold`. |
| Lifecycle / VM | `androidx.lifecycle:lifecycle-viewmodel-compose` `2.9.6` | Multiplatform ViewModels. |
| Serialization | `kotlinx-serialization-json` `1.9.0` | Used by import/export. |
| Date/time | `kotlinx-datetime` `0.7.1` | Local-time formatting on the History screen. |
| Logging | Kermit `2.1.0` | Tagged loggers per class. Android `BleManager` uses `android.util.Log` directly because it's the only place tightly coupled to the Android Bluetooth APIs. |
| Min SDK | API 29 (Android 10) | Lower than the original target so legacy permission branching is supported (see *Permissions*). |
| Compile SDK / Target SDK | 36 | |
| JVM target | 11 (shared, androidApp), 17 (desktopApp) | |

---

## Module layout

```
QBSmarter/
├── shared/          # All code worth sharing – UI, domain, data, DI
├── androidApp/      # Application class, MainActivity, manifest
├── desktopApp/      # JVM-desktop entry point (placeholder window)
└── webApp/          # JS / WASM entry points (excluded from settings.gradle.kts)
```

`webApp` is **excluded** in `settings.gradle.kts` because korender's WASM/JS variant doesn't currently work for our use case. The directory is left in place for when web support is revisited; the file `webApp/build.gradle.kts` and the entry-point sources are kept as a starting point.

### Source-set hierarchy in `shared`

The actively-built source sets are:

```
common
├── android   (real)
└── jvm       (stubs)
```

A `webMain` directory still exists on disk under `shared/src/` from earlier development (kept as a starting point for a future revisit), but the JS and WASM-JS targets are **commented out** in `shared/build.gradle.kts` together with the `web` intermediate source-set declaration. They are not part of the current build. If web support is brought back, the intended hierarchy is:

```
common
├── android   (real)
├── jvm       (stubs)
└── web       (intermediate, stubs)
    ├── js
    └── wasmJs
```

The `web` intermediate source set was/will-be used so `js` and `wasmJs` can share their stubs (`BleManager`, `DriverFactory`, `GanEncryptor`, `currentTimeMillis`, `generateUuid`, etc.) without duplication.

### Why no `iosMain`

korender publishes Android, JVM-desktop, JS, and WASM only. Cube domain types (`Vec3`, `Quaternion`, `Transform`, `TouchEvent`) come from korender, so iOS cannot consume `commonMain` until either (a) korender adds an iOS variant, or (b) korender is wrapped behind an `expect/actual` seam with an iOS-specific renderer (Metal / SceneKit). Documented in `shared/build.gradle.kts`.

---

## Package structure (`shared`)

```
com.zucham.qbsmarter
├── app/                        # Top-level composition: App, AppNavHost, AppLifecycle
├── data/
│   ├── ble/                    # BleManager (expect/actual), BleCubeTransport, ConnectionOrchestrator
│   ├── cache/                  # AppCache, CacheController
│   ├── db/                     # Database, DriverFactory, *Repository classes
│   └── profile/                # ActiveProfile (single source of truth for the active user)
├── di/                         # AppModule (common Koin), AndroidPlatformModule, jvm/web stubs
├── domain/
│   ├── cube/                   # RubiksCube, CubeState, CubeMove, CubeMoveQueue, CubeOrbiter, etc.
│   ├── driver/                 # SmartCubeDriver/Event/Command, CubeTransport, CubeEncryptor,
│   │   │                       # CubeVendor, AesCbcMacSaltEncryptor, AesEcbEncryptor, BitView
│   │   ├── protocol/           # CubeProtocol, ProtocolCubeDriver, CubeProtocolRegistry,
│   │   │                       # ProtocolCodecs, MoveRecoveryFifo
│   │   ├── gan/                # GanGen1/2/3/4Protocol, GanEncryptor
│   │   ├── moyu/               # MoyuWcuProtocol, MoyuMhcProtocol, MoyuEncryptor,
│   │   │                       # MoyuFaceletDecoder
│   │   ├── qiyi/               # QiyiProtocol
│   │   ├── gocube/             # GoCubeProtocol (GoCube + Rubik's Connected)
│   │   └── giiker/             # GiikerProtocol
│   ├── timing/                 # SolveTimer, ClockSkewEstimator
│   └── user/                   # UserProfile data class
├── ui/
│   ├── components/             # AppScaffold, NavigationDrawer, ConfirmationDialog,
│   │                           # VerticalScrollbar (VerticalScrollbarBox composable)
│   ├── i18n/                   # LocaleController, AppLanguage, LocaleApplier
│   ├── screens/
│   │   ├── devices/            # DevicesScreen, DevicesViewModel
│   │   ├── guide/              # GuideScreen (renders the bundled localised usage_guide_<lang>.md)
│   │   ├── history/            # HistoryScreen, HistoryViewModel
│   │   ├── settings/           # SettingsScreen, SettingsViewModel
│   │   └── solve/              # SolveScreen, SolveViewModel, CubeView, ScrambleGenerator,
│   │       │                   # InspectionTimer, SolveState
│   │       └── stats/          # SolveStat, StatRegistry, builtin/BuiltinStats
│   └── theme/                  # AppTheme, ColorSchemes, ThemeController, StatusColors, SystemBars
└── util/                       # Hex, Time, Uuid, UrlOpener, FileExporter, formatting helpers
```

The `androidMain` mirror under each package supplies `actual` definitions and Android-specific helper classes (`AndroidLocaleApplier`, `AndroidFileExporter`, `AndroidScreenKeeper`, `AndroidUrlOpener`, `AndroidBluetoothSettings`, `AndroidSqliteDriver` factory).

---

## Architecture overview

QBSmarter is a fairly conventional **MVVM + Repository** app, with a few twists shaped by the smart-cube use case:

```
       ┌──────────────────────────────────────────────────┐
 UI →  │ Compose screens (DevicesScreen, SolveScreen, …)  │
       └────────────────────┬─────────────────────────────┘
                            │ koinViewModel()
       ┌────────────────────▼─────────────────────────────┐
 VM →  │ ViewModels (per screen, screen-scoped lifecycle) │
       └────────┬───────┬────┬──────────────────┬─────────┘
                │       │    │                  │
       ┌────────▼─┐  ┌──▼─┐  │           ┌──────▼─────┐
 →     │ AppCache │  │ … │  │           │ ActiveProfile │  Long-lived
       └────┬─────┘  └────┘  │           └──────┬─────┘  singletons
            │                │                  │
       ┌────▼────────────────▼──────────────────▼────────┐
 →     │ Repositories (UserRepo, SolvesRepo, DevicesRepo, │
       │ SettingsRepo)                                    │
       └──────────────────────┬───────────────────────────┘
                              │
                ┌─────────────▼──────────────┐
 →              │ SQLDelight (QbsmarterDatabase) │
                └─────────────┬──────────────┘
                              │
                       ┌──────▼───────┐
                       │ SQLite (file) │
                       └──────────────┘

Smart-cube data plane (parallel to the read/write plane above):

 BleManager (platform) ─→ BleCubeTransport ─→ ProtocolCubeDriver
       │                       │                    │
       │                       │              decrypts, then decodes
       │                       │              via the bound CubeProtocol
       │                       │                    │
       │                       │                    ▼
       │                       │            SmartCubeEvent stream
       │                       │                    │
       │                       │             ┌──────┴───────┐
       │                       │             │              │
       │                       │             ▼              ▼
       │                       │    ConnectionOrchestrator  SolveViewModel
       │                       │    (DB writes for INFO,   (logical state,
       │                       │     battery cache, resync) timer, stats)
       │                       │
       └── notification stream ┘
```

### Three "always-on" singletons

These are constructed eagerly by `QbsmarterApp.onCreate` so they're warm before any UI mounts:

1. **`ActiveProfile`** – reactive `id` / `profile` flows for the current user.
2. **`AppCache`** – in-memory `StateFlow`s for hot reads (paired cubes, recent solves, settings, profile list, best time, solve count). Gated by a `cache.enabled` setting.
3. **`ConnectionOrchestrator`** – long-lived BLE connect/handshake state machine and driver-event listener. Survives ViewModel teardown so navigating away mid-pair doesn't break the connection.

### Profile-reactive design

Every ViewModel reads the active user **at call-time** (`activeProfile.idSnapshot()`) for writes, and **subscribes** to flows keyed off `activeProfile.id` for reads. ViewModels never capture a `userId` at construction. This means switching profiles cleanly cycles every observed flow without rebuilding any VM. The cube is automatically disconnected on profile switch (the cube logically belongs to the previous user).

---

## Layered breakdown

### BLE layer

**Files:** `data/ble/BleManager.kt` (`expect`), `data/ble/BleManager.android.kt` (real), `data/ble/BleDevice.kt`, `data/ble/BleCubeTransport.kt`, `data/ble/ConnectionOrchestrator.kt`.

`BleManager` is a thin wrapper around `BluetoothLeScanner` + `BluetoothGatt`. It exposes:

- `connectionState: StateFlow<ConnectionState>` – `DISCONNECTED, SCANNING, CONNECTING, CONNECTED, PERMISSION_DENIED, BLUETOOTH_DISABLED, ERROR`
- `scannedDevices: StateFlow<List<BleDevice>>`
- `discoveredServices` / `characteristicData` – raw GATT mirrors
- `notificationsReady: StateFlow<Boolean>` – flips true once the CCCD descriptor write succeeds (see *Post-connect ordering* below)
- imperative methods: `scanForDevices`, `stopScan`, `connectToDevice`, `disconnect`, `writeCharacteristic`, `enableNotifications`, `hasRequiredPermissions`, `isBluetoothEnabled`

#### Defensive BLE name discovery

BLE name discovery is asynchronous: a cube's first advertising packet often arrives without a name; a follow-up scan-response packet carries it. The Android `scanCallback.onScanResult` updates an existing entry's name when a non-blank one finally arrives, so a cube doesn't stay listed as "Unknown" forever.

#### Defensive copy of characteristic data

`BluetoothGattCharacteristic.value` is reused by the framework. `publishChange` does `value.copyOf()` before pushing into the `_characteristicData` map.

#### Both characteristic-callback signatures are overridden (Android 12 fix)

Android 13 (API 33) added new `onCharacteristicRead(gatt, characteristic, value, status)` and `onCharacteristicChanged(gatt, characteristic, value)` overloads that take the payload as an explicit `ByteArray` (the older `characteristic.value` accessor was deprecated for thread-safety reasons). The framework dispatches **exactly one** of the variants depending on platform version AND which one the app overrides:

- API < 33 (Android 10/11/12): the framework only invokes the deprecated 2-/3-parameter forms. The new (4-parameter) variant is never invoked, even if overridden – so an app that overrides only the new form receives **zero notifications** on Android 12 and earlier. This was the root cause of the "connects but no moves" bug observed on Android 12 devices.
- API ≥ 33: the framework prefers the new variant when both are overridden; the deprecated form is also called for backwards compatibility with apps that didn't update.

Solution: override **both** signatures in `BleManager.android.kt`. The deprecated variant reads `characteristic.value` (deprecated but functional everywhere; suppressed with `@Suppress("DEPRECATION")`) and routes through the same `publishChange`. The new variant is the path taken on API 33+.

#### Disconnect close-ordering

`BluetoothGatt.close()` must be called **after** the BLE stack acknowledges the disconnect via `onConnectionStateChange(STATE_DISCONNECTED)`, never synchronously back-to-back with `gatt.disconnect()`. Closing too early can leave the peripheral firmware in a "still connected" state – it won't return to advertising mode and won't show in scans on this or any other phone until the user power-cycles their phone's Bluetooth radio.

The two-stage teardown in `BleManager.disconnect()`:

1. Call `gatt.disconnect()` immediately and return.
2. Schedule a fallback close in `cleanupScope` after `DISCONNECT_TIMEOUT_MS = 1500` ms, gated by an identity check (`bluetoothGatt === gatt`) so a fast reconnect during the wait doesn't double-close.
3. The actual `gatt.close() + bluetoothGatt = null + state-flow resets` happen in `onConnectionStateChange(STATE_DISCONNECTED)` – the path that fires under normal conditions.

The Devices screen's `forget()` waits for `connectionState == DISCONNECTED` (with a 2 s outer timeout) before deleting the DB row, so the user never sees the racy intermediate state.

#### Defensive guards on connect/scan entry points

`BleManager.connectToDevice()` and `BleManager.scanForDevices()` both refuse with a logged warning if `bluetoothGatt != null` at entry. This catches future regressions where a caller initiates a new connect or scan without first cleanly tearing down the existing connection.

Without these guards, the previous `bluetoothGatt` reference would be silently overwritten (in `connectToDevice`) or the connection-state flow would lie to observers about being CONNECTED while the GATT is still alive (in `scanForDevices`'s `_connectionState.value = SCANNING` transition). Either case left the peripheral in the same "stuck-connected" state as the close-ordering bug.

The orchestrator's `connect()` and `DevicesViewModel.startScan()` are the upstream paths that respect this contract: both wait for `connectionState == DISCONNECTED` (with timeouts) before calling the BleManager entry points. The guards are there to surface upstream bugs as `ERROR` connection states rather than silent corruption.

#### Bluetooth-disabled handling

Every entry point that opens the radio (`scanForDevices`, `connectToDevice`) checks `isBluetoothEnabled()` first and flips the connection state to `BLUETOOTH_DISABLED` if not. The Devices screen reacts with an "Enable Bluetooth" CTA (`AndroidBluetoothSettings.openSettings()` opens `Settings.ACTION_BLUETOOTH_SETTINGS`).

#### `BleCubeTransport`

Adapts `BleManager`'s state-characteristic flow into the protocol-agnostic `CubeTransport` interface (`incoming: Flow<ByteArray>`, `write`, `enableNotifications`). `distinctUntilChanged { a, b -> a.contentEquals(b) }` filters duplicate state packets so the parser doesn't see the same byte array twice.

#### `ConnectionOrchestrator` – post-connect handshake ordering

This is the most subtle piece of BLE plumbing in the app. The order is:

1. `devicesRepo.rememberCube(...)` – persist before anything else, so a flaky connect still leaves a row.
2. **Tear down any existing connection first.** If `ble.connectionState.value` is `CONNECTED` or `CONNECTING`, call `ble.disconnect()` and `withTimeout(2 s) { ble.connectionState.first { it == DISCONNECTED } }`. This is the central enforcement point – every callable path that asks to connect a new cube routes through here, and `BleManager.connectToDevice`'s defensive guard backs it up by refusing if the GATT is somehow still alive.
3. Set `_activeMac.value = device.address`. Note: this happens AFTER the teardown, not before – otherwise the long-lived Hardware/Battery event handlers (which read `_activeMac.value`) would attribute trailing events from the old cube to the new MAC.
4. Build a `CubeIdentity(mac, name)` for the cube. Everything downstream that needs to know *which* cube this is – the AES salt, QiYi's hello, GAN's key-set choice – takes it from here rather than reaching for the device again.
5. `ble.connectToDevice(device)`.
6. **Wait for service discovery + resolve the protocol.** Collect `ble.discoveredServices` until a snapshot that `CubeProtocolRegistry.resolve(uuids, identity)` matches. Collecting until a match (rather than taking the first snapshot) matters because Android delivers discovered services incrementally. The winning row supplies the characteristic UUIDs, the encryptor factory and the protocol factory together, so they cannot disagree. `devicesRepo.updateVendor` stamps `spec.vendor` immediately; a row with `supported = false` is logged as recognised-but-not-drivable and otherwise proceeds.
7. Build `BleCubeTransport(serviceUuid, commandCharUuid, stateCharUuid)` from the resolved spec, then call `driver.connect(transport, spec.createEncryptor(identity), spec.createProtocol(identity))`. The driver binds the fresh protocol instance and enables notifications, which kicks off the CCCD descriptor write. The protocol's own `onConnected` handshake runs once notifications are live.
8. **Wait for `ble.notificationsReady` to flip true** (with a 3 s timeout fallback). Without this gate, the next 3 command writes race the descriptor write – the cube either drops them or replies into a void.
9. Wait a further `FIRST_COMMAND_SETTLE_MS` (150 ms), then send `RequestHardware`, `RequestFacelets`, `RequestBattery` with **120 ms gaps** so back-to-back GATT writes don't overflow the queue on flaky stacks. Each is wrapped in `runCatching` – failures don't tear down the connection.
10. Run `ensureHardwareInfo()` – see *Hardware handshake* below.

##### Hardware handshake

`notificationsReady` flipping true is necessary but not sufficient. It fires inside the CCCD descriptor-write callback, and on several Android stacks a characteristic write issued in that same breath is **silently dropped** — no error, no reply, the cube simply never hears it. Whichever command goes first absorbs that risk, and `RequestHardware` goes first.

That made the hardware reply uniquely fragile. Facelets and battery are naturally re-requested over the life of a session (MovesMissed resync, user actions), so a lost one self-heals. Hardware was asked for exactly once, and it is the *only* source of the cube's declared gyro capability. Lose that single write and the cube reports blank hw/sw versions and "gyro: unknown" for as long as it stays paired — while moves, facelets and battery all keep working, which makes it look like a UI bug rather than a lost packet. This was observed in the field on a GAN12 ui.

Two mitigations, both cheap:

- `FIRST_COMMAND_SETTLE_MS` (150 ms) before the first write, so it isn't issued into the descriptor-callback window at all.
- `ensureHardwareInfo()` re-sends `RequestHardware` every `HARDWARE_RETRY_INTERVAL_MS` (700 ms) until a `Hardware` event arrives, giving up after `HARDWARE_RETRY_ATTEMPTS` (3). The event handler sets `hardwareReceived`; the flag is cleared whenever `activeMac` changes. A healthy cube answers in ~150 ms and never triggers a retry.

Giving up is not a failure state — capability detection has a second, independent path (see *Gyro capability detection*), so a cube that never answers this handshake can still light up the Gyro button by simply sending gyro data.

**On cancel.** `disconnect()` is called via the user tapping Cancel mid-handshake or switching cubes. It cancels the connect job, calls `driver.disconnect()` and `ble.disconnect()`, then `withTimeout(2 s)` waits for `connectionState == DISCONNECTED` before clearing battery + active-MAC state. Without this final await, a subsequent action would race the in-flight teardown – exactly the family of bugs the close-ordering fix in `BleManager.disconnect` was meant to avoid.

The orchestrator also listens to driver events forever and:

- routes `SmartCubeEvent.Hardware` → `devicesRepo.updateHardwareInfo(...)`, repeatedly — Gen4 reports in instalments and the retry above may land more than one — and sets `hardwareReceived` to stop the retry
- routes the first `SmartCubeEvent.Gyro` of a connection → `devicesRepo.markGyroSupported(mac)`, latched by `gyroObserved` so a ~50 Hz sample stream doesn't become a ~50 Hz write loop
- caches `SmartCubeEvent.Battery` per MAC into a `_batteryByMac` `StateFlow`
- responds to `SmartCubeEvent.MovesMissed` with a debounced `RequestFacelets` (1500 ms minimum interval) – see *Move-history overflow* in the GAN section below. (For Gen3/Gen4, individual gaps are recovered transparently by the parser via the move-history backfill mechanism; MovesMissed only fires when the parser's FIFO overflows past 16 entries, i.e. backfill itself isn't keeping up.)

`activeMac` is a `StateFlow<String?>` exposed publicly, and it is **the** answer to "which cube is on the wire". The Devices screen combines it with `connectionState == CONNECTING` to derive `connectingMac` (per-row "Connecting…" spinner) and with `CONNECTED` to derive `connectedCubeId` (green dot + accent border). The Solve screen resolves its `connectionSummary` cube the same way.

Both screens used to guess instead — "whichever paired cube was seen most recently", on the theory that connecting refreshes `last_seen` and floats the right row to the head of the list. That guess fails whenever the ordering doesn't cooperate: the cube connects fine and no row lights up, or the wrong one does. It also mattered beyond cosmetics, because the resolved row is where `gyroSupported` is read from, and that gates the Gyro button.

The reason all of this lives in a long-lived singleton (not a VM) is so navigation doesn't tear it down mid-handshake.

---

### Smart-cube driver layer

**Files:** `domain/driver/{CubeTransport, CubeEncryptor, CubeVendor, AesCbcMacSaltEncryptor, AesEcbEncryptor, SmartCubeCommand, SmartCubeEvent, SmartCubeDriver, BitView}.kt`, `domain/driver/protocol/{CubeProtocol, ProtocolCubeDriver, CubeProtocolRegistry, ProtocolCodecs, MoveRecoveryFifo}.kt`, `domain/driver/gan/{GanGen1Protocol, GanGen2Protocol, GanGen3Protocol, GanGen4Protocol, GanEncryptor}.kt`, `domain/driver/moyu/{MoyuWcuProtocol, MoyuMhcProtocol, MoyuEncryptor, MoyuFaceletDecoder}.kt`, `domain/driver/qiyi/QiyiProtocol.kt`, `domain/driver/gocube/GoCubeProtocol.kt`, `domain/driver/giiker/GiikerProtocol.kt`.

The driver layer is **protocol-agnostic everywhere except the protocols themselves**. `SmartCubeDriver` is an interface with exactly one implementation, `ProtocolCubeDriver`; what varies between cube families lives in a `CubeProtocol`, and which one to build is a lookup in `CubeProtocolRegistry`.

`CubeVendor` is a *labelling* concept, not a protocol one — several vendors share a wire protocol, so a vendor may map to a protocol owned by someone else. It decides what the Devices screen prints on the chip and what goes in the `cubes.vendor` column; it decides nothing about how the app talks to the cube.

```
┌──────────────────┐   raw bytes   ┌──────────────────────┐
│ CubeTransport    │──────────────→│ ProtocolCubeDriver   │
│ (BLE adapter)    │←──────────────│  decrypt (or not)    │
└──────────────────┘   commands    │  ↓                   │
                                   │  CubeProtocol.decode │
                                   └──────────┬───────────┘
                                              │
                    ProtocolCubeDriver.events ─→ subscribers
                    (single stable SharedFlow that the rest
                     of the app binds against as
                     `SmartCubeDriver.events`)

                                          ▼
                                 ┌────────────────────┐
                                 │ SmartCubeEvent     │
                                 │  Move / Facelets   │
                                 │  Hardware / Battery│
                                 │  Gyro / Disconnect │
                                 │  MovesMissed       │
                                 └────────────────────┘
```

**Encryption.** GAN and MoYu both use AES-128 CBC with a static root key + IV mixed with a 6-byte per-cube salt derived from the BLE MAC (reversed bytes). Only the root key and IV differ between vendors; the salt-mix algorithm and the two-block encryption-for-payloads-larger-than-16-bytes scheme are identical. The shared expect/actual `AesCbcMacSaltEncryptor(rootKey, rootIv, salt)` carries the actual AES code; `GanEncryptor` and `MoyuEncryptor` are thin wrappers that bake in the vendor-specific constants. (The `% 0xFF` salt-mix modulus instead of `% 0x100` is a quirk of the original GAN protocol that MoYu inherited verbatim.)

**One driver, many protocols.** Every cube family runs through a single `ProtocolCubeDriver`. What differs between families lives entirely in a `CubeProtocol` — a pure codec plus an optional handshake, owning no coroutines, no BLE handles and no lifecycle. Adding a brand is: write one `CubeProtocol`, add one row to `CubeProtocolRegistry`. Nothing else changes — not the driver, not the transport, not the orchestrator, not the UI.

This replaced a driver *per vendor* (`GanCubeDriver`, `MoyuCubeDriver`) plus a `CubeDriverFacade` that multiplexed between them. Each driver carried its own copy of the same scope, ingest job, event flow, decrypt call and connect/disconnect bookkeeping, and the set was about to grow to six. With one driver there is nothing left to multiplex, so the facade is gone too — every subscriber, the orchestrator's own event-handler `init` block included, binds `driver.events` once at construction and is never re-bound, because swapping cubes swaps the driver's *protocol*, not the driver.

```
CubeProtocolRegistry  (the table: UUIDs + encryptor factory + protocol factory)
        |  resolve(advertisedServices, identity)
        v
ConnectionOrchestrator --- BleCubeTransport ---+
        |                                      |
        +---------> ProtocolCubeDriver <-------+
                        |  decrypt -> CubeProtocol.decode -> SmartCubeEvent
                        v
                   events: SharedFlow   (stable across every cube swap)
```

**The registry is the whole cube catalogue.** One row per wire protocol, carrying service + characteristic UUIDs, name prefixes, an encryptor factory (or none — GoCube, Giiker and MoYu MHC are plaintext) and a protocol factory. Resolution is service-UUID-first, name-second; the name only ever breaks ties between protocols sharing a service.

| Protocol id | Vendor | Service | Notable cubes |
|---|---|---|---|
| `gan-gen2` | GAN | `6e400001…4179` | GAN12 ui / ui FreePlay, i Carry, i Carry S, i3, Mini ui FreePlay, Monster Go AI, MoYu AI 2023 |
| `gan-gen3` | GAN | `8653000a…` | GAN356 i Carry 2 |
| `gan-gen4` | GAN | `00000010-0000-fff7…` | GAN12 ui Maglev, GAN14 ui FreePlay, GAN i4 |
| `gan-gen1` | GAN | `0000fff0` + `0000180a` | GAN356 i, i Play / i2 — **registered, not drivable** |
| `moyu-wcu` | MoYu | `0783b03e…cb0` | WeiLong V10 / V11 AI family |
| `moyu-mhc` | MoYu | `00001000` | WeiLong AI (2021) — moves only |
| `qiyi` | QiYi | `0000fff0` | QiYi Smart Cube, X-Man Tornado V4 AI |
| `gocube` | GoCube | `6e400001…ca9e` | GoCube, GoCube Edge, GoCube X |
| `rubiks-connected` | Rubik's | `6e400001…ca9e` | Rubik's Connected / Connected X |
| `giiker` | GiiKER | `0000aadb` | Super Cube i3 / i3S / i3SE, Xiaomi Mi Smart |

Two service collisions are resolved deliberately. `0000fff0` is shared by QiYi and GAN Gen1 — Gen1 additionally requires `0000180a` (Device Information) and sits last in the table, so QiYi wins on name. The GoCube UART service is shared by GoCube and Rubik's Connected, which are the *same protocol* under two brands: one `GoCubeProtocol` class takes the vendor as a constructor argument purely so the Devices screen prints the right chip.

**Shared protocol machinery** lives in `domain/driver/protocol/`:

- `MoveRecoveryFifo` — serial-gap detection and backfill for cubes that number their moves (GAN Gen3/Gen4). This existed *twice*, copied verbatim into both parsers under a comment claiming that factoring it out would obscure the per-generation differences. There were none: only the wire offsets that build a `Move` differ, and those stayed behind. Equivalence of the extracted version against the original was checked over 3000 randomised move/gap/backfill sessions.
- `ProtocolCodecs` — CRC-16/MODBUS, big/little-endian readers, GAN's sign-magnitude decoder, and `unitQuaternion`, which normalises by *measured* magnitude rather than the vendor's nominal scale (GoCube documents 2^14 but emits ~16355; QiYi documents 1000 but emits ~1002.6).

**Gyro capability is detected by observation, never by model name.** The GAN Gen4 allow-list (`GAN12uiM`) is provably incomplete — the GAN i4 streams `0xEC` gyro packets and is not on it — so `Hardware.gyroSupported` stays `Boolean?` and the orchestrator upgrades a cube to gyro-capable the moment real gyro data arrives. That one rule is what makes unreleased models work for free.

**Known protocol gaps**, deliberately left as documented TODOs rather than guesses:

- **GAN Gen1** needs a polled multi-characteristic transport (four read-only characteristics, no notifications) and a System-ID-salted encryptor with no IV. Registered with `supported = false` so connecting reports "recognised, not supported" instead of "unknown device".
- **MoYu MHC** streams battery/hardware on `0x1002` and gyro on `0x1004`; our transport binds one notify characteristic, so only moves (`0x1003`) are reachable.
- **Giiker battery** lives on a second service (`0000aaaa`), unreachable for the same reason.
- **GAN 16 UI, i Carry 4, i Carry E, 12 UI SP, 356 i3 V2** have no public protocol data in any repository — not cstimer, not gan-web-bluetooth, not cubing.js. They are not stubbed, because a stub implies a known shape. If they reuse an existing service UUID they will be driven correctly today with no code change.


**Driver scope:** the driver owns a `CoroutineScope(SupervisorJob() + parserDispatcher)` (default `Dispatchers.Default`), so decryption and decoding never run on the BLE binder thread. The events `SharedFlow` has `replay = 0`, `extraBufferCapacity = 64` – generous enough that a paused subscriber (user navigated away momentarily) doesn't drop moves.

---

### Cube domain (logical + visual)

**Files in `domain/cube/`:** `CubeFace`, `CubeMove`, `CubeMoveQueue`, `CubeOrbiter`, `CubeOrientation`, `CubePiece`, `CubeState`, `RubiksCube`, `StateToTransforms`.

This is the most carefully designed part of the codebase, because mismatches between *logical* state and *visual* state used to produce bug classes that were very hard to debug ("two corners stuck in the same slot", "an animated face turn applies twice", etc.).

#### Single source of truth: `CubeState`

`CubeState` is the **logical** state in the standard Kociemba representation:

- `cp[8]` – corner permutation (which corner is at slot _i_)
- `co[8]` – corner orientation (twist 0..2)
- `ep[12]` – edge permutation
- `eo[12]` – edge orientation (flip 0..1)

`RubiksCube._state: MutableState<CubeState>` is the **only** mutable cube state in the app. Everything visual derives from it on demand:

```
              ┌──────────────────┐
              │ _state: CubeState │ (the only mutable state)
              └─────────┬─────────┘
                        │
              transformForMesh(state, meshIndex, centerOrientations)
                        │
                        ▼
            per-cubie 3D transform (no caching)
```

`StateToTransforms.kt` builds the rotation **directly from first principles** – there's no precomputed lookup table, no per-piece stored transform field. For each render call it picks two reference vectors (where the cubie's center goes, where its U/D facelet goes) and constructs the rotation by orthonormal frame fitting. ~50 floating-point ops per call × 26 cubies × 60 fps = trivial. The advantage: there is **nothing to desync**. Earlier designs with a stored per-piece `transform` field could drift if anything updated one without the other.

#### Center piece rotations (visual-only)

Center cubies stay in their slot but rotate visually with face turns – when you turn U, the U center's logo (or whatever marking is on it) rotates 90° with it. The GAN cube doesn't report center orientation, and folding it into `CubeState` would break the scramble-progress equality check (which compares whole `CubeState`s against precomputed prefix states whose centers are always implicitly zero). So centers are tracked separately:

- `RubiksCube._centerOrientations: MutableState<IntArray>` – one int per face (`CubeFace.ordinal` indexed), in 0..3 quarter-turn units.
- `commitMove(move)` bumps `_centerOrientations[move.face.ordinal]` by `+1` (CW) or `+3` (CCW, equivalent to `−1 mod 4`) when committing a turn.
- `applyResetTarget(target)` zeroes the centers along with replacing `_state`. Called for both `RubiksCube.resetState()` (user pressed New Scramble) and for queue-routed Resets (from `resync`/`catchUpVisualTo`).
- `transformForMesh(state, meshIndex, centerOrientations)` – when `meshIndex` resolves to a center via `MESH_TO_CENTER_FACE`, returns `Transform.rotate(face.axis, -PI/2 * orientation)` instead of `IDENTITY`.

The animation overlay must capture pre-move centers too – same Korender race as `preMoveState` (next section). `ActiveAnimation.preMoveCenterOrientations` is a copy taken at animation start; the renderer reads it whenever the animation is active so that `commit` can bump `_centerOrientations` to the post-move value without the renderer ever observing both the new value AND the still-active overlay together.

#### Mid-animation rendering and the Korender race

`RubiksCube.pieceTransform(meshIndex)` is called by Korender's render thread on every frame. There's a subtle race: Korender reads `MutableState.value` outside any Compose snapshot, so two adjacent reads of `activeAnimation` and `_state` can fall on opposite sides of an atomic write.

**Symptom (if uncorrected):** the renderer reads `activeAnimation` first (gets the still-non-null overlay) then reads `_state` (gets the *new* committed state). It then composes `rotate(90°) * rest_post-move` – a visible 180° turn.

**Fix:** `ActiveAnimation` carries `preMoveState` AND `preMoveCenterOrientations` – the cube's visual state captured at the moment the animation started. While an animation is active, `pieceTransform` reads rest from those snapshots, not from the live `_state`/`_centerOrientations`. The only switch-over point is `activeAnimation` going from non-null to null, at which moment `_state` and `_centerOrientations` have already been updated to the post-move value. Whichever order the renderer observes the writes, the result is consistent.

Note `IntArray` in `ActiveAnimation` requires custom `equals`/`hashCode` (using `contentEquals`/`contentHashCode`) – the auto-generated `data class` versions would compare by reference and break Compose change detection.

#### Move queue

`CubeMoveQueue` is a producer-consumer queue with a single coroutine consumer. It serialises:

- **Turns** (`QueueItem.Turn`) – animated face turn
- **Resets** (`QueueItem.Reset`) – authoritative state replacement (for `RubiksCube.resetState()`, `RubiksCube.resync(target)`, and `RubiksCube.catchUpVisualTo(target)`)

Animation duration policy adapts to backlog:

- 0 waiting → smooth 180 ms (`DURATION_FULL_MS`)
- 1 waiting → quick 80 ms (`DURATION_QUICK_MS`)
- 2+ waiting → snap (0 ms, just commit)

Opposite-face pairs (e.g. `R` and `L` arriving within 60 ms of each other – `SLICE_PAIR_WINDOW_MS`) are coalesced into a single visual animation with both layers turning concurrently. They share one `Animatable` so they stay perfectly in lockstep.

**Local pending deque inside `consume()`.** The kotlinx `Channel` is FIFO with no front-insertion API. The earlier design re-enqueued drained-but-not-yet-animated moves via `channel.trySend(...)` to the channel tail – which under simultaneous producer-side `enqueueMove` writes would land NEW moves ahead of the reclaimed ones, **reordering the move stream**. Since `logicalState` (in `SolveViewModel`) is updated synchronously in event-arrival order, any reorder here desyncs the visual from the logical (and the physical cube). The current design uses a local `ArrayDeque` inside `consume()`: items drained but not animated stay in `pending`, and the consumer always reads from `pending` first before falling through to `channel.receive()`. New producer-side moves always go to the channel tail, so the order seen by the consumer is `[stuff drained earlier] → [stuff sent later]` – correct event order. `waitForPartner` likewise pushes non-partner Moves it sees onto the same `pending` deque rather than re-sending them to the channel.

**Reset handling is non-trivial.** A reset that arrives mid-animation does NOT cancel the running animation; cancelling could either drop a move or commit one we'd then have to suppress. Instead, the in-flight animation runs to completion (its commit lands), then the reset overwrites `_state` and zeroes `_centerOrientations` (via `applyResetTarget`). The single-source-of-truth model means the in-flight commit's effect is harmlessly overwritten. Items that arrived in the channel *after* the reset (`postReset`) are preserved and become the new `pending` deque after the reset is applied – those are user moves the cube has already physically made and they need to apply on top of the resync target.

#### `resync` vs `catchUpVisualTo`

Two ways to bring `_state` to a target value:

- **`resync(target)`** – for hardware-reported snapshots (BLE Facelets event). Always zeroes centers because the cube doesn't report center rotation, so we have no reliable source for what the centers should be. The one-quarter-turn-off center is a smaller visual glitch than a misaligned permutation.
- **`catchUpVisualTo(target)`** – for visual catch-up to a known-correct logical state (Solve screen entry). No-op if `_state` already matches `target` (preserves centers in the common case). Routes through the queue's reset only if state actually differs – and in that case centers get zeroed too because if the visual was lagging, we have no way to know what centers should be.

The Solve screen uses `catchUpVisualTo` because the move queue is stopped while the user is on a different screen, so any moves received over BLE during that window pile up in the channel. `logicalState` (in the VM) stays current synchronously. When the user returns, `catchUpVisualTo` snaps the visual to the same state without replaying the stale backlog as visible animations.

#### Cube orbit (user manual rotation)

`CubeOrbiter` owns a `MutableState<Transform>` that the user drags. Key behaviours:

- DOWN captures start state; MOVE accumulates yaw + pitch on the start rotation
- UP closes the gesture and schedules an auto-snap (500 ms `SNAP_DELAY_MS`)
- Auto-snap chooses the nearest of the **24 cube-symmetric orientations** (`CUBE_ORIENTATIONS`) – fights floating-point drift after many gestures
- Auto-snap is gated on the `autoSnapAllowed` predicate injected by `RubiksCube` (`{ !gyroscope.enabled }`). With the gyro live the composed pose isn't axis-aligned whatever the drag component snaps to, so snapping would just yank the cube. Injected as a predicate rather than a mutable flag so there's no second copy of the condition to keep in sync.
- Manual "Reset orientation" button slerp-animates back to identity
- `snapToNearest()` is also called directly when the **Gyro toggle is switched off** (`RubiksCube.snapOrientationToAxes()`). Auto-snap was suppressed for the whole time the gyro was live, so the drag half has been accumulating un-snapped; switching off is the moment squaring it up becomes both meaningful and possible.
- `resetImmediately()` drops the drag offset with no animation and needs no scope. It is the fallback used on disconnect, because a link can just as easily drop while the user is on Devices with the cube view disposed (and therefore with no bound scope to animate in). A `resetGeneration` counter makes an in-flight slerp go quiet instead of painting over the forced reset for the rest of its tween.

The Solve screen hides the Reset Orientation button when the orbit is already approximately at identity (`isApproximatelyIdentity`, ~1.8° tolerance) **and** the gyro is not live – with the gyro running the button is the only way back to a centred pose, so it stays up. "Live" includes the connection: the gyro term is `gyroEnabled && connection.isConnected`.

#### Gyro

`CubeGyroscope` (in `domain/cube/`) owns everything about the physical cube's orientation, so `RubiksCube` stays about cube *state* and `CubeOrbiter` stays about the user's drag. Any cube reporting gyro data feeds it via `SolveViewModel` → `cube.gyroscope.onSample(quat)`; the pipeline is vendor- and generation-agnostic.

**Pipeline.** `raw (cube axes) --remap--> sample --basis--> target --slerp--> displayed`

1. **Remap** (`Quaternion.toRendererFrame()`). The cube's sensor frame is not the renderer's: `(x, y, z) -> (x, z, -y)`, a −90° rotation about X. A basis change of a rotation transforms only its axis, so permuting the vector part and leaving `w` alone is the entire conversion. Same mapping the official GAN reference client applies. Normalised on the way out — the wire format quantises each component to 15 bits + sign.
2. **Basis.** `target = basis * sample`. `basis` starts at identity, so tracking is **absolute**: enabling the gyro shows the orientation the cube actually reports. `recenter()` sets `basis = sample.conjugate()`, re-homing the cube without interrupting tracking.
3. **Smoothing.** Gyro notifications arrive in bursts at an uneven rate well below the display refresh rate; rendering `target` directly reads as a visible stutter — the cube teleports between poses. `advance(dt)` eases `displayed` toward `target` with `t = 1 - exp(-dt / SMOOTHING_TAU)`. Deriving `t` from the real frame delta rather than a fixed per-frame fraction keeps the settle time identical at 60/90/120 Hz. `SMOOTHING_TAU = 0.06 s` puts `t ≈ 0.24` at 60 Hz, matching the reference client's fixed 0.25.

`SETTLED_CLOSENESS` (`|dot| ≥ 0.9999999`, ≈0.05°) is where interpolation latches onto its goal exactly. The latch is a jump, so it has to sit below visibility: the obvious-looking 0.99999 is 0.51°, about 1.6 px on a 350 px cube — a small twitch at the end of every settle.

**Composition.** Gyro and orbit are layered, not alternatives: `outer = orbiter.rotation * gyroscope.orientation`. Ordering matters — `a * b` applies `b` first, so the drag wraps the gyro pose exactly the way it wraps a stationary cube, and dragging feels identical either way. `gyroscope.isIdle` short-circuits the multiply (26 per frame) whenever the gyro contributes nothing.

**Driving the loop.** `CubeView`'s `Frame { }` calls `cube.advanceFrame(frameInfo.dt)` once per rendered frame, before any piece transform is read, so every cubie in a frame sees the same orientation. `CubeGyroscope` deliberately avoids Compose `MutableState`: samples arrive tens of times a second and the only consumer is the polling render loop, so routing them through the snapshot system would invalidate state on every packet for nothing. The BLE/UI threads write `@Volatile` fields; the render thread exclusively owns `displayed` / `cachedTransform`, and the cached transform is rebuilt only when `displayed` actually moves.

**Reset semantics.** Switching the toggle off — or losing the BLE link — calls `reset()`, which clears `basis` / `latestSample` and points `target` at identity. `displayed` is deliberately left alone so `advance` eases the cube home rather than snapping at a frame boundary. "Reset orientation" calls `recenter()` **and** animates the orbiter to identity, so both layers of `outer` come home as one motion.

**Disconnect.** Losing the cube runs `RubiksCube.resetView()`: gyro `reset()`, orientation home (animated when a scope is bound, `orbiter.resetImmediately()` otherwise), and logical/visual state back to solved. Everything on screen came from a cube that is no longer reporting — a pose frozen at the angle the link died at, and a permutation the user is free to change behind our back — and both read as "broken" rather than "disconnected". The Gyro button is hidden while disconnected (`connection.isConnected && gyroSupported == true`), which matters because `connectionSummary` falls back to the most-recently-seen paired cube when no MAC is active and the button would otherwise linger.

**Live tracking = preference AND connection.** `cube.gyroscope.setEnabled(...)` is driven by `combine(_gyroEnabled, ble.connectionState)`, not by the preference alone. A gyroscope left "on" with nothing feeding it is not harmless: `CubeOrbiter`'s auto-snap is gated on `!gyroscope.enabled`, so the cube would stay un-snappable for the whole time it is disconnected. Gating on the connection also means a reconnect resumes tracking by itself, with no second copy of the preference to keep in sync.

**Persistence.** The toggle lives in the per-profile `solving.gyroEnabled` setting (default false), surfaced as `SolveViewModel.gyroEnabled`. `_gyroEnabled` is seeded from `cube.gyroscope.enabled` rather than `false`: `RubiksCube` is an app-wide singleton while the VM is recreated on every navigation back to Solve, so seeding from `false` would cycle the gyro off then on and discard the user's re-centering.

#### Gyro capability detection

Whether the Gyro button appears at all is a separate question from the rendering pipeline above, and it has **two independent sources**, because neither is sufficient alone:

1. **Declared.** GAN Gen2 carries an explicit capability bit (bit 104 of the hardware event); Gen3 never has the sensor; Gen4 carries no flag and infers support from the hardware name against a one-entry allow-list (`GAN12uiM`); MoYu V10 reports its own flag. `Hardware.gyroSupported` is `Boolean?` so "not established yet" stays distinct from "no" — persisting a premature `false` would hide the feature permanently.
2. **Observed.** `ConnectionOrchestrator` calls `devicesRepo.markGyroSupported(mac)` the first time actual gyro data arrives (latched per connection). Gyro notifications are unsolicited on cubes with the sensor, so this lands within about a second of connecting, and it cannot be wrong.

The second path exists because the first fails in at least three real ways: a Gen4 hardware name outside the allow-list (the GAN14 ui FreePlay emits gyro data while sitting outside it), a Gen2 capability bit that reads 0 on a cube that plainly has the sensor, and — the one actually observed in the field — a hardware handshake that never completes at all, leaving the cube stuck reporting "hardware blank, gyro unknown" for as long as it stays paired. See *Hardware handshake* in the connection-orchestrator section.

---

### Solve timing

**Files:** `domain/timing/{SolveTimer, ClockSkewEstimator}.kt`, `ui/screens/solve/InspectionTimer.kt`.

Solve duration uses **dual-clock semantics**:

- **Wall-clock** (`currentTimeMillis()`) drives the on-screen tick at 16 ms – the displayed timer never freezes between moves.
- **Cube-clock** drives the **canonical** solve duration. Cubes accumulate `cubeTimestamp` from per-move `elapsed` values reported on each packet; this is monotonic, runs on the cube's crystal oscillator, and has no BLE-jitter. `SolveTimer.finish()` returns `lastCubeMs - firstCubeMs`.

`ClockSkewEstimator` still runs an online linear regression (cube_ts → device_ts) using O(1) running sums and is exposed for future stat code that wants to map cube timestamps back to wall-clock for display. It is **no longer in the critical path** for the canonical duration, because mixing scales caused user-visible drift. An earlier design used `firstCorrectedMs = deviceTs` (raw, when the regression had only 1 sample and was deemed unreliable) and `lastCorrectedMs = predict(cubeTs)` (regression-fitted later, after ≥20 samples) – these landed on different scales, so a 60-second wall-clock solve could finish reporting 20 seconds. Computing duration purely from cube timestamps avoids the mix entirely.

If the cube timestamps are degenerate (`first == last`) or non-monotonic (`last < first`, shouldn't happen in normal operation), `finish()` falls back to wall-clock duration as a defensive measure.

`InspectionTimer` is a separate 15 s countdown (WCA standard) that cancels when the user makes their first move.

---

### Database & repositories

**Schema files:** `shared/src/commonMain/sqldelight/com/zucham/qbsmarter/db/{Users, AppState, Cubes, CubeNames, Solves, SolveTracks, Settings}.sq`, plus the `.sqm` migration files in the same directory (see *Migrations*). The `.sq` files always describe the *current* schema; each `.sqm` is a delta applied to installs that are behind, and `Schema.version` is the highest migration number plus one.

**Repository files:** `data/db/{UserRepository, DevicesRepository, SolvesRepository, SettingsRepository, Database}.kt`.

See *Database schema* below for SQL details. Each repository exposes both reactive flows (`asFlow().mapToList(ioDispatcher)`) and snapshot reads. Snapshot reads are used in non-coroutine contexts (event handlers, init blocks) and by the import/export flow.

#### `UserRepository.bootstrap()`

Idempotent. After it returns:
- there is at least one row in `users`
- `app_state.active_user_id` points to a real row

Three cases handled:
- (a) no users → create one, make active
- (b) users exist, active is null/stale → pick first, store
- (c) users exist, active is valid → no-op

#### `UserRepository.deleteProfile`

Enforces the "always at least one profile" invariant. If you delete the only profile, a fresh empty one is auto-created and made active. If you delete the active profile, the next-most-recent profile becomes active. Cubes/solves/settings/names and the reconstruction tracks cascade-delete via FK — which, as of v1.3.0, is finally true rather than merely intended; see *Foreign keys*.

#### `UserRepository.observeActive` and the rename-propagation fix

A naive `observeActiveId().map { id -> selectById(id).executeAsOneOrNull() }` only re-emits when the active *id* changes – not when the active row's `display_name` changes. So renaming a profile didn't propagate to the sidebar pill, History title, or Settings field. The fix is `flatMapLatest` into a reactive query on the specific row, so any UPDATE on the active row fans out to every downstream consumer.

#### `SolvesRepository`

Effective solve time is `durationMs + penaltyMs` (excluding DNFs). DNF/+2 are stored separately so removing a +2 doesn't lose data. Stat queries (`bestDuration`, `bestAo5`, `pageByDurationAsc`) use the effective time and skip DNFs.

It also owns the derived `ao5_ms` / `ao5_times` columns — computed inside the insert transaction from the rows the database holds, and repaired after every penalty edit, delete and import (see *Ao5 as a maintained column*) — and the reconstruction tracks: `saveTracks`, `moveTrack` / `gyroTrack`, `setGyroPinned`, and the three retention sweeps.

`pageByDurationDesc` (worst-time sort) puts DNFs at the top – treated as "very bad" – then descending effective time. `pageByDurationAsc` (best-time sort) puts DNFs at the bottom regardless.

---

### Profile, cache, controllers

**Files:** `data/profile/ActiveProfile.kt`, `data/cache/{AppCache, CacheController}.kt`, `ui/theme/ThemeController.kt`, `ui/i18n/LocaleController.kt`.

#### `ActiveProfile`

Single source of truth for the active user.

- `id: StateFlow<String?>` – null only briefly before bootstrap
- `profile: StateFlow<UserProfile?>` – full row (auto-updates on rename)
- `idSnapshot()` – non-reactive read for event handlers
- `switchTo(newId)` – flips the active pointer

#### `AppCache`

In-memory cache for hot reads. When enabled (default), holds `StateFlow` snapshots of:

- `allProfiles` – every profile row
- `pairedCubes` – paired cubes for the active profile
- `recentSolves` – most recent 100 solves for stats
- `solveCount` – total count for active profile
- `bestDurationMs` – effective best for active profile
- `settings` – settings key/value map for active profile

Every flow is keyed off `activeProfile.id` via `flatMapLatest`. Profile switch = automatic cancellation + restart on the new id, no manual invalidation.

All of them except `settings` are additionally gated by `enabled`. When `setEnabled(false)` is called, gated flows emit defaults (empty/null/0) and stop re-observing. Synchronous typed reads (`boolSetting`, `intSetting`, `snapshotPairedCubes`) fall through to the repository. Toggle back on → observers automatically resubscribe.

##### Why `settings` is not gated

It used to be, and that was a bug. Every control on the Settings screen renders `settings[key] ?: <default>`, so with caching off the map went empty and each control displayed its default instead of the stored value — including the caching toggle itself, which sprang back to showing "on". Tapping it then wrote `false` a second time, and caching could never be turned back on from the UI.

The general lesson: a flow whose empty state is indistinguishable from "everything is at its default" cannot be gated behind a user-visible flag. The specific trade is also lopsided — a profile's settings are a handful of rows, and the flag exists to stop the app holding hot *bulk* data (cube lists, solve windows) in memory. The synchronous accessors still honour `enabled`, because those serve callers who asked not to be served out of memory; the flow serves the screen that shows the user what is actually in the database, which is a different question.

**History solves are NOT cached.** A profile may have thousands; the History screen uses windowed pagination.

#### `CacheController`

Bridges the per-profile `app.cache.enabled` setting to `AppCache.setEnabled`. Lives one level up from AppCache to avoid a dependency cycle (settings reads themselves go through the cache). Reads through `SettingsRepository.observeBool` directly so it's not circular.

#### `ThemeController`, `LocaleController`

Both observe `cache.settings` for the active profile and project a single key into a `StateFlow`. They write through `SettingsRepository.setString` directly (cache will pick up the change reactively).

`LocaleController` additionally calls `LocaleApplier.apply` whenever the value changes. The reactive `onEach` collector wraps the apply in `withContext(Dispatchers.Main)` because `AppCompatDelegate.setApplicationLocales` internally calls `Activity.recreate()`, which is strictly main-thread-only. Without this funnelling, a manual language toggle or an import-flow setting write would crash with `IllegalStateException: Must be called from main thread` (the controller's scope is `Dispatchers.Default`, which is what publishes the cache change). The init-time apply stays synchronous because Koin construction runs on Main during `Application.onCreate`.

The import flow also calls `flushApplied()` explicitly (already on Main, wrapped in `withContext(Dispatchers.Main)` by `SettingsViewModel.importAll`) to force the apply at a known point AFTER all DB writes have committed – defensive, but the reactive collector would handle it correctly on its own now.

---

### UI: scaffold, drawer, theme, i18n

#### `App` and `AppNavHost`

`MainActivity.setContent { App() }` mounts once. `App` wraps `AppTheme` around `AppNavHost`. There is **no `safeContentPadding`** at the root: Material3's `TopAppBar` paints into the status-bar inset itself, giving us a tinted system bar that matches the theme. Adding safeContentPadding back would create a transparent (Window-default) gap above the bar – visible as a white strip in dark mode on Android 12.

`AppNavHost` is a single `NavHost` rooted at `AppScaffold`. Per-screen scaffolds were rejected because the chrome would flicker on navigation. Routes: `solve` (start destination), `devices`, `history`, `settings`, `guide`. Drawer entry selection navigates with `launchSingleTop = true`, `popUpTo(SOLVE) { saveState = true }`, `restoreState = true`.

#### `AppScaffold`

Owns the modal navigation drawer + `TopAppBar`. The TopAppBar uses theme `primaryContainer` so it picks up the seed color the user picked.

Drawer gestures: `gesturesEnabled = drawerState.targetValue == DrawerValue.Open || !gestureGuard.isSuppressed`.

Once the drawer is open (or animating toward open) every gesture is welcome — swipe-close, scrim tap, Back. `targetValue` rather than `currentValue` so gestures unlock the instant the hamburger is tapped, not when the animation finishes.

While closed, swipe-to-open works **everywhere except inside a region that has claimed the guard**. Material's `ModalNavigationDrawer` applies its drag detection to the whole content area rather than a screen-edge strip, so a horizontal drag on the Solve screen's 3D cube would otherwise read as "open the menu" instead of "rotate the cube". The previous fix disabled the open-gesture globally, which traded that for a smaller problem: swipe-to-open stopped working on every screen, including the four with no conflict at all.

`DrawerGestureGuard` (in `ui/components/DrawerGestures.kt`) is a counter published through `LocalDrawerGestureGuard`, provided by `AppScaffold` around its content. `Modifier.suppressDrawerGesturesWhileTouched()` claims it for the duration of a touch and is applied to exactly one element: the square `Box` holding `CubeView`.

Two details make the timing work. The latch happens on `PointerEventPass.Initial` of the down event — the earliest any node sees it, and before any drag has begun, since a `draggable` only claims a gesture once touch slop is crossed, which takes at least one further pointer event. And **nothing is consumed**: the events continue to the Korender surface underneath exactly as before, so cube rotation is untouched. The guard observes the gesture, it doesn't take it.

#### Drawer

Flat list rows with a leading 3 dp colored stripe (filled on the active route). Width is 280 dp (vs Material's default 360 dp). Bottom block has version + copyright + "Report a bug" (which `mailto:`s through `UrlOpener`). Active profile name is a centered pill above the bottom block.

The pill is **tappable** when an `onProfileTap` callback is supplied (it always is in the `AppNavHost` wiring) – tapping jumps to **Settings**, where profile management lives (rename, switch, export). Same nav pattern as the entry list rows: drawer closes, route navigates with `saveState`/`restoreState` so coming back from Settings restores the previous screen's scroll/state.

#### Dialog buttons

Every confirming dialog and modal in the app uses `DialogButton` (`ui/components/DialogButton.kt`) — an `OutlinedButton` with a 1 dp border drawn in the button's own content colour at 50% alpha.

They used to be bare `TextButton`s: coloured text, no container. That reads well in Material's own specimens but poorly here — on a dialog surface with body text directly above, a coloured word is not obviously a *button*, and the tap target has no visible edge. Drawing the border in the content colour rather than the theme `outline` means a destructive action gets a red outline as well as red text: the warning is carried by the whole control, not just the word.

Three emphases cover every dialog: `PRIMARY` (the action the dialog exists for), `DESTRUCTIVE` (theme `error`), `NEUTRAL` (`onSurfaceVariant`, normal weight so the eye lands on the action first and the way out second). Content padding is tighter than `ButtonDefaults.ContentPadding` (16 dp vs 24 dp horizontal), which keeps a two-button action row from wrapping on narrow phones.

Centralised deliberately — an `OutlinedButton` spelled out at each of the dozen call sites is how the previous inconsistency (three different shades of "cancel") happened in the first place. Note this covers *dialog* actions only: the Info/Forget text buttons inside a paired-cube card are row actions, not dialog actions, and stay as they are.

#### `AppTheme` and `ApplySystemBarsTheme`

`AppTheme` reads `seed` and `mode` from `ThemeController` and chooses light/dark. Material3 doesn't ship "tertiary" colors per seed, so `AppColorSchemes` mirrors `primary` into `secondary` and `tertiary` – components like `FilterChip` (selected → `secondaryContainer`) and `SegmentedButton` automatically pick up the right color without per-component overrides.

`ApplySystemBarsTheme(darkTheme)` is `expect`/`actual`; on Android it sets the icon-color flags via `WindowInsetsControllerCompat` and (on API < 35) sets the bar colors transparent so the theme surface shows through. JVM and Web are no-ops.

`StatusColors` holds the **theme-independent** semantic colors (connected green, disconnected gray, urgency amber/red). These are intentionally NOT keyed off the seed – a "connected" green needs to read the same in BLUE theme as in ORANGE.

##### Surface tonal ladder

The seed palette is bound only to the `primary*` / `secondary*` / `tertiary*` slots; the `surface*` family is hand-picked per mode and lives at the bottom of `lightSchemeFor` / `darkSchemeFor`. Two M3 conventions to keep in mind when editing those:

1. **Container ladder is monotonic.** `surfaceContainerLowest` → `Low` → `Container` → `High` → `Highest` step from "least raised" to "most raised". In **light** mode each step is **darker** (lower brightness); in **dark** mode each step is **brighter** (higher brightness). Inverting any step makes the meaning of the role names confusing – calling something `Low` only makes sense if it's predictably lower-prominence than `Container`.
2. **`surface` ≥ `background` brightness in dark mode; `surface` ≈ `background` in light mode.** The page edge (`background`) is the darkest plane in dark mode; raised surfaces step up off it. Inverting this makes everything painted with `surface` (or with the `lerp(surface, surfaceVariant, k)` tint pattern) read as a recess in the page rather than a raised slab.

Pre-rebuild, both rules were broken: light mode had `surface = #F7F7F9` darker than `surfaceContainerLowest = #FFFFFF`, and three roles (`surfaceContainerLowest`, `background`, `surfaceBright`) collided on `#FFFFFF`. Dark mode had `background = #101012` brighter than `surface = #0B0B0D` and `surfaceContainerLowest = #060607` was the absolute darkest of all surface roles – darker than even the page. The visible symptoms were:
- The History card override to `surfaceContainerLowest` rendered as a black pit in dark mode (below page brightness) and as the page color in light mode (no step).
- The Solve scramble & stat-grid containers (`lerp(surface, surfaceVariant, 0.3)`) read as recessed rather than raised in dark mode because the `surface` base was already darker than the page.
- Several roles were degenerate, so picking between them gave the same fill.

Post-rebuild, the ladders are monotonic and each role has a distinct hex. Rule of thumb when picking a `surface*` token:

| Goal | Token | Light hex | Dark hex |
|---|---|---|---|
| Page background | `background` | `#FFFFFF` | `#0B0B0D` |
| Default content surface | `surface` | `#FFFFFF` | `#141416` |
| Tappable list tile, distinct from page | `surfaceContainerLow` | `#F2F2F6` | `#1A1A1D` |
| Non-active row in a list of cards | `surfaceContainer` | `#EBEBF1` | `#1F1F22` |
| Tile-on-panel (one step up from panel) | `surfaceContainerHigh` | `#DEDEE5` | `#28282C` |
| Strong-contrast tile (`StatTile`) | `outlineVariant` | `#C3C4CC` | `#44454D` |

The last row bends the role-name semantics: `outlineVariant` is nominally a divider/border color, but its tonal value lands exactly where we want a "strong-contrast tile" in this scheme (clearly darker than the container in light mode, clearly lighter in dark mode), and the alternative was hardcoded per-mode hex pairs scattered across files. The `StatTile` doc comment links back to this section.

##### Tinted container helper

`SolveScreen.tintedContainerBackground()` produces the shared raised-container fill used by both `ScrambleCard` and `StatGrid`:

```kotlin
lerp(surface, surfaceVariant, 0.45f)
```

Two non-obvious choices baked in:

- **Opaque, not `surfaceVariant.copy(alpha = 0.3f)`** – `Modifier.shadow` only renders a meaningful drop shadow when the bounds are opaque; with a translucent fill the shadow shows *through* the container itself.
- **Lerp factor 0.45**, not the original 0.3. With the rebuilt surface ladder, 0.3 lands the result barely above page brightness in dark mode (the container reads as part of the page); 0.45 gives a perceptible lift while staying recognisably "tinted surface" rather than "full surfaceVariant".

#### `LocaleController` and `AndroidLocaleApplier`

Locale changes use **AppCompat's per-app locale API** (`AppCompatDelegate.setApplicationLocales`). This:

- shows up in *Settings → Apps → QBSmarter → Languages*
- persists across process restarts without us re-applying
- triggers an **Activity recreate** with the new Configuration, which is what compose-resources needs to pick up the new strings

The locale change in the **import flow** has special handling: a recreate on a still-active SAF result main-thread frame crashed Android 12. So the import sequence is:

1. all DB writes commit on `Dispatchers.Default`
2. then `withContext(Dispatchers.Main) { localeController.flushApplied() }`

Doing the locale flush last ensures no half-finished DB work gets cancelled by the Activity recreate.

`MainActivity` extends **`AppCompatActivity`** specifically because we use AppCompat's per-app locale API (`ComponentActivity` alone doesn't pick up the new Configuration on locale change).

---

### Screens & ViewModels

#### Solve screen – the main one

Layout (top → bottom):

```
ConnectionIndicator
CubeView (square, fills available vertical space)
ActionRow  [Gyro?] [Reset Orientation]   [Reset State]
ScrambleCard  [scramble text + correction prefix]   [New]
─── flexible spacer ───
TimerArea  (timer / status / inspection / post-solve, centered in the spacer)
StatGrid  (3-column compact tiles, anchored to the bottom)
```

The cube box is `weight(1f)` and fills whatever vertical space remains between the connection indicator at top and the fixed-size bottom block. `BoxWithConstraints` chooses `min(maxWidth, maxHeight)` so the cube is always square – fills column width on phones, fills available height on tablets. A separate flexible spacer between the ScrambleCard and the TimerArea lets the timer breathe vertically on tall devices without pushing the stats off-screen on short ones.

When disconnected, a translucent scrim `Box` is drawn on top of `CubeView`. (`Modifier.alpha` doesn't work – Korender renders into a separate hardware overlay layer that bypasses Compose's graphics layer.)

The dot+name row in `ConnectionIndicator` is a tappable shortcut to the Devices screen **when connected**. When disconnected the row is non-tappable because the explicit "Connect cube" button below already provides that path; making both tappable would split the user's attention.

##### ActionRow ordering

Gyro sits at the fixed left edge; Reset Orientation grows and shrinks to its right. The order matters because Reset Orientation is shown only while the cube is off-identity, which — with the gyro live — is most of the time the cube is being handled. With Reset Orientation on the left, every one of those appearances shoved Gyro sideways, and Gyro is the button the user is most likely to be reaching for while the cube is moving. Putting the stable control first means only the transient one moves. Gyro still shifts slightly as its own active dot animates, but that motion belongs to the button and reflects a state the user just changed.

##### ActionRow button colors

- **Reset Orientation** and **Gyro** use `primaryContainer/onPrimaryContainer` so they pick up the current seed color (`ThemedButton`).
- **Reset State** uses `error/onError` (saturated red + white) via `DestructiveButton`. This is the only destructive control in the row, and the previous `errorContainer/onErrorContainer` rendered as a soft pink in light mode that read like a neutral chip rather than a wipe-everything affordance.

##### The Gyro button's active dot

While gyro is on, the label is led by a green dot — same `StatusColors.ConnectedGreen`, same `ConnectionDotSize`, as the connection dot at the top of the screen, so "green dot" means one thing app-wide: something is streaming right now. `ThemedToggleButton`'s fill swap alone is a hue shift inside a single palette, which is easy to miss at a glance and invisible to anyone who can't separate the two shades. `stateDescription` remains the accessible signal; the dot itself is decorative and carries no content description, so it doesn't announce twice.

The dot is wrapped in `AnimatedVisibility` with `fadeIn + expandHorizontally` / `fadeOut + shrinkHorizontally` on matched durations (220 ms in, 160 ms out). The expand/shrink half is what keeps the button from lurching: it animates the element's *measured width* from zero, so the button — and the buttons beside it in the row — reflow a fraction of a dp per frame instead of stepping by the dot's full width in one. Collapsing toward `Alignment.Start` slides the dot out from behind the button's leading edge rather than across the label. The dot and its 6 dp trailing gap share one `Row` inside the transition, so the gap animates with it instead of being left behind when gyro is off.

Two details are easy to lose:

- `ActiveDot` is a **standalone composable**, not an inline `AnimatedVisibility` in the button's content lambda. Written inline it would sit in a `RowScope`, where overload resolution picks `RowScope.AnimatedVisibility` and its own default enter/exit — the same trap `AnimatedResetOrientationButton` sidesteps.
- The toggle button carries `Modifier.widthIn(min = 1.dp)`. Material's `Button` applies a 58 dp `defaultMinSize`, and "Gyro" at this row's compact padding measures under that, so the unchecked button is width-clamped and would swallow the first two thirds of the expansion — sitting perfectly still, then lurching. `defaultMinSize` only applies when incoming constraints leave `minWidth` at zero, so a nominal `widthIn` ahead of it takes the clamp out of play and the width simply follows the content.

##### StatGrid contrast

`StatGrid`'s container background comes from `tintedContainerBackground()` (see *Tinted container helper* under *AppTheme*). Inside, the 3-column grid of `StatTile`s uses `outlineVariant` as the tile fill – nominally a divider role, used here for its tonal value: clearly darker than the tinted container in light mode, clearly lighter in dark mode, in both cases without per-mode branching. Earlier choices here (plain `surface`, then `surfaceContainerHighest`) gave too small a step against the container; `outlineVariant` is the smallest-step-up that reads as a deliberately-different surface in either theme.

##### Phase state machine (`SolvePhase`)

```
IDLE → SCRAMBLING → READY → (INSPECTION) → RUNNING → SOLVED
                                           ↑                ↓
                                           ├────── newScramble() ──┐
                                           └─── abortToIdle() ───┐  │
                                                                 ↓  │
                                                                IDLE
```

- `SCRAMBLING → READY`: live state matches the scramble target (`scramblePrefixStates[k]`)
- `READY → INSPECTION`: scramble complete and `inspection.enabled` setting on
- `READY → RUNNING` (skip INSPECTION): inspection disabled; first move starts the timer
- `INSPECTION → RUNNING`: 15 s elapsed OR first move
- `RUNNING → SOLVED`: live state is solved

##### Scramble generator (`ScrambleGenerator`)

**Status: random-move approximation, not bit-exact WCA.** TNoodle (the official WCA scrambler) generates a uniformly random cube state and asks Kociemba's two-phase algorithm for an inverse-solution as the scramble – that produces uniform-distributed scrambles at variable length 17–21. Porting min2phase to Kotlin Multiplatform is a multi-day project tracked separately; this generator is the interim approximation.

What we do instead: pick random face turns with two canonical-filtering rules.

1. **Same face forbidden.** `R` cannot be followed by any modifier of `R`. Without this, sequences like `R R'` would degenerate.
2. **Sandwich forbidden.** A move whose face equals the *previous-previous* face AND whose previous move is the *opposite* face is rejected – e.g. `R L R`, `R L R'`, `R L2 R'` are all rejected. Such sequences canonicalise to a 2-move equivalent (`R L R` ≡ `R2 L`); emitting the 3-move form would mean two distinct prefixes mapping to the same state.

Both rules are face-only – the modifier (`""`, `'`, `2`) doesn't affect filtering. Min2phase enforces these same rules via its `ckmv2bit` lookup during search.

Length is **sampled per call** uniformly from `[19, 23]` to mimic TNoodle's variable output (TNoodle is 17–21; we go slightly longer because random-move scrambles include "wasted" moves like `R F R'` that a Kociemba solver wouldn't emit). Callers do not pass a length – passing one would just be ignored conceptually since the variability is part of the design.

Rejection vs explicit allow-list: rather than a `generateSequence { random face }.first { allowed }` retry loop, the implementation builds the small allowed-list (4–6 faces depending on history) and indexes into it. Equivalent uniform distribution, bounded work, no retry tail.

##### `logicalState` separate from `RubiksCube.state`

The VM maintains its own `logicalState: CubeState` advanced by every `Move` event. This is independent of `RubiksCube._state` (which is updated asynchronously by the visual move queue / animations). The VM needs a synchronous, race-free ground truth to detect phase transitions – e.g. "we just landed back on a scramble prefix state" can't be observed reliably through the visual queue.

##### Scramble progress + deviation

Precomputed `scramblePrefixStates[k]` = state after applying the first _k_ scramble moves to SOLVED. After each move, `matchedPrefixIndex` checks (in this order):
- next prefix index (`hint+1`)
- current index (`hint`)
- previous index (`hint-1`)
- linear scan as fallback

If the user is off-rails, `_deviationMoves` accumulates. The displayed scramble shows a **correction prefix** in red: `mergeAdjacentSameFace(deviationMoves.reversed().map { it.inverse() })`. The first correction move is bolded and slightly larger – that's the user's next physical action.

##### Half-turn grace window

When the next scramble token is `R2` and the user has just done its first quarter (`R`), the deviation list briefly shows `[R]`. Without a delay, the user sees a confusing flash of red correction text between the two halves of a single physical motion. The VM's `deviationMoves` flow `transformLatest`s with a `HALF_TURN_GRACE_MS` delay in the mid-half-turn case; new emissions cancel the delay so any *real* mistake (or completion of the half-turn) shows immediately.

##### Connection-loss abort and connection-gain reset

A `var wasConnected: Boolean` flag tracks whether we ever reached `CONNECTED`.

- On transition `wasConnected && state ∈ {DISCONNECTED, ERROR, PERMISSION_DENIED, BLUETOOTH_DISABLED}`, the VM calls `abortToIdle()`: timer + inspection cancelled, move count + scramble progress reset, phase set to IDLE. The scramble itself is **not cleared** – the user might want to see what they were working on after reconnect.
- On transition `!wasConnected → CONNECTED` (the cube just reconnected after a previous abort or fresh app start), the VM calls `newScramble()`. Reason: stale state from a previous session can leave the timer/phase machine in a weird limbo (e.g. `–` status forever) when reconnecting after an abort. A clean `newScramble()` resets timer, inspection, move count, scramble progress, and phase in one shot.

The `wasConnected` guard matters: the initial app state is `DISCONNECTED`, so without it we'd immediately abort the freshly-generated first scramble back to IDLE on every cold start. The pair of transitions (true→false aborts; false→true newScramble) gives a fresh state every time the cube comes online.

##### Visual catch-up on screen entry

A `LaunchedEffect(Unit)` in `SolveScreen` calls `vm.resyncVisualToLogical()` whenever the screen enters composition. This catches up the visual state to `logicalState` whenever the user navigates back to Solve from Devices/History/Settings. While the user was away, the cube's move queue was stopped (`CubeView.DisposableEffect.onDispose` calls `cube.stop()`), so any moves received over BLE during that window piled up in the channel without animating. `logicalState` is maintained synchronously by the VM regardless of which screen is shown; this effect ensures the visual catches up cleanly without replaying a stale backlog as visible animations. See *`resync` vs `catchUpVisualTo`* in the Cube domain section.

##### Starting the next solve

Two modes, chosen by the per-profile `solving.anyMoveStartsNewSolve` setting (**default on**).

**Any turn (default).** Any move made in the SOLVED phase brings up a new scramble and starts the next solve. Resolved at the *top* of `handleMove`, before the move is applied — not inside the SOLVED branch — because `newScramble()` resets the cube to solved and a move applied first would simply be erased. The app would then believe the cube is solved while the one in the user's hands is a quarter turn off, which desyncs scramble progress and, worse, means `logicalState.isSolved()` never fires and the *next* solve's timer never stops. Generating the scramble first puts the phase in SCRAMBLING, so the turn falls straight through into normal scramble handling and lands on the fresh scramble as its first move: progress 1 if it happens to match the opening move, otherwise a correction move to undo. Either way the model and the physical cube agree, which is the property everything downstream rests on.

**`U U'` gesture.** With the setting off, a face turn and its immediate reversal within `NEXT_SOLVE_GESTURE_WINDOW_MS` (1500 ms) starts the new scramble instead (`handleNextSolveGesture`). The gesture exists precisely because it is net-zero — it leaves the cube solved, which is the state a fresh scramble is written for — so it remains available for users who would rather their cube never drift off solved.

The post-solve tip (`NextSolveTip`) renders whichever of the two is active; the branch lives inside the one composable rather than at the call site so "what does the app do after a solve" has a single answer next to the setting that decides it.

##### Personal-best celebration

On `finishSolve`, the VM captures `previousBest` from `cache.bestDurationMs.value` **before inserting** the new row (otherwise the cache reflects the new value and we'd compare the solve to itself). If the new effective time strictly beats `previousBest`, `_newPbEvent.value = effective`. The dialog is dismissable via the button, an outside tap, or system back; `dismissPbEvent()` clears the flow.

If the user later marks the just-finished solve `+2` or `DNF`, `recomputePbAfterPenalty` re-evaluates: DNF → never PB; otherwise compare the new effective time to `previousBest` and raise/clear the event accordingly.

##### Keep-screen-on policy

Combined: `(phase != IDLE) && (display.keepScreenOn setting on)` → `screenKeeper.setKeepScreenOn(true)`.

#### Devices screen

Two sections:

- **Paired cubes** – every cube the user previously connected. Active row gets a 2 dp accent border, slightly higher elevation, a green indicator dot, a battery indicator next to the name (when known and connected), and a Disconnect button in place of Connect. Each card has Info + Forget buttons in a bottom row, and a small pencil inline with the cube's name.
- **Available devices** (only while scanning) – fresh BLE results. GAN cubes (MAC prefix `AB:12:34`) are sorted to the top.

##### Available-devices palette

Three layers, deliberately stepped so the relationship reads as panel → tile → highlighted tile in either theme:

- **Panel** (the wrapping `Column`) uses `surfaceContainerLow`. In light mode it sits one step darker than the page; in dark mode (post-ladder-rebuild) it lifts one step above the page. Earlier `surfaceContainer` was a step too dark in light mode and made the panel feel like a heavy slab dropped on the page.
- **Non-GAN tile** uses `surfaceContainerHigh`. The tile sits on top of the `surfaceContainerLow` panel, so `High` gives a clear one-step lift in both modes without the heavy-slab feel of the previous `surfaceContainerHighest` (which read as too dark in light mode against the lighter panel) or the original `surfaceVariant` (which landed essentially on top of the older `surfaceContainer` panel and read as one undifferentiated block).
- **GAN tile** uses the seed's `primary` (with `onPrimary` text) – fully saturated so the cubes-the-user-actually-wants-to-connect-to draw the eye even when several non-GAN devices are listed. Earlier `primaryContainer` was a soft tint that didn't pull focus.

The `VerticalScrollbarBox` in this section overrides the default thumb color with `onSurface @ alpha = 0.7` (vs. the default 0.5) because the thumb sits over the lighter panel rather than the page background, and the default tuned for the page background read as faint here.

##### Renaming a cube

Two entry points, one dialog. The pencil beside the name in the paired list and the **Edit** button in the cube info dialog both set the same `pendingRename` state, so there is a single `RenameCubeDialog` to reason about. In the info dialog **Edit** occupies the `confirmButton` slot (right-hand, the thumb's default target) and **Close** the `dismissButton` slot: the dialog is opened to look at a cube, and renaming it is the one thing you can actually *do* from there, so it gets the reachable position. Editing from the info dialog *replaces* it rather than stacking on top: two modals deep over a list row is more chrome than the task deserves, and the info dialog's title is the very name being edited, so leaving it behind would show a stale value.

The pencil is deliberately below the 48 dp Material touch minimum (32 dp button, 18 dp glyph). It sits inline with the name, and a full-size target would push the name's baseline around and compete with Connect/Forget for the row's attention. The info dialog — one tap away, with a full-size Edit button — is the accessible route to the same action.

Persistence is `DevicesRepository.rename(userId, mac, name)` → a row in `cube_names`, **scoped to one profile**. A blank name deletes the row rather than storing `""`: "no name of my own" is the absence of a row, so the cube goes back to showing whatever it advertises, which is the only sensible reading of clearing the field. See *Per-profile cube names* under *Database schema* for why the name doesn't live on the `cubes` row. No BLE work is involved; the name lives only in our database, so renaming is safe on a connected cube and takes effect immediately because the paired list observes both tables.

The call site passes `cube.userId` rather than re-reading the active profile: the row came out of the paired list, which is queried for the active profile, so its `userId` *is* the active profile — carrying it along removes a nullable lookup that could only ever disagree with the row the user is looking at.

##### Per-row connect feedback

Tapping Connect on a paired cube produces immediate feedback on **that specific row**, not on the screen header – the cube the user just tapped is the one they want to see lighting up:

- the row's right-side button changes to a disabled "Connecting…" pill with a small spinner inside (`ButtonProgressDot`, a 14 dp `CircularProgressIndicator` tinted via `LocalContentColor`),
- every other paired row's Connect button is greyed out (only one connect can be in flight at a time; the orchestrator would cancel the previous job otherwise),
- the row's card gets a thin 1 dp primary border (lighter than the 2 dp border used for fully-connected) so the eye is drawn there.

This is driven by `connectingMac: StateFlow<String?>` in `DevicesViewModel`, which combines `orchestrator.activeMac` with `connectionState == CONNECTING`. The flow flips on as soon as the orchestrator's `connect()` reaches `ble.connectToDevice` – which, when switching cubes, happens after the previous connection has fully torn down (see *Pair new while connected* below).

##### Pair new while connected

Tapping **Pair new** while a cube is connected used to put the peripheral into a half-state: the connection-state flow transitioned to `SCANNING` – telling observers "no longer connected" – while the GATT link was still alive on the radio side. The cube's firmware kept thinking it was connected and wouldn't return to advertising mode until the user power-cycled their phone's Bluetooth.

`DevicesViewModel.startScan()` now handles this:

1. If `connectionState != CONNECTED && != CONNECTING` → fast path, scan immediately.
2. Otherwise launch on `viewModelScope`:
   - `orchestrator.disconnect()` – schedules the BLE teardown.
   - `withTimeout(2 s) { ble.connectionState.first { it == DISCONNECTED } }` – wait for the platform to acknowledge. Same outer bound as `forget()`'s wait.
   - `ble.scanForDevices()`.

Same pattern as `forget()` and the same close-ordering rule applies. After the user picks a new device from the scan results, `vm.pair(device)` → `orchestrator.connect()` runs; the orchestrator itself defensively re-checks for a live connection and tears it down before initiating the new one (covers any edge case where `startScan` was bypassed).

`ConnectionHeader` action by state:

| State | Right-side action |
|---|---|
| DISCONNECTED, ERROR, PERMISSION_DENIED, BLUETOOTH_DISABLED | "Pair" |
| SCANNING | "Cancel" |
| CONNECTING (new device, not yet paired) | disabled "Connecting…" pill with spinner |
| CONNECTING (reconnecting a paired cube) | nothing – the per-row feedback above owns it |
| CONNECTED | "Pair new" outlined; left side adds an animated "GO SOLVE" CTA |

The split between header and per-row feedback during CONNECTING avoids two simultaneous spinners on the same screen for the reconnect case.

When BLUETOOTH_DISABLED, a banner + "Enable Bluetooth" button appears in the screen body.

The "GO SOLVE" CTA scales+fades in when CONNECTED – deliberately the most prominent control on the screen at that moment so the user is pulled toward the timer.

Per-row `Disconnect` does the actual disconnect work; the header doesn't duplicate it (cleaner mental model: per-cube actions on cube cards, screen-level actions in the header).

Per-row `Forget` is async – when forgetting the **currently connected** cube it must wait for the BLE stack to fully unwind before deleting the DB row, otherwise the cube's firmware can be left thinking it's still connected (blocks re-discovery until the user toggles their phone's Bluetooth). The flow:

1. If `connectedCubeId.value != id` → fast path, just `devicesRepo.forget(id)` synchronously. No BLE work needed.
2. Otherwise launch on `viewModelScope`:
   - `orchestrator.disconnect()` (fire-and-forget, schedules the BLE teardown).
   - `withTimeout(2 s) { ble.connectionState.first { it == DISCONNECTED } }` – wait for the platform to acknowledge. The 2 s ceiling is a safety bound; under normal conditions [BleManager]'s own 1.5 s internal fallback resolves first.
   - `devicesRepo.forget(id)` – drop the row.

The matching close-ordering fix lives in `BleManager.android.kt`: `gatt.disconnect()` is called immediately, but `gatt.close()` is **deferred** to the `onConnectionStateChange(STATE_DISCONNECTED)` callback. Closing earlier – synchronously after `disconnect()`, which is what older code did – is a documented Android anti-pattern that can leave the peripheral stuck believing the link is still up. A 1500 ms `cleanupScope` timeout in `disconnect()` force-closes if the callback never arrives (peripheral out of range, stack wedged). The Forget await-loop keys off the same `connectionState` flow, so both paths converge through the same point.

Plain Disconnect-and-then-Reconnect-on-the-same-paired-cube masks the close-ordering issue – the cube's BLE radio still accepts the GATT connect attempt even when its internal "connected" state is wrong. Forget exposes it because the next action requires scanning, which requires the cube in pairing-advertisement mode.

##### `connectedCubeId` heuristic

The BLE side doesn't track which MAC is *actually* on the wire. So `connectedCubeId` uses a heuristic: while `connectionState == CONNECTED`, return the most-recently-seen paired cube id. This works because `ConnectionOrchestrator` `rememberCube`s before connecting, bumping `last_seen`. Same heuristic powers `SolveViewModel.connectionSummary`.

#### History screen

Plain `LazyColumn` driven by a windowed `StateFlow` in the VM. Each call to `maybeLoadMore()` (fired via `snapshotFlow` watching the visible window) appends up to `PAGE_SIZE = 50` rows. `PREFETCH_TRIGGER = 10` items from the bottom.

Rows are swipe-to-dismiss (end-to-start only — right-to-left in LTR locales) with a confirmation dialog. Tap opens a detail dialog (date, scramble, ao5 snapshot, fluency, turn count); the detail dialog has a Delete button that goes through the same confirmation. The turn-count line is hidden for solves that pre-date the `move_count` column (`> 0` guard) so historical rows aren't misleadingly shown as "Turns: 0".

A two-effect pattern handles the swipe state: one effect raises the delete request when `state.currentValue == EndToStart`; a second resets the row to settled when the global `pendingDeleteId` no longer points at this row (confirm or cancel). Same pattern in `SettingsScreen.ProfileRow`.

**Why end-to-start.** Start-to-end is the navigation drawer's open gesture. A drag that begins on a list row and travels that way is ambiguous, and whichever handler wins the race, the other one feels broken — the drawer peeking open behind a half-swiped row, or a row refusing to move. End-to-start belongs to nothing else on these screens, so delete takes it outright. `SwipeToDismissBox`'s direction values are layout-relative rather than absolute, so the two gestures stay mirror images of each other under an RTL layout instead of colliding there. The red `Delete` background aligns its label to `CenterEnd`, the edge the row uncovers as it travels away.

Sort options (chips at the top): newest, oldest, best time, worst time. Sort change scrolls back to the top.

##### Scroll-to-top: window generations

The VM publishes **one** value, not two: `HistoryWindow(generation, rows)`. `generation` increments on every *reset* of the window — sort change, profile switch, screen-entry refresh — and stays put while `maybeLoadMore()` appends pages or `delete()` optimistically drops a row. `resetAndLoadFirst(sort, uid, anchorTop)` bumps it at the moment it publishes the rows, never before.

The screen then needs exactly one effect:

```kotlin
LaunchedEffect(window.generation) { listState.scrollToItem(0) }
```

Because generation and rows arrive in the same value, that effect fires in the same recomposition that first shows the new list.

`refresh(anchorTop = false)` is the in-place variant, used by `setPenalty`: the rows may reorder under a time sort, but the user edited a row they were looking at and should not be thrown back to the top for it. Screen entry passes `anchorTop = true`, which is what covers `AppNavHost`'s `saveState`/`restoreState` restoring a mid-list `LazyListState` when navigating back to History.

**What this replaced, and why.** The previous scheme waited on `vm.loading` cycling false → true → false (`vm.loading.drop(1).first { it }; vm.loading.first { !it }`) before scrolling. That is a race the UI usually loses: `setSort` writes the sort flow synchronously from the click, and the VM's collector commonly flipped `loading` to true *before* the recomposition that launched the waiting effect. `drop(1)` then ate the only `true` that cycle would ever emit, and the effect sat waiting for a load that might never come — so the scroll reset simply never ran.

That left `LazyColumn`'s key-based scroll anchoring in charge. It remembers the key of the first visible row and, when the list changes underneath it, re-finds that key and scrolls to wherever it now lives. Best and Worst are near reverses of each other, so the row that had been at the top of the window turned up at the far end of the new one — and the list obediently jumped to the bottom, exactly the reported symptom.

Two changes fix it independently, which is deliberate:

1. **Item keys are scoped to the generation** (`"$generation#$id"`). A reset publishes a list whose keys share nothing with the one it replaces, so the anchoring has nothing to re-find and cannot chase a row across the sort. Within a generation the keys are stable, so appending a page or dropping a deleted row still holds the user's place.
2. **The generation effect scrolls to the top**, covering the case where the user was scrolled deep into a large window: with no key to anchor on, `LazyColumn` keeps the raw index, which a fresh 50-row page would otherwise clamp to its end.

##### Load-token guard on `loading`

`loadToken` is a monotonic id stamped on each load; only the load still holding the token clears `_loading` in its `finally`. Without it, a page load cancelled by a reset (`loadJob?.cancel()`) runs its `finally` *after* the reset has already set `loading = true`, publishing a spurious `false` mid-reset — which hides the spinner and re-opens the `maybeLoadMore` gate while the reset query is still in flight. `maybeLoadMore` additionally re-checks the generation before appending, so a page that outlived its window can't splice old-sort rows onto the new one.

##### Solve card color

`SolveListItem` overrides `Card`'s default container color with `surfaceContainerLow`. The Material3 default landed on `surfaceContainerHigh`, which on this app's pumped-contrast color scheme came out noticeably darker than users expect for a tappable list tile in light mode. `surfaceContainerLow` reads as one clear step off the page in both modes – darker than the page in light mode (#F2F2F6 vs #FFFFFF) and lighter than the page in dark mode (#1A1A1D vs #0B0B0D).

#### Settings screen

Sections:

1. **Profile** – hint line + picker (active row on top, gear IconButton at row start opens per-profile settings dialog, swipe-to-delete + delete IconButton at row end), Create + Import side-by-side. Per-profile export lives inside the per-profile settings dialog.
2. **Solving** – inspection enabled, keep screen on (sound-effects toggle is commented out – see *Setting keys*)
3. **Display** – theme mode (segmented), theme color (8 swatches), language (System / Manual two-segment with dropdown inside the Manual segment)
4. **Advanced** – cache enabled (with explanation)
5. **About** – version, user id (selectable for support)

Toggle rows read from `cache.settings` so they reflect the active profile reactively. Profile switch = the switches reflect the new profile's values without a VM rebuild.

##### Language picker

Two-segment row: `[✓ System] [Manual – English ▾]`. The Manual segment's label is always `"Manual – <currentlanguage> ▾"` and tapping it behaves contextually:

- In System mode: tapping Manual flips the mode to Manual (using the last-remembered manual language). Doesn't open the dropdown – the user's intent was to flip the mode, not to pick a language.
- In Manual mode: tapping Manual toggles the dropdown so the user can pick a different language.

The `DropdownMenu` is anchored to the Manual segment via a wrapping `Box`, so it appears directly underneath. Languages are listed English-first (the de-facto default) then alphabetically by enum name (`MANUAL_LANGUAGES_ORDERED`). The dropdown closes itself on selection or outside tap. Both segments use `SegmentedButtonDefaults.Icon(selected)` (the Material3 default) which renders a checkmark when active.

Why not the previous design (separate System/Manual segmented row + always-visible dropdown below): the dropdown was visible-but-disabled in System mode, which was a confusing affordance ("why is this thing here that I can't tap?"). Folding the dropdown into the Manual segment removes the disabled-but-shown anti-pattern.

##### Profile management

- `createProfile(name?)` – creates and switches; disconnects active cube first (cube belongs conceptually to the previous profile)
- `switchTo(id)` – disconnects active cube first
- `deleteProfile(id)` – if it's the active profile, disconnect first; `UserRepository.deleteProfile` handles the "always-one-profile" invariant
- `renameProfile(id, name)` – exposed by the per-profile settings dialog
- `exportProfile(id)` – per-profile export, same envelope as `exportAll` but `profiles` contains exactly one entry. Filename embeds a sanitised display name (`FILENAME_UNSAFE_CHARS` regex strips anything outside `[A-Za-z0-9._-]`).
- `solveCountFor(id)` – snapshot read of `solvesRepo.snapshotAllForUser(id).size`, used by the dialog to show "Total solves: N"

The "disconnect on profile switch" is what makes `SolveViewModel.abortToIdle()` fire (via the connection-loss observer), which cleanly resets any in-flight solve before the new profile takes over.

##### `key(profile.id)` around `ProfileRow`

Each `ProfileRow` is wrapped in `key(profile.id) { ... }` inside the `for (profile in sorted)` loop. Without this, Compose's slot table reuses the per-position composition state when the list reorders – and after deleting the active profile A, the next profile B is promoted to active and slides up to slot 0, with A's mid-dismiss `rememberSwipeToDismissBoxState` still hanging around. The result was a visible bug: B rendered already-swiped, AND the delete-confirmation dialog re-fired for B (because `LaunchedEffect(state.currentValue)` ran again for the new identity while `currentValue` was still `EndToStart`). Keying by id gives each profile its own composition region, so B always starts at `Settled` regardless of what A's row was doing.

##### Per-profile settings dialog (`ProfileSettingsDialog`)

Triggered by tapping the gear IconButton on a profile row. Folds three things that used to be separate UI elements into one place:

- editable display name (commits on every change – no separate Save button needed in a single-input dialog)
- total-solves snapshot ("Total solves: N", read once on dialog open)
- per-profile export button (centered in a wrapping `Row` rather than `fillMaxWidth` – a full-width outlined button on a narrow modal looked like a confirm/CTA, which fought the dialog's actual confirm row at the bottom; wrap-content sized to its label reads as the secondary action it is and balances the dialog visually)

Replaces (1) the old standalone DisplayNameField that only edited the active profile, and (2) the old single all-profiles export button. Editing any profile (active or not) now goes through the same UI, which gives a uniform mental model.

The dialog tracks the target profile by id rather than by `UserProfile` instance so an in-flight rename (which causes the profile flow to re-emit with a new `displayName`) doesn't dismiss the dialog. If the profile vanishes while the dialog is open (deleted by another action), a `LaunchedEffect(Unit)` clears the open-id pointer on the next composition.

##### Export / import schema

Current version is **v1**. Pre-release builds emitted v1/v2/v3 bundles with a different envelope shape (single legacy user fields plus a profiles list); the legacy synthesis path was removed for the public release in favour of a single clean schema. The constant `EXPORT_SCHEMA_VERSION` at the bottom of `SettingsViewModel.kt` is the source of truth – bump it (and add a clearly-named migration path) if the schema ever changes again.

`importAll` validates the version strictly: it rejects any bundle whose `schemaVersion` doesn't match `EXPORT_SCHEMA_VERSION` or whose `profiles` list is empty, surfacing the failure via the existing `ImportFailed(reason)` status path.

Settings values import is **whitelisted**: `ALLOWED_SETTING_KEYS` in `SettingsViewModel`. New keys must be added before they round-trip. Stops a malformed/malicious bundle from injecting arbitrary keys.

Merge policy:
- Settings: imported values overwrite local for matching keys; local-only keys survive
- Solves: appended with **full-field dedup** within the target profile. Two solves are considered identical when every persisted field matches: `solvedAt`, `durationMs`, `scramble`, `ao5Ms`, `fluency`, `extras`, `isDnf`, `penaltyMs`, `moveCount`. The auto-incrementing DB `id` and the `userId` FK are intentionally excluded from the fingerprint – id is local to each DB and would never match across exports, and userId is implicit because the dedup set is partitioned per target profile. `moveCount` participates despite being a "history-only" field (no stat consumes it) because two solves at the same epoch ms with the same time and scramble but different turn counts are genuinely different recordings; for v1 bundles produced before the column existed, the field defaults to 0 on both sides of the comparison so older bundles round-trip unchanged. Implementation snapshots existing rows once into a `HashSet<SolveFingerprint>` per profile (O(N+M)), and the same set is used to dedup against earlier rows of the same import batch so a self-duplicating bundle isn't double-inserted either. Re-importing the same backup is a no-op; a freshly-recorded solve done between exports cannot collide because at minimum its `solvedAt` epoch-millisecond differs.
- Cubes: `rememberCube + updateHardwareInfo`
- DisplayName: imported value overwrites local **only if non-null**

Imports run on `appScope` (not `viewModelScope`) so an Activity recreate triggered mid-import (locale change cascading from imported settings) doesn't cancel the import coroutine and leave the DB in a partial state.

##### Localised import/export status

`SettingsViewModel.statusMessage` is a `StateFlow<ImportExportStatus?>` – a sealed interface with seven variants (`NoActiveProfile`, `ProfileNotFound`, `Exported`, `ExportCancelled`, `Imported`, `ImportCancelled`, `ImportFailed(reason)`). The VM publishes the structured value rather than a pre-formatted string because `stringResource` is only callable from `@Composable` code. `SettingsScreen` resolves the variant via `resolveStatusMessage(status)` (a `@Composable` helper at the bottom of the file) before passing it into `ProfilePicker` for rendering. `ImportFailed` interpolates the underlying error message via the format-arg overload of `stringResource`. The `reason` itself is the raw `Throwable.message` / class simpleName and isn't localised – it's a developer-facing detail kept for support.

#### Guide screen

**Files:** `ui/screens/guide/GuideScreen.kt`.

The Guide screen renders a bundled Markdown user guide (the same document users read to learn how the app works). The actual file path is resolved via the localised `guide_file` string resource: `files/usage_guides/usage_guide_en.md` for English, `files/usage_guides/usage_guide_cs.md` for Czech. Adding a new locale therefore needs both a translated `strings.xml` (with the `guide_file` key pointing at the new file) and the matching `usage_guide_<lang>.md` next to the existing ones.

The Markdown is read once via the suspend `Res.readBytes(...)` call inside a `LaunchedEffect(Unit)` and cached in a `String?` state for the rest of the screen's lifetime. While the read is in flight the screen shows nothing (the read is fast – kilobyte-scale – so a spinner would just flicker); on failure (e.g. resource stripped from a malformed APK) the `guide_load_failed` string is shown. The screen does not retry: if the bundle is corrupted the user has bigger problems than a missing guide.

Link clicks inside the rendered Markdown are routed through Compose's `LocalUriHandler` CompositionLocal, which the screen overrides with one backed by the app's `UrlOpener` Koin singleton. On Android `UrlOpener` translates to `Intent.ACTION_VIEW`, so `https://`, `mailto:` and any other registered scheme leaves the app to be handled by the user's default browser/email app – never an embedded WebView. On JVM-desktop and Web stubs the override is the place where a real platform handler would plug in if those targets ever ship.

There is no ViewModel: the Guide screen is purely composable state (`markdownText: String?`, `loadFailed: Boolean`) and doesn't survive process death itself; on re-entry the file just gets read again, which is cheap.

---

### Solve stats

**Files:** `ui/screens/solve/stats/{SolveStat, StatRegistry}.kt`, `ui/screens/solve/stats/builtin/BuiltinStats.kt`.

`SolveStat` is intentionally string-shaped: `compute(history, current): String?`. A stat is a value the user reads, not data we plot. Returning null hides the row (used for Ao5/Ao12 with too few solves and for `TotalSolvesStat` when the count is 0).

`StatRegistry` holds the default order of six stats: `Fluency, Ao5, Ao12, Fastest, Mean, TotalSolves`. They render as a 3-column grid → 2 rows of 3 visible tiles (Best/Mean/Ao5/Ao12 cluster the time-based numbers; TPS and Total close out the second row). The registry is mutable so settings can later let users reorder/hide entries; no UI for that exists yet.

`SolveSession` is the live snapshot passed to stats while a solve is running: `running, durationMs, moveCount, totalSolves, bestDurationMs`. `totalSolves` comes from the cache's `solveCount` because the recent-100 history isn't enough – a profile can have thousands of older solves outside the in-memory window. `bestDurationMs` comes from `cache.bestDurationMs` (an indexed `MIN(duration_ms + penalty_ms)` query, DNFs excluded) and is used by `FastestStat`.

`FastestStat` reads `bestDurationMs` directly – it does **not** mix the running solve's in-flight `durationMs` into the comparison. The previous implementation min'd the running timer with the historical best, which made the "fastest solve" tile track the live timer the moment a running solve dropped below the previous record (effectively duplicating the main timer until SOLVED committed the new row). The displayed value now always reflects the persisted DB record, which is what the user expects to compare their current solve against. If `bestDurationMs` is null (caching disabled), the stat falls back to `history.minOf { it.effectiveMs }` – not perfect (a best older than the recent-100 window slips out) but honest. A new PB triggers `cache.recentSolves` to re-emit (which fans out into `bestDurationMs`), the stat grid recomposes via the `history` parameter changing, and the tile updates without any explicit refresh.

WCA-ish trimmed average: drop top/bottom 5% (≥1 each). Returns null below 5 samples.

---

## Cross-cutting concerns

### Boot ordering in `QbsmarterApp.onCreate`

Order matters because several singletons assume the active profile exists:

1. `startKoin { modules(androidPlatformModule, sharedModule) }`
2. `activeProfile.ensureBootstrapped()` – creates default profile if missing, sets `active_user_id`
3. `get<CacheController>()` – wires `cache.enabled` setting → `AppCache.setEnabled` (resolved before LocaleController so locale's settings read goes through a fully-wired cache)
4. `get<LocaleController>()` – applies persisted language to `AppCompatDelegate` before the first Activity is created
5. `get<ConnectionOrchestrator>()` – starts driver-event listener before any pair attempt
6. `get<AppLifecycle>() + ProcessLifecycleOwner observer` – process-wide foreground/background

### App lifecycle (foreground / background)

`AppLifecycle` (driven by `ProcessLifecycleOwner` on Android):

- **on backgrounded**: `ble.stopScan()` immediately; arm an auto-disconnect timer to save the cube's battery — a smart cube holding a BLE link keeps its radio awake and will flatten itself overnight
- **on foregrounded**: cancel the pending auto-disconnect

#### Auto-disconnect period

The period is the per-profile setting `cube.autoDisconnectMinutes` (default `SettingsRepository.Defaults.AUTO_DISCONNECT_MINUTES` = 5), read through `AppCache.intSetting`. **0 means never**, not "immediately": a zero-length period would be a setting whose only effect is to make the feature unusable, while "stay connected until I say otherwise" is something people actually want. `AppLifecycle` guards on `minutes <= 0` rather than `== 0`, so a negative value from a hand-edited or imported bundle also reads as "off" instead of arming a zero-length timer.

The value is **read once, when the app is backgrounded**, not observed. This is the correct shape rather than a shortcut: the only instant it matters is when the timer is armed, and between then and the disconnect the app is in the background where the user cannot be changing it. Observing it would mean holding a subscription open across the whole background period to react to a change that cannot happen. A change made while the app is open governs the *next* backgrounding — which is also the only sequence a user can observe.

The picker (Settings → Advanced) offers 1 / 2 / 5 / 10 / 15 / 30 minutes and Never. It's a dropdown rather than a segmented row because seven options would either wrap or shrink to unreadable on a phone. A stored value outside that set (hand-edited, or imported from a build with different periods) is added to the menu as a normal entry rather than silently rewritten, so the control never misreports what is in effect.

### Lifecycle-gated CubeView

Korender renders into a native Android `SurfaceView` that lives in the window layer, *not* in Compose's drawing tree. When the user navigates away, Compose removes the `CubeView` composable but the SurfaceView itself takes ~1 frame to detach – and during that frame the new screen has already started drawing on top, so the cube briefly shows through.

`CubeView` proactively hides the Korender block on `Lifecycle.ON_PAUSE`/`ON_STOP` via a `renderActive` flag, falling back to a theme-colored placeholder Box. The `onDispose` also flips the flag for the case of in-Activity navigation (no lifecycle event, just NavHost dispose).

### Initial-frame cover (the doc-comment vs. code gap)

A fresh SurfaceView is opaque-black until its OpenGL surface is ready and the first frame is rendered. The `Modifier.background(theme)` on the Box around the Korender block doesn't help because the SurfaceView sits in a SEPARATE window layer above the Compose drawing tree, so it covers up whatever Compose drew underneath it.

The kdoc at the top of `CubeView.kt` describes a "fade-out cover Box" workaround for this, but the current implementation does **not** ship that fade. What `CubeView.kt` does today is:

- While the host screen is `STARTED`/`RESUMED`, `renderActive` is true and the Korender block is composed; while paused/stopped (or once the composable is disposed), `renderActive` flips to false and a same-coloured placeholder `Box` replaces the Korender block. This is what handles the "lingering surface" case where Compose tears down but the SurfaceView takes a frame to detach.
- There is no `coverAlpha` / `animateFloatAsState` / `LaunchedEffect(renderActive)` fade cycle in the file. On a true cold-start the user may briefly see the SurfaceView's black initial frame before Korender's first paint. Fixing it properly along the lines of the kdoc is open work.

### Theme change forces Korender re-init

Korender's `this.background = ...` is set once during scene setup; assigning to it inside `Frame { }` doesn't propagate to the GL clear color reliably across versions. So `CubeView` wraps the whole `Korender { }` block in `key(backgroundColor) { ... }` – a theme change tears down and rebuilds the surface with the new color. Theme changes are infrequent, so this is not a per-frame cost.

### File export rationale (`AndroidFileExporter`)

SAF-based save/open via `ActivityResultContracts.{CreateDocument, OpenDocument}`. Bound to `MainActivity` in `onCreate` (must be before `onStart` per AndroidX rules). Held by `WeakReference` to avoid leaks if the activity is destroyed mid-flight. Every entry-point that can throw – `launcher.launch`, `contentResolver.openInputStream`, `readBytes` – is wrapped in `runCatching` so failures surface as `null`/`false` returns instead of activity crashes.

---

## GAN Gen2 protocol notes

Static reverse-engineered constants (key, IV, character map, command codes) are documented in the codebase:

- **AES-128 CBC, no padding.** 16-byte key + 16-byte IV are static ("well-known" constants from the smart-cube community, pulled from disassembly of the official Gan i Carry app).
- **Per-cube salt:** 6 bytes derived from the BLE MAC (`mac.split(':').map { hex }.toByteArray().reversedArray()`). Salt mixes into bytes 0..5 of both key and IV via `(byte + salt) % 0xFF` (yes, `0xFF`, not `0x100` – that's the protocol).
- **Two-block encryption for >16-byte payloads:** encrypt block at offset 0, then block at `size - 16`. Decryption reverses the order.

### Service / characteristic UUIDs

Three sets, one per drivable protocol generation, held as `CubeProtocolRegistry` rows (`gan-gen2`, `gan-gen3`, `gan-gen4`) and resolved at connect time by `CubeProtocolRegistry.resolve(...)`:

```
Gen2 (i Carry, i Carry S, i 3, GAN12 ui, GAN Mini ui FreePlay, Monster Go 3Ai)
  service: 6e400001-b5a3-f393-e0a9-e50e24dc4179
  command: 28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4    (write)
  state:   28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4    (notify)

Gen3 (i Carry 2)
  service: 8653000a-43e6-47b7-9cb0-5fc21d4ae340
  command: 8653000c-43e6-47b7-9cb0-5fc21d4ae340    (write)
  state:   8653000b-43e6-47b7-9cb0-5fc21d4ae340    (notify)

Gen4 (GAN12 ui Maglev, GAN14 ui FreePlay)
  service: 00000010-0000-fff7-fff6-fff5fff4fff0
  command: 0000fff5-0000-1000-8000-00805f9b34fb    (write)
  state:   0000fff6-0000-1000-8000-00805f9b34fb    (notify)
```

CCCD descriptor `00002902-0000-1000-8000-00805f9b34fb` is written to enable notifications on the state characteristic. The successful `onDescriptorWrite` callback is what flips `BleManager.notificationsReady` to true – same mechanism for all three generations.

### Packet format (decrypted) – Gen2

20-byte packets, big-endian bit-packed. The first 4 bits are the message type:

| Type | Hex | Meaning |
|---|---|---|
| `0x01` | Gyro | Quaternion + angular velocity |
| `0x02` | Move | Up to 7 most recent moves with cube timestamps |
| `0x04` | Facelets | Full corner/edge state |
| `0x05` | Hardware | HW/SW version + name + gyro support flag |
| `0x09` | Battery | 0..100 % |
| `0x0D` | Disconnect | Cube notifies us it's going away |

Move packets carry a **rolling 8-bit serial number**. The cube's on-board buffer holds the **7** most recent moves (`MOVE_HISTORY_SIZE`). On each move packet, the parser computes:

```
rawDiff = (serial - lastSerial) & 0xFF
diff    = min(rawDiff, 7)
missed  = max(rawDiff - 7, 0)
```

If `missed > 0`, the parser emits a `SmartCubeEvent.MovesMissed` and the orchestrator triggers a `RequestFacelets` resync (debounced).

**Facelets packets also carry a serial.** `parseFacelets` always updates `lastSerial = serial` upon receiving a Facelets packet – not just on the very first packet (that was a bug). After a `MovesMissed → RequestFacelets → Facelets` cycle, the next Move packet must be diff'd against the Facelets serial; otherwise the diff would span moves the cube ALREADY encoded into the Facelets snapshot, and those moves would get re-emitted on top of the just-applied Facelets state – i.e. **double-applied**. Symptom: visualisation drifting away from physical reality after a resync, with the drift growing every subsequent move.

### Move face encoding

Gen2 reports a 3-bit face index in URFDLB order (`0=U, 1=R, 2=F, 3=D, 4=L, 5=B`). `GAN_FACE_ORDER` translates to `CubeFace`.

Gen3 and Gen4 use a 6-bit one-hot encoding for live MOVE events (`GAN_GEN3_4_FACE_ONE_HOT = [2, 32, 8, 1, 16, 4]`, indexOf-mapped to URFDLB) and a different 3-bit lookup for MOVE_HISTORY responses (`GAN_GEN3_4_HISTORY_FACE_ORDER = [1, 5, 3, 0, 4, 2]`). The two-table arrangement comes from the wire designers needing to pack MOVE_HISTORY as 4-bit pairs (face + direction) for density, while MOVE events have room for the verbose one-hot field.

### Gyro signed encoding

Each component is packed as `[sign_bit | magnitude_bits]`. `fixSigned` recovers the float in `[-1, 1]`. Used by both Gen2 and Gen4 parsers (Gen3 doesn't report gyro).

### Command codes – Gen2

```
0x04  RequestFacelets
0x05  RequestHardware
0x09  RequestBattery
0x0A  RequestReset (16-byte payload of magic bytes)
```

Each command is a 20-byte payload with the opcode at byte 0; reset has a fixed magic-byte tail. `RequestMoveHistory` is also defined in `SmartCubeCommand` for Gen3/Gen4 compatibility, but `GanGen2Protocol.buildCommand` returns `null` for it – Gen2's recovery path is the orchestrator's MovesMissed → Facelets resync.

### Packet format (decrypted) – Gen3 / Gen4

These two generations share a buffered-FIFO recovery model, but their wire formats differ in framing.

**Gen3 framing.** 16-byte packets, byte-aligned (vs Gen2's bit-packed). Magic byte `0x55` at offset 0; event-type byte at offset 1; data length at offset 2. Notable events:

| Hex | Meaning |
|---|---|
| `0x01` | Move (cube timestamp LE u32, serial LE u16, direction 2 bits, face 6-bit one-hot) |
| `0x02` | Facelets (serial LE u16, then CP/CO/EP/EO bit-packed) |
| `0x06` | MOVE_HISTORY response (startSerial + N×4-bit face/direction pairs) |
| `0x07` | Hardware (HW/SW version + 5-byte name) |
| `0x10` | Battery |
| `0x11` | Disconnect |

Commands are 16-byte payloads with `0x68` at byte 0 and a sub-opcode at byte 1: `0x01` Facelets, `0x03` MoveHistory, `0x04` Hardware, `0x05` Reset, `0x07` Battery.

**Gen4 framing.** 20-byte packets, no magic byte – event-type at offset 0, data length at offset 1. Field offsets shifted by –8 bits relative to Gen3. Distinctive events:

| Hex | Meaning |
|---|---|
| `0x01` | Move |
| `0xD1` | MOVE_HISTORY response |
| `0xED` | Facelets |
| `0xFA-0xFE` | Hardware fragments (date / name / SW / HW – emit unified Hardware once all collected) |
| `0xEC` | Gyro (only on hardware named `GAN12uiM`) |
| `0xEF` | Battery |
| `0xEA` | Disconnect |

Commands use `0xDD`/`0xDF`/`0xD2`/`0xD1` at byte 0.

**Recovery (Gen3/Gen4).** When a Move event's serial is more than 1 ahead of the parser's `lastSerial`, the parser inserts the move into a FIFO and asks the cube to retransmit the gap via `RequestMoveHistory(startSerial, count)`. The cube responds with a `MOVE_HISTORY` event whose moves the parser injects at the FIFO head. The FIFO is drained from the head as long as serials remain contiguous. If the FIFO grows past 16 entries the parser surfaces `MovesMissed` and the orchestrator falls back to `RequestFacelets` resync – same bail-out as Gen2.

The protocol asks for backfill through the `ProtocolIo` handed to `decode`, whose `send(...)` encrypts and routes the request over the active transport. The gap bookkeeping itself is `MoveRecoveryFifo`, shared by Gen3 and Gen4 rather than copied into each.

---

## MoYu WeiLong V10 AI protocol notes

Reverse-engineered protocol details for the MoYu V10 AI smart cube. Source: the [WeiLong V10 AI protocol writeup](https://github.com/lukeburong/weilong-v10-ai-protocol) by `lukeburong`.

### Service / characteristic UUIDs

```
MoYu V10 AI (device name prefix WCU_MY)
  service: 0783b03e-7735-b5a0-1760-a305d2795cb0
  notify:  0783b03e-7735-b5a0-1760-a305d2795cb1
  write:   0783b03e-7735-b5a0-1760-a305d2795cb2
```

The cube also advertises an OTA firmware-update service at `02f00000-…-fe00` (`READ`/`WRITE`/`NOTIFY` on `…ff00-ff03`). This isn't used by QBSmarter – firmware update is the official WCU app's domain.

### Encryption

AES-128 CBC, no padding. Same scheme as GAN Gen2+ (key + IV mixed with reversed-MAC salt, `% 0xFF` modulus, two-block tail-encryption for payloads > 16 bytes), with a *different* root key + IV. Implementation lives in the shared `AesCbcMacSaltEncryptor`; `MoyuEncryptor` is a thin factory that supplies the MoYu constants:

```
Root key: 15773A5C670E2D1F17672A139B675257
Root IV:  11232625862A2C3B55067F317E672157
```

### Packet format (decrypted)

20-byte packets, message type at byte 0. Distinct events:

| Hex | Meaning |
|---|---|
| `0xA1` | Cube Info (8-byte model name, HW/SW versions, gyro flags, move counter) |
| `0xA3` | Cube Status / Facelets (48 stickers × 3 bits in FBUDLR face order + serial) |
| `0xA4` | Cube Power (battery 0..100) |
| `0xA5` | Cube Move (5 most-recent moves + 5 × u16 per-move ms + serial) |
| `0xAB` | Gyroscope (4 × LE s32 quaternion over `2^30`, component order `(w, x, -z, y)`) |
| `0xAC` | Gyro enable/disable command + ack |

Commands use the same opcode bytes as the corresponding events (e.g. `0xA1` requests Cube Info; the cube replies with `0xA1`).

**Init quirk.** Per the writeup: "Immediately after connecting to the cube, you need to write a Cube Info (0xA1) message to initialize correctly the cube." The orchestrator's `RequestHardware` post-connect command maps to 0xA1 on MoYu, so this just happens naturally as part of the existing handshake.

**Facelets representation.** Unlike GAN, MoYu reports facelet state as sticker colors (3 bits each, 0=Green, 1=Blue, 2=White, 3=Yellow, 4=Orange, 5=Red) rather than CP/CO/EP/EO. The `MoyuFaceletDecoder` reorders the 48 stickers from FBUDLR to URFDLB, relabels colors to face letters (WCA orientation: green front, white top), and hands the resulting 54-char Kociemba string to `CubeState.fromKociembaFacelets(...)`. That helper does the corner/edge identification by walking the same `CORNER_FACELET_MAP`/`EDGE_FACELET_MAP` tables `toKociembaFacelets` uses in the forward direction.

**Recovery model.** The 0xA5 packet always carries the 5 most-recent moves keyed by a rolling 8-bit serial counter. Same shape as GAN Gen2's 7-move buffer but two moves shorter. Parser logic mirrors Gen2: `rawDiff = (serial - lastSerial) & 0xFF`, `diff = min(rawDiff, 5)`, `missed = max(rawDiff - 5, 0)`. On `missed > 0` it surfaces `MovesMissed` and the orchestrator's debounced `RequestFacelets` resync recovers. No targeted move-history retransmit is documented in the protocol; `SmartCubeCommand.RequestMoveHistory` returns `null` from `buildCommand` for MoYu, same as for GAN Gen2.

**Move encoding.** Five 5-bit codes packed back-to-back at bit offset 96. Each code directly encodes face + direction (no separate direction bit, unlike GAN's encodings):

```
0  → F     1  → F'
2  → B     3  → B'
4  → U     5  → U'
6  → D     7  → D'
8  → L     9  → L'
10 → R     11 → R'
```

The moves are packed newest-first; the parser iterates `diff - 1 downTo 0` to emit them oldest-to-newest (causal order), matching the Gen2 emission loop and the order the rest of the app expects.

**Gyro.** Cube ships with gyro on by default. We send `0xAC + 0x00 + 0x01` (enable) as the last post-connect command to ensure a known-on state regardless of whatever previous-client-session state the cube remembers. The cube replies with a `0xAC` ack which the driver swallows.

The quaternion encoding has a known firmware quirk where the official implementation's signed-shift causes off-by-one sign extension; we follow the writeup's corrected interpretation: read each 4-byte chunk as a signed 32-bit LE int, divide by `2^30`, and apply the documented component order `(w, x, -z, y)`. Korender's `Quaternion(w, Vec3(x, y, z))` constructor then receives a properly negated z.

**No reset opcode.** The protocol writeup documents no software-reset command. `SmartCubeCommand.RequestReset` returns `null` from MoYu's `buildCommand`; user-initiated "reset visual state" is purely app-side (reset internal `CubeState` to SOLVED + zero centres; nothing is written to the cube). The cube reports the physical state, so any real "fix mismatch" workflow is to physically solve the cube and let the resulting Facelets event drive the resync.

---

## Database schema

```
users                       app_state                cubes                     solves                       settings          cube_names
─────                       ─────────                ─────                     ──────                       ────────          ──────────
id PK                       id PK (=0)               id PK                     id PK AUTOINCREMENT          (user_id, key) PK (user_id, mac) PK
display_name                active_user_id ─→ users  mac UNIQUE                user_id ─→ users (CASCADE)   value             name NOT NULL
created_at                                           name (advertised)         solved_at
                                                     last_seen                 duration_ms
                                                     user_id ─→ users          scramble
                                                     hw_version                ao5_ms      ─┐ derived
                                                     sw_version                ao5_times   ─┘
                                                     gyro_supported            fluency
                                                     vendor (default 'gan')    extras
                                                                               is_dnf
                                                                               penalty_ms
                                                                               move_count
```

- `app_state` is a single-row pattern: PK is constant 0 (`CHECK (id = 0)`), so it can hold at most one row. `INSERT OR IGNORE` bootstraps; `UPDATE` mutates.
- All three child tables (`cubes`, `solves`, `settings`) reference `users(id) ON DELETE CASCADE`. `app_state.active_user_id` is `ON DELETE SET NULL`.
- `cubes.upsert` updates `user_id` on conflict – critical for multi-profile flows: a cube paired under profile A and re-paired under B must transfer ownership; otherwise `selectByUser(B)` won't return it and the cube is invisible in B's Paired list.
- `cubes.name` is the name the cube **advertises** over BLE — hardware-level, shared by every profile. A user's own name for a cube lives in `cube_names`, keyed by profile. See *Per-profile cube names* below.
- `cubes.upsert` sets `name = COALESCE(excluded.name, name)`: take the newly-advertised value whenever the cube reports one, keep the last one we saw when it doesn't. This clause used to be the other way round (fill-only, `COALESCE(name, excluded.name)`) because user renames lived in this column and every reconnect would otherwise have stamped the manufacturer's name back over them. With renames moved out to `cube_names`, the clause went back to meaning what it says.
- `cubes.vendor` is the persisted form of `CubeVendor` (`'gan'` / `'moyu'`), `NOT NULL DEFAULT 'gan'`. Stamped by the orchestrator via `updateVendor(mac, vendor)` right after service-UUID-based detection, well before the INFO round-trip lands. The `'gan'` default covers the brief pre-detection window for newly-paired rows and any pre-feature exports (which deserialise as `vendor = "gan"` by default).
- **Foreign keys are enforced.** `DriverFactory` (Android) passes an `AndroidSqliteDriver.Callback` whose `onConfigure` calls `setForeignKeyConstraintsEnabled(true)`. Until v1.3.0 nothing did, so *every* `ON DELETE CASCADE` in this schema was inert and `deleteProfile` silently orphaned the profile's cubes, solves and settings. See *Foreign keys* below.
- `solves` indexes: `(user_id, solved_at DESC)`, `(user_id, is_dnf, (duration_ms + penalty_ms))`, `(user_id, ao5_ms) WHERE ao5_ms IS NOT NULL`. See *Record queries and the ranking index*.
- `solves.bestDuration` returns `MIN(duration_ms + penalty_ms)` skipping DNFs. Aliased `AS best` so the generated row class has a stable Kotlin property name.
- `solves.ao5_ms` / `ao5_times` are **derived** columns, maintained by `Ao5` on every path that can change them (insert, penalty edit, delete, import rebuild). `ao5_times` holds the five effective times oldest-first with `D` for a DNF. They are nullable independently: a window of five holding two DNFs has times but no average.
- `solves.cube_mac` records which physical cube the solve was done on. **No FK** — forgetting a cube must neither delete its solves nor be refused, and the MAC outlives the `cubes` row, the same reasoning `cube_names` uses.
- `solves.move_count` (default 0) is the total cube turns recorded during the solve. Already counted at runtime by `SolveViewModel` for the live TPS calculation (`fluency = moveCount * 1000 / durationMs`); persisting it lets the History detail dialog show "Turns: N" alongside the time. **Not consumed by any stat** – it's a History-only field by product spec. The 0 default keeps the column SQL-compatible with old call sites (e.g. tests that insert via the repo without the new arg) and lets the History dialog hide the row for pre-feature data via a `> 0` guard.
- `settings` value is always TEXT; typed accessors in `SettingsRepository` parse to bool/int/string.

### Per-profile cube names

`cubes` is keyed by MAC — one row per *physical* cube — and `cubes.upsert` transfers `user_id` on re-pair rather than inserting a second row, so profile B pairing a cube profile A had paired takes over A's row. With the name on that row, A's rename travelled with it: rename your cube in one profile and it was renamed in all of them.

`cube_names(user_id, mac, name)` splits the two apart. Hardware facts — `hw_version`, `sw_version`, `gyro_supported`, `vendor` — stay on the shared row, because they are true of the cube no matter who is holding it, and sharing them means a cube re-paired under a second profile keeps everything the app has learned about it. A name is not a fact about the hardware; it is one person's label, and two profiles on one phone are two people.

Details worth keeping straight:

- **Keyed by `(user_id, mac)`, not by `cubes.id`.** The MAC is the physical cube's identity and outlives the `cubes` row — forgetting and re-pairing mints a fresh id — so a name keyed by MAC is still the right name when the cube comes back, and survives the cube being borrowed by another profile and handed back.
- **`name` is `NOT NULL`.** "This profile has no name of its own" is the absence of a row, not a row holding NULL. `DevicesRepository.rename` deletes the row for a blank name, which is what clearing the field means: go back to whatever the cube calls itself.
- **`PairedCube` carries both.** `advertisedName` and `customName` are stored fields; `name` is a computed `customName ?: advertisedName`. Display code wants `name`. Anything handing a name **back to the BLE layer wants `advertisedName`** — `CubeIdentity.name` drives protocol resolution and the GAN key derivation, so `DevicesViewModel.reconnect` passing a user's label would have broken cube detection on reconnect. (It did, before the split: the display name was the only name there was.)
- **One joined query.** `cubes.selectByUser` LEFT JOINs `cube_names` on `(user_id, mac)`. SQLDelight notifies a query when any table it reads is written, so a rename re-emits the paired list exactly like a pairing does, and the list is never assembled from a fresh cube row and a stale name map.
- **Forget takes the name with it.** `DevicesRepository.forget` runs `cube_names.removeForCubeId` then `cubes.deleteById` in a transaction — in that order, because the name delete resolves the MAC and owner *through* the cube row.
- **Export/import.** `ExportCube` gained an optional `customName` alongside `name` (which is now the advertised one). Import calls `rememberCube` for the hardware row and `rename` for the profile's label separately, so importing a bundle into profile B cannot rename a cube out from under profile A. Bundles predating the split put the rename in `name`; those import as an advertised name, which is the only reading available without a second field to compare against. The envelope is unchanged otherwise, so `EXPORT_SCHEMA_VERSION` stays at 1 — same additive trick as `vendor`.

#### Migrations

The `.sq` files always describe the *current* schema. Each `.sqm` is a delta applied to installs that are behind, and `QbsmarterDatabase.Schema.version` is the highest migration number plus one — so a fresh install is created straight from the `.sq` files at that version and runs no migration at all.

**One change per file, and a shipped file is frozen.** The version a device reports is decided by the *set* of migration files, not by what is inside them. A device is therefore stamped with the version of the build it installed and will never run those files again — so extending a migration that has already reached a device strands it silently: it sits at the version the extended file produces, and nothing will ever apply the part that was added afterwards. Every schema change gets its own file for that reason, and no file is edited once it has shipped.

| file | version | what it does |
|---|---|---|
| `1.sqm` | 1 → 2 | creates `cube_names` and copies each existing `cubes.name` across as a per-profile override |
| `2.sqm` | 2 → 3 | deletes the rows orphaned by profile deletions made while foreign keys were unenforced |
| `3.sqm` | 3 → 4 | adds `solves.ao5_times` and backfills both Ao5 columns for the whole history |
| `4.sqm` | 4 → 5 | replaces `solves_user_duration` with `solves_user_rank` and `solves_user_ao5` |
| `5.sqm` | 5 → 6 | adds `solves.cube_mac` and `solves_user_cube` |
| `6.sqm` | 6 → 7 | creates `solve_moves`, `solve_gyro` and `solve_gyro_prune` |

**`1.sqm`.** Creates `cube_names` and carries every existing `cubes.name` across as an override belonging to the profile that currently owns the cube: whoever renamed a cube keeps seeing their name, nobody else inherits it. Cubes whose owning profile no longer exists are skipped — there is no profile for the name to belong to, and filtering here rather than relying on a cleanup elsewhere keeps this file correct under foreign-key enforcement on its own, which matters for a file that will still be run years after it was written.

`cubes.name` is deliberately **not** cleared. Post-migration it means "the advertised name", and for a renamed cube it briefly holds the user's label instead — harmless, because the owning profile now reads its name from `cube_names`, and the next connect overwrites the column with the real advertised name. Clearing it would be worse: it would blank the fallback name for every cube never connected again.

**`2.sqm`.** Deletes rows in `settings`, `solves`, `cubes` and `cube_names` whose `user_id` no longer exists — the wreckage of every profile deletion made while foreign keys were unenforced (see *Foreign keys*). The pragma alone does not clean this up: enforcement validates what you write, not what is already stored, so without the sweep those rows would outlive every future release, counted by `PRAGMA foreign_key_check` and by nothing else. `cube_names` cannot actually hold orphans — `1.sqm` filters them out and every write since goes through an enforced key — and is swept anyway, because a sweep naming four tables reads better than three tables and a paragraph about the fourth.

**`3.sqm`.** Adds `ao5_times` and backfills both Ao5 columns for every historical solve — without it the "show me the five times behind this average" feature would work only for solves recorded from here on.

`ao5_ms` is **rewritten**, not merely filled in where NULL: the stored values were computed from raw `duration_ms` ignoring penalties and DNFs, so leaving them would mean a solve displaying five times whose trimmed average is visibly not the average printed beside them. `ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY solved_at, id)` materialises a ranked helper table keyed `(user_id, rn)`, and the five window slots are read as five indexed point lookups — deliberately not a `GROUP_CONCAT` over a window frame, because the order of values inside one is not something SQLite promises and "oldest first" is exactly what the column means. Both helper tables are dropped before the file ends. Window functions need SQLite 3.25; `minSdk = 29` ships 3.28, and the SQLDelight dialect is already pinned to 3.25.

ALTER TABLE appends, so the order the files run in decides a migrated database's column order: `ao5_times` here and `cube_mac` in `5.sqm`, matching the order they appear at the end of the `CREATE TABLE` in `Solves.sq`.

Verified by executing the whole chain against a real SQLite database built from the v1-era schema, from every starting version, with foreign keys on and orphans present: ~1 s for 50,000 solves, `PRAGMA integrity_check` and `foreign_key_check` clean, all 100,000 backfilled Ao5 values matching an independent implementation, and the resulting schema identical to a database created fresh from the `.sq` files.

**`4.sqm`.** Swaps the ranking indexes. Its own file, after the backfill rather than inside it, so the bulk UPDATE in `3.sqm` does not maintain `solves_user_ao5` row by row on the way through. See *Record queries and the ranking index* for why `solves_user_duration` served none of the queries that wanted it.

### Record queries and the ranking index

`AppCache.bestDurationMs` re-reads the profile's best time on every emission of `recentSolves` — every solve, every penalty edit, and every profile switch. That was fine as a design and expensive as an implementation, because the index it relied on did not fit the query.

`solves_user_duration` indexed `(user_id, duration_ms)` — the **raw** duration. Every query that ranks solves works in **effective** time (`duration_ms + penalty_ms`), and an index cannot answer a query about an expression it does not contain. So `MIN(duration_ms + penalty_ms)` walked the index and fetched each candidate row from the table to add the penalty and check the DNF flag, and the History best/worst sorts ignored the index entirely and did a full table scan plus a temp B-tree — *per page*.

One index replaces it, with its columns in the order the History sort actually asks for:

```sql
CREATE INDEX solves_user_rank ON solves(user_id, is_dnf ASC, (duration_ms + penalty_ms) ASC);
```

Measured on a 100,000-solve database:

| query | before | after |
|---|---|---|
| `bestDuration` | 59.4 ms | 0.02 ms |
| History best-time page | 14.5 ms (SCAN + temp B-tree) | 0.17 ms |
| History worst-time page | same | 0.22 ms |

`bestDuration` gets SQLite's MIN/MAX optimisation because `user_id` and `is_dnf` are equalities and the MIN argument is the next indexed expression. `pageByDurationAsc` reads the index in order with no sort step, and `pageByDurationDesc` sorts by exactly the reverse and walks the same index backwards.

**This is why there is no denormalised records table.** Storing `best_single` / `best_ao5` per profile and maintaining them on insert, penalty edit, delete and import buys nothing over an index seek that is already O(log n) — a profile with 500,000 solves costs the same as one with 500 — and costs a whole invalidation surface where a cached record can disagree with the data. `bestAo5` is the same story: a partial covering index over the persisted `ao5_ms`.

### Ao5 as a maintained column

An Ao5 belongs to a solve but is a fact about its *neighbours*, which is what makes it awkward. `domain/stats/Ao5.kt` is the single definition — effective time, DNF as "no time" rather than a large one, drop best and worst, two DNFs means no average — and every path that can invalidate a stored value re-derives it through that one object:

| event | what is repaired |
|---|---|
| insert | the new solve, inside the insert transaction |
| penalty / DNF edit | that solve and the four after it (`solvesAffectedByChangeAt`) |
| delete | the up-to-four solves that had it in their window |
| import | the whole profile, one ordered pass (`rebuildAo5ForUser`) |

Three bugs went away with this. The computation used to live in `SolveViewModel.finishSolve` and read `AppCache.recentSolves`, which is **gated on the `app.cacheEnabled` setting** — so with caching off the window was empty, `ao5_ms` stopped being written entirely, and personal-best detection (reading the same cache) went with it. It averaged raw `durationMs`, so a +2 never counted. And it ignored DNFs, so a failed solve entered the average as an ordinary time.

Because the column is now trustworthy, `Ao5Stat` displays it rather than recomputing — the stat card and the History row can no longer disagree — and `MeanStat` / `Ao12Stat` were brought onto the same rules.

**`ao5Ms` is no longer part of the import dedup fingerprint.** A derived column cannot identify the row it is derived for: the local value legitimately differs from a bundle written before the two histories were merged, and with it in the fingerprint re-importing your own backup would have duplicated every solve.

### Solve reconstruction: `solve_moves` and `solve_gyro`

Two side tables, one blob each, so a solve can be replayed turn by turn with the cube rotating as it actually did.

**Side tables, not columns.** `pageByDateDesc` and friends are `SELECT *`; a blob on the `solves` row would be read on every History page and carried in every `SolveRow` the UI holds.

**Blobs, not rows.** A row per move is ~40 bytes of SQLite overhead around 3 bytes of payload and multiplies the database's row count by ~55. The encodings in `domain/reconstruction/TrackCodecs.kt` — a face/direction nibble plus a varint delta for moves, smallest-three 32-bit quaternions plus varint deltas for gyro — measure at **2.87 B/move** and **5.2 B/sample**. Each blob carries a `format` column so a future encoding needs no migration.

**One timeline.** `SmartCubeEvent.Gyro` carries only a device wall-clock timestamp; the solve's duration is defined by `SolveTimer` from cube-clock deltas. `SolveRecorder` buffers both streams in RAM for the length of the solve and, at `finish()`, projects the gyro samples onto the cube clock through `ClockSkewEstimator.predictCube` — the inverse of the regression the timer has been fitting all along. Projecting per packet would stamp early samples with a badly-fitted mapping and late ones with a better one, warping the middle of the timeline.

**Deadband sampling.** `SolveRecorder` keeps a gyro sample when the pose has moved more than 3° from the last kept one, or 250 ms have passed. Simulated against a 15-second solve with six reorientations:

| policy | samples | bytes | worst replay error |
|---|---|---|---|
| every packet (50 Hz source) | 750 | 3900 | 0.57° |
| deadband 3° / 250 ms | 112 | 582 | 7.03° |
| fixed 10 Hz | 150 | 780 | 10.63° |

Two counter-intuitive results are worth keeping. **The threshold barely matters** — 2°, 3° and 5° keep the same number of samples, because during a flick consecutive packets are already 10–20° apart and clear any of them, while during stillness nothing reaches even 2° and it is the heartbeat that fires. The heartbeat is the real cost knob. And **the fidelity ceiling is the cube**: during a flick the deadband is already keeping every packet the cube sent, so the remaining error is slerp cutting the corner between widely-spaced poses. If the replay needs to look better, the win is a squad/Catmull-Rom interpolator at playback — no storage at all — not a higher sample rate.

**Retention.** Move tracks are never pruned; at 1.6 MB per 10,000 solves there is no reason to. `solve_gyro` denormalises `user_id` and `solved_at` from its parent — the one deliberate duplication in this schema — so every retention rule is an indexed DELETE with no join: keep the newest N, drop everything older than a date, drop all of it. `pinned` exempts a track from all of them and is set automatically for a solve holding a record, so no housekeeping can quietly delete the replay of a personal best. `putGyro`'s upsert deliberately does **not** take `pinned` from the excluded row, so re-recording cannot clear a pin.

`pruneGyroKeepingNewest` is phrased as "delete everything not in the newest N" rather than as a cutoff timestamp: an OFFSET is one row off from the count it reads like, and two solves sharing a `solved_at` to the millisecond cannot be separated by a comparison. The subquery names exactly N rows under a total order (`solved_at DESC, solve_id DESC`) and everything else goes.

### Foreign keys

SQLite defaults foreign-key enforcement **off**, `AndroidSqliteDriver` does not turn it on, and until v1.3.0 nothing else did. Every `ON DELETE CASCADE` in this schema was decorative: `UserRepository.deleteProfile` left the profile's cubes, solves, settings and names behind, and `app_state.active_user_id`'s `ON DELETE SET NULL` never fired (the repository survived only because it re-checks the pointer afterwards regardless).

The reconstruction tables are what forced the fix. They are the largest rows in the database and they hang off `solves(id)`, so every solve deleted from History would have leaked its blobs permanently. `DriverFactory` now passes a callback that enables the pragma in `onConfigure` — the only correct hook, since SQLite refuses to change it inside a transaction and the framework calls `onConfigure` before `onCreate`/`onUpgrade`.

Turning it on fixes every future deletion and nothing at all about the past, so the rows already orphaned are swept by a migration of their own: see *Migrations*.

### Setting keys

Centralised in `SettingsRepository.Keys` so a typo at one call site can't drift away from another:

```
solving.inspectionEnabled   "1"/"0"   default true
solving.keepScreenOn        "1"/"0"   default true
solving.gyroEnabled         "1"/"0"   default false
solving.anyMoveStartsNewSolve "1"/"0" default true
display.theme.seed          ThemeSeed.key – "blue", "green", "purple", "orange", "red", "pink", "yellow", "mono"
display.theme.mode          ThemeMode.key – "system", "light", "dark"
display.ui.language         AppLanguage.key – "system", "en", "cs"
cube.autoDisconnectMinutes  whole minutes as a decimal string, 0 = never   default 5
app.cacheEnabled            "1"/"0"   default true
```

Boolean defaults stay inline at their call sites — there are only two candidate values and the switch row and its reader are usually the same screen. `cube.autoDisconnectMinutes` is different: the Settings picker and `AppLifecycle` read it independently, so its default lives in `SettingsRepository.Defaults` where both can see it. "The picker shows 5 but backgrounding uses 10" is exactly the drift a shared constant prevents.

`solving.soundEnabled` is **commented out** in `SettingsRepository.Keys` and `SettingsViewModel.ALLOWED_SETTING_KEYS`, and the corresponding switch row in `SettingsScreen.kt` is also commented out. The associated string resource (`settings_sound`) is preserved in both `values/strings.xml` and `values-cs/strings.xml`. Re-enabling the setting is a multi-line revert (uncomment all four sites). The setting was hidden because the cube-event sound design hasn't landed yet; persisting a switch the user can flip but that does nothing was confusing.

---

## Permissions, edge-to-edge & system bars

### Permissions

Two regimes coexist via manifest `maxSdkVersion`:

| Android version | Permissions (runtime) |
|---|---|
| 12+ (API 31+) | `BLUETOOTH_SCAN` (declared with `usesPermissionFlags="neverForLocation"`) + `BLUETOOTH_CONNECT` |
| 10/11 (API 29–30) | `ACCESS_FINE_LOCATION` (the legacy `BLUETOOTH` + `BLUETOOTH_ADMIN` are install-time normal-protection so they don't enter the runtime ask) |

The legacy `ACCESS_FINE_LOCATION` is needed because on Android 10/11 the OS treats BLE scan results as location data – without the permission, scans return no results even if BT works fine.

`BleManager.requiredRuntimePermissions(): Array<String>` returns the right set for the current API level; `MainActivity.ensureBlePermissions()` requests them at startup. `BleManager.hasRequiredPermissions()` checks the right set internally before every scan/connect.

### Edge-to-edge

`MainActivity.enableEdgeToEdge` is called with **explicit transparent `SystemBarStyle.auto`** for both bars. The no-arg variant uses an `auto` style that on API < 30 paints an opaque scrim derived from the system theme – that's the Android-12-white-status-bar bug. Forcing transparent both ways pairs cleanly with `ApplySystemBarsTheme`, which controls icon color at every recomposition.

### System bar icon color

`ApplySystemBarsTheme(darkTheme: Boolean)` (Android actual): sets `WindowInsetsControllerCompat`'s `isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars` to `!darkTheme`. On API < 35, also sets the bar colors to `TRANSPARENT` (older Androids draw an opaque scrim behind the nav bar by default).

JVM-desktop and Web actuals are no-ops (no Android-style system bars).

---

## Internationalisation

- Two locales today: **English** (default) and **Czech**.
- All strings live under `shared/src/commonMain/composeResources/values/strings.xml` and `values-cs/strings.xml`. Both files have identical key sets (verified).
- The Android module has its own minimal `strings.xml` with only `app_name`, used by the manifest's `android:label`. Compose strings are accessed via `Res.string.<name>` from generated `qbsmarter.shared.generated.resources.Res`.

### Language values

```
SYSTEM   key="system"   tag=null   → AppCompatDelegate.setApplicationLocales(empty)
ENGLISH  key="en"       tag="en"
CZECH    key="cs"       tag="cs"
```

### Adding a new language

1. Add an entry to `AppLanguage`.
2. Create `composeResources/values-<tag>/strings.xml`.
3. Add the key to `Res.string.language_<name>` in `values/strings.xml` (and translations).
4. Add a case in `SettingsScreen.languageLabelOf`.

---

## Theming

8 hand-rolled seeds (Blue, Green, Purple, Orange, Red, Pink, Yellow, Mono) × light/dark theme → 16 static color schemes. Each seed defines `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer` for both modes. `AppColorSchemes` mirrors `primary` into `secondary` and `tertiary` (see *AppTheme* above for the rationale).

Static schemes keep `commonMain` pure-Kotlin; dynamic generation would need `material-color-utilities`, which has no Compose Multiplatform variant currently bundled.

Theme is per-profile: switching profiles swaps the theme to whatever was persisted for the new profile.

---

## Build, dependencies, versions

See `gradle/libs.versions.toml` for the source of truth. Notable choices:

- **Kotlin 2.3.10** matched with **Compose Multiplatform 1.10.1** and **`composeMaterialIcons` 1.7.3** (pinned by JetBrains, decoupled from the main Compose MP version).
- **AGP 9.2.0** with `compileSdk 36`, `minSdk 29`, `targetSdk 36`.
- **JVM target 11** (shared, androidApp), **17** (desktopApp).
- **multiplatform-settings has been removed** in favour of the per-profile SQLDelight `settings` table. Single backing store covers both hot-path toggles and profile-scoped preferences.
- **paging-multiplatform has been removed** in favour of a windowed `StateFlow` in `HistoryViewModel`. Plain `LazyColumn` driven off a `StateFlow` is smoother; the previous paging-multiplatform setup did extra DB queries per page boundary plus diffing work that never paid off.
- **JitPack** is restricted to `includeGroup("com.github.zakgof")` (just korender) so it can't shadow other coordinates.

### Release build configuration

The release build type in `androidApp/build.gradle.kts` enables:

- **R8 (`isMinifyEnabled = true`).** Shrinking + obfuscation. Lowers APK size and makes reverse-engineering modestly harder.
- **Resource shrinking (`isShrinkResources = true`).** Drops unused string resources / drawables. Safe with compose-resources because `Res.string.*` references are statically tracked.
- **`proguard-rules.pro`** in `androidApp/proguard-rules.pro` does three things, each commented inline:
  1. Strips `.d` / `.v` / `.i` log calls (Kermit and `android.util.Log`) via `-assumenosideeffects`. `.w` / `.e` survive.
  2. Keeps reflection-using libraries: Koin, kotlinx.serialization, AndroidX Lifecycle, Compose Compiler internals, Korender, SQLDelight-generated query classes.
  3. Suppresses warnings for missing-class references that are normal in cross-platform builds (JS-only kotlinx classes etc.).

Debug builds keep `isMinifyEnabled = false` – readable stack traces matter more than APK size during development.

### Packaging an APK for distribution

Three paths, in order of polish:

1. **Quick debug APK for tester sideloading** (no signing setup needed):
   - Android Studio: **Build > Build Bundle(s) / APK(s) > Build APK(s)**, output goes to `androidApp/build/outputs/apk/debug/`.
   - Or via terminal: `./gradlew :androidApp:assembleDebug`.
   - The tester needs **Settings > Apps > Special access > Install unknown apps** enabled for whatever app is used to install the APK (e.g. Drive, Files).

2. **Signed release APK** (uses R8, smaller, harder to RE):
   - Android Studio: **Build > Generate Signed Bundle / APK > APK > Next**, then either pick an existing keystore or click **Create new...** to generate one. Pick the **release** build variant. Output goes to `androidApp/release/`.
   - Or via terminal once a keystore exists and is wired up in `signingConfigs { ... }`: `./gradlew :androidApp:assembleRelease`.
   - First-time keystore creation: pick a strong password, **back up the .jks file outside the repo** – losing it means losing the ability to ship updates that Play Store will accept under the same package name.

3. **Play Store** (when shipping for real):
   - **Build > Generate Signed Bundle / APK > Android App Bundle** instead of APK. Bundles are smaller per-install because Play generates per-device APKs from them.
   - Upload to Play Console alongside the **mapping file** (`androidApp/build/outputs/mapping/release/mapping.txt`) so crash reports come back deobfuscated.

---

## Multiplatform stubs

JVM and Web platform actuals are stubs that throw `NotImplementedError` in everything cube-related (BLE, AES, file IO, time, UUID). The desktop and web entry-point composables show a "not yet implemented" placeholder rather than `App()` so they don't crash on first DI lookup.

If you ever need to ship desktop:

1. Implement `jvmMain` actuals: `currentTimeMillis`, `generateUuid`, `DriverFactory` (use `JdbcSqliteDriver`), `BleManager` (e.g. via BlueZ on Linux / BluetoothLEAdvertisementWatcher on Windows).
2. Implement `GanEncryptor` using `javax.crypto` (essentially identical to the Android version – it already uses `javax.crypto`). The class is generation-neutral – the same key/IV works for Gen2/Gen3/Gen4.
3. Wire the platform module: `single<UrlOpener> { … }`, etc.
4. Replace the placeholder `App()` body in `desktopApp/Main.kt`.

For web targets you'd additionally need to revisit korender (per the comment in `settings.gradle.kts`, web support breaks with korender).

---

## Conventions & gotchas

### Logging

- **Kermit** (`co.touchlab.kermit.Logger`) is the convention everywhere except `BleManager.android.kt`, which uses `android.util.Log` directly because it's already Android-specific code tightly coupled to Android Bluetooth APIs. Don't extend that exception elsewhere.
- Tag your logger: `private val log = Logger.withTag("ClassName")`.
- **Severities**: `.d` / `.v` / `.i` for normal flow tracing, `.w` for recoverable problems, `.e` for failures. Default to `.d`.
- **In release builds**, R8 strips all `.d` / `.v` / `.i` calls (Kermit and `android.util.Log` both) via `-assumenosideeffects` rules in `androidApp/proguard-rules.pro`. `.w` and `.e` survive because they're useful for crash diagnostics. So feel free to leave debug-level logs in committed code – they're free in production.

### `koinViewModel()` vs `koinInject()`

- `koinViewModel()` for ViewModels in screens – picks up the navigation back-stack scope automatically.
- `koinInject()` for non-ViewModel singletons used inside Compose (like `UrlOpener` in `AppNavHost`).

### Where to read settings from

Three layers, in order of preference for hot reads:

1. **`AppCache.boolSetting(key, default)` / `cache.settings.value[key]`** – fastest, reactive, profile-aware. Use this in ViewModels and Composables.
2. **`settingsRepo.observeBool(uid, key, default)`** – reactive, but opens its own DB observer. Use only when you can't depend on `AppCache` (e.g. inside `CacheController`, where AppCache itself isn't ready).
3. **`settingsRepo.getBool(uid, key, default)`** – synchronous DB read. Use only in non-coroutine event handlers when you need a value right now.

### Where to write settings to

Always through **`SettingsRepository.set*`** keyed on the active profile id (`activeProfile.idSnapshot()`). Never bypass to write directly to SQLDelight. AppCache will pick up the change reactively.

### Error / edge handling pattern

External boundaries (BLE callbacks, SAF result handlers, intent launches) are wrapped in `runCatching` so a vendor-specific quirk doesn't crash the whole app. Internal call sites can let exceptions propagate normally.

### Logical state vs visual state

The single-source-of-truth model means: **only `RubiksCube._state` (the `CubeState`) is mutable cube state**. Don't introduce parallel "visual state" fields. If you need to know which cubies are on a face, derive it from state via `cubiesOnFaceMeshes(state, face)`.

### Profile-aware ViewModels

ViewModels never capture `userId` at construction. Reads → flows keyed off `activeProfile.id`. Writes → `activeProfile.idSnapshot()` at call time. Never store `private val userId: String` on a VM.

### Lifecycle-aware singletons

Anything that should outlive screen-scoped ViewModels (`ConnectionOrchestrator`, `AppCache`, `ActiveProfile`, `AppLifecycle`, `ThemeController`, `LocaleController`) is a Koin singleton resolved eagerly in `Application.onCreate`. Don't promote VMs to singletons for "convenience" – they'd outlive their composition scope and the ViewModel contract breaks.

### Compose `expect`/`actual` for system bars

The `@Composable expect fun ApplySystemBarsTheme` pattern works because Compose Multiplatform supports `expect`/`actual` on `@Composable` functions. JVM/Web actuals are no-ops; Android pokes `WindowInsetsControllerCompat` inside a `SideEffect`.

---

## Known issues & future work

- **Sound effects setting is hidden.** The `solving.soundEnabled` switch is commented out in `SettingsRepository.Keys`, `SettingsViewModel.ALLOWED_SETTING_KEYS`, the corresponding switch row in `SettingsScreen.kt`, and the `settings_sound` import. The string resource itself is preserved in both `values/strings.xml` and `values-cs/strings.xml`. Re-enabling the setting is a multi-line uncomment once the actual sound design lands. App is not yet distributed, so no migration is needed – fresh installs simply won't have any rows for this key in the `settings` table.
- **`enqueueReset` reset-vs-reset race.** If a Facelets event triggers `enqueueReset(target_A)` while `waitForPartner` is mid-poll holding a different Reset (the consumer has already `tryReceive`d an older Reset and is about to put it back), the older Reset can land in the channel ahead of the newer one. Net result: the older target wins. Requires an extreme race window and is rare; not fixed because `enqueueReset` itself is rare (only on Facelets resync) and the diff window is microseconds.
- **History total-count plural form is one/other only.** `HistoryScreen.totalCountLabel` resolves to `Res.string.history_total_one` for count == 1 and `Res.string.history_total_other` otherwise. English uses singular/plural ("1 solve total" / "N solves total"); Czech uses the same template for both forms ("Celkem N složení") because that wording works for any count. If a third language with more involved plural rules (e.g. Russian's three forms, or Slavic few/many splits) is added, switch to a proper plural string-resource mechanism instead of the two-key one/other split.
- **Per-step time analysis (cross/F2L/OLL/PLL)** is not yet implemented. The architecture for detection isn't wired yet; it would live in the driver/parser layer (track move sequences against known algorithm signatures). When it lands, a new stat could be added to `StatRegistry` to surface per-step times.
- **`BleManager.android.kt` uses `android.util.Log` directly** while the rest of the app uses Kermit. This is platform-specific code tightly coupled to Android Bluetooth APIs, so it's defensible, but a unified Kermit-on-Android setup would be cleaner.
- **No iOS support** – see *Module layout / Why no iosMain*. Blocked on korender adding an iOS variant or a renderer abstraction.
- **JVM-desktop and Web targets are stubs**. The desktop entry point shows a placeholder window; web modules are excluded from `settings.gradle.kts`. Implementing them is feasible (see *Multiplatform stubs* for the steps).
- **Crypto constants are hardcoded.** GAN Gen2/3/4 share the same key + IV; MoYu V10 uses its own key + IV. Both are static and well-known in the smart-cube community, so this is fine. Future vendors can plug in via a new `SmartCubeDriver` implementation + a thin wrapper around the shared `AesCbcMacSaltEncryptor` (or a fresh encryptor entirely, if a future vendor uses a different scheme).
- **Vendor detection on the Devices screen** (`BleDevice.detectVendor()` → `CubeVendor.detectFromScan`) runs pre-connect and layers three signals, strongest first: **advertised service UUIDs** from the scan record (authoritative, and identical to the post-connect `CubeVendor.detect` check), **device-name prefix** (`GAN` / `MG` / `AiCube` for GAN, `WCU_MY` for MoYu — what both cstimer and gan-web-bluetooth key off), and **MAC OUI prefix** as a last resort. It previously used the OUI list *alone*, and that list held a single entry (`AB:12:34`); every GAN cube built on a different radio module was therefore unrecognised — no vendor chip, not sorted to the top, buried among the earbuds — even though connecting to it worked perfectly, which made it read as a scanning bug rather than a classification one. Any one signal here is incomplete; the union is not. Advertised service UUIDs are plumbed through `BleDevice.advertisedServiceUuids`, merged across advertising and scan-response packets in the Android scan callback (the first packet frequently carries neither name nor services).
