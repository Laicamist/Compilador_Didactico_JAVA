package edu.itssg.compiler.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Implementación Android de [DatabaseDriverFactory].
 *
 * - La base vive en el sandbox privado de la app (`Context.getDatabasePath`).
 * - `SupportSQLiteOpenHelper` crea/migra el esquema con `PRAGMA user_version`.
 * - `onConfigure` se ejecuta en CADA apertura de conexión, antes de onCreate/onUpgrade/onOpen,
 *   y fuera de transacción: es el único lugar donde Android permite activar las FOREIGN KEY.
 */
actual class DatabaseDriverFactory(
    private val context: Context
) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = CompilerDatabase.Schema,
            context = context.applicationContext,
            name = COMPILER_DATABASE_FILE_NAME,
            callback = object : AndroidSqliteDriver.Callback(CompilerDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                }
            }
        )
    }
}