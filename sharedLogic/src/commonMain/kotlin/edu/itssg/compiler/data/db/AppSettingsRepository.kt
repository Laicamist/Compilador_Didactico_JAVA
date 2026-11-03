package edu.itssg.compiler.data.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ajustes de la app guardados como pares clave/valor en la tabla `AppSetting`.
 *
 * El repositorio no interpreta los valores: la capa de UI decide las claves y cómo convertirlas
 * (ver `AppSettingsCodec` en sharedUI). Todas las consultas son las precompiladas de `CompilerDatabase.sq`.
 *
 * @param dispatcher Despachador para la E/S de base de datos (en Android/Desktop, `Dispatchers.IO`).
 */
class AppSettingsRepository(
    private val provider: CompilerDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val queries: CompilerDatabaseQueries get() = provider.database.compilerDatabaseQueries

    /** Todos los ajustes guardados (vacío la primera vez que se abre la app). */
    suspend fun loadAll(): Map<String, String> = withContext(dispatcher) {
        queries.selectAllSettings().executeAsList().associate { it.setting_key to it.setting_value }
    }

    /** Guarda o reemplaza cada par en una sola transacción: o se guardan todos o ninguno. */
    suspend fun saveAll(values: Map<String, String>) = withContext(dispatcher) {
        if (values.isEmpty()) return@withContext
        provider.database.transaction {
            for ((key, value) in values) {
                require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Clave de ajuste inválida: '$key'" }
                queries.upsertSetting(setting_key = key, setting_value = value.take(MAX_VALUE_LENGTH))
            }
        }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 256
    }
}
