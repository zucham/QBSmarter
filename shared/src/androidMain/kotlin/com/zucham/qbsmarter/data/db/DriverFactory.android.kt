package com.zucham.qbsmarter.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.zucham.qbsmarter.db.QbsmarterDatabase

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = QbsmarterDatabase.Schema,
            context = context,
            name = "qbsmarter.db",
            callback = ForeignKeyCallback,
        )
}

/**
 * Turns on SQLite foreign-key enforcement for every connection.
 *
 * **This schema has always assumed it and never had it.** Every child
 * table — `cubes`, `solves`, `settings`, `cube_names`, and now
 * `solve_moves` and `solve_gyro` — declares `ON DELETE CASCADE`, and
 * `UserRepository.deleteProfile` is written on the premise that deleting
 * a profile row takes all of it with it. SQLite defaults foreign keys
 * *off*, `AndroidSqliteDriver` does not turn them on, and nothing else
 * did either, so none of those clauses have ever executed: deleting a
 * profile silently orphaned everything it owned, and `app_state`'s
 * `ON DELETE SET NULL` never fired (the repository survived that only
 * because it re-checks the active pointer afterwards anyway).
 *
 * The reconstruction tables are what made this worth fixing rather than
 * documenting. They are the largest rows in the database and they hang
 * off `solves(id)`, so without enforcement every solve deleted from the
 * History screen would leave its move and gyro blobs behind forever,
 * unreachable and uncountable. The alternative — deleting children by
 * hand at each call site — means the schema says one thing and the code
 * does another in six places instead of one.
 *
 * Enforcement is not retroactive: it validates what you write, not what
 * is already stored. The one-time sweep of what accumulated while this
 * was off is therefore a migration of its own, `2.sqm`.
 *
 * `onConfigure` is the correct hook and the only correct one: SQLite
 * refuses to change this pragma inside a transaction, and the framework
 * calls `onConfigure` before `onCreate`/`onUpgrade` and outside any
 * transaction of its own.
 */
private object ForeignKeyCallback : AndroidSqliteDriver.Callback(QbsmarterDatabase.Schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
