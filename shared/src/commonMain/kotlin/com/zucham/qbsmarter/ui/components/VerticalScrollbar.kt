package com.zucham.qbsmarter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Wrapping `Box` that draws a smooth, draggable scrollbar in a right-edge
 * gutter alongside its content. The caller is responsible for laying out
 * its scrollable content (typically a `LazyColumn`) inside the [content]
 * slot and applying the gutter padding the wrapper hands back via the
 * lambda parameter.
 *
 * **Smooth scroll tracking.** Thumb position is computed from
 * `firstVisibleItemIndex` AND `firstVisibleItemScrollOffset`. The first
 * one alone gives a segmented thumb that jumps as items cross the
 * viewport boundary; folding in the offset within the current item
 * gives sub-item resolution and the thumb glides continuously.
 *
 * Item heights aren't constant in general, but we estimate the average
 * from the currently-visible items. For uniform-height content this is
 * exact. For mixed-height content the thumb size and position can drift
 * slightly as the user scrolls into denser regions; in practice the
 * variation is invisible for the lists this app uses (uniform solve
 * rows in History; uniform Bluetooth-device buttons in Devices).
 *
 * **Draggable thumb.** The thumb itself is a real composable with
 * `Modifier.draggable`. Drag deltas are mapped back to a
 * `LazyListState.scrollBy` call via the inverse of the position
 * formula (see [ScrollbarThumb]). Tap-to-jump on the track is **not**
 * supported because the most common interactions on small lists are
 * wheel and direct drag; a track tap would be an extra surface to
 * maintain without much payoff.
 *
 * **Visibility.** The scrollbar is shown only when the content is
 * actually scrollable (more total items than fit on screen). Faded to
 * 40 % alpha when idle, full opacity while the user is scrolling or
 * actively dragging the thumb. Fades happen via a 300 ms tween so the
 * transition reads as deliberate rather than flickery.
 *
 * @param state the `LazyListState` driving the wrapped content.
 * @param modifier modifier applied to the wrapping `Box`.
 * @param scrollbarWidth thumb width.
 * @param gutterPadding extra space between the right edge of the
 *   content and the scrollbar. Total reserved end padding is
 *   `scrollbarWidth + gutterPadding`.
 * @param thumbColor base color for the thumb. Defaults to a
 *   half-opacity `onSurface`, which reads well on the app's default
 *   page surface. Override when the scrollbar sits on top of a darker
 *   container (e.g. the Devices screen's `surfaceContainer` panel)
 *   where the default would otherwise blend into the background.
 * @param content content slot. Receives the end padding the caller
 *   should apply to its scrolling surface (typically a `LazyColumn`'s
 *   `Modifier.padding(end = ...)`). Receiver is `BoxScope` so callers
 *   can place additional overlays via `Modifier.align` if needed.
 */
@Composable
fun VerticalScrollbarBox(
    state: LazyListState,
    modifier: Modifier = Modifier,
    scrollbarWidth: Dp = 6.dp,
    gutterPadding: Dp = 4.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    content: @Composable BoxScope.(endPadding: Dp) -> Unit,
) {
    val totalEndPadding = scrollbarWidth + gutterPadding

    Box(modifier = modifier) {
        // Caller draws their LazyColumn here, applying `totalEndPadding`
        // as right padding so items don't sit under the gutter where the
        // scrollbar tracks.
        content(totalEndPadding)

        // Show the scrollbar only when scrolling is possible.
        // derivedStateOf re-evaluates only when its inputs change, so
        // the !showScrollbar path is cheap on every recomposition.
        val showScrollbar by remember(state) {
            derivedStateOf {
                val info = state.layoutInfo
                info.totalItemsCount > info.visibleItemsInfo.size
            }
        }
        if (!showScrollbar) return@Box

        ScrollbarThumb(
            state = state,
            scrollbarWidth = scrollbarWidth,
            thumbColor = thumbColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(scrollbarWidth),
        )
    }
}

/**
 * The actual draggable thumb, drawn as a child of [VerticalScrollbarBox].
 * Calculates its own size and Y-offset every frame from the [state]'s
 * layout info, and translates user drags back into list scroll commands
 * via [LazyListState.scrollBy].
 */
@Composable
private fun ScrollbarThumb(
    state: LazyListState,
    scrollbarWidth: Dp,
    thumbColor: Color,
    modifier: Modifier = Modifier,
    minThumbHeight: Dp = 24.dp,
    fadedAlpha: Float = 0.4f,
    activeAlpha: Float = 1f,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // Thumb metrics, recomputed when layout info changes via derivedStateOf.
    // We bundle the four derived values together so they're always read
    // from the same layout-info snapshot – mixing values across snapshots
    // produces visible thumb jitter.
    val metrics by remember(state, density, minThumbHeight) {
        derivedStateOf {
            val info = state.layoutInfo
            val visibleItems = info.visibleItemsInfo
            if (visibleItems.isEmpty() || info.totalItemsCount == 0) {
                ThumbMetrics.Empty
            } else {
                val viewportHeight =
                    (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                val avgItemSize = visibleItems
                    .sumOf { it.size }
                    .toFloat() / visibleItems.size
                val itemSpacing = info.mainAxisItemSpacing.toFloat()
                val itemStride = avgItemSize + itemSpacing
                // Last item has no trailing spacing, so subtract one
                // spacing from the total.
                val totalContentHeight =
                    itemStride * info.totalItemsCount - itemSpacing

                // Defensive guard: if items somehow report zero size
                // (e.g. mid-layout-pass when the LazyColumn hasn't yet
                // measured anything), bail out so we don't produce
                // NaN/Infinity from the viewport-ratio division. The
                // showScrollbar predicate in the parent has already
                // ruled out totalItemsCount == 0, but a degenerate
                // zero-height item is still possible briefly.
                if (totalContentHeight <= 0f || viewportHeight <= 0f) {
                    return@derivedStateOf ThumbMetrics.Empty
                }

                val scrollOffsetPx =
                    state.firstVisibleItemIndex * itemStride +
                            state.firstVisibleItemScrollOffset.toFloat()

                val minThumbPx = with(density) { minThumbHeight.toPx() }
                val thumbHeight = (
                    viewportHeight * (viewportHeight / totalContentHeight)
                ).coerceAtLeast(minThumbPx).coerceAtMost(viewportHeight)
                val travel = (viewportHeight - thumbHeight).coerceAtLeast(0f)
                val scrollableHeight =
                    (totalContentHeight - viewportHeight).coerceAtLeast(1f)
                val thumbY = (
                    travel * (scrollOffsetPx / scrollableHeight)
                ).coerceIn(0f, travel)

                ThumbMetrics(
                    thumbHeightPx = thumbHeight,
                    thumbYPx = thumbY,
                    totalContentHeightPx = totalContentHeight,
                    viewportHeightPx = viewportHeight,
                )
            }
        }
    }

    // Track interactions for the thumb-active vs idle alpha. The list
    // can be scrolling because the user dragged the thumb OR because
    // the user flung the list directly OR because of a programmatic
    // animateScrollToItem – any of these should keep the thumb visible.
    val draggingThumb = remember { mutableStateOf(false) }
    val isActive = state.isScrollInProgress || draggingThumb.value
    val targetAlpha = if (isActive) activeAlpha else fadedAlpha
    val alpha by animateFloatAsState(targetAlpha, tween(durationMillis = 300))

    val resolvedThumbColor = thumbColor

    val draggableState = rememberDraggableState { dragDeltaPx ->
        val m = metrics
        if (m === ThumbMetrics.Empty || m.viewportHeightPx <= 0f) {
            return@rememberDraggableState
        }
        // Inverse of the position formula: a thumb drag of dragDeltaPx
        // corresponds to a list scroll of
        //   scrollDelta = dragDeltaPx × scrollableHeight / travel
        // where scrollableHeight = totalContentHeight - viewportHeight
        // and  travel           = viewportHeight    - thumbHeight.
        // The naive simplification "scale by totalContentHeight /
        // viewportHeight" breaks down near the minThumbHeight cap
        // (travel and thumbHeight diverge from the strictly-proportional
        // formula). Use the exact form so dragging a clamped thumb
        // still scrolls the full content.
        val travel = (m.viewportHeightPx - m.thumbHeightPx).coerceAtLeast(1f)
        val scrollableHeight =
            (m.totalContentHeightPx - m.viewportHeightPx).coerceAtLeast(1f)
        val scrollDelta = dragDeltaPx * (scrollableHeight / travel)
        coroutineScope.launch { state.scrollBy(scrollDelta) }
    }

    // No track background – tap-on-track jumping isn't implemented and a
    // visible empty track would just add visual noise. The thumb is
    // positioned via Modifier.offset so it tracks layout updates frame
    // by frame.
    Box(modifier = modifier) {
        if (metrics === ThumbMetrics.Empty) return@Box
        val thumbHeightDp = with(density) { metrics.thumbHeightPx.toDp() }
        val thumbOffsetDp = with(density) { metrics.thumbYPx.toDp() }
        Box(
            modifier = Modifier
                .offset(y = thumbOffsetDp)
                .width(scrollbarWidth)
                .height(thumbHeightDp)
                .clip(RoundedCornerShape(scrollbarWidth / 2))
                .background(resolvedThumbColor.copy(alpha = resolvedThumbColor.alpha * alpha))
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { draggingThumb.value = true },
                    onDragStopped = { draggingThumb.value = false },
                ),
        )
    }
}

/**
 * Cached layout-info derived metrics. Bundled so the thumb size and
 * position are always read from the same layout snapshot; reading
 * `state.layoutInfo` separately for each value lets layout passes
 * race in between, producing visible jitter.
 */
private data class ThumbMetrics(
    val thumbHeightPx: Float,
    val thumbYPx: Float,
    val totalContentHeightPx: Float,
    val viewportHeightPx: Float,
) {
    companion object {
        /** Sentinel for "no items / not enough info to compute". */
        val Empty = ThumbMetrics(0f, 0f, 0f, 0f)
    }
}
