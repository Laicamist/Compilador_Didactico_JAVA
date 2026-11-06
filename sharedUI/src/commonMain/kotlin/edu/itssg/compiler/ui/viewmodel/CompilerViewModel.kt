package edu.itssg.compiler.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.core.lexer.Lexer
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.parser.SyntaxTreeNode
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.semantic.SemanticAstNode
import edu.itssg.compiler.core.symboltable.SymbolEntry
import edu.itssg.compiler.ui.data.SessionStore
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.CompilerUiState
import edu.itssg.compiler.ui.state.EditorFocusRequest
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.state.StepByStepState
import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.state.UNTITLED_SESSION_TITLE
import edu.itssg.compiler.ui.state.UiMessage
import edu.itssg.compiler.ui.state.buildPhaseStates
import edu.itssg.compiler.ui.state.deriveSessionTitle
import edu.itssg.compiler.ui.state.initialPhaseStates
import edu.itssg.compiler.ui.state.resultTab
import edu.itssg.compiler.ui.state.runningPhaseStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel del espacio de trabajo (editor + resultados de las fases).
 *
 * - Expone un único [uiState] inmutable ([StateFlow]) y avisos breves en [messages].
 * - Compila fuera del hilo principal con [JavaDidacticCompiler.compileAsync].
 * - Paso a paso: compila una sola vez y revela las fases de una en una; el backend ejecuta el pipeline completo,
 *   así que cada paso muestra resultados reales, no simulados.
 * - Resaltado del editor: vuelve a tokenizar el código 150 ms después de la última tecla (tokens y errores léxicos).
 *
 * @param compilerFactory Crea un compilador NUEVO por compilación: `Parser` y `SemanticAnalyzer` guardan estado
 *   interno, así que una misma instancia no se puede usar desde dos compilaciones a la vez.
 * @param nowMillis Hora actual en milisegundos (fecha de las sesiones guardadas).
 */
