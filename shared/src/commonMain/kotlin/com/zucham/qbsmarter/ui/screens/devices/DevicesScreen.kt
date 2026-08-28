package com.zucham.qbsmarter.ui.screens.devices

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zucham.qbsmarter.data.ble.BleDevice
import com.zucham.qbsmarter.data.ble.ConnectionState
import com.zucham.qbsmarter.data.db.PairedCube
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocolRegistry
import com.zucham.qbsmarter.ui.components.ConfirmationDialog
import com.zucham.qbsmarter.ui.components.VerticalScrollbarBox
import com.zucham.qbsmarter.ui.theme.ConnectionDotSize
import com.zucham.qbsmarter.ui.theme.StatusColors
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.device_vendor_gan
import qbsmarter.shared.generated.resources.device_vendor_giiker
import qbsmarter.shared.generated.resources.device_vendor_gocube
import qbsmarter.shared.generated.resources.device_vendor_moyu
import qbsmarter.shared.generated.resources.device_vendor_qiyi
import qbsmarter.shared.generated.resources.device_vendor_rubiks
import qbsmarter.shared.generated.resources.devices_available
import qbsmarter.shared.generated.resources.devices_bt_disabled
import qbsmarter.shared.generated.resources.devices_bt_enable
import qbsmarter.shared.generated.resources.devices_cancel
import qbsmarter.shared.generated.resources.devices_connect
import qbsmarter.shared.generated.resources.devices_connected
import qbsmarter.shared.generated.resources.devices_connecting
import qbsmarter.shared.generated.resources.devices_detail_gyro
import qbsmarter.shared.generated.resources.devices_detail_gyro_unknown
import qbsmarter.shared.generated.resources.devices_detail_hw
import qbsmarter.shared.generated.resources.devices_detail_mac
import qbsmarter.shared.generated.resources.devices_detail_sw
import qbsmarter.shared.generated.resources.devices_disconnect
import qbsmarter.shared.generated.resources.devices_forget
import qbsmarter.shared.generated.resources.devices_forget_message
import qbsmarter.shared.generated.resources.devices_forget_title
import qbsmarter.shared.generated.resources.devices_go_solve
import qbsmarter.shared.generated.resources.devices_info
import qbsmarter.shared.generated.resources.devices_no_permissions
import qbsmarter.shared.generated.resources.devices_pair
import qbsmarter.shared.generated.resources.devices_pair_new
import qbsmarter.shared.generated.resources.devices_paired
import qbsmarter.shared.generated.resources.devices_paired_empty
import qbsmarter.shared.generated.resources.devices_scanning
import qbsmarter.shared.generated.resources.devices_unknown
import qbsmarter.shared.generated.resources.history_close
import qbsmarter.shared.generated.resources.devices_detail_battery
import qbsmarter.shared.generated.resources.devices_detail_gyro_no
import qbsmarter.shared.generated.resources.devices_detail_gyro_yes
import qbsmarter.shared.generated.resources.devices_disconnected

/**
 * Devices screen. Two sections:
 *   • Paired cubes – every cube the user previously connected. The actively-
 *     connected one is highlighted with a green dot + accent border, shows
 *     a battery indicator next to the name, and exposes a per-row
 *     Disconnect (in place of Connect on other rows). Below that row,
 *     each card has a left-aligned Info button and a right-aligned Forget
 *     button.
 *   • Available devices (only while scanning) – fresh BLE results.
 */
