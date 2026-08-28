package com.zucham.qbsmarter.ui.screens.solve

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.domain.cube.CubeMove
import com.zucham.qbsmarter.domain.cube.isApproximatelyIdentity
import com.zucham.qbsmarter.ui.components.DialogButton
import com.zucham.qbsmarter.ui.components.DialogButtonEmphasis
import com.zucham.qbsmarter.ui.screens.solve.stats.SolveSession
import com.zucham.qbsmarter.ui.screens.solve.stats.StatRegistry
import com.zucham.qbsmarter.util.formatDuration
import com.zucham.qbsmarter.ui.theme.ConnectionDotSize
import com.zucham.qbsmarter.ui.theme.StatusColors
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.devices_connect
import qbsmarter.shared.generated.resources.solve_idle
import qbsmarter.shared.generated.resources.solve_new_scramble
import qbsmarter.shared.generated.resources.solve_no_cube
import qbsmarter.shared.generated.resources.solve_connect_cube
import qbsmarter.shared.generated.resources.solve_dnf
import qbsmarter.shared.generated.resources.solve_pb_dismiss
import qbsmarter.shared.generated.resources.solve_pb_message
import qbsmarter.shared.generated.resources.solve_pb_title
import qbsmarter.shared.generated.resources.solve_plus2
import qbsmarter.shared.generated.resources.solve_ready
import qbsmarter.shared.generated.resources.solve_quick_overview
import qbsmarter.shared.generated.resources.solve_reset_orientation
import qbsmarter.shared.generated.resources.solve_reset_state
import qbsmarter.shared.generated.resources.solve_running
import qbsmarter.shared.generated.resources.solve_scramble_prompt
import qbsmarter.shared.generated.resources.solve_solved
import qbsmarter.shared.generated.resources.solve_tip_any_move
import qbsmarter.shared.generated.resources.solve_tip_uu_prefix
import qbsmarter.shared.generated.resources.solve_tip_uu_suffix
import qbsmarter.shared.generated.resources.solve_toggle_gyro
import qbsmarter.shared.generated.resources.solve_toggle_gyro_off
import qbsmarter.shared.generated.resources.solve_toggle_gyro_on

/**
 * Solve screen layout (top → bottom):
 *
 *   ConnectionIndicator
 *   CubeView (fixed-square)
 *   ActionRow (3 themed buttons)
 *   ScrambleCard (scramble + New button in a surface)
 *   ─── flexible spacer ───
 *   TimerArea (timer, prompt, or countdown – centered in spacer)
 *   StatGrid (3-col, anchored to bottom, fixed compact height)
 *
 * The flexible spacer puts the timer in whatever room remains between the
 * scramble card and the stat container. That gives the timer room to grow
 * visually on tall devices without pushing the stats off-screen on short
 * ones.
 */
private object SolveSizes {
    /**
     * Opacity of the scrim overlay drawn on top of the cube view when no
     * cube is connected. Higher = more "greyed out". Tuned by eye: low
     * enough that users can still tell the cube is rendered underneath,
     * high enough to read as "not active" rather than "still working but
     * dim".
     */
    const val disconnectedScrimAlpha = 0.6f

    /** Compact button row. */
    val actionButtonHeight = 36.dp
    val actionButtonFontSize = 13.sp
    val actionButtonHorizontalPadding = 10.dp

    val scrambleFontSize = 14.sp
    /** Bumped size for the "next move" highlight (correction or normal). */
    val scrambleHighlightFontSize = 17.sp
    val scrambleLineHeight = 22.sp
    /** Cap so a freakishly long scramble + correction prefix never grows past 4 lines. */
    val scrambleLineMaxHeight = 67.dp

    val timerFontSize = 56.sp
    /** Smaller font for non-numeric prompts ("Scramble the cube", "Ready"). */
    val statusFontSize = 22.sp

    /**
     * Fixed height of the timer area. Pinning the height keeps the layout
     * above (cube + scramble + action row) stationary regardless of phase
     * – the SOLVED phase shows extra content (Solved! label, penalty
     * buttons, next-solve tip) that would otherwise grow this area.
     */
    val timerAreaHeight = 180.dp

    /** Stat grid sits at the bottom with this fixed height – tweak if rows clip. */
    val statGridHeight = 180.dp
}