class CompilerViewModel(
    private val compilerFactory: () -> JavaDidacticCompiler,
    private val sessionStore: SessionStore,
    private val securityConfig: CompilerSecurityConfig,
    private val nowMillis: () -> Long
) : ViewModel() {

    private val state = MutableStateFlow(CompilerUiState())
    val uiState: StateFlow<CompilerUiState> = state.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    private val highlightLexer = Lexer(config = securityConfig)
    private val sourceForHighlight = MutableStateFlow("")

    private var compileJob: Job? = null
    private var saveJob: Job? = null
    private var focusRequestCounter = 0L

    init {
        viewModelScope.launch {
            sourceForHighlight.collectLatest { source ->
                if (source.isEmpty()) {
                    state.update { it.copy(highlightTokens = emptyList(), highlightAnomalies = emptyList()) }
                    return@collectLatest
                }
                delay(HIGHLIGHT_DEBOUNCE_MS)
                val result = withContext(Dispatchers.Default) { highlightLexer.tokenize(source) }
                state.update {
                    if (it.sourceCode == source) {
                        it.copy(highlightTokens = result.tokens, highlightAnomalies = result.lexicalAnomalies)
                    } else {
                        it
                    }
                }
            }
        }
    }

    //Código fuente

    fun onSourceChange(newSource: String) {
        if (newSource == state.value.sourceCode) return
        if (newSource.length > securityConfig.maxSourceLength) {
            notifySourceTooLarge()
            return
        }
        state.update { it.copy(sourceCode = newSource) }
        sourceForHighlight.value = newSource
    }

    /** "Nuevo trabajo": limpia el espacio de trabajo, opcionalmente con la plantilla mínima de una clase. */
    fun newWork(withTemplate: Boolean = true) {
        resetWorkspace(
            title = null,
            source = if (withTemplate) NEW_WORK_TEMPLATE else "",
            alreadySaved = false
        )
    }

    /**
     * Carga un código en el editor y lo compila.
     * @param title Nombre del archivo o de la sesión; si es `null` se toma el nombre de la clase al compilar.
     * @param alreadySaved `true` al reabrir desde el Historial (no hay cambios sin guardar).
     */
    fun openSource(title: String?, source: String, alreadySaved: Boolean = false, compileImmediately: Boolean = true) {
        if (source.length > securityConfig.maxSourceLength) {
            notifySourceTooLarge()
            return
        }
        resetWorkspace(
            title = title?.trim()?.take(MAX_TITLE_LENGTH)?.takeIf { it.isNotEmpty() },
            source = source,
            alreadySaved = alreadySaved
        )
        if (compileImmediately && source.isNotBlank()) compileAll()
    }

    fun renameSession(title: String) {
        val clean = title.trim().take(MAX_TITLE_LENGTH)
        if (clean.isNotEmpty()) state.update { it.copy(sessionTitle = clean, isTitleCustom = true) }
    }

    //Compilación

    /** Compilar todo: ejecuta todas las fases y muestra todos los resultados. */
    fun compileAll() = launchCompilation(stepByStep = false)

    /** ⏭ Paso a paso: compila y muestra solo la fase Léxica; [nextStep] revela la siguiente. */
    fun startStepByStep() = launchCompilation(stepByStep = true)

    fun cancelCompilation() {
        if (compileJob?.isActive != true) return
        compileJob?.cancel()
        state.update { current ->
            current.copy(
                isCompiling = false,
                phases = current.report?.let { buildPhaseStates(it, current.stepByStep?.currentIndex) }
                    ?: initialPhaseStates()
            )
        }
    }

    fun nextStep() {
        val step = state.value.stepByStep ?: return
        if (step.isLast) finishStepByStep() else moveToStep(step.currentIndex + 1)
    }

    fun previousStep() {
        val step = state.value.stepByStep ?: return
        if (!step.isFirst) moveToStep(step.currentIndex - 1)
    }

    /** Sale de Paso a paso y muestra todas las fases. */
    fun finishStepByStep() {
        state.update { current ->
            val report = current.report ?: return@update current.copy(stepByStep = null)
            current.copy(stepByStep = null, phases = buildPhaseStates(report, null))
        }
    }

    //Panel de resultados

    /** Clic en un chip de la barra de fases: abre su pestaña si la fase ya está visible. */
    fun onPhaseSelected(phase: CompilerPhase) {
        state.update { current ->
            if (!current.isRevealed(phase)) current
            else current.copy(selectedTab = phase.resultTab(), treeKind = treeKindFor(phase, current.treeKind))
        }
    }

    fun onTabSelected(tab: ResultTab) = state.update { it.copy(selectedTab = tab) }

    fun onIrFormatSelected(format: IrFormatKind) = state.update { it.copy(irFormat = format) }

    fun onTreeKindSelected(kind: TreeKind) = state.update { it.copy(treeKind = kind) }

    fun toggleErrorPanel() = state.update { it.copy(isErrorPanelExpanded = !it.isErrorPanelExpanded) }

    //Navegación al código

    fun focusOnError(error: CompilerError) = focusOn(error.span)

    fun focusOnToken(token: Token) = focusOn(token.span)

    fun focusOnSymbol(symbol: SymbolEntry) = focusOn(spanOf(symbol.firstSeen, symbol.lexeme.length))

    fun focusOnSyntaxNode(node: SyntaxTreeNode) = focusOn(node.span)

    fun focusOnSemanticNode(node: SemanticAstNode) = focusOn(node.span)

    fun focusOn(span: SourceSpan) {
        val requestId = ++focusRequestCounter
        state.update { it.copy(focusRequest = EditorFocusRequest(span, requestId)) }
    }

    /** El editor llama a esto después de mover la selección. */
    fun onFocusRequestHandled(requestId: Long) {
        state.update { if (it.focusRequest?.requestId == requestId) it.copy(focusRequest = null) else it }
    }

    //Historial

    /**
     * Guarda la sesión en el historial. Si el código cambió desde la última compilación, primero lo compila.
     * Cada guardado crea una entrada nueva; si el código no cambió desde el último guardado, no se duplica.
     */
    fun saveSession(title: String? = null) {
        if (saveJob?.isActive == true) return
        val snapshot = state.value
        if (snapshot.sourceCode.isBlank()) {
            messageChannel.trySend(UiMessage(EMPTY_SOURCE_MESSAGE, isError = true))
            return
        }
        val requestedTitle = title?.trim()?.take(MAX_TITLE_LENGTH)?.takeIf { it.isNotEmpty() }
        if (!snapshot.hasUnsavedChanges) {
            messageChannel.trySend(UiMessage("Esta versión ya está guardada en el historial."))
            return
        }

        saveJob = viewModelScope.launch {
            state.update { it.copy(isSaving = true) }
            try {
                val source = snapshot.sourceCode
                val report = snapshot.report?.takeIf { snapshot.compiledSource == source }
                    ?: run {
                        compileJob?.cancel()
                        runCompilation(source, stepByStep = false)
                    }
                    ?: return@launch
                val finalTitle = requestedTitle ?: state.value.sessionTitle
                sessionStore.save(title = finalTitle, timestampMillis = nowMillis(), report = report)
                state.update {
                    it.copy(
                        sessionTitle = finalTitle,
                        isTitleCustom = it.isTitleCustom || requestedTitle != null,
                        lastSavedSource = source
                    )
                }
                messageChannel.trySend(UiMessage("“$finalTitle” se guardó en el historial."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(
                    UiMessage("No se pudo guardar la sesión: ${e.message ?: e::class.simpleName}", isError = true)
                )
            } finally {
                state.update { it.copy(isSaving = false) }
            }
        }
    }

    //Internos

    private fun launchCompilation(stepByStep: Boolean) {
        val source = state.value.sourceCode
        if (source.isBlank()) {
            messageChannel.trySend(UiMessage(EMPTY_SOURCE_MESSAGE, isError = true))
            return
        }
        compileJob?.cancel()
        compileJob = viewModelScope.launch { runCompilation(source, stepByStep) }
    }

    /** @return el reporte, o `null` si el compilador lanzó una excepción inesperada. */
    private suspend fun runCompilation(source: String, stepByStep: Boolean): CompilationReport? {
        state.update { it.copy(isCompiling = true, compilationFailure = null, phases = runningPhaseStates()) }

        val report = try {
            compilerFactory().compileAsync(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { current ->
                current.copy(
                    isCompiling = false,
                    compilationFailure = "El compilador se detuvo inesperadamente: ${e.message ?: e::class.simpleName}",
                    phases = current.report?.let { buildPhaseStates(it, current.stepByStep?.currentIndex) }
                        ?: initialPhaseStates()
                )
            }
            return null
        }

        state.update { current ->
            val step = if (stepByStep) StepByStepState(currentIndex = 0) else null
            val focusPhase = if (stepByStep) CompilerPhase.LEXICAL else null
            current.copy(
                isCompiling = false,
                report = report,
                compiledSource = source,
                stepByStep = step,
                phases = buildPhaseStates(report, step?.currentIndex),
                selectedTab = focusPhase?.resultTab() ?: current.selectedTab,
                sessionTitle = if (current.isTitleCustom) {
                    current.sessionTitle
                } else {
                    deriveSessionTitle(report.tokens) ?: UNTITLED_SESSION_TITLE
                },
                isErrorPanelExpanded = current.isErrorPanelExpanded || report.errors.isNotEmpty()
            )
        }
        return report
    }

    private fun moveToStep(index: Int) {
        state.update { current ->
            val report = current.report ?: return@update current
            val phase = CompilerPhase.entries[index]
            current.copy(
                stepByStep = StepByStepState(index),
                phases = buildPhaseStates(report, index),
                selectedTab = phase.resultTab(),
                treeKind = treeKindFor(phase, current.treeKind)
            )
        }
    }

    /** @param title `null` = el título se tomará del nombre de la clase al compilar. */
    private fun resetWorkspace(title: String?, source: String, alreadySaved: Boolean) {
        compileJob?.cancel()
        state.value = CompilerUiState(
            sessionTitle = title ?: UNTITLED_SESSION_TITLE,
            isTitleCustom = title != null,
            sourceCode = source,
            lastSavedSource = if (alreadySaved) source else null,
            isErrorPanelExpanded = state.value.isErrorPanelExpanded,
            irFormat = state.value.irFormat,
            documentVersion = state.value.documentVersion + 1
        )
        sourceForHighlight.value = source
    }

    private fun notifySourceTooLarge() {
        messageChannel.trySend(
            UiMessage(
                "El código supera el límite de ${securityConfig.maxSourceLength} caracteres.",
                isError = true
            )
        )
    }

    private fun treeKindFor(phase: CompilerPhase, current: TreeKind): TreeKind = when (phase) {
        CompilerPhase.SYNTACTIC -> TreeKind.SYNTACTIC
        else -> current
    }

    /** Rango de un lexema de una sola línea que empieza en [start]. */
    private fun spanOf(start: SourcePosition, length: Int): SourceSpan {
        val safeLength = length.coerceAtLeast(0)
        return SourceSpan(
            start = start,
            end = SourcePosition(
                line = start.line,
                column = start.column + safeLength,
                offset = start.offset + safeLength
            )
        )
    }

    override fun onCleared() {
        messageChannel.close()
        super.onCleared()
    }

    companion object {
        const val HIGHLIGHT_DEBOUNCE_MS = 150L
        const val MAX_TITLE_LENGTH = 120
        private const val EMPTY_SOURCE_MESSAGE = "Escribe o abre un programa antes de compilar."

        /** Plantilla de "Nuevo trabajo"*/
        val NEW_WORK_TEMPLATE: String = """
            public class Main{
                public static void main(String []args){

                }
            }
        """.trimIndent()
    }
}
