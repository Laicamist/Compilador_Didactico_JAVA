package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver

/** Nombre único del archivo SQLite en las tres plataformas. */
internal const val COMPILER_DATABASE_FILE_NAME: String = "itssg_compiler.db"

//Fábrica multiplataforma (`expect`/`actual`) del [SqlDriver] de SQLDelight.

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}