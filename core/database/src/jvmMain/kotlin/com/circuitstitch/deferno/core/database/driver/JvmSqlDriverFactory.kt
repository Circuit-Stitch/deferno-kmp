package com.circuitstitch.deferno.core.database.driver

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.SqlDriverFactory
import com.circuitstitch.deferno.core.database.databaseFileName
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.AccountId
import java.util.Properties

/**
 * JVM/desktop production driver (issue #21): a file-backed [JdbcSqliteDriver] under [databasesDir],
 * one file per Account ([databaseFileName]). Passing `DefernoDatabase.Schema` to the constructor
 * lets the driver create/migrate by `user_version` automatically.
 *
 * v1 desktop posture (ADR-0009): the bearer token lives in the OS keychain and the DB file relies
 * on OS-level disk protection — at-rest SQLCipher on the JVM needs a bespoke JDBC build and is a
 * tracked follow-up; the Android/iOS clients are encrypted today. Not on the headless coverage gate
 * (it touches the filesystem; excluded in `CoverageConfig`).
 */
class JvmSqlDriverFactory(
    private val databasesDir: String,
    private val account: AccountId,
) : SqlDriverFactory {
    override fun create(): SqlDriver =
        JdbcSqliteDriver(
            url = "jdbc:sqlite:$databasesDir/${databaseFileName(account)}",
            properties = Properties(),
            schema = DefernoDatabase.Schema,
        )
}
