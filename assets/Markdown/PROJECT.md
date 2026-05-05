# QBSmarter – Project Knowledge

This document captures everything a developer should know to work on QBSmarter productively without re-deriving it from the source. It covers architecture, decisions, conventions, and the protocol details that aren't obvious from reading individual files.

> **Audience.** A new contributor who knows Kotlin and Compose Multiplatform and wants to learn about this codebas. Read top-to-bottom once; after that, the inline comments in the source assume you've seen the big picture here.

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
10. [Permissions, edge-to-edge & system bars](#permissions-edge-to-edge--system-bars)
11. [Internationalisation](#internationalisation)
12. [Theming](#theming)
13. [Build, dependencies, versions](#build-dependencies-versions)
14. [Multiplatform stubs](#multiplatform-stubs)
15. [Conventions & gotchas](#conventions--gotchas)
16. [Known issues & future work](#known-issues--future-work)

---

## What QBSmarter is

QBSmarter is an **Android-first Compose Multiplatform** companion app for **GAN** smart cubes. It connects via **Bluetooth Low Energy (BLE)** using the **GAN Gen2** &ndash; **Gen4** protocols, renders the cube in 3D with the **korender** engine, and provides:

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

```
common
├── android   (real)
├── jvm       (stubs)
└── web       (intermediate, stubs)
    ├── js
    └── wasmJs
```

The `web` intermediate source set lets `js` and `wasmJs` share their stubs (`BleManager`, `DriverFactory`, `GanGen2Encryptor`, `currentTimeMillis`, `generateUuid`, etc.).

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
│   ├── driver/                 # SmartCubeDriver/Event/Command, CubeTransport, CubeEncryptor
│   │   └── gan/                # GanCubeDriver (Gen2/3/4), GanGeneration, GanParser interface,
│   │                           # GanGen2Parser/Gen3Parser/Gen4Parser, BitView, GanGen2Encryptor
│   ├── timing/                 # SolveTimer, ClockSkewEstimator
│   └── user/                   # UserProfile data class
├── ui/
│   ├── components/             # AppScaffold, NavigationDrawer, ConfirmationDialog, StatCard,
│   │                           # VerticalScrollbar (Modifier.verticalScrollbar(state))
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

 BleManager (platform) ─→ BleCubeTransport ─→ GanCubeDriver (Gen2/3/4)
       │                       │                    │
       │                       │              decrypts + parses
       │                       │              via active GanParser
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
4. Build a `GanGen2Encryptor` from the cube's MAC. Same encryptor across all three generations – the class name is historical.
5. `ble.connectToDevice(device)`.
6. **Wait for service discovery + detect the protocol generation.** Collect `ble.discoveredServices` until a snapshot contains a UUID matching one of `GanGeneration.{GEN2, GEN3, GEN4}.serviceUuid`. The detected generation determines both the BLE characteristic UUIDs the transport binds to AND which parser the driver activates.
7. Build `BleCubeTransport(serviceUuid, commandCharUuid, stateCharUuid)` from the detected generation's fields, then call `driver.connect(transport, encryptor, generation)`. The driver activates the matching parser, calls `parser.reset()`, enables notifications, which kicks off the CCCD descriptor write.
8. **Wait for `ble.notificationsReady` to flip true** (with a 3 s timeout fallback). Without this gate, the next 3 command writes race the descriptor write – the cube either drops them or replies into a void.
9. Send `RequestHardware`, `RequestFacelets`, `RequestBattery` with **120 ms gaps** so back-to-back GATT writes don't overflow the queue on flaky stacks. Each is wrapped in `runCatching` – failures don't tear down the connection.

**On cancel.** `disconnect()` is called via the user tapping Cancel mid-handshake or switching cubes. It cancels the connect job, calls `driver.disconnect()` and `ble.disconnect()`, then `withTimeout(2 s)` waits for `connectionState == DISCONNECTED` before clearing battery + active-MAC state. Without this final await, a subsequent action would race the in-flight teardown – exactly the family of bugs the close-ordering fix in `BleManager.disconnect` was meant to avoid.

The orchestrator also listens to driver events forever and:

- routes `SmartCubeEvent.Hardware` → `devicesRepo.updateHardwareInfo(...)`
- caches `SmartCubeEvent.Battery` per MAC into a `_batteryByMac` `StateFlow`
- responds to `SmartCubeEvent.MovesMissed` with a debounced `RequestFacelets` (1500 ms minimum interval) – see *Move-history overflow* in the GAN section below. (For Gen3/Gen4, individual gaps are recovered transparently by the parser via the move-history backfill mechanism; MovesMissed only fires when the parser's FIFO overflows past 16 entries, i.e. backfill itself isn't keeping up.)

`activeMac` is a `StateFlow<String?>` exposed publicly. The Devices screen combines it with `connectionState == CONNECTING` to derive `connectingMac`, which drives the per-row "Connecting…" spinner.

The reason all of this lives in a long-lived singleton (not a VM) is so navigation doesn't tear it down mid-handshake.

---

### Smart-cube driver layer

**Files:** `domain/driver/{CubeTransport, CubeEncryptor, SmartCubeCommand, SmartCubeEvent, SmartCubeDriver}.kt`, `domain/driver/gan/{GanCubeDriver, GanGeneration, GanParser, GanGen2Parser, GanGen3Parser, GanGen4Parser, BitView, GanGen2Encryptor}.kt`.

The driver layer is **generation-agnostic at the SmartCubeDriver interface** and **generation-aware inside the GAN driver**. `SmartCubeDriver` is an interface; any future *cube vendor* (MoYu, QiYi…) would get its own implementation. Today only the GAN family is implemented, via `GanCubeDriver`, which itself supports three protocol generations (Gen2, Gen3, Gen4) covering the full GAN smart-cube lineup as of writing.

```
┌──────────────────┐   raw bytes   ┌──────────────────────┐
│ CubeTransport    │──────────────→│ GanCubeDriver        │
│ (BLE adapter)    │←──────────────│  active GanParser    │
└──────────────────┘   commands    │  (Gen2 / Gen3 / Gen4)│
                                   └──────────────────────┘
                                          │
                                          ▼
                                 ┌────────────────────┐
                                 │ SmartCubeEvent     │
                                 │  Move / Facelets   │
                                 │  Hardware / Battery│
                                 │  Gyro / Disconnect │
                                 │  MovesMissed       │
                                 └────────────────────┘
```

**Generation auto-detection.** The `ConnectionOrchestrator` waits for BLE service discovery, then calls `GanGeneration.detect(advertisedServices)` – a case-insensitive lookup against the three known service UUIDs. The matched generation is passed to `GanCubeDriver.connect(transport, encryptor, generation)`, which selects the corresponding pre-allocated parser. The encryptor is the same `GanGen2Encryptor` for all three generations: per the upstream gan-web-bluetooth reference, all GAN cubes since Gen2 share a static AES-128 CBC key + IV, and per-cube salt derivation from the MAC is identical across generations.

**Per-generation parsers.** All three implement the small `GanParser` interface (`reset()`, `buildCommand(cmd)`, `suspend parseStatePacket(bytes, historyRequester)`). They diverge in:

- **Packet format.** Gen2 is heavily bit-packed with BE words; Gen3 prefixes a `0x55` magic byte and uses byte-aligned fields with a mix of LE timestamps; Gen4 drops the magic byte but otherwise mirrors Gen3 with shifted offsets.
- **Recovery model.** Gen2 has a 7-move on-cube replay buffer; if we lag further it surfaces `MovesMissed` and the orchestrator does a full `RequestFacelets` resync. Gen3/Gen4 add a targeted move-history retransmit (`SmartCubeCommand.RequestMoveHistory(serial, count)`); the parsers maintain a FIFO of pending moves and ask the cube to backfill gaps via the `historyRequester` callback. The orchestrator's MovesMissed → Facelets path is still the bail-out for FIFO overflow.
- **Hardware reporting.** Gen4 spreads the hardware-info reply across four separate events (`0xFA`/`0xFC`/`0xFD`/`0xFE`); the parser accumulates fragments and emits a single unified `Hardware` event once all four arrive.
- **Gyro support.** Gen2 always reports gyro; Gen3 never does (i Carry 2 hardware lacks the sensor); Gen4 reports it only on specific hardware names (currently `GAN12uiM`, the GAN12 ui Maglev).

**Why per-connect encryptor:** GAN cubes derive their AES salt from the BLE MAC. Each cube gets its own `GanGen2Encryptor` (the class name is preserved for incremental migration; the class itself is generation-neutral), but the `GanCubeDriver` itself is a Koin singleton bound twice: as `GanCubeDriver` (so the orchestrator can call the generation-aware overload) and as `SmartCubeDriver` (so VMs and `AppLifecycle` see the generic interface). Subscribers to `driver.events` therefore stay stable across cube swaps **and across generation swaps** – they automatically see events from whichever cube is currently bound.

**Driver scope:** the driver owns its own `CoroutineScope(SupervisorJob() + parserDispatcher)` (default `Dispatchers.Default`), so decryption and parsing never run on the BLE binder thread. The events `SharedFlow` has `replay = 0`, `extraBufferCapacity = 64` – generous enough that a paused subscriber (user navigated away momentarily) doesn't drop moves.

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
- Manual "Reset orientation" button slerp-animates back to identity

The Solve screen hides the Reset Orientation button when the orbit is already approximately at identity (`isApproximatelyIdentity`, ~1.8° tolerance).

#### Gyro

Cubes that report it can override the orbiter rotation with their own measured orientation. `RubiksCube.gyroEnabled.value = true` swaps the outer rotation source from `orbiter.rotation` to `orientationQuat.value` (last-received `SmartCubeEvent.Gyro.quat`).

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

**Schema files:** `shared/src/commonMain/sqldelight/com/zucham/qbsmarter/db/{Users, AppState, Cubes, Solves, Settings}.sq`.

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

Enforces the "always at least one profile" invariant. If you delete the only profile, a fresh empty one is auto-created and made active. If you delete the active profile, the next-most-recent profile becomes active. Cubes/solves/settings cascade-delete via FK.

#### `UserRepository.observeActive` and the rename-propagation fix

A naive `observeActiveId().map { id -> selectById(id).executeAsOneOrNull() }` only re-emits when the active *id* changes – not when the active row's `display_name` changes. So renaming a profile didn't propagate to the sidebar pill, History title, or Settings field. The fix is `flatMapLatest` into a reactive query on the specific row, so any UPDATE on the active row fans out to every downstream consumer.

#### `SolvesRepository`

Effective solve time is `durationMs + penaltyMs` (excluding DNFs). DNF/+2 are stored separately so removing a +2 doesn't lose data. Stat queries (`bestDuration`, `pageByDurationAsc`) use the effective time and skip DNFs.

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

Each flow is gated by `enabled` AND keyed off `activeProfile.id` via `flatMapLatest`. Profile switch = automatic cancellation + restart on the new id, no manual invalidation.

When `setEnabled(false)` is called, gated flows emit defaults (empty/null/0) and stop re-observing. Synchronous typed reads (`boolSetting`, `snapshotPairedCubes`) fall through to the repository. Toggle back on → observers automatically resubscribe.

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

Drawer **gestures are gated on `drawerState.targetValue == DrawerValue.Open`**. While fully closed, the Solve screen's 3D cube needs the full horizontal surface for orientation drags; an edge-swipe-to-open would steal them. Once the drawer starts opening (target = Open), every dismiss gesture is welcome.

#### Drawer

Flat list rows with a leading 3 dp colored stripe (filled on the active route). Width is 280 dp (vs Material's default 360 dp). Bottom block has version + copyright + "Report a bug" (which `mailto:`s through `UrlOpener`). Active profile name is a centered pill above the bottom block.

The pill is **tappable** when an `onProfileTap` callback is supplied (it always is in the `AppNavHost` wiring) – tapping jumps to **Settings**, where profile management lives (rename, switch, export). Same nav pattern as the entry list rows: drawer closes, route navigates with `saveState`/`restoreState` so coming back from Settings restores the previous screen's scroll/state.

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
ActionRow  [Reset Orientation] [Gyro?]   [Reset State]
ScrambleCard  [scramble text + correction prefix]   [New]
TimerArea  (timer / status / inspection / post-solve)
StatGrid  (3-column compact tiles)
```

The cube box is `weight(1f)` and fills whatever vertical space remains between the connection indicator at top and the fixed-size bottom block. `BoxWithConstraints` chooses `min(maxWidth, maxHeight)` so the cube is always square – fills column width on phones, fills available height on tablets.

When disconnected, a translucent scrim `Box` is drawn on top of `CubeView`. (`Modifier.alpha` doesn't work – Korender renders into a separate hardware overlay layer that bypasses Compose's graphics layer.)

The dot+name row in `ConnectionIndicator` is a tappable shortcut to the Devices screen **when connected**. When disconnected the row is non-tappable because the explicit "Connect cube" button below already provides that path; making both tappable would split the user's attention.

##### ActionRow button colors

- **Reset Orientation** and **Gyro** use `primaryContainer/onPrimaryContainer` so they pick up the current seed color (`ThemedButton`).
- **Reset State** uses `error/onError` (saturated red + white) via `DestructiveButton`. This is the only destructive control in the row, and the previous `errorContainer/onErrorContainer` rendered as a soft pink in light mode that read like a neutral chip rather than a wipe-everything affordance.

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

##### "Next solve" gesture

Post-SOLVED, performing a quick `U U'` (or any face + its inverse, within 1500 ms) starts a new scramble. Detection is in `handleNextSolveGesture`.

##### Personal-best celebration

On `finishSolve`, the VM captures `previousBest` from `cache.bestDurationMs.value` **before inserting** the new row (otherwise the cache reflects the new value and we'd compare the solve to itself). If the new effective time strictly beats `previousBest`, `_newPbEvent.value = effective`. The dialog is dismissable via the button, an outside tap, or system back; `dismissPbEvent()` clears the flow.

If the user later marks the just-finished solve `+2` or `DNF`, `recomputePbAfterPenalty` re-evaluates: DNF → never PB; otherwise compare the new effective time to `previousBest` and raise/clear the event accordingly.

##### Keep-screen-on policy

Combined: `(phase != IDLE) && (display.keepScreenOn setting on)` → `screenKeeper.setKeepScreenOn(true)`.

#### Devices screen

Two sections:

- **Paired cubes** – every cube the user previously connected. Active row gets a 2 dp accent border, slightly higher elevation, a green indicator dot, a battery indicator next to the name (when known and connected), and a Disconnect button in place of Connect. Each card has Info + Forget buttons in a bottom row.
- **Available devices** (only while scanning) – fresh BLE results. GAN cubes (MAC prefix `AB:12:34`) are sorted to the top.

##### Available-devices palette

Three layers, deliberately stepped so the relationship reads as panel → tile → highlighted tile in either theme:

- **Panel** (the wrapping `Column`) uses `surfaceContainerLow`. In light mode it sits one step darker than the page; in dark mode (post-ladder-rebuild) it lifts one step above the page. Earlier `surfaceContainer` was a step too dark in light mode and made the panel feel like a heavy slab dropped on the page.
- **Non-GAN tile** uses `surfaceContainerHigh`. The tile sits on top of the `surfaceContainerLow` panel, so `High` gives a clear one-step lift in both modes without the heavy-slab feel of the previous `surfaceContainerHighest` (which read as too dark in light mode against the lighter panel) or the original `surfaceVariant` (which landed essentially on top of the older `surfaceContainer` panel and read as one undifferentiated block).
- **GAN tile** uses the seed's `primary` (with `onPrimary` text) – fully saturated so the cubes-the-user-actually-wants-to-connect-to draw the eye even when several non-GAN devices are listed. Earlier `primaryContainer` was a soft tint that didn't pull focus.

The `VerticalScrollbarBox` in this section overrides the default thumb color with `onSurface @ alpha = 0.7` (vs. the default 0.5) because the thumb sits over the lighter panel rather than the page background, and the default tuned for the page background read as faint here.

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

Rows are swipe-to-dismiss (left-to-right only) with a confirmation dialog. Tap opens a detail dialog (date, scramble, ao5 snapshot, fluency, turn count); the detail dialog has a Delete button that goes through the same confirmation. The turn-count line is hidden for solves that pre-date the `move_count` column (`> 0` guard) so historical rows aren't misleadingly shown as "Turns: 0".

A two-effect pattern handles the swipe state: one effect raises the delete request when `state.currentValue == StartToEnd`; a second resets the row to settled when the global `pendingDeleteId` no longer points at this row (confirm or cancel). Same pattern in `SettingsScreen.ProfileRow`.

Sort options (chips at the top): newest, oldest, best time, worst time. Sort change scrolls back to the top.

##### Scroll-to-top: screen entry AND sort change

Two `LaunchedEffect`s use the same wait-for-load-cycle pattern (helper: `scrollToTopAfterLoadCycle(listState, vm)`):

- `LaunchedEffect(Unit)` fires on screen entry: scrolls to top, calls `vm.refresh()`, waits for the loading cycle, scrolls again. Required because `AppNavHost` is wired with `saveState`/`restoreState`, so navigating back to History from another screen restores the previous `LazyListState` and could land the user mid-list. Sort hasn't changed, so the sort-change effect alone wouldn't fire.
- `LaunchedEffect(sort)` fires on sort change: scrolls, waits for the new page to commit, scrolls again.

The wait-for-load-cycle is `vm.loading.drop(1).first { it }; vm.loading.first { !it }` – wait for the false→true→false cycle. A naive single `scrollToItem(0)` could land on the empty-list intermediate emission and then have `LazyColumn`'s saver snap to a remembered offset when the populated list arrives. Waiting for the cycle to fully commit makes the final scroll deterministic.

##### Solve card color

`SolveListItem` overrides `Card`'s default container color with `surfaceContainerLow`. The Material3 default landed on `surfaceContainerHigh`, which on this app's pumped-contrast color scheme came out noticeably darker than users expect for a tappable list tile in light mode. `surfaceContainerLow` reads as one clear step off the page in both modes – darker than the page in light mode (#F2F2F6 vs #FFFFFF) and lighter than the page in dark mode (#1A1A1D vs #0B0B0D).

#### Settings screen

Sections:

1. **Profile** – hint line + picker (active row on top, gear IconButton at row start opens per-profile settings dialog, swipe-to-delete + delete IconButton at row end), Create + Import side-by-side. Per-profile export lives inside the per-profile settings dialog.
2. **Solving** – inspection enabled (sound-effects toggle is commented out – see *Setting keys*)
3. **Display** – keep screen on, theme mode (segmented), theme color (8 swatches), language (System / Manual two-segment with dropdown inside the Manual segment)
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

Each `ProfileRow` is wrapped in `key(profile.id) { ... }` inside the `for (profile in sorted)` loop. Without this, Compose's slot table reuses the per-position composition state when the list reorders – and after deleting the active profile A, the next profile B is promoted to active and slides up to slot 0, with A's mid-dismiss `rememberSwipeToDismissBoxState` still hanging around. The result was a visible bug: B rendered already-swiped, AND the delete-confirmation dialog re-fired for B (because `LaunchedEffect(state.currentValue)` ran again for the new identity while `currentValue` was still `StartToEnd`). Keying by id gives each profile its own composition region, so B always starts at `Settled` regardless of what A's row was doing.

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

`SolveStat` is intentionally string-shaped: `compute(history, current): String?`. A stat is a value the user reads, not data we plot. Returning null hides the row (used for Ao5 with too few solves and for `StepTimesStat` always until step detection is implemented).

`StatRegistry` holds the default order (`Best, Mean, Ao5, Ao12, Fluency, Total, Steps`). Currently displayed as a 3-column grid → 6 visible tiles (Steps is filtered out as it always returns null).

`SolveSession` is the live snapshot passed to stats while a solve is running: `running, durationMs, moveCount, totalSolves, bestDurationMs`. `totalSolves` comes from the cache's `solveCount` because the recent-100 history isn't enough – a profile can have thousands of older solves outside the in-memory window. `bestDurationMs` comes from `cache.bestDurationMs` (an indexed `MIN(duration_ms + penalty_ms)` query, DNFs excluded) and is used by `BestStat`.

`BestStat` reads `bestDurationMs` directly – it does **not** mix the running solve's in-flight `durationMs` into the comparison. The previous implementation min'd the running timer with the historical best, which made the "fastest solve" tile track the live timer the moment a running solve dropped below the previous record (effectively duplicating the main timer until SOLVED committed the new row). The displayed value now always reflects the persisted DB record, which is what the user expects to compare their current solve against. If `bestDurationMs` is null (caching disabled), the stat falls back to `history.minOf { it.effectiveMs }` – not perfect (a best older than the recent-100 window slips out) but honest. A new PB triggers `cache.recentSolves` to re-emit (which fans out into `bestDurationMs`), the stat grid recomposes via the `history` parameter changing, and the tile updates without any explicit refresh.

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

- **on backgrounded**: `ble.stopScan()` immediately; schedule auto-disconnect after 5 minutes (`DISCONNECT_AFTER_BG_MS`) to save the cube's battery
- **on foregrounded**: cancel the pending auto-disconnect

### Lifecycle-gated CubeView

Korender renders into a native Android `SurfaceView` that lives in the window layer, *not* in Compose's drawing tree. When the user navigates away, Compose removes the `CubeView` composable but the SurfaceView itself takes ~1 frame to detach – and during that frame the new screen has already started drawing on top, so the cube briefly shows through.

`CubeView` proactively hides the Korender block on `Lifecycle.ON_PAUSE`/`ON_STOP` via a `renderActive` flag, falling back to a theme-colored placeholder Box. The `onDispose` also flips the flag for the case of in-Activity navigation (no lifecycle event, just NavHost dispose).

### Initial-frame cover (the black-flash fix)

A fresh SurfaceView is opaque-black until its OpenGL surface is ready and the first frame is rendered – typically 600–900 ms on a cold start. The `Modifier.background(theme)` on the Box around the Korender block doesn't help because the SurfaceView sits in a SEPARATE window layer above the Compose drawing tree, so it covers up whatever Compose drew underneath it.

Workaround: stack a same-colored cover `Box` ON TOP of the Korender block for a short window, then fade it out. The cover is exactly the theme background color, so for the period it's visible the user sees a solid square in their theme – visually indistinguishable from "the cube is there but isn't drawn yet". When the cover fades, the cube comes up smoothly rather than blinking from black.

Implementation in `CubeView.kt`: a `coverAlpha` state driven by `animateFloatAsState`, reset to 1f and then to 0f via a `LaunchedEffect(renderActive)` with a `delay(SURFACE_COVER_HOLD_MS = 600 ms)` and a fade duration of `SURFACE_COVER_FADE_MS = 250 ms`. Resets to opaque immediately on `renderActive = false` so the next resume doesn't briefly leak a stale frame through a half-faded cover.

### Theme change forces Korender re-init

Korender's `this.background = ...` is set once during scene setup; assigning to it inside `Frame { }` doesn't propagate to the GL clear color reliably across versions. So `CubeView` wraps the whole `Korender { }` block in `key(background) { }` – a theme change tears down and rebuilds the surface with the new color. Theme changes are infrequent, so this is not a per-frame cost.

### File export rationale (`AndroidFileExporter`)

SAF-based save/open via `ActivityResultContracts.{CreateDocument, OpenDocument}`. Bound to `MainActivity` in `onCreate` (must be before `onStart` per AndroidX rules). Held by `WeakReference` to avoid leaks if the activity is destroyed mid-flight. Every entry-point that can throw – `launcher.launch`, `contentResolver.openInputStream`, `readBytes` – is wrapped in `runCatching` so failures surface as `null`/`false` returns instead of activity crashes.

---

## GAN Gen2 protocol notes

Static reverse-engineered constants (key, IV, character map, command codes) are documented in the codebase:

- **AES-128 CBC, no padding.** 16-byte key + 16-byte IV are static ("well-known" constants from the smart-cube community, pulled from disassembly of the official Gan i Carry app).
- **Per-cube salt:** 6 bytes derived from the BLE MAC (`mac.split(':').map { hex }.toByteArray().reversedArray()`). Salt mixes into bytes 0..5 of both key and IV via `(byte + salt) % 0xFF` (yes, `0xFF`, not `0x100` – that's the protocol).
- **Two-block encryption for >16-byte payloads:** encrypt block at offset 0, then block at `size - 16`. Decryption reverses the order.

### Service / characteristic UUIDs

Three sets, one per protocol generation. The active orchestrator picks the matching set at runtime via `GanGeneration.detect(...)`:

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

Each command is a 20-byte payload with the opcode at byte 0; reset has a fixed magic-byte tail. `RequestMoveHistory` is also defined in `SmartCubeCommand` for Gen3/Gen4 compatibility, but `GanGen2Parser.buildCommand` returns `null` for it – Gen2's recovery path is the orchestrator's MovesMissed → Facelets resync.

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

The parser asks for backfill via a `historyRequester: suspend (startSerial, count) -> Unit` callback supplied to `parseStatePacket`, which `GanCubeDriver` wires to its own `send(...)` path so the request is encrypted and routed through the active transport.

---

## Database schema

```
users                       app_state                cubes                     solves                       settings
─────                       ─────────                ─────                     ──────                       ────────
id PK                       id PK (=0)               id PK                     id PK AUTOINCREMENT          (user_id, key) PK
display_name                active_user_id ─→ users  mac UNIQUE                user_id ─→ users (CASCADE)   value
created_at                                           name                      solved_at
                                                     last_seen                 duration_ms
                                                     user_id ─→ users          scramble
                                                     hw_version                ao5_ms
                                                     sw_version                fluency
                                                     gyro_supported            extras
                                                                               is_dnf
                                                                               penalty_ms
                                                                               move_count
```

- `app_state` is a single-row pattern: PK is constant 0 (`CHECK (id = 0)`), so it can hold at most one row. `INSERT OR IGNORE` bootstraps; `UPDATE` mutates.
- All three child tables (`cubes`, `solves`, `settings`) reference `users(id) ON DELETE CASCADE`. `app_state.active_user_id` is `ON DELETE SET NULL`.
- `cubes.upsert` updates `user_id` on conflict – critical for multi-profile flows: a cube paired under profile A and re-paired under B must transfer ownership; otherwise `selectByUser(B)` won't return it and the cube is invisible in B's Paired list.
- `solves` indexes: `(user_id, solved_at DESC)` and `(user_id, duration_ms ASC)`. Both used by the History sort modes.
- `solves.bestDuration` returns `MIN(duration_ms + penalty_ms)` skipping DNFs. Aliased `AS best` so the generated row class has a stable Kotlin property name.
- `solves.move_count` (default 0) is the total cube turns recorded during the solve. Already counted at runtime by `SolveViewModel` for the live TPS calculation (`fluency = moveCount * 1000 / durationMs`); persisting it lets the History detail dialog show "Turns: N" alongside the time. **Not consumed by any stat** – it's a History-only field by product spec. The 0 default keeps the column SQL-compatible with old call sites (e.g. tests that insert via the repo without the new arg) and lets the History dialog hide the row for pre-feature data via a `> 0` guard.
- `settings` value is always TEXT; typed accessors in `SettingsRepository` parse to bool/int/string.

### Setting keys

Centralised in `SettingsRepository.Keys` so a typo at one call site can't drift away from another:

```
solving.inspection.enabled  "1"/"0"   default true
display.keepScreenOn        "1"/"0"   default true
theme.seed                  ThemeSeed.key – "blue", "green", "purple", "orange", "mono"
theme.mode                  ThemeMode.key – "system", "light", "dark"
ui.language                 AppLanguage.key – "system", "en", "cs"
app.cache.enabled           "1"/"0"   default true
```

`solving.sound.enabled` is **commented out** in `SettingsRepository.Keys` and `SettingsViewModel.ALLOWED_SETTING_KEYS`, and the corresponding switch row in `SettingsScreen.kt` is also commented out. The associated string resource (`settings_sound`) is preserved in both `values/strings.xml` and `values-cs/strings.xml`. Re-enabling the setting is a multi-line revert (uncomment all four sites). The setting was hidden because the cube-event sound design hasn't landed yet; persisting a switch the user can flip but that does nothing was confusing.

---

## Permissions, edge-to-edge & system bars

### Permissions

Two regimes coexist via manifest `maxSdkVersion`:

| Android version | Permissions (runtime) |
|---|---|
| 12+ (API 31+) | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, both with `usesPermissionFlags="neverForLocation"` |
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

8 hand-rolled seeds (BLUE, GREEN, PURPLE, ORANGE, RED, PINK, YELLOW, MONO) × light/dark/system → 16 (or 17 with system) static color schemes. Each seed defines `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer` for both modes. `AppColorSchemes` mirrors `primary` into `secondary` and `tertiary` (see *AppTheme* above for the rationale).

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
2. Implement `GanGen2Encryptor` using `javax.crypto` (essentially identical to the Android version – it already uses `javax.crypto`). The class is generation-neutral – the same key/IV works for Gen2/Gen3/Gen4, the file name is historical.
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

- **Sound effects setting is hidden.** The `solving.sound.enabled` switch is commented out in `SettingsRepository.Keys`, `SettingsViewModel.ALLOWED_SETTING_KEYS`, the corresponding switch row in `SettingsScreen.kt`, and the `settings_sound` import. The string resource itself is preserved in both `values/strings.xml` and `values-cs/strings.xml`. Re-enabling the setting is a multi-line uncomment once the actual sound design lands. App is not yet distributed, so no migration is needed – fresh installs simply won't have any rows for this key in the `settings` table.
- **`enqueueReset` reset-vs-reset race.** If a Facelets event triggers `enqueueReset(target_A)` while `waitForPartner` is mid-poll holding a different Reset (the consumer has already `tryReceive`d an older Reset and is about to put it back), the older Reset can land in the channel ahead of the newer one. Net result: the older target wins. Requires an extreme race window and is rare; not fixed because `enqueueReset` itself is rare (only on Facelets resync) and the diff window is microseconds.
- **Czech plural rules in History total count.** `HistoryScreen.totalCountLabel` uses a single template ("Celkem X složení") that works grammatically for all counts in Czech, but the English version still uses simple singular/plural ("1 solve total" / "N solves total"). If a third language with more involved plural rules is added, switch to a proper plural string-resource mechanism.
- **Step-time stat** (`StepTimesStat`) always returns null – placeholder until cross/F2L/OLL/PLL detection lands. The architecture for detection isn't wired yet; it would live in the driver/parser layer (track move sequences against known algorithm signatures).
- **`MainActivity` uses `android.util.Log` indirectly via `BleManager`** while everything else uses Kermit. This is platform-specific code so it's defensible, but a unified Kermit-on-Android setup would be cleaner.
- **No iOS support** – see *Module layout / Why no iosMain*. Blocked on korender adding an iOS variant or a renderer abstraction.
- **JVM-desktop and Web targets are stubs**. The desktop entry point shows a placeholder window; web modules are excluded from `settings.gradle.kts`. Implementing them is feasible (see *Multiplatform stubs* for the steps).
- **Crypto constants are hardcoded.** GAN Gen2 keys/IVs are static and well-known in the smart-cube community, so this is fine, but future GAN generations (Gen3, Gen4) will need new driver/encryptor implementations behind `SmartCubeDriver`.
- **The "is GAN" detection on the Devices screen** uses a hardcoded MAC OUI prefix. Future GAN models with new prefixes will need a list (`GAN_OUI_PREFIXES`) rather than a single string.
