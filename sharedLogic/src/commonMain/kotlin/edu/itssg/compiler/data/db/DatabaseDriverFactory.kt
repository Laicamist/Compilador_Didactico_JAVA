package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver

/** Nombre único del archivo SQLite en las tres plataformas. */
internal const val COMPILER_DATABASE_FILE_NAME: String = "itssg_compiler.db"

/**
 * Fábrica multiplataforma (`expect`/`actual`) del [SqlDriver] de SQLDelight.
 *
 * Contrato que cumple cada `actual` (Android, iOS y Desktop):
 * 1. Crea el esquema en la primera apertura y registra su versión (`PRAGMA user_version`),
 *    para que futuras migraciones (`2.sqm`, `3.sqm`, ...) se apliquen automáticamente.
 * 2. Activa `PRAGMA foreign_keys = ON` en CADA conexión: sin esto SQLite ignora las
 *    FOREIGN KEY y `deleteSessionById` dejaría filas huérfanas en las tablas hijas.
 *
 * Construcción por plataforma (código de plataforma, no de commonMain):
 * - Android: `DatabaseDriverFactory(context)`
 * - iOS y Desktop: `DatabaseDriverFactory()`
 */
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
