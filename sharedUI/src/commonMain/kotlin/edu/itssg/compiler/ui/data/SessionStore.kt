package edu.itssg.compiler.ui.data

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.data.db.CompilerSessionRepository
import edu.itssg.compiler.ui.state.CompilerPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Resumen de una sesión guardada para la pantalla de Historial.
 *
 * @property hasErrors Valor guardado en la base.
 * @property originalInstructionCount Cuádruplos del código intermedio original.
 * @property optimizedInstructionCount Cuádruplos después de optimizar.
 */
data class SavedSessionSummary(
    val id: Long,
    val title: String,
    val sourceCode: String,
    val createdAtMillis: Long,
    val hasErrors: Boolean,
    val lexicalErrorCount: Int,
    val syntacticErrorCount: Int,
    val semanticErrorCount: Int,
    val originalInstructionCount: Int,
    val optimizedInstructionCount: Int
) {
    val totalErrorCount: Int
        get() = lexicalErrorCount + syntacticErrorCount + semanticErrorCount

    /** Un solo renglón, para la tarjeta del historial. */
    val snippet: String =
        sourceCode.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(SNIPPET_MAX_LINES)
            .joinToString("  ")
            .take(SNIPPET_MAX_CHARS)

    /** Primera fase con errores (para los puntos de fase de la tarjeta); `null` si compiló sin errores. */
    val firstFailedPhase: CompilerPhase?
        get() = when {
            lexicalErrorCount > 0 -> CompilerPhase.LEXICAL
            syntacticErrorCount > 0 -> CompilerPhase.SYNTACTIC
            semanticErrorCount > 0 -> CompilerPhase.SEMANTIC
            hasErrors -> CompilerPhase.LEXICAL // abortada por límites de seguridad
            else -> null
        }

    private companion object {
        const val SNIPPET_MAX_LINES = 6
        const val SNIPPET_MAX_CHARS = 160
    }
}

/** Historial de sesiones de compilación, independiente de cómo se guarde. */
interface SessionStore {
    /** Aumenta cada vez que el historial cambia (guardar o eliminar); el Historial lo observa para recargarse. */
    val revision: StateFlow<Long>

    /** @return id de la sesión creada. */
    suspend fun save(title: String, timestampMillis: Long, report: CompilationReport): Long

    /** Sesiones de la más reciente a la más antigua. */
    suspend fun loadSummaries(): List<SavedSessionSummary>

    /** @return `true` si la sesión existía y se eliminó. */
    suspend fun delete(sessionId: Long): Boolean

    /** Borra todo el historial (Ajustes). @return cuántas sesiones se eliminaron. */
    suspend fun deleteAll(): Int
}

/** [SessionStore] respaldado por el [CompilerSessionRepository] de SQLDelight. */
class RepositorySessionStore(
    private val repository: CompilerSessionRepository
) : SessionStore {

    private val revisionState = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = revisionState.asStateFlow()

    override suspend fun save(title: String, timestampMillis: Long, report: CompilationReport): Long {
        val id = repository.saveCompilationReport(title = title, timestampMillis = timestampMillis, report = report)
        revisionState.update { it + 1 }
        return id
    }

    /** Una sola consulta con los conteos ya calculados por SQLite (ver `selectSessionSummaries`). */
    override suspend fun loadSummaries(): List<SavedSessionSummary> =
        repository.getSessionSummaries().map { row ->
            SavedSessionSummary(
                id = row.id,
                title = row.title,
                sourceCode = row.sourceCode,
                createdAtMillis = row.createdAtMillis,
                hasErrors = row.hasErrors,
                lexicalErrorCount = row.lexicalErrorCount,
                syntacticErrorCount = row.syntacticErrorCount,
                semanticErrorCount = row.semanticErrorCount,
                originalInstructionCount = row.originalQuadrupleCount,
                optimizedInstructionCount = row.optimizedQuadrupleCount
            )
        }

    override suspend fun delete(sessionId: Long): Boolean {
        val deleted = repository.deleteSession(sessionId)
        if (deleted) revisionState.update { it + 1 }
        return deleted
    }

    override suspend fun deleteAll(): Int {
        val deleted = repository.deleteAllSessions().toInt()
        revisionState.update { it + 1 }
        return deleted
    }
}