@Composable
fun SolveScreen(onNavigateToDevices: () -> Unit = {}) {
    val vm: SolveViewModel = koinViewModel()
    val cubeScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(vm.cube) {
        vm.cube.start(cubeScope)
        onDispose { vm.cube.stop() }
    }

    // Resync the visualisation to the logical state every time the Solve
    // screen is displayed. While the user is on Devices/History/Settings
    // the cube's move queue is stopped (CubeView disposes its scope on
    // navigation), so BLE moves received during that window queue up
    // without animating. logicalState – maintained synchronously by the
    // VM regardless of which screen is shown – stays correct; this
    // effect ensures the visual catches up cleanly without replaying
    // a stale backlog.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.resyncVisualToLogical()
    }
    val phase by vm.phase.collectAsState()
    val scramble by vm.scramble.collectAsState()
    val scrambleProgress by vm.scrambleProgress.collectAsState()
    val deviationMoves by vm.deviationMoves.collectAsState()
    val elapsedMs by vm.elapsedMs.collectAsState()
    val inspectionMs by vm.inspectionMs.collectAsState()
    val inspectionRunning by vm.inspectionRunning.collectAsState()
    val history by vm.history.collectAsState()
    val connection by vm.connectionSummary.collectAsState()
    val newPbEvent by vm.newPbEvent.collectAsState()
    val lastSolveInfo by vm.lastSolveInfo.collectAsState()
    val mode by vm.themeController.mode.collectAsState()
    val gyroEnabled by vm.gyroEnabled.collectAsState()
    val anyMoveStartsNewSolve by vm.anyMoveStartsNewSolve.collectAsState()

    // Derive "is the cube already aligned" from the orbiter's
    // rotation. The orbiter exposes a Compose MutableState<Transform>, so
    // derivedStateOf re-evaluates on every animation frame the slerp
    // updates it. The result drives Reset Orientation visibility.
    //
    // Only the drag offset is considered, deliberately: the gyro pose is
    // owned by the render thread and polled per frame, so observing it
    // here would mean recomposing this screen at the display refresh
    // rate. The gyro's contribution to the button's visibility is handled
    // by the `gyroEnabled` term at the call site instead – while gyro is
    // running the cube is almost never at home, so the button stays up.
    val isOrientationAligned by remember(vm.cube) {
        derivedStateOf {
            isApproximatelyIdentity(vm.cube.orbiter.rotation)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionIndicator(connection, onNavigateToDevices = onNavigateToDevices)
        Spacer(Modifier.size(4.dp))

        // The cube takes ALL the vertical room left between the
        // top connection indicator and the fixed-size bottom block
        // (action row + scramble + timer + stat grid). The cube box is
        // weight(1f), and inside it we use BoxWithConstraints to size
        // a centered square at min(maxWidth, maxHeight) – so:
        //   - On a tall narrow phone, the cube becomes the screen width
        //     (the smaller of the two), keeping it square.
        //   - On a tablet, the cube grows to fill whatever vertical
        //     room is available, capped by the screen width.
        //
        // When the cube is disconnected, overlay a translucent scrim.
        // Modifier.alpha doesn't reach the Korender SurfaceView (it's
        // rendered on a separate hardware overlay layer that bypasses
        // Compose's graphics layer). A scrim Box drawn via Compose paints
        // over the GL surface and gives us the visual "inactive" cue.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Largest square that fits this box. minOf chooses the
            // smaller of the two; on phones this is usually maxWidth
            // (cube fills column width), on tablets it can be maxHeight.
            val side = minOf(maxWidth, maxHeight)
            Box(modifier = Modifier.size(side)) {
                CubeView(
                    cube = vm.cube,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = MaterialTheme.colorScheme.background,
                    mode = mode
                )
                if (!connection.isConnected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.background.copy(
                                    alpha = SolveSizes.disconnectedScrimAlpha,
                                ),
                            ),
                    )
                }
            }
        }

        ActionRow(
            onResetOrientation = vm::resetOrientation,
            onResetState = vm::resetState,
            onToggleGyro = vm::toggleGyro,
            // Hide the gyro button by default – show it only when a
            // cube is actually on the wire AND the INFO handshake
            // confirmed it has a gyro. For unknown (null) and
            // unsupported (false) we hide the button entirely; the user
            // discovers the toggle only on hardware that has the
            // feature. The connected term matters because
            // connectionSummary falls back to the most recently seen
            // paired cube when no MAC is active, so without it the
            // button would linger after a disconnect, offering to track
            // a cube that isn't sending anything.
            showGyroButton = connection.isConnected && connection.gyroSupported == true,
            gyroEnabled = gyroEnabled,
            // Reset Orientation is only meaningful when there's
            // something to reset. That's true when the drag offset is
            // off-identity, and also whenever the gyro is live – the
            // button then re-homes the gyro baseline, which is the only
            // way back to a default pose while the cube is being moved.
            showResetOrientation = !isOrientationAligned ||
                (gyroEnabled && connection.isConnected),
        )
        ScrambleCard(scramble, scrambleProgress, deviationMoves, vm::newScramble)

        // Timer + post-solve buttons. Fixed-size content (no weight) so
        // the cube above gets the leftover vertical space, not this row.
        TimerArea(
            phase = phase,
            elapsedMs = elapsedMs,
            inspectionMs = inspectionMs,
            inspectionRunning = inspectionRunning,
            lastSolveInfo = lastSolveInfo,
            anyMoveStartsNewSolve = anyMoveStartsNewSolve,
            onMarkDnf = vm::markLastSolveDnf,
            onMarkPlus2 = vm::markLastSolvePlus2,
        )

        StatGrid(
            history = history,
            session = vm.currentSession(),
            statRegistry = vm.statRegistry,
            modifier = Modifier
                .fillMaxWidth()
                .height(SolveSizes.statGridHeight)
                .padding(bottom = 12.dp)
                // Shadow + clip share the 16 dp corner radius. Shadow
                // first so the elevation paints around the rounded edge,
                // then clip so the contents (including the inner tinted
                // background painted at 8 dp) get masked to the same
                // rounded outline. Same 1 dp elevation as the paired
                // cube card on the Devices screen for visual consistency.
                .shadow(
                    elevation = 1.dp,
                    shape = RoundedCornerShape(16.dp),
                )
                .clip(RoundedCornerShape(16.dp)),
        )
    }

    // PB celebration. Shown over the screen on top of the
    // SOLVED phase whenever finishSolve() detected a new personal best.
    // The dialog is the explicit dismissal mechanism – outside-tap and
    // the "Hooray!" button both clear the event.
    newPbEvent?.let { effectiveMs ->
        PbDialog(durationMs = effectiveMs, onDismiss = vm::dismissPbEvent)
    }
}

@Composable
private fun ConnectionIndicator(
    connection: CubeConnectionSummary,
    onNavigateToDevices: () -> Unit,
) {
    val connected = connection.isConnected
    val name = connection.displayName
    val dotColor = if (connected) StatusColors.ConnectedGreen else StatusColors.DisconnectedGray
    val text = when {
        connected && !name.isNullOrBlank() -> name
        connected -> stringResource(Res.string.devices_connect)
        else -> stringResource(Res.string.solve_no_cube)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The device-name row is tappable when connected – gives the
        // user a fast path to the Devices screen without reaching for
        // the navigation drawer. Deliberately NOT tappable when
        // disconnected because the explicit "Connect cube" button below
        // already provides that path; making both tappable would split
        // the user's attention.
        //
        // Default ripple (from `clickable`) provides visual feedback on
        // tap; the row also surfaces "click" as the talkback action so
        // it's discoverable for screen readers.
        val rowModifier = if (connected) {
            Modifier.padding(top = 12.dp).clickable(onClick = onNavigateToDevices)
        } else {
            Modifier.padding(top = 12.dp)
        }
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(ConnectionDotSize)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!connected) {
            // Disconnected → offer a one-tap shortcut to the Devices screen.
            // The button is themed-primary so it reads as "the next thing to
            // do" without a heavy CTA visual that would dominate the header.
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onNavigateToDevices,
                modifier = Modifier.heightIn(min = SolveSizes.actionButtonHeight),
                contentPadding = PaddingValues(
                    horizontal = SolveSizes.actionButtonHorizontalPadding,
                    vertical = 4.dp,
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(
                    stringResource(Res.string.solve_connect_cube),
                    fontSize = SolveSizes.actionButtonFontSize,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Solve action row layout:
 *
 *   [Reset Orientation (animated)] [Gyro?] ←Spacer weight=1f→ [Reset State]
 *
 * Reset Orientation is the leftmost slot and only visible when the cube
 * is NOT already aligned to identity. It's wrapped in a separate
 * composable ([AnimatedResetOrientationButton]) so the
 * [androidx.compose.animation.AnimatedVisibility] call resolves
 * unambiguously to the unscoped overload – the version with a `RowScope`
 * receiver kept being picked here when the call sat directly inside a
 * `Row { … }`, which produced "cannot be called in this context with an
 * implicit receiver" errors. Hoisting it into its own composable means
 * the call site is no longer inside a RowScope.
 *
 * Gyro is the second slot and only shows when [showGyroButton] is true
 * (cube confirmed to support gyro via the INFO handshake). Hidden in all
 * other cases (unknown / unsupported) so the button doesn't tease a
 * non-functional feature. Unlike its neighbours it's a *toggle*, so it
 * carries its state in its fill via [gyroEnabled] – without that the
 * user has no way to tell whether a tap turned the feature on or off,
 * since a stationary cube looks identical either way.
 *
 * The Spacer with weight=1f anchors Reset State to the right edge no
 * matter how many of the left-side slots are currently visible.
 *
 * Reset State sits on the right and is themed in error red because it's
 * the only destructive action in the row.
 */
@Composable
private fun ActionRow(
    onResetOrientation: () -> Unit,
    onResetState: () -> Unit,
    onToggleGyro: () -> Unit,
    showGyroButton: Boolean,
    gyroEnabled: Boolean,
    showResetOrientation: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedResetOrientationButton(
            visible = showResetOrientation,
            onClick = onResetOrientation,
        )
        if (showGyroButton) {
            ThemedToggleButton(
                label = stringResource(Res.string.solve_toggle_gyro),
                checked = gyroEnabled,
                stateLabel = stringResource(
                    if (gyroEnabled) Res.string.solve_toggle_gyro_on
                    else Res.string.solve_toggle_gyro_off,
                ),
                onClick = onToggleGyro,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        DestructiveButton(stringResource(Res.string.solve_reset_state), onResetState)
    }
}

/**
 * Reset Orientation button with show/hide animation. Hosted in its own
 * composable so [androidx.compose.animation.AnimatedVisibility] doesn't
 * collide with the `RowScope.AnimatedVisibility` overload at the call
 * site. (See [ActionRow] for the diagnosis.)
 */
@Composable
private fun AnimatedResetOrientationButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        exit = scaleOut(animationSpec = tween(160)) + fadeOut(animationSpec = tween(160)),
    ) {
        ThemedButton(stringResource(Res.string.solve_reset_orientation), onClick)
    }
}

/**
 * Themed compact button. Uses primaryContainer/onPrimaryContainer so the
 * button blends with the active theme – picks up color through the seed
 * picker without any per-screen wiring.
 */
@Composable
private fun ThemedButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = SolveSizes.actionButtonHeight),
        contentPadding = PaddingValues(
            horizontal = SolveSizes.actionButtonHorizontalPadding,
            vertical = 4.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(label, fontSize = SolveSizes.actionButtonFontSize, maxLines = 1)
    }
}

/**
 * On/off variant of [ThemedButton] for actions that latch rather than
 * fire once (currently just Gyro).
 *
 * Checked uses the theme's full-strength `primary` fill; unchecked keeps
 * [ThemedButton]'s softer `primaryContainer`, so a toggled-on button
 * reads as clearly "active" next to the plain action buttons beside it
 * while still sitting inside the same seed-driven palette. Colors are
 * animated so the state change is legible even if the user's eye is on
 * the cube rather than the button.
 *
 * [stateLabel] carries the on/off state for screen readers – the visual
 * fill is the only cue otherwise, and colour alone isn't an accessible
 * signal. It's published as `stateDescription` (which augments the
 * button's own text) rather than `contentDescription` (which would
 * replace it).
 */
@Composable
private fun ThemedToggleButton(
    label: String,
    checked: Boolean,
    stateLabel: String,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(180),
        label = "toggleContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = tween(180),
        label = "toggleContent",
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = SolveSizes.actionButtonHeight)
            .semantics {
                stateDescription = stateLabel
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        contentPadding = PaddingValues(
            horizontal = SolveSizes.actionButtonHorizontalPadding,
            vertical = 4.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Text(label, fontSize = SolveSizes.actionButtonFontSize, maxLines = 1)
    }
}

/**
 * Destructive variant of [ThemedButton]. Filled in the theme's `error`
 * red with `onError` text – on every supported seed/mode this resolves
 * to a strong, saturated red with white text so the destructive
 * affordance is unambiguous (the previous `errorContainer/onErrorContainer`
 * pair rendered as a soft pink in light mode, which read more like a
 * neutral chip than "this will wipe your state"). Used for Reset State.
 */
@Composable
private fun DestructiveButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = SolveSizes.actionButtonHeight),
        contentPadding = PaddingValues(
            horizontal = SolveSizes.actionButtonHorizontalPadding,
            vertical = 4.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(
            label,
            fontSize = SolveSizes.actionButtonFontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Scramble lives in a surface card matching the stat container's styling.
 * The text wraps multi-line; the New button sits to the right.
 *
 * When the user makes wrong moves and [deviationMoves] is non-empty, the
 * card prepends a "correction prefix" – the inverse moves required to
 * return the cube to the prefix-state at [progress] – rendered in red.
 * The first correction move is the one the user should perform now.
 */
@Composable
private fun ScrambleCard(
    scramble: String,
    progress: Int,
    deviationMoves: List<CubeMove>,
    onNewScramble: () -> Unit,
) {
    val doneColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val upcomingColor = MaterialTheme.colorScheme.onSurface
    val nextColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    // The half-turn grace window (suppress red warning while the user is
    // halfway through a scramble half-turn) is applied at the VM layer in
    // SolveViewModel.deviationMoves. By the time the list reaches us here,
    // it's already been delay-filtered, so we render it directly.
    val annotated = remember(
        scramble, progress, deviationMoves, doneColor, upcomingColor, nextColor, errorColor,
    ) {
        buildScrambleAnnotated(
            scramble, progress, deviationMoves,
            doneColor, upcomingColor, nextColor, errorColor,
        )
    }

    // Pre-blend the tinted background. Was previously
    // `surfaceVariant.copy(alpha = 0.3f)` painted over the screen's
    // surface. That works visually but `Modifier.shadow` only renders
    // a meaningful drop shadow when the bounds are opaque – with a
    // semi-transparent fill the shadow shows *through* the container
    // itself instead of just outside it. Manually lerping toward
    // surfaceVariant keeps the same hue while letting the shadow
    // render cleanly behind the card.
    //
    // Lerp factor lives in [tintedContainerBackground] – see there
    // for why 0.45 (vs the previous 0.3): with the rebuilt surface
    // ladder, 0.3 puts the result barely above page brightness in
    // dark mode and the container reads as part of the page; 0.45
    // gives it a perceptible lift while staying recognisably "tinted
    // surface" rather than "full surfaceVariant".
    val tinted = tintedContainerBackground()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Shadow must come before background in the modifier chain so
            // the rounded corner the elevation paints around matches the
            // visible rounded fill below. Uses the same 1 dp default
            // elevation as the My-cubes paired-cube card so the look is
            // consistent across screens.
            .shadow(
                elevation = 1.dp,
                shape = RoundedCornerShape(8.dp),
            )
            .background(
                color = tinted,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp).height(SolveSizes.scrambleLineMaxHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
        ) {
            Text(
                text = annotated,
                fontSize = SolveSizes.scrambleFontSize,
                fontFamily = FontFamily.Monospace,
                lineHeight = SolveSizes.scrambleLineHeight,
            )
        }
        ThemedButton(stringResource(Res.string.solve_new_scramble), onNewScramble)
    }
}

/**
 * Build the scramble [AnnotatedString]. Three sections, in this order:
 *   1. Done tokens (dimmed) – the prefix the user has already correctly
 *      executed.
 *   2. Optional correction prefix (red) – only present when the user is
 *      off-rails. The first correction move is bolded; it's what the user
 *      should turn next. Inserted between done and upcoming because the
 *      user is "logically there" – they need to undo their wrong moves
 *      before the next scramble token applies.
 *   3. Upcoming tokens (normal). The next-up bold highlight here is
 *      suppressed when a correction prefix is showing – that bold is
 *      stolen by the first correction move.
 *
 * Correction moves are also collapsed via [CubeMove.mergeAdjacentSameFace]
 * so two consecutive identical inverse moves render as a half-turn:
 * `U' U'` becomes `U2`. This matches how a human would naturally read
 * "what do I turn next" rather than the raw move-by-move undo list.
 */
private fun buildScrambleAnnotated(
    scramble: String,
    progress: Int,
    deviationMoves: List<CubeMove>,
    doneColor: Color,
    upcomingColor: Color,
    nextColor: Color,
    errorColor: Color,
): AnnotatedString {
    val tokens = scramble.split(' ').filter { it.isNotBlank() }
    // Inverse + reverse the deviations to produce the literal "what to
    // turn next" list, then merge adjacent same-face moves so the user
    // sees `U2` instead of `U' U'`. The merge pass also drops cancelling
    // pairs (e.g. R + R' that survived because the deviation tracker
    // doesn't pop across face changes).
    val correction: List<String> = CubeMove
        .mergeAdjacentSameFace(deviationMoves.asReversed().map { it.inverse() })
        .map { it.notation() }
    val hasCorrection = correction.isNotEmpty()

    return buildAnnotatedString {
        var anythingAppended = false

        // 1. Done prefix.
        for (idx in 0 until progress.coerceAtMost(tokens.size)) {
            append(if (anythingAppended) " " else "")
            withStyle(SpanStyle(color = doneColor)) { append(tokens[idx]) }
            anythingAppended = true
        }

        // 2. Correction in the middle, between done and upcoming.
        if (hasCorrection) {
            if (anythingAppended) append("  ")  // visual separator
            correction.forEachIndexed { idx, tok ->
                if (idx > 0) append(' ')
                val style = if (idx == 0) {
                    // First correction move = the user's next physical action.
                    // We layer three cues so it stands out even at a glance:
                    // color (error red), heaviest weight, AND a slightly
                    // larger fontSize since Monospace doesn't render different
                    // FontWeights very dramatically on Android Roboto Mono.
                    SpanStyle(
                        color = errorColor,
                        fontWeight = FontWeight.Black,
                        fontSize = SolveSizes.scrambleHighlightFontSize,
                    )
                } else {
                    SpanStyle(color = errorColor)
                }
                withStyle(style) { append(tok) }
            }
            anythingAppended = true
        }

        // 3. Upcoming tokens. The bold "next" highlight on the original
        // scramble is suppressed when a correction prefix is showing.
        var upcomingFirst = true
        for (idx in progress until tokens.size) {
            if (anythingAppended) {
                // Two spaces only the first time we cross from a previous
                // section; single space between upcoming tokens.
                append(if (upcomingFirst) "  " else " ")
            }
            val style = when {
                idx == progress && !hasCorrection ->
                    // Same color + weight + bigger size emphasis as the
                    // correction-mode case.
                    SpanStyle(
                        color = nextColor,
                        fontWeight = FontWeight.Black,
                        fontSize = SolveSizes.scrambleHighlightFontSize,
                    )
                else -> SpanStyle(color = upcomingColor)
            }
            withStyle(style) { append(tokens[idx]) }
            anythingAppended = true
            upcomingFirst = false
        }
    }
}

/**
 * Timer / status / countdown – chosen by phase.
 *
 * For SOLVED phase, the displayed time uses the effective time
 * (`durationMs + penaltyMs`, or "DNF" when DNF) from [lastSolveInfo]
 * if available. This lets the user see their time update live when
 * they tap +2 / DNF on the post-solve action row.
 */
@Composable
private fun TimerArea(
    phase: SolvePhase,
    elapsedMs: Long,
    inspectionMs: Long,
    inspectionRunning: Boolean,
    lastSolveInfo: LastSolveInfo?,
    anyMoveStartsNewSolve: Boolean,
    onMarkDnf: () -> Unit,
    onMarkPlus2: () -> Unit,
) {
    // Fixed timer-area height keeps the layout above (cube + scramble +
    // action row) stationary regardless of phase – the SOLVED state shows
    // extra content (Solved! label, penalty buttons, next-solve tip) that
    // would otherwise grow the column and push the cube around.
    Column(
        modifier = Modifier.fillMaxWidth().height(SolveSizes.timerAreaHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceAround,
    ) {
        when {
            inspectionRunning -> InspectionCountdown(inspectionMs)
            phase == SolvePhase.RUNNING ->
            {
                StatusText(stringResource(Res.string.solve_running))
                BigTimer(formatDuration(elapsedMs))
            }
            phase == SolvePhase.SOLVED -> {
                // Show DNF or effective time from the persisted record
                // when we have it; fall back to the running-clock value
                // otherwise (covers the brief window between phase=SOLVED
                // and finishSolve() committing).
                val display = when {
                    lastSolveInfo == null -> formatDuration(elapsedMs)
                    lastSolveInfo.isDnf -> "DNF"
                    lastSolveInfo.penaltyMs > 0 -> formatDuration(lastSolveInfo.effectiveMs) + "+"
                    else -> formatDuration(lastSolveInfo.effectiveMs)
                }
                BigTimer(display)
            }
            phase == SolvePhase.READY ->
                StatusText(stringResource(Res.string.solve_ready))
            phase == SolvePhase.SCRAMBLING ->
                StatusText(stringResource(Res.string.solve_scramble_prompt))
            else ->
                StatusText(stringResource(Res.string.solve_idle))
        }
        if (phase == SolvePhase.SOLVED) {
            Text(
                text = stringResource(Res.string.solve_solved),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Post-solve penalty action row. Only shown once the
            // solve has been committed (lastSolveInfo != null) – there's
            // a tiny window where phase=SOLVED but the row hasn't landed
            // yet, during which we don't show buttons.
            lastSolveInfo?.let {
                PostSolvePenaltyRow(
                    info = it,
                    onMarkDnf = onMarkDnf,
                    onMarkPlus2 = onMarkPlus2,
                )
            }
            NextSolveTip(anyMoveStartsNewSolve)
        }
    }
}

/**
 * Post-solve "+2 / DNF" action row. Visible only while we're in SOLVED
 * with a committed solve. Each button is a toggle:
 *   - tapping a non-highlighted button selects that penalty (and
 *     deselects the other if it was active);
 *   - tapping a highlighted button deselects it.
 * Mutually exclusive: at most one of {+2, DNF} can be active at any
 * given time. Highlight = `primary` fill; non-highlight = outlined.
 *
 * The buttons appear once a solve commits and disappear when a new
 * solve/scramble starts – handled by [SolveViewModel] clearing
 * `_lastSolveInfo` in `newScramble()` and `abortToIdle()`.
 */
@Composable
private fun PostSolvePenaltyRow(
    info: LastSolveInfo,
    onMarkDnf: () -> Unit,
    onMarkPlus2: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PenaltyButton(
            label = stringResource(Res.string.solve_plus2),
            highlighted = info.penaltyMs > 0L && !info.isDnf,
            onClick = onMarkPlus2,
        )
        PenaltyButton(
            label = stringResource(Res.string.solve_dnf),
            highlighted = info.isDnf,
            onClick = onMarkDnf,
        )
    }
}

/**
 * Small button for the penalty row. When [highlighted] is true the
 * button is filled with `primary`-on-`onPrimary` (the user's current
 * choice); when false it's outlined.
 */
@Composable
private fun PenaltyButton(label: String, highlighted: Boolean, onClick: () -> Unit) {
    if (highlighted) {
        Button(
            onClick = onClick,
            modifier = Modifier.heightIn(min = SolveSizes.actionButtonHeight),
            contentPadding = PaddingValues(
                horizontal = SolveSizes.actionButtonHorizontalPadding,
                vertical = 4.dp,
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(label, fontSize = SolveSizes.actionButtonFontSize, maxLines = 1,
                fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = SolveSizes.actionButtonHeight),
            contentPadding = PaddingValues(
                horizontal = SolveSizes.actionButtonHorizontalPadding,
                vertical = 4.dp,
            ),
        ) {
            Text(label, fontSize = SolveSizes.actionButtonFontSize, maxLines = 1)
        }
    }
}

/**
 * QoL hint shown after a solve, telling the user how to start the next
 * one. Which hint depends on the setting that decides it: with
 * "any turn starts a new solve" on there is no gesture to teach, so the
 * tip is a plain sentence; with it off the U U' gesture is spelled out,
 * its move substring highlighted in primary so the user reads it as an
 * actual cube-move suggestion rather than prose.
 *
 * Keeping the two in one composable (rather than branching at the call
 * site) keeps the "what does the app do after a solve" answer in a
 * single place, next to the setting that decides it.
 */
@Composable
private fun NextSolveTip(anyMoveStartsNewSolve: Boolean) {
    if (anyMoveStartsNewSolve) {
        Text(
            text = stringResource(Res.string.solve_tip_any_move),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
        )
        return
    }
    val prefix = stringResource(Res.string.solve_tip_uu_prefix)
    val suffix = stringResource(Res.string.solve_tip_uu_suffix)
    val highlight = MaterialTheme.colorScheme.primary
    val tipColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(prefix, suffix, highlight, tipColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = tipColor)) { append(prefix) }
            append(' ')
            withStyle(
                SpanStyle(color = highlight, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold),
            ) { append("U U'") }
            append(' ')
            withStyle(SpanStyle(color = tipColor)) { append(suffix) }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
    )
}

/** Numeric timer (running solve, finished solve). Big monospaced digits. */
@Composable
private fun BigTimer(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = SolveSizes.timerFontSize,
        maxLines = 1,
    )
}

