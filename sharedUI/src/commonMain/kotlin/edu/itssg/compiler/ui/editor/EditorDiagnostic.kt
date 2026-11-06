package edu.itssg.compiler.ui.editor

import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.lexer.LexicalAnomaly

/**
 * Error que el editor marca en el código (subrayado ondulado, ícono en el número de línea y globo con la
 * justificación).
 *
 * @property span Rango en el texto del editor, ya limitado a su longitud. Puede estar vacío (p. ej., "se esperaba
 *   LLC y se encuentra EOF"); el editor lo dibuja como una marca corta en esa posición.
 * @property line Línea (base 1) según el Analizador Léxico, la misma de la Tabla de Errores.
 * @property number Número del error en la Tabla de Errores, o `null` si es un error léxico en vivo (sin compilar).
 */
data class EditorDiagnostic(
    val span: TextSpan,
    val type: ErrorPhaseType,
    val line: Int,
    val column: Int,
    val lexeme: String,
    val message: String,
    val rule: String? = null,
    val number: Int? = null
) {
    /** `true` si [offset] cae sobre el error (un error sin ancho cuenta en su posición). */
    fun covers(offset: Int): Boolean =
        if (span.length == 0) offset == span.start else offset in span.start until span.end
}

internal object EditorDiagnostics {

    /** Errores de la última compilación (solo cuando el reporte corresponde al texto del editor). */
    fun fromErrors(errors: List<CompilerError>, textLength: Int): List<EditorDiagnostic> = errors.map { error ->
        EditorDiagnostic(
            span = clampSpan(error.span.start.offset, error.span.end.offset, textLength),
            type = error.type,
            line = error.line,
            column = error.column,
            lexeme = error.offendingLexeme,
            message = error.justification,
            rule = error.ruleName,
            number = error.index
        )
    }

    /** Errores léxicos en vivo del resaltado (mientras se escribe, antes de compilar). */
    fun fromAnomalies(anomalies: List<LexicalAnomaly>, textLength: Int): List<EditorDiagnostic> =
        anomalies.map { anomaly ->
            EditorDiagnostic(
                span = clampSpan(anomaly.span.start.offset, anomaly.span.end.offset, textLength),
                type = ErrorPhaseType.LEXICAL,
                line = anomaly.span.start.line,
                column = anomaly.span.start.column,
                lexeme = anomaly.lexeme,
                message = anomaly.justification
            )
        }

    /** Errores agrupados por línea (para los íconos del margen). */
    fun byLine(diagnostics: List<EditorDiagnostic>): Map<Int, List<EditorDiagnostic>> =
        diagnostics.groupBy { it.line }

    /** Primer error que cubre [offset]. */
    fun at(diagnostics: List<EditorDiagnostic>, offset: Int): EditorDiagnostic? =
        diagnostics.firstOrNull { it.covers(offset) }

    private fun clampSpan(start: Int, end: Int, textLength: Int): TextSpan {
        val safeStart = start.coerceIn(0, textLength)
        val safeEnd = end.coerceIn(safeStart, textLength)
        return TextSpan(safeStart, safeEnd)
    }
}
