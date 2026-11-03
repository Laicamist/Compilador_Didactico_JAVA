package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Una sola conexión a `itssg_compiler.db` compartida por todos los repositorios
 * ([CompilerSessionRepository] y [AppSettingsRepository]).
 *
 * - La base se abre de forma perezosa en el primer uso; los repositorios siempre la usan dentro de su
 *   despachador de E/S, así que crear el proveedor desde la UI no toca el disco.
 * - Al abrirla, cada fábrica crea el esquema o aplica las migraciones pendientes (`1.sqm`, ...) según
 *   `PRAGMA user_version`.
 *
 * @param createDriver Crea el [SqlDriver]; en la app es `DatabaseDriverFactory.createDriver`, en pruebas
 *   puede ser un driver sobre un archivo temporal.
 */
class CompilerDatabaseProvider(createDriver: () -> SqlDriver) {

    constructor(driverFactory: DatabaseDriverFactory) : this(driverFactory::createDriver)

    private val driverDelegate = lazy(createDriver)

    val database: CompilerDatabase by lazy { CompilerDatabase(driverDelegate.value) }

    /** Cierra la conexión si llegó a abrirse. */
    fun close() {
        if (driverDelegate.isInitialized()) driverDelegate.value.close()
    }
}