@Composable
fun DevicesScreen( onNavigateToSolve: () -> Unit = {}) {
    val vm: DevicesViewModel = koinViewModel()
    val connectionState by vm.connectionState.collectAsState()
    val scanned by vm.scannedDevices.collectAsState()
    val paired by vm.pairedCubes.collectAsState()
    val connectedId by vm.connectedCubeId.collectAsState()
    val connectingMac by vm.connectingMac.collectAsState()
    val batteryByMac by vm.batteryByMac.collectAsState()
    val missingPerms by vm.missingPermissions.collectAsState()
    val btDisabled by vm.bluetoothDisabled.collectAsState()

    var pendingForget by remember { mutableStateOf<PairedCube?>(null) }
    var detail by remember { mutableStateOf<PairedCube?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // The header's Pair button renders a spinner when a connect is
        // in flight FOR A NEW (not-yet-paired) device – i.e. CONNECTING
        // state with a MAC that isn't in [paired]. For an already-paired
        // cube the spinner shows on its row instead, see PairedCubeRow.
        val isPairingNewDevice = connectingMac != null &&
            paired.none { it.mac == connectingMac }
        ConnectionHeader(
            state = connectionState,
            isPairingNewDevice = isPairingNewDevice,
            onPair = vm::startScan,
            onCancel = vm::cancelScan,
            onDisconnect = vm::disconnect,
            onNavigateToSolve = onNavigateToSolve,
        )

        if (missingPerms) {
            Text(
                stringResource(Res.string.devices_no_permissions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (btDisabled) {
            // When the user has Bluetooth turned off, show a banner with
            // an "Enable Bluetooth" button that sends them to the system
            // settings panel. The line above is the explanation; the
            // button below performs the action.
            Text(
                stringResource(Res.string.devices_bt_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Button(
                onClick = vm::openBluetoothSettings,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(stringResource(Res.string.devices_bt_enable))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (connectionState == ConnectionState.SCANNING) {
            Text(stringResource(Res.string.devices_available), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Same 1 dp drop shadow as the My-cubes paired card
                    // so the screen reads consistently. Shadow before
                    // background so the elevation paints around the
                    // rounded corner the background fills below.
                    //
                    // surfaceContainerLow (one step lighter than the
                    // previous surfaceContainer) so the panel reads as a
                    // soft tray rather than a heavy slab. The inner
                    // device buttons sit on top with a darker container
                    // step (see DeviceList) so the layered relationship
                    // stays legible.
                    .shadow(
                        elevation = 1.dp,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DeviceList(scanned, onPair = vm::pair)
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(stringResource(Res.string.devices_paired), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (paired.isEmpty()) {
            Text(
                stringResource(Res.string.devices_paired_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            PairedList(
                paired,
                connectedId = connectedId,
                connectingMac = connectingMac,
                batteryByMac = batteryByMac,
                onConnect = vm::reconnect,
                onDisconnect = vm::disconnect,
                onForget = { pendingForget = it },
                onDetail = { detail = it },
            )
        }
    }

    detail?.let { cube ->
        CubeDetailDialog(cube, batteryLevel = batteryByMac[cube.mac], onDismiss = { detail = null })
    }

    pendingForget?.let { cube ->
        ConfirmationDialog(
            title = stringResource(Res.string.devices_forget_title),
            message = stringResource(Res.string.devices_forget_message),
            confirmLabel = stringResource(Res.string.devices_forget),
            cancelLabel = stringResource(Res.string.devices_cancel),
            onConfirm = { vm.forget(cube.id); pendingForget = null },
            onDismiss = { pendingForget = null },
        )
    }
}

/**
 * Top-of-screen action bar.
 *
 * Layout per state:
 *  • DISCONNECTED / PERMISSION_DENIED / ERROR → only "Pair" on the right.
 *  • SCANNING → only "Cancel" on the right.
 *  • CONNECTING → disabled "Connecting…" pill on the right.
 *  • CONNECTED → animated "GO SOLVE" CTA on the left, "Pair new" on the right.
 *
 * Per-row Disconnect on the connected paired-cube card does the actual
 * disconnect work; the header doesn't duplicate it.
 *
 * The "GO SOLVE" CTA scales+fades in when the cube finishes connecting
 * (`AnimatedVisibility` keyed off `state == CONNECTED`). It's deliberately
 * the most prominent control on the screen at that moment so the user is
 * pulled toward the timer rather than fiddling with cube settings.
 */
@Composable
private fun ConnectionHeader(
    state: ConnectionState,
    isPairingNewDevice: Boolean,
    onPair: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToSolve: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // -- Left side: animated GO SOLVE CTA when connected --------------
        // AnimatedVisibility keeps the slot occupying space only while the
        // CTA is showing; it springs in with scale+fade when the connect
        // handshake completes. tween is short (220 ms) so it feels snappy
        // – long enough to read as "something just happened", short enough
        // not to delay the eager user.
        AnimatedVisibility(
            visible = state == ConnectionState.CONNECTED,
            enter = scaleIn(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = scaleOut(animationSpec = tween(160)) + fadeOut(animationSpec = tween(160)),
        ) {
            Button(
                onClick = onNavigateToSolve,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    stringResource(Res.string.devices_go_solve),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Flexible spacer pushes the right-side action to the screen edge
        // regardless of whether the GO SOLVE button is currently showing.
        // Without this, when the AnimatedVisibility slot becomes empty
        // mid-transition, the right button would slide left.
        Spacer(modifier = Modifier.weight(1f))

        // -- Right side: state-dependent secondary action ----------------
        when (state) {
            ConnectionState.SCANNING ->
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(Res.string.devices_cancel))
                }
            ConnectionState.CONNECTING ->
                // The header's CONNECTING pill only shows when the
                // connection in flight is for a NEW (not-yet-paired)
                // device. Reconnecting an already-paired cube routes
                // its spinner to the per-row "Connecting…" UI inside
                // PairedCubeRow – having two spinners on the same screen
                // would be visually noisy. When [isPairingNewDevice] is
                // false we render an empty box so the right edge keeps
                // the same padding and nothing visually flickers.
                if (isPairingNewDevice) {
                    Button(onClick = onDisconnect, enabled = true) {
                        ButtonProgressDot()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.devices_connecting))
                    }
                } else {
                    // Empty placeholder – no header action while reconnecting
                    // a paired cube; the row owns the feedback.
                    Spacer(Modifier.size(0.dp))
                }
            ConnectionState.CONNECTED ->
                // Connected state: secondary "Pair new" affordance so
                // the user can swap cubes without having to manually
                // disconnect first. Outlined to read as "less primary"
                // than the GO SOLVE CTA on the left.
                OutlinedButton(onClick = onPair) {
                    Text(stringResource(Res.string.devices_pair_new))
                }
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR,
            ConnectionState.PERMISSION_DENIED,
            // BLUETOOTH_DISABLED: the user can still tap Pair, which
            // BleManager will translate into another BLUETOOTH_DISABLED
            // state emission. The dedicated banner + "Enable Bluetooth"
            // CTA in the screen body (see DevicesScreen) is the real
            // affordance for this state.
            ConnectionState.BLUETOOTH_DISABLED ->
                Button(onClick = onPair) {
                    Text(stringResource(Res.string.devices_pair))
                }
        }
    }
}

@Composable
private fun PairedList(
    cubes: List<PairedCube>,
    connectedId: String?,
    connectingMac: String?,
    batteryByMac: Map<String, Int>,
    onConnect: (PairedCube) -> Unit,
    onDisconnect: () -> Unit,
    onForget: (PairedCube) -> Unit,
    onDetail: (PairedCube) -> Unit,
) {
    // True when ANY connect handshake is in flight. Used to disable
    // every Connect button (across all rows) so the user can't kick off
    // a parallel connect – the orchestrator only handles one at a
    // time and a second connect would cancel the first, racing with
    // its in-flight GATT writes. The row that's actually being
    // connected to gets its own "Connecting…" affordance instead.
    val anyConnecting = connectingMac != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (cube in cubes) {
            PairedCubeRow(
                cube = cube,
                isConnected = cube.id == connectedId,
                isConnecting = cube.mac == connectingMac,
                anyConnecting = anyConnecting,
                batteryLevel = batteryByMac[cube.mac],
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onForget = onForget,
                onDetail = onDetail,
            )
        }
    }
}

/**
 * Two-row card layout:
 *   - Top: name/battery + Connect/Disconnect button on the right.
 *   - Bottom: Info button on the left + Forget button on the right, both
 *     anchored to the card's bottom edge so they line up at exactly the
 *     same height regardless of the top-row content.
 *
 * The connected row gets:
 *   • a 2 dp accent border + slightly raised elevation (visual anchor)
 *   • the Disconnect button (in place of Connect)
 *   • a battery indicator on the right side of the name row, when known
 */
@Composable
private fun PairedCubeRow(
    cube: PairedCube,
    isConnected: Boolean,
    isConnecting: Boolean,
    anyConnecting: Boolean,
    batteryLevel: Int?,
    onConnect: (PairedCube) -> Unit,
    onDisconnect: () -> Unit,
    onForget: (PairedCube) -> Unit,
    onDetail: (PairedCube) -> Unit,
) {
    // Highlight the card with a thin colored border in BOTH the
    // connected and connecting states – visually it reads as "this row
    // is the active one right now". The color tracks elevation so the
    // connecting state pops a touch less than the fully-connected one.
    val accent = MaterialTheme.colorScheme.primary
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { m ->
            when {
                isConnected -> m.border(2.dp, accent, RoundedCornerShape(12.dp))
                isConnecting -> m.border(1.dp, accent, RoundedCornerShape(12.dp))
                else -> m
            }
        }

    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isConnected || isConnecting) 4.dp else 1.dp,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ------- Top row: name/battery + Connect/Disconnect ---------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        cube.name ?: stringResource(Res.string.devices_unknown),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(ConnectionDotSize)
                                .background(
                                    if (isConnected) StatusColors.ConnectedGreen else StatusColors.DisconnectedGray,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isConnected) stringResource(Res.string.devices_connected) else stringResource(Res.string.devices_disconnected),
                            fontSize = 12.sp,
                            color = if (isConnected) StatusColors.ConnectedGreen else StatusColors.DisconnectedGray,
                        )
                        Spacer(Modifier.width(8.dp))
                        // Battery indicator only when known and the cube is
                        // currently connected – old battery values from a
                        // previous session would be misleading.
                        if (isConnected && batteryLevel != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "$batteryLevel%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                }
                Spacer(Modifier.width(8.dp))
                // Three-way action button:
                //   • CONNECTED:   Disconnect (outlined) – tear down the link.
                //   • CONNECTING:  Connecting… pill with spinner. We allow clicking
                //                  the pill to Disconnect the device, because otherwise
                //                  there is no way to cancel the connection process.
                //                  CAUTION - this might lead to weird states.
                //   • else:        Connect (filled). Disabled when any OTHER
                //                  row's connect is in flight, so the user
                //                  can't queue parallel attempts.
                when {
                    isConnected -> OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(Res.string.devices_disconnect), fontSize = 13.sp)
                    }
                    isConnecting -> Button(onClick = onDisconnect, enabled = true) {
                        ButtonProgressDot()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.devices_connecting), fontSize = 13.sp)
                    }
                    else -> Button(
                        onClick = { onConnect(cube) },
                        enabled = !anyConnecting,
                    ) {
                        Text(stringResource(Res.string.devices_connect), fontSize = 13.sp)
                    }
                }
            }

            // ------- Bottom row: Info (left) + Forget (right) -----------
            // Both are TextButtons sharing the same row; their baselines
            // line up by virtue of being siblings in the same Row, so
            // they're guaranteed to be at the same vertical position
            // regardless of content above.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { onDetail(cube) },
                    contentPadding = TightTextButtonPadding,
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(stringResource(Res.string.devices_info), fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onForget(cube) },
                    contentPadding = TightTextButtonPadding,
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(stringResource(Res.string.devices_forget), fontSize = 12.sp)
                }
            }
        }
    }
}

/** Compact text-button padding so the Info button doesn't dominate the row. */
private val TightTextButtonPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)

/**
 * Cube detail dialog – shown when the user taps the Info button on a row.
 * Surfaces everything we know from the GAN INFO handshake.
 */
@Composable
private fun CubeDetailDialog(cube: PairedCube, batteryLevel: Int?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cube.name ?: stringResource(Res.string.devices_unknown)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(Res.string.devices_detail_mac), cube.mac)
                DetailRow(
                    stringResource(Res.string.devices_detail_hw),
                    cube.hwVersion ?: "–",
                )
                DetailRow(
                    stringResource(Res.string.devices_detail_sw),
                    cube.swVersion ?: "–",
                )
                DetailRow(
                    stringResource(Res.string.devices_detail_gyro),
                    when (cube.gyroSupported) {
                        true -> stringResource(Res.string.devices_detail_gyro_yes)
                        false -> stringResource(Res.string.devices_detail_gyro_no)
                        null -> stringResource(Res.string.devices_detail_gyro_unknown)
                    },
                )
                DetailRow(
                    stringResource(Res.string.devices_detail_battery),
                    batteryLevel?.let { "$it%" } ?: "–",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                // onSurfaceVariant is Material3's documented role for
                // "secondary text" – same vocabulary as the Cancel
                // buttons across the app's dialogs.
                Text(
                    stringResource(Res.string.history_close),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeviceList(devices: List<BleDevice>, onPair: (BleDevice) -> Unit) {
    if (devices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(Res.string.devices_scanning)) }
        return
    }

    val sorted = remember(devices) {
        // Known-vendor devices float to the top of the list so the cubes
        // the user actually paired this app for are surfaced first. Among
        // those, GAN comes before MoYu by enum ordinal – arbitrary but
        // stable. Unknown-vendor scan hits (other BLE peripherals nearby:
        // headphones, watches, etc.) sink to the bottom.
        devices.sortedWith(
            compareBy(
                { it.detectVendor()?.ordinal ?: Int.MAX_VALUE },
            )
        )
    }

    val listState = rememberLazyListState()

    // VerticalScrollbarBox provides a draggable scrollbar in a right-edge
    // gutter alongside the list. The gutterEnd value is the right
    // padding the LazyColumn applies so device rows don't sit under
    // the scrollbar track.
    //
    // The thumb sits over the discovered-devices panel
    // (surfaceContainerLow), which is one step *off* the page in both
    // modes – darker than the page in light mode (#F2F2F6 vs #FFFFFF)
    // and lighter than the page in dark mode (#1A1A1D vs #0B0B0D).
    // The default scrollbar thumb color (`onSurface @ 0.5`) is tuned
    // for the page background; against this offset panel it can read
    // as too faint. We pass an explicit brighter `onSurface @ 0.7` so
    // the indicator stays clearly visible in both modes.
    VerticalScrollbarBox(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(280.dp),
        thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    ) { gutterEnd ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = gutterEnd),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sorted) { device ->
                // Known-vendor cubes use the saturated `primary` color
                // so the cubes the user is most likely there to connect
                // pop visually; unknown-vendor scan hits use the panel-
                // matching `surfaceContainerHigh`.
                val vendor = device.detectVendor()
                val isKnownCube = vendor != null
                Button(
                    onClick = { onPair(device) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    // Known-vendor cubes use the seed's full `primary`
                    // (saturated, text in onPrimary) so they stand out
                    // clearly from non-cube entries on the same panel.
                    // Previously primaryContainer rendered too soft to
                    // draw the eye when there were several devices
                    // visible.
                    //
                    // Other devices use surfaceContainerHigh. The tile
                    // sits on top of a surfaceContainerLow panel, so
                    // High gives a clear one-step lift in both modes
                    // without the heavy-slab feel of the previous
                    // `surfaceContainerHighest` (which read as too
                    // dark in light mode against the lighter panel).
                    colors = if (isKnownCube) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                device.name ?: stringResource(Res.string.devices_unknown),
                                fontWeight = FontWeight.Bold,
                            )
                            if (vendor != null) {
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "• " + stringResource(vendor.labelRes()),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Text(device.address, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


/**
 * MAC OUI prefixes assigned to GAN smart cubes. New GAN models that show up
 * with a different prefix should be added here so they're surfaced to the
 * top of the available-devices list. Detection is by MAC because the GAN
 * device-name format varies (cube-name, model id, etc.) but the OUI is
 * stable across the product line.
 */
/**
 * Vendor inferred from a pre-connect scan hit. Used by the
 * available-devices list to sort known cubes to the top and give them
 * the saturated tile plus a vendor chip.
 *
 * All the judgement lives in [CubeProtocolRegistry.detectVendorFromScan] — advertised
 * service UUIDs first, then device-name prefix, then MAC OUI — so the
 * scan-time answer and the post-connect answer can't drift apart. This
 * screen only decides what to *do* with the result.
 *
 * The previous version made the call here, from the MAC alone, against a
 * one-entry OUI list. Cubes built on any other radio module went
 * unrecognised: no chip, no sorting, buried among the earbuds — while
 * connecting to them worked fine, which made it read as a scanning bug
 * rather than a classification one.
 */
private fun BleDevice.detectVendor(): CubeVendor? =
    CubeProtocolRegistry.detectVendorFromScan(
        name = name,
        macAddress = address,
        advertisedServices = advertisedServiceUuids,
    )

/**
 * Localised label for a [CubeVendor]. Used by the available-devices tile
 * chip and (in future) anywhere else the vendor needs to be named in UI.
 * Brand names are kept identical across locales, but routing through
 * `stringResource` keeps the wiring uniform if any translation ever
 * decides otherwise (e.g. a different transliteration for a CJK locale).
 */
private fun CubeVendor.labelRes() = when (this) {
    CubeVendor.GAN -> Res.string.device_vendor_gan
    CubeVendor.MOYU -> Res.string.device_vendor_moyu
    CubeVendor.QIYI -> Res.string.device_vendor_qiyi
    CubeVendor.GOCUBE -> Res.string.device_vendor_gocube
    CubeVendor.RUBIKS -> Res.string.device_vendor_rubiks
    CubeVendor.GIIKER -> Res.string.device_vendor_giiker
}

/**
 * Tiny in-button spinner used inside a disabled "Connecting…" pill.
 * Sized down to 14dp + thin stroke so it sits comfortably next to the
 * label without inflating the button's height.
 *
 * Stroke color is derived from `LocalContentColor` so the spinner
 * matches whatever color the host button is using for its label –
 * the disabled-state grey for filled buttons, the primary color for
 * outlined buttons, etc.
 */
@Composable
private fun ButtonProgressDot() {
    CircularProgressIndicator(
        modifier = Modifier.size(14.dp),
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}
