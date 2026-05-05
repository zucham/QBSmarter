package com.zucham.qbsmarter.util

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
actual fun generateUuid(): String = java.util.UUID.randomUUID().toString()
