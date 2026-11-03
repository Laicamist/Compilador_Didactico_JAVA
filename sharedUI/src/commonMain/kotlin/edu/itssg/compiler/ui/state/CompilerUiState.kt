package edu.itssg.compiler.ui.state

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.security.SourceSpan

/** Fases que aparecen en la barra de fases del espacio de trabajo, en orden de ejecución. */
enum class CompilerPhase(val displayName: String, val shortName: String) {
    LEXICAL("Léxico", "Lex"),
    SYNTACTIC("Sintáctico", "Sin"),
    SEMANTIC("Semántico", "Sem"),
    INTERMEDIATE_CODE("Código intermedio", "CI"),
    OPTIMIZATION("Optimización", "Opt");

    companion object {
        fun of(type: ErrorPhaseType): CompilerPhase = when (type) {
            ErrorPhaseType.LEXICAL -> LEXICAL
            ErrorPhaseType.SYNTACTIC -> SYNTACTIC
            ErrorPhaseType.SEMANTIC -> SEMANTIC
        }
    }
}

/**
 * Estado de una fase en la barra de fases:
 * - [PENDING]: todavía no se ejecuta o, en Paso a paso, aún no se muestra.
 * - [RUNNING]: compilando.
 * - [SUCCESS] (✓) / [FAILED] (✗ con conteo).
 * - [BLOCKED]: no se genera porque una fase anterior tuvo errores (Código intermedio y Optimización).
 */
enum class PhaseStatus { PENDING, RUNNING, SUCCESS, FAILED, BLOCKED }

/**
 * @property summary Texto corto para la barra de fases y la tarjeta de Paso a paso
 */
data class PhaseState(
    val phase: CompilerPhase,
    val status: PhaseStatus,
    val errorCount: Int = 0,
    val summary: String = ""
)

/** Pestañas del panel de resultados. */
enum class ResultTab(val displayName: String) {
    TOKENS("Tokens"),
    SYMBOLS("Símbolos"),
    TREE("Árbol"),
    INTERMEDIATE_CODE("Código intermedio")
}

/** Árbol que se muestra en la pestaña Árbol. */
enum class TreeKind(val displayName: String) {
    SYNTACTIC("Sintáctico"),
    SEMANTIC("Abstracto-semántico")
}

/** Modo Paso a paso: [currentIndex] es la última fase revelada (índice en [CompilerPhase.entries]). */
data class StepByStepState(val currentIndex: Int) {
    val total: Int get() = CompilerPhase.entries.size
    val currentPhase: CompilerPhase get() = CompilerPhase.entries[currentIndex]
    val isFirst: Boolean get() = currentIndex == 0
    val isLast: Boolean get() = currentIndex == total - 1
}

/**
 * Pide al editor seleccionar y desplazarse a [span] (clic en una fila de una tabla, un error o un nodo del árbol).
 * [requestId] distingue dos clics seguidos sobre la misma fila.
 */
data class EditorFocusRequest(val span: SourceSpan, val requestId: Long)

/** Aviso breve para la interfaz (snackbar). */
data class UiMessage(val text: String, val isError: Boolean = false)

const val UNTITLED_SESSION_TITLE: String = "Sin título"

/**
 * Estado completo e inmutable del espacio de trabajo.
 *
 * @property highlightTokens Tokens del código actual para el resaltado del editor (se recalculan con cada cambio).
 * @property report Último resultado del compilador; corresponde a [compiledSource].
 * @property isTitleCustom `false` mientras el título se toma del nombre de la clase (se actualiza al compilar);
 *   `true` si viene de un archivo, del historial o el usuario lo cambió.
 * @property lastSavedSource Código de la última vez que se guardó en el historial.
 */
