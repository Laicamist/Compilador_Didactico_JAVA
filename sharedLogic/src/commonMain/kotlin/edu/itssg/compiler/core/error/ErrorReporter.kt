package edu.itssg.compiler.core.error

import edu.itssg.compiler.core.lexer.LexicalAnomaly
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Gestor centralizado y seguro de la Tabla de Errores del compilador.
 *
 * Recolecta de forma ordenada todos los errores Léxicos, Sintácticos y Semánticos
 * durante el ciclo de compilación sin lanzar excepciones no controladas,
 * aplicando límites de cuota ([CompilerSecurityConfig.maxErrorCount]) para evitar
 * desbordamiento de memoria (OOM) ante archivos masivamente corruptos.
 */
class ErrorReporter(
    private val config: CompilerSecurityConfig = CompilerSecurityConfig()
) {
    private val errors = ArrayList<CompilerError>()
    private var quotaReached: Boolean = false

    /**
     * Indica si se ha registrado al menos un error en cualquiera de las fases.
     */
    val hasErrors: Boolean
        get() = errors.isNotEmpty()

    /**
     * Indica si se alcanzó el tope de seguridad de errores permitidos por sesión.
     */
    val isQuotaExceeded: Boolean
        get() = quotaReached

    /**
     * Cantidad total de errores registrados hasta el momento.
     */
    val errorCount: Int
        get() = errors.size

    /**
     * Importa todas las anomalías detectadas por el [edu.itssg.compiler.core.lexer.Lexer]
     * hacia la Tabla de Errores centralizada bajo la categoría `Léxico`.
     */
    fun ingestLexicalAnomalies(anomalies: List<LexicalAnomaly>) {
        for (anomaly in anomalies) {
            reportLexicalError(
                lexeme = anomaly.lexeme,
                justification = anomaly.justification,
                span = anomaly.span
            )
        }
    }

    /**
     * Registra un error de tipo **Léxico**.
     */
    fun reportLexicalError(
        lexeme: String,
        justification: String,
        span: SourceSpan
    ): Boolean = appendError(
        type = ErrorPhaseType.LEXICAL,
        justification = justification,
        offendingLexeme = lexeme,
        ruleName = null,
        span = span
    )

    /**
     * Registra un error de tipo **Sintáctico** vinculado a una regla de la GLC v7.
     */
    fun reportSyntacticError(
        ruleName: String,
        offendingLexeme: String,
        justification: String,
        span: SourceSpan
    ): Boolean = appendError(
        type = ErrorPhaseType.SYNTACTIC,
        justification = justification,
        offendingLexeme = offendingLexeme,
        ruleName = ruleName,
        span = span
    )

    /**
     * Registra un error de tipo **Semántico** durante la validación de tipos, constructores o ámbitos.
     */
    fun reportSemanticError(
        offendingLexeme: String,
        justification: String,
        span: SourceSpan,
        ruleName: String? = null
    ): Boolean = appendError(
        type = ErrorPhaseType.SEMANTIC,
        justification = justification,
        offendingLexeme = offendingLexeme,
        ruleName = ruleName,
        span = span
    )

    /**
     * Devuelve una copia inmutable de la Tabla de Errores completa, ordenada por aparición.
     */
    fun getAllErrors(): List<CompilerError> = errors.toList()

    /**
     * Filtra los errores por fase específica (`LEXICAL`, `SYNTACTIC`, `SEMANTIC`).
     */
    fun getErrorsByPhase(phase: ErrorPhaseType): List<CompilerError> =
        errors.filter { it.type == phase }

    /**
     * Limpia el historial de errores para iniciar una nueva sesión de análisis.
     */
    fun clear() {
        errors.clear()
        quotaReached = false
    }

    private fun appendError(
        type: ErrorPhaseType,
        justification: String,
        offendingLexeme: String,
        ruleName: String?,
        span: SourceSpan
    ): Boolean {
        if (errors.size >= config.maxErrorCount) {
            quotaReached = true
            return false
        }
        // Se normaliza igual que al almacenar (trim) para que el filtro de duplicados no falle por espacios.
        val normalizedJustification = justification.trim()

        // Evitar duplicados exactos en la misma posición y fase (común durante recuperación en Modo Pánico)
        val isDuplicate = errors.any { existing ->
            existing.type == type &&
                    existing.span.start.offset == span.start.offset &&
                    existing.justification == normalizedJustification
        }
        if (isDuplicate) return false

        val sanitizedLexeme = offendingLexeme.take(MAX_DISPLAY_LEXEME_LENGTH)
        errors.add(
            CompilerError(
                index = errors.size + 1,
                type = type,
                justification = normalizedJustification,
                offendingLexeme = sanitizedLexeme,
                ruleName = ruleName,
                span = span
            )
        )
        return true
    }

    companion object {
        private const val MAX_DISPLAY_LEXEME_LENGTH: Int = 120

        /**
         * Utilidad para construir un [SourceSpan] puntual cuando un error ocurre en Fin de Archivo (EOF).
         */
        fun pointSpan(position: SourcePosition): SourceSpan =
            SourceSpan(start = position, end = position)
    }
}