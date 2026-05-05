package com.zucham.qbsmarter.util

/**
 * Wall-clock time in milliseconds since the Unix epoch.
 *
 * We keep this as a tiny `expect` because:
 *   • `kotlinx-datetime` is great for date manipulation but its
 *     `Clock.System.now().toEpochMilliseconds()` is overkill for hot paths.
 *   • `kotlin.time.Clock` (3.x) is still experimental enough that we don't
 *     want to commit the whole codebase to it yet.
 */
expect fun currentTimeMillis(): Long
