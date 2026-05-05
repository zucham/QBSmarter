package com.zucham.qbsmarter.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.zucham.qbsmarter.db.QbsmarterDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(QbsmarterDatabase.Schema, context, "qbsmarter.db")
}
