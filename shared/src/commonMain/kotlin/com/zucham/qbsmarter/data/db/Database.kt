package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.db.SqlDriver
import com.zucham.qbsmarter.db.QbsmarterDatabase

/** Platform driver factory. Android = AndroidSqliteDriver. */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** Open the database. Schema is applied/migrated automatically by SQLDelight. */
fun createDatabase(factory: DriverFactory): QbsmarterDatabase =
    QbsmarterDatabase(factory.createDriver())
