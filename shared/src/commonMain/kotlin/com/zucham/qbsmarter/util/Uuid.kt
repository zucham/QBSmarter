package com.zucham.qbsmarter.util

/**
 * Generate a random UUID-shaped string. Cube IDs and user IDs use this.
 *
 * Kotlin 2.0+ has `kotlin.uuid.Uuid` which we could use directly – but
 * keeping the abstraction lets stub platforms return a deterministic
 * value for tests if we ever want that.
 */
expect fun generateUuid(): String
