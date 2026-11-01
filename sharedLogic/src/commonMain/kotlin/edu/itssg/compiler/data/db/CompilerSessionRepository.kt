package edu.itssg.compiler.data.db

import app.cash.sqldelight.db.SqlDriver
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.ir.IntermediateCodeBundle
import edu.itssg.compiler.core.ir.IrFormatKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repositorio tipado y transaccional respaldado por SQLDelight para almacenar y consultar
 * sesiones de compilación, Tabla de Símbolos, Tabla de Errores y Código Intermedio.
 *
 * Seguridad y rendimiento:
 * - Cero concatenación de SQL: todas las consultas son las precompiladas de `CompilerDatabase.sq`.
 * - La base se abre de forma perezosa en el primer uso, que siempre ocurre dentro de [dispatcher]:
 *   construir el repositorio desde la UI no hace E/S de disco en el hilo principal.
 * - Cada guardado es una transacción atómica: o se guarda la sesión completa o nada.
 *
 * @param dispatcher Despachador para la E/S de base de datos. En Android/Desktop conviene
 *   pasar `Dispatchers.IO` desde el código de plataforma; por defecto `Dispatchers.Default`.
 */
class CompilerSessionRepository(
    driverFactory: DatabaseDriverFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val driver: SqlDriver by lazy { driverFactory.createDriver() }
    private val database: CompilerDatabase by lazy { CompilerDatabase(driver) }
    private val queries: CompilerDatabaseQueries get() = database.compilerDatabaseQueries

    /**
     * Guarda de forma atómica una sesión completa de compilación junto con su Tabla de Símbolos,
     * Tabla de Errores y sus 6 variantes de Código Intermedio (Original y Optimizado).
     *
     * @return id de la sesión creada.
     */
    suspend fun saveCompilationReport(
        title: String,
        timestampMillis: Long,
        report: CompilationReport
    ): Long = withContext(dispatcher) {
        database.transactionWithResult {
            val safeTitle = title.trim().ifEmpty { "Sesión sin título" }.take(MAX_TITLE_LENGTH)
            queries.insertSession(
                title = safeTitle,
                source_code = report.sourceCode,
                created_at = timestampMillis,
                has_errors = if (report.hasErrors) 1L else 0L
            )
            // Misma transacción ⇒ misma conexión ⇒ last_insert_rowid() es el de esta sesión.
            val sessionId = queries.lastInsertedSessionId().executeAsOne()

            for (symbol in report.symbolEntries) {
                queries.insertSymbolEntry(
                    session_id = sessionId,
                    symbol_index = symbol.symbolId.toLong(),
                    lexeme = symbol.lexeme,
                    token_code = symbol.tokenCategory.mnemonic,
                    data_type = symbol.dataType ?: symbol.role.label,
                    scope = symbol.scope,
                    line_number = symbol.firstSeen.line.toLong(),
                    column_number = symbol.firstSeen.column.toLong()
                )
            }

            for (err in report.errors) {
                queries.insertErrorEntry(
                    session_id = sessionId,
                    error_type = err.type.displayName,
                    lexeme = err.offendingLexeme,
                    line_number = err.line.toLong(),
                    column_number = err.column.toLong(),
                    justification = err.justification
                )
            }

            persistIrBundle(sessionId = sessionId, isOptimized = 0L, bundle = report.intermediateCode)
            persistIrBundle(sessionId = sessionId, isOptimized = 1L, bundle = report.optimizationResult.optimizedBundle)

            sessionId
        }
    }

    suspend fun getAllSavedSessions(): List<CompilationSession> = withContext(dispatcher) {
        queries.selectAllSessions().executeAsList()
    }

    suspend fun getSymbolsForSession(sessionId: Long): List<SymbolTableEntryEntity> = withContext(dispatcher) {
        queries.selectSymbolsBySession(sessionId).executeAsList()
    }

    suspend fun getErrorsForSession(sessionId: Long): List<ErrorTableEntryEntity> = withContext(dispatcher) {
        queries.selectErrorsBySession(sessionId).executeAsList()
    }

    suspend fun getIntermediateCodeForSession(
        sessionId: Long,
        kind: IrFormatKind,
        isOptimized: Boolean
    ): List<IntermediateCodeEntity> = withContext(dispatcher) {
        queries.selectIntermediateCodeBySession(
            session_id = sessionId,
            representation_kind = kind.name,
            is_optimized = if (isOptimized) 1L else 0L
        ).executeAsList()
    }

    /**
     * Elimina la sesión; sus símbolos, errores y código intermedio se borran en cascada
     * (requiere `PRAGMA foreign_keys = ON`, que activa [DatabaseDriverFactory]).
     *
     * @return `true` si existía y se eliminó.
     */
    suspend fun deleteSession(sessionId: Long): Boolean = withContext(dispatcher) {
        queries.deleteSessionById(sessionId).value > 0L
    }

    /** Cierra el driver (al salir de la app Desktop o en pruebas). */
    fun close() {
        driver.close()
    }

    private fun persistIrBundle(
        sessionId: Long,
        isOptimized: Long,
        bundle: IntermediateCodeBundle
    ) {
        for (t in bundle.triples) {
            queries.insertIntermediateCode(
                session_id = sessionId,
                representation_kind = IrFormatKind.TRIPLE.name,
                is_optimized = isOptimized,
                step_label = t.stepNumber.toString(),
                val1 = t.val1,
                operator_ = t.operator,
                val2 = t.val2,
                result_value = t.annotation
            )
        }
        for (q in bundle.quadruples) {
            queries.insertIntermediateCode(
                session_id = sessionId,
                representation_kind = IrFormatKind.QUADRUPLE.name,
                is_optimized = isOptimized,
                step_label = q.stepNumber.toString(),
                val1 = q.val1,
                operator_ = q.operator,
                val2 = q.val2,
                result_value = q.result
            )
        }
        for (tac in bundle.threeAddress) {
            queries.insertIntermediateCode(
                session_id = sessionId,
                representation_kind = IrFormatKind.THREE_ADDRESS.name,
                is_optimized = isOptimized,
                step_label = tac.targetLabel,
                val1 = tac.val1.orEmpty(),
                operator_ = tac.operator.orEmpty(),
                val2 = tac.val2.orEmpty(),
                result_value = tac.freeNote
            )
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 120
    }
}