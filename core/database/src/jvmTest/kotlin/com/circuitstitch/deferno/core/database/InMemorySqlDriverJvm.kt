package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase

// Distinct file name from the commonTest expect (which also holds the concrete
// inMemoryDefernoDatabase) so the two don't collide on the same `...Kt` JVM facade class.
internal actual fun inMemorySqlDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }
