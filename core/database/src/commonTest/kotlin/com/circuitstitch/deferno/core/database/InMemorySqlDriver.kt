package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.db.SqlDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase

/**
 * A fresh, unencrypted, in-memory [SqlDriver] with the schema created — the ADR-0006 JVM-fast DB
 * test path (issue #21: "in-memory driver enables fast commonTest DB tests"). Encryption changes
 * only how bytes hit disk, never the SQL, so the tests run against the same schema/queries that
 * ship. JVM + Android-host use `JdbcSqliteDriver(IN_MEMORY)`; iOS uses the native in-memory driver.
 */
internal expect fun inMemorySqlDriver(): SqlDriver

/** A fresh in-memory [DefernoDatabase] for tests. */
internal fun inMemoryDefernoDatabase(): DefernoDatabase = DefernoDatabase(inMemorySqlDriver())