/** Non-numeric prompt (Ready / Scramble the cube / –). */
@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = SolveSizes.statusFontSize,
        maxLines = 2,
    )
}

/**
 * 15→0 inspection countdown. Color tracks remaining time so the user feels
 * the urgency: white-ish first 7s, yellow at 8–11s, red at 12–15s. The cube
 * itself starts the timer on first turn (handled by the VM), so the count-
 * down doesn't need its own button.
 */
@Composable
private fun InspectionCountdown(remainingMs: Long) {
    val seconds = (remainingMs + 999) / 1000  // round up so "1s" shows during the last 1000ms
    val elapsed = (15_000 - remainingMs).coerceIn(0, 15_000)
    val color = when {
        elapsed >= 12_000 -> StatusColors.UrgencyRed
        elapsed >= 8_000 -> StatusColors.UrgencyAmber
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "${seconds}s",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = SolveSizes.timerFontSize,
        color = color,
        maxLines = 1,
    )
}

/**
 * Shared tinted background color for the Solve screen's two raised
 * containers (scramble card, stat grid). Lerps from `surface` toward
 * `surfaceVariant` at factor 0.45 to give a perceptible lift above the
 * page in dark mode without abandoning the surface hue. The factor was
 * 0.3 in the original implementation, but with the rebuilt surface
 * tonal ladder (see ColorSchemes.kt – `surface` and `background` no
 * longer collide on the same hex) 0.3 produced a color barely above
 * page brightness in dark mode and the container read as part of the
 * page; 0.45 lifts it clearly while staying recognisably "tinted
 * surface" rather than "full surfaceVariant".
 *
 * Used opaquely (vs `surfaceVariant.copy(alpha = 0.3f)`) so that
 * `Modifier.shadow` can render a real drop shadow behind the
 * container; with a semi-transparent fill the shadow shows through
 * the container itself.
 */
