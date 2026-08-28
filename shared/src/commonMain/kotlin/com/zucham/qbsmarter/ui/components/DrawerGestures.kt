package com.zucham.qbsmarter.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Lets a region of the UI ask the app-wide navigation drawer to keep its
 * hands off the current gesture.
 *
 * ## Why this exists
 *
 * `ModalNavigationDrawer`'s `gesturesEnabled` is all-or-nothing and
 * applies to the whole content area, not just the screen edge: with it
 * on, a horizontal drag anywhere can open the drawer. That is fine on
 * every screen in this app except one - the Solve screen's 3D cube,
 * where a horizontal drag *is* the interaction. The previous fix was to
 * turn the drawer's open-gesture off globally, which traded one problem
 * for a smaller one: swipe-to-open stopped working everywhere, on
 * screens that had no conflict at all.
 *
 * This restores the gesture and scopes the exception to the cube.
 *
 * ## How the timing works
 *
 * The suppression is per *gesture*, not per screen: it latches on when a
 * finger goes down inside the guarded region and releases when it lifts.
 * The latch happens on [PointerEventPass.Initial] of the down event,
 * which is the earliest any node in the tree sees it, and crucially
 * before any drag has begun - a `draggable` only claims a gesture once
 * the touch slop is crossed, which takes at least one further pointer
 * event. By the time that event arrives, the recomposition triggered
 * here has already flipped `gesturesEnabled` off, so the drawer's drag
 * detection never starts.
 *
 * Nothing is consumed. The pointer events continue on to the Korender
 * surface underneath exactly as before, so cube rotation is untouched -
 * this observes the gesture, it doesn't take it.
 *
 * ## Nesting
 *
 * [DrawerGestureGuard] counts rather than flags, so overlapping or
 * re-entrant claims release cleanly. In practice there is one claimant,
 * but a counter costs nothing and removes a whole class of "who turned
 * it back on" bug.
 */
class DrawerGestureGuard {

    private var claims by mutableStateOf(0)

    /** True while at least one region is holding the drawer's gestures back. */
    val isSuppressed: Boolean get() = claims > 0

    fun claim() {
        claims++
    }

    fun release() {
        claims = (claims - 1).coerceAtLeast(0)
    }
}

/**
 * The guard owned by the enclosing [AppScaffold]. The default instance is
 * never suppressed, so a composable used outside the scaffold (a preview,
 * a test) behaves as if there were no drawer at all.
 */
val LocalDrawerGestureGuard = staticCompositionLocalOf { DrawerGestureGuard() }

/**
 * Hold the navigation drawer's swipe gestures back for as long as a
 * pointer is down inside this element's bounds.
 *
 * Apply it to the smallest region that actually conflicts - the point of
 * the guard is that the rest of the screen keeps working normally.
 */
@Composable
fun Modifier.suppressDrawerGesturesWhileTouched(): Modifier {
    val guard = LocalDrawerGestureGuard.current
    return pointerInput(guard) {
        awaitEachGesture {
            // requireUnconsumed = false: something below us (the cube's
            // own touch handling) may well have taken the event already,
            // and we are only watching.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.claim()
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    pressed = event.changes.any { it.pressed }
                }
            } finally {
                // Also covers cancellation, which is how this coroutine
                // ends if the element leaves the composition mid-drag.
                guard.release()
            }
        }
    }
}
