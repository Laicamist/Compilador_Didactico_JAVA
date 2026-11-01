package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/**
 * Implementación Desktop (JVM: Windows, macOS, Linux) de [DatabaseDriverFactory].
 *
 * - La base vive en un directorio fijo de la app dentro de `user.home` (`.itssg_compiler_data`);
 *   ninguna parte de la ruta proviene de datos del usuario, así que no hay path traversal posible.
 * - `JdbcSqliteDriver(url, properties, schema)` (función oficial de sqlite-driver) crea el esquema o
 *   migra según `PRAGMA user_version`, dentro de una transacción: si la app se cierra a mitad de la
 *   creación no queda un esquema incompleto.
 * - Como el `.sq` usa `CREATE TABLE IF NOT EXISTS`, también adopta sin error las bases creadas por
 *   la versión anterior de esta fábrica (que no registraba `user_version`) y un `.db` vacío.
 * - `foreign_keys=true` se aplica a cada conexión que abre el driver (una por hilo).
 */
actual class DatabaseDriverFactory {

    actual fun createDriver(): SqlDriver {
        val dbFile = File(resolveAppDirectory(), COMPILER_DATABASE_FILE_NAME)
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            properties = Properties().apply { put("foreign_keys", "true") },
            schema = CompilerDatabase.Schema,
        )
    }

    private fun resolveAppDirectory(): File {
        val userHome = System.getProperty("user.home")
            ?.takeIf { it.isNotBlank() }
            ?: error("No se pudo determinar el directorio del usuario (user.home) para la base de datos.")
        val appDir = File(userHome, APP_DIRECTORY_NAME).canonicalFile
        if (!appDir.isDirectory && !appDir.mkdirs()) {
            error("No se pudo crear el directorio de datos: ${appDir.absolutePath}")
        }
        return appDir
    }

    private companion object {
        const val APP_DIRECTORY_NAME = ".itssg_compiler_data"
    }
}