@Composable
private fun tintedContainerBackground(): Color = lerp(
    MaterialTheme.colorScheme.surface,
    MaterialTheme.colorScheme.surfaceVariant,
    0.45f,
)

@Composable
private fun StatGrid(
    history: List<SolveRow>,
    session: SolveSession,
    statRegistry: StatRegistry,
    modifier: Modifier = Modifier,
) {
    val items = remember(history, session) {
        statRegistry.all.mapNotNull { stat ->
            stat.compute(history, session)?.let { stat to it }
        }
    }
    // Pre-blend tinted surface – see the matching comment in ScrambleCard
    // for why we don't use surfaceVariant.copy(alpha = 0.3f) here when the
    // caller wraps us in Modifier.shadow.
    val tinted = tintedContainerBackground()
    Column(
        modifier = modifier
            .background(
                color = tinted,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            stringResource(Res.string.solve_quick_overview),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // 3-column grid of compact tiles instead of one tile per row.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items) { (stat, value) ->
                StatTile(label = stringResource(stat.label), value = value)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    // The outer StatGrid's container is a 0.45-lerp from surface
    // toward surfaceVariant (see [tintedContainerBackground]). The
    // tile fill needs to step distinctly off that container in BOTH
    // modes:
    //   - light: container ≈ #F1F1F8, tile must be DARKER → easy
    //   - dark:  container ≈ #1F2025, tile must be LIGHTER → easy
    //
    // surfaceContainerHighest – the previous choice – satisfies both
    // ordinally but the step is too small. `outlineVariant` is
    // technically a divider-role color, but its tonal value lands
    // exactly where we want a "strong-contrast tile" in this app's
    // scheme: #C3C4CC in light (clearly darker than the container)
    // and #44454D in dark (clearly lighter than the container). Using
    // it as a fill is unconventional but pragmatic – the alternative
    // is hardcoded per-mode hex values, which is worse for theme
    // consistency. The role's role-name semantics are bent here, not
    // its visual semantics.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * "New personal best!" celebration dialog. Shows the freshly-recorded
 * duration in mm:ss.cc, a celebratory message, and a single "Hooray!"
 * dismiss button – playful tone deliberately, this is the moment the
 * user has been chasing.
 *
 * The dialog is dismissable via the button, an outside-tap, or the system
 * back button. All three route through [onDismiss] which clears the VM's
 * pb-event flow back to null.
 */
@Composable
private fun PbDialog(durationMs: Long, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(Res.string.solve_pb_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column {
                // The new time, big and monospaced – same vocabulary as
                // the running timer so the user immediately recognises
                // "this is your time".
                Text(
                    text = formatDuration(durationMs),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.solve_pb_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            DialogButton(
                label = stringResource(Res.string.solve_pb_dismiss),
                onClick = onDismiss,
                emphasis = DialogButtonEmphasis.PRIMARY,
            )
        },
    )
}