data class CompilerUiState(
    val sessionTitle: String = UNTITLED_SESSION_TITLE,
    val isTitleCustom: Boolean = false,
    val sourceCode: String = "",
    val highlightTokens: List<Token> = emptyList(),
    val isCompiling: Boolean = false,
    val report: CompilationReport? = null,
    val compiledSource: String? = null,
    val compilationFailure: String? = null,
    val phases: List<PhaseState> = initialPhaseStates(),
    val stepByStep: StepByStepState? = null,
    val selectedTab: ResultTab = ResultTab.TOKENS,
    val irFormat: IrFormatKind = IrFormatKind.THREE_ADDRESS,
    val treeKind: TreeKind = TreeKind.SYNTACTIC,
    val isErrorPanelExpanded: Boolean = true,
    val focusRequest: EditorFocusRequest? = null,
    val isSaving: Boolean = false,
    val lastSavedSource: String? = null
) {
    /** El código cambió después de compilar: los resultados en pantalla ya no corresponden al editor. */
    val isReportStale: Boolean
        get() = report != null && compiledSource != sourceCode

    val hasUnsavedChanges: Boolean
        get() = sourceCode.isNotBlank() && sourceCode != lastSavedSource

    /** Índice de la última fase visible (todas, salvo en Paso a paso). */
    val revealedThroughIndex: Int
        get() = stepByStep?.currentIndex ?: CompilerPhase.entries.lastIndex

    fun isRevealed(phase: CompilerPhase): Boolean = report != null && phase.ordinal <= revealedThroughIndex

    fun phaseState(phase: CompilerPhase): PhaseState = phases[phase.ordinal]

    /** Errores de las fases ya visibles, en el orden de la Tabla de Errores (línea, columna). */
    val visibleErrors: List<CompilerError>
        get() = report?.errors.orEmpty().filter { isRevealed(CompilerPhase.of(it.type)) }

    /** Pestañas con contenido para mostrar. */
    val availableTabs: Set<ResultTab>
        get() = buildSet {
            if (isRevealed(CompilerPhase.LEXICAL)) add(ResultTab.TOKENS)
            if (isRevealed(CompilerPhase.SYNTACTIC)) add(ResultTab.TREE)
            if (isRevealed(CompilerPhase.SEMANTIC)) add(ResultTab.SYMBOLS)
            if (isRevealed(CompilerPhase.INTERMEDIATE_CODE)) add(ResultTab.INTERMEDIATE_CODE)
        }
}

internal fun initialPhaseStates(): List<PhaseState> =
    CompilerPhase.entries.map { PhaseState(it, PhaseStatus.PENDING) }

internal fun runningPhaseStates(): List<PhaseState> =
    CompilerPhase.entries.map { PhaseState(it, PhaseStatus.RUNNING) }

/** Pestaña que se abre al llegar a una fase (Paso a paso) o al tocar su chip en la barra de fases. */
internal fun CompilerPhase.resultTab(): ResultTab = when (this) {
    CompilerPhase.LEXICAL -> ResultTab.TOKENS
    CompilerPhase.SYNTACTIC -> ResultTab.TREE
    CompilerPhase.SEMANTIC -> ResultTab.SYMBOLS
    CompilerPhase.INTERMEDIATE_CODE, CompilerPhase.OPTIMIZATION -> ResultTab.INTERMEDIATE_CODE
}

/**
 * Traduce un [CompilationReport] al estado de cada fase de la barra.
 *
 * Regla de bloqueo: si hubo cualquier error (léxico, sintáctico o semántico) o se abortó por los límites de
 * seguridad, el Código intermedio y la Optimización quedan BLOQUEADOS, aunque el backend los haya generado.
 * Las fases posteriores a [revealedThroughIndex] quedan PENDIENTES (modo Paso a paso).
 */
internal fun buildPhaseStates(report: CompilationReport, revealedThroughIndex: Int?): List<PhaseState> {
    val limit = revealedThroughIndex ?: CompilerPhase.entries.lastIndex
    val quotaMessage = report.quotaAbortedMessage
    val irBlockReason: String? = when {
        quotaMessage != null -> quotaMessage
        report.errors.isNotEmpty() -> "Corrige los errores para generar el código intermedio."
        else -> null
    }

    return CompilerPhase.entries.map { phase ->
        if (phase.ordinal > limit) return@map PhaseState(phase, PhaseStatus.PENDING)
        when (phase) {
            CompilerPhase.LEXICAL -> {
                val count = report.lexicalErrorCount
                val tokenCount = report.tokens.count { it.category != TokenCategory.EOF }
                PhaseState(
                    phase = phase,
                    status = if (count > 0 || quotaMessage != null) PhaseStatus.FAILED else PhaseStatus.SUCCESS,
                    errorCount = count,
                    summary = quotaMessage ?: "$tokenCount tokens · ${errorLabel(count, "léxico", "léxicos")}"
                )
            }

            CompilerPhase.SYNTACTIC -> when {
                quotaMessage != null -> PhaseState(phase, PhaseStatus.BLOCKED, summary = quotaMessage)
                else -> {
                    val count = report.syntacticErrorCount
                    PhaseState(
                        phase = phase,
                        status = if (count > 0) PhaseStatus.FAILED else PhaseStatus.SUCCESS,
                        errorCount = count,
                        summary = if (count > 0) errorLabel(count, "sintáctico", "sintácticos")
                        else "Estructura válida según la GLC v7"
                    )
                }
            }

            CompilerPhase.SEMANTIC -> when {
                quotaMessage != null -> PhaseState(phase, PhaseStatus.BLOCKED, summary = quotaMessage)
                else -> {
                    val count = report.semanticErrorCount
                    val symbols = report.symbolEntries.size
                    PhaseState(
                        phase = phase,
                        status = if (count > 0) PhaseStatus.FAILED else PhaseStatus.SUCCESS,
                        errorCount = count,
                        summary = "$symbols ${if (symbols == 1) "símbolo" else "símbolos"} · " +
                                errorLabel(count, "semántico", "semánticos")
                    )
                }
            }

            CompilerPhase.INTERMEDIATE_CODE -> when {
                irBlockReason != null -> PhaseState(phase, PhaseStatus.BLOCKED, summary = irBlockReason)
                else -> {
                    val count = report.intermediateCode.quadruples.size
                    PhaseState(
                        phase = phase,
                        status = PhaseStatus.SUCCESS,
                        summary = "$count ${if (count == 1) "instrucción" else "instrucciones"}"
                    )
                }
            }

            CompilerPhase.OPTIMIZATION -> when {
                irBlockReason != null -> PhaseState(phase, PhaseStatus.BLOCKED, summary = irBlockReason)
                else -> PhaseState(phase, PhaseStatus.SUCCESS, summary = optimizationSummary(report))
            }
        }
    }
}

/** "6 → 3 instrucciones (−50 %)" o "Sin cambios (4 instrucciones)". */
internal fun optimizationSummary(report: CompilationReport): String {
    val original = report.intermediateCode.quadruples.size
    val optimized = report.optimizationResult.optimizedBundle.quadruples.size
    if (original == 0 || optimized >= original) {
        return "Sin cambios ($original ${if (original == 1) "instrucción" else "instrucciones"})"
    }
    val percent = (original - optimized) * 100 / original
    return "$original → $optimized instrucciones (−$percent %)"
}

private fun errorLabel(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 error $singular" else "$count errores $plural"

/**
 * Título de la sesión a partir del código: el nombre de la clase (`class Main` → "Main.java").
 * Usa los tokens del Analizador Léxico (secuencia `CL ID`) en lugar de buscar texto.
 */
internal fun deriveSessionTitle(tokens: List<Token>): String? {
    val classIndex = tokens.indexOfFirst { it.category == TokenCategory.CL }
    if (classIndex < 0) return null
    val name = tokens.getOrNull(classIndex + 1)?.takeIf { it.category == TokenCategory.ID }?.lexeme
    return name?.let { "$it.java" }
}
