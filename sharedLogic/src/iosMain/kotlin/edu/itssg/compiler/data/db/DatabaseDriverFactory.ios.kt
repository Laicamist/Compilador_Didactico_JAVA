package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * Implementación iOS (Kotlin/Native) de [DatabaseDriverFactory].
 *
 * - SQLiter (dependencia `api` de native-driver) guarda la base en el contenedor de la app
 *   y crea/migra el esquema con `PRAGMA user_version`.
 * - `extendedConfig.foreignKeyConstraints = true` ejecuta `PRAGMA foreign_keys = ON`
 *   en cada conexión del pool.
 * - El enlace con libsqlite3 lo agrega el plugin de SQLDelight (`linkSqlite` es `true` por defecto).
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = CompilerDatabase.Schema,
            name = COMPILER_DATABASE_FILE_NAME,
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true)
                )
            }
        )
    }
}
