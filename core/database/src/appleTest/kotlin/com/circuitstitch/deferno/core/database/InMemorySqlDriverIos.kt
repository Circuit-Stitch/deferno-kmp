package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase

// native-driver's in-memory driver creates the schema itself; unencrypted, like the JVM path.
internal actual fun inMemorySqlDriver(): SqlDriver = inMemoryDriver(DefernoDatabase.Schema)
