package edu.itssg.compiler.ui.data

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.ir.IrFormatKind
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

    override suspend fun loadSummaries(): List<SavedSessionSummary> =
        repository.getAllSavedSessions().map { session ->
            val errorTypes = repository.getErrorsForSession(session.id).map { it.error_type }
            SavedSessionSummary(
                id = session.id,
                title = session.title,
                sourceCode = session.source_code,
                createdAtMillis = session.created_at,
                hasErrors = session.has_errors != 0L,
                lexicalErrorCount = errorTypes.count { it == ErrorPhaseType.LEXICAL.displayName },
                syntacticErrorCount = errorTypes.count { it == ErrorPhaseType.SYNTACTIC.displayName },
                semanticErrorCount = errorTypes.count { it == ErrorPhaseType.SEMANTIC.displayName },
                originalInstructionCount = repository
                    .getIntermediateCodeForSession(session.id, IrFormatKind.QUADRUPLE, isOptimized = false).size,
                optimizedInstructionCount = repository
                    .getIntermediateCodeForSession(session.id, IrFormatKind.QUADRUPLE, isOptimized = true).size
            )
        }

    override suspend fun delete(sessionId: Long): Boolean {
        val deleted = repository.deleteSession(sessionId)
        if (deleted) revisionState.update { it + 1 }
        return deleted
    }
}
