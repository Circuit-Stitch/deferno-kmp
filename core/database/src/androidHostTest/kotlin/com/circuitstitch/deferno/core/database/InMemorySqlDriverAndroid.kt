package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase

// Android unit tests run on the JVM host (no device), so the in-memory driver is the same
// JdbcSqliteDriver the JVM target uses — the on-device AndroidSqliteDriver needs a Context.
internal actual fun inMemorySqlDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }
