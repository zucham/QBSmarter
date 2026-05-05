package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.db.SqlDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        throw NotImplementedError("TODO: jvmMain DriverFactory")
}
