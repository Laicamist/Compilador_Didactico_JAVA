package edu.itssg.compiler.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.ui.data.AppSettings
import edu.itssg.compiler.ui.editor.CodeEditor
import edu.itssg.compiler.ui.editor.EditorDiagnostics
import edu.itssg.compiler.ui.editor.ErrorTypeMarker
import edu.itssg.compiler.ui.files.SourceLoadResult
import edu.itssg.compiler.ui.files.rememberSourceFilePicker
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.CompilerUiState
import edu.itssg.compiler.ui.state.PhaseState
import edu.itssg.compiler.ui.state.PhaseStatus
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.CompilerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Espacio de trabajo (Paso 3.2): barra superior, barra de fases, editor y panel de resultados con divisor
 * arrastrable, y la lista de errores plegable. Los paneles tabulares completos (Tokens, Símbolos, Errores con el
 * formato de la materia y Código intermedio) llegan en el Paso 3.3, y el visor de árboles en el 3.4.
 *
 * Atajos: Ctrl+S guardar · Ctrl+O abrir · F5 o Ctrl+Enter compilar todo · F6 paso a paso.
 * Volver al Inicio no descarta nada: el trabajo sigue en el ViewModel y se puede continuar desde ahí.
 *
 * @param onMessage Muestra un aviso breve (snackbar).
 */
@Composable
fun WorkspaceScreen(
    state: CompilerUiState,
    viewModel: CompilerViewModel,
    settings: AppSettings,
    securityConfig: CompilerSecurityConfig,
    onFontSizeChange: (Int) -> Unit,
    onNavigateHome: () -> Unit,
    onMessage: (text: String, isError: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val scope = rememberCoroutineScope()

    var confirmOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val picker = rememberSourceFilePicker(securityConfig) { result ->
        when (result) {
            is SourceLoadResult.Success -> {
                viewModel.openSource(title = result.title, source = result.text)
                result.notes.forEach { onMessage(it, false) }
            }
            is SourceLoadResult.Failure -> onMessage(result.message, true)
        }
    }

    /** Abrir reemplaza el documento: si hay cambios sin guardar, pregunta primero. */
    fun openFile() {
        if (state.hasUnsavedChanges) confirmOpen = true else picker.launch()
    }

    // Errores que marca el editor: los de la compilación si el reporte corresponde al texto; si no, los léxicos
    // que el resaltado encuentra mientras se escribe.
    val reportIsCurrent = state.report != null && !state.isReportStale
    val diagnostics = remember(state.report, state.compiledSource, state.sourceCode, state.highlightAnomalies, state.stepByStep) {
        if (reportIsCurrent) {
            EditorDiagnostics.fromErrors(state.visibleErrors, state.sourceCode.length)
        } else {
            EditorDiagnostics.fromAnomalies(state.highlightAnomalies, state.sourceCode.length)
        }
    }
    val errorRows = remember(state.report, state.compiledSource, state.sourceCode, state.highlightAnomalies, state.stepByStep) {
        if (reportIsCurrent) {
            state.visibleErrors.map { ErrorRow(it.type, it.line, it.column, it.offendingLexeme, it.justification, it.span, it.index) }
        } else {
            state.highlightAnomalies.map {
                ErrorRow(ErrorPhaseType.LEXICAL, it.span.start.line, it.span.start.column, it.lexeme, it.justification, it.span, null)
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                when {
                    ctrl && event.key == Key.S -> { viewModel.saveSession(); true }
                    ctrl && event.key == Key.O -> { openFile(); true }
                    event.key == Key.F5 || (ctrl && (event.key == Key.Enter || event.key == Key.NumPadEnter)) -> {
                        viewModel.compileAll()
                        true
                    }
                    event.key == Key.F6 -> { viewModel.startStepByStep(); true }
                    else -> false
                }
            }
    ) {
        WorkspaceTopBar(
            state = state,
            onHome = onNavigateHome,
            onRename = { showRename = true },
            onOpen = ::openFile,
            onSave = { viewModel.saveSession() },
            onStepByStep = viewModel::startStepByStep,
            onCompile = viewModel::compileAll,
            onCancel = viewModel::cancelCompilation
        )
        if (state.isCompiling) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surface3)
        } else {
            HorizontalDivider(color = colors.border)
        }

        PhaseBar(state = state, onPhaseSelected = viewModel::onPhaseSelected)
        state.stepByStep?.let { step ->
            StepCard(
                phaseState = state.phaseState(step.currentPhase),
                index = step.currentIndex,
                total = step.total,
                isFirst = step.isFirst,
                isLast = step.isLast,
                onPrevious = viewModel::previousStep,
                onNext = viewModel::nextStep,
                onFinish = viewModel::finishStepByStep
            )
        }
        state.compilationFailure?.let { failure ->
            Text(
                text = failure,
                color = colors.errorLexical,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(colors.errorLexical.copy(alpha = 0.1f)).padding(10.dp)
            )
        }
        HorizontalDivider(color = colors.border)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val editor: @Composable (Modifier) -> Unit = { editorModifier ->
                CodeEditor(
                    documentKey = state.documentVersion,
                    initialText = state.sourceCode,
                    onTextChange = viewModel::onSourceChange,
                    tokens = state.highlightTokens,
                    diagnostics = diagnostics,
                    fontSizeSp = settings.editorFontSizeSp,
                    onFontSizeChange = onFontSizeChange,
                    maxLength = securityConfig.maxSourceLength,
                    onTextTooLong = {
                        onMessage("El código no puede pasar de ${securityConfig.maxSourceLength} caracteres.", true)
                    },
                    focusRequest = state.focusRequest,
                    onFocusRequestHandled = viewModel::onFocusRequestHandled,
                    modifier = editorModifier
                )
            }
            val errorList: @Composable (Modifier, Boolean) -> Unit = { listModifier, collapsible ->
                ErrorStrip(
                    rows = errorRows,
                    isLive = !reportIsCurrent,
                    isStale = state.isReportStale,
                    expanded = !collapsible || state.isErrorPanelExpanded,
                    collapsible = collapsible,
                    onToggle = viewModel::toggleErrorPanel,
                    onRowClick = { row -> viewModel.focusOn(row.span) },
                    modifier = listModifier
                )
            }

            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                val totalWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                var splitFraction by remember { mutableFloatStateOf(DEFAULT_SPLIT) }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        editor(Modifier.weight(splitFraction).fillMaxHeight())
                        Box(
                            Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .background(colors.border)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    state = rememberDraggableState { delta ->
                                        splitFraction = (splitFraction + delta / totalWidthPx).coerceIn(MIN_SPLIT, MAX_SPLIT)
                                    }
                                )
                        )
                        ResultsPanel(state = state, onTabSelected = viewModel::onTabSelected, modifier = Modifier.weight(1f - splitFraction).fillMaxHeight())
                    }
                    HorizontalDivider(color = colors.border)
                    errorList(Modifier.fillMaxWidth(), true)
                }
            } else {
                CompactWorkspace(
                    errorCount = errorRows.size,
                    focusRequestId = state.focusRequest?.requestId,
                    editor = editor,
                    results = { ResultsPanel(state = state, onTabSelected = viewModel::onTabSelected, modifier = it) },
                    errors = { errorList(it, false) }
                )
            }
        }
    }

    if (confirmOpen) {
        UnsavedChangesDialog(
            title = state.sessionTitle,
            isSaving = state.isSaving,
            onDiscard = {
                confirmOpen = false
                picker.launch()
            },
            onSaveAndContinue = {
                scope.launch {
                    viewModel.saveSession()
                    // saveSession marca isSaving de inmediato (viewModelScope usa Main.immediate).
                    val saved = viewModel.uiState.first { !it.isSaving }
                    confirmOpen = false
                    if (!saved.hasUnsavedChanges) picker.launch()
                }
            },
            onCancel = { confirmOpen = false }
        )
    }

    if (showRename) {
        RenameDialog(
            current = state.sessionTitle,
            onConfirm = {
                viewModel.renameSession(it)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
}

/** Fila de la lista de errores (de la compilación o, sin compilar, léxicos en vivo). */
private data class ErrorRow(
    val type: ErrorPhaseType,
    val line: Int,
    val column: Int,
    val lexeme: String,
    val message: String,
    val span: SourceSpan,
    val number: Int?
)

//Barra superior

@Composable
private fun WorkspaceTopBar(
    state: CompilerUiState,
    onHome: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onStepByStep: () -> Unit,
    onCompile: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onHome) { Text("← Inicio", color = colors.textSecondary) }
        Row(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClick = onRename).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = state.sessionTitle,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp)
            )
            if (state.hasUnsavedChanges) {
                Box(Modifier.size(7.dp).background(colors.intermediate, CircleShape))
            }
            if (state.isReportStale) {
                Text("· el código cambió desde la última compilación", fontSize = 12.sp, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = onOpen) { Text("Abrir", color = colors.textPrimary) }
            TextButton(onClick = onSave, enabled = !state.isSaving && state.sourceCode.isNotBlank()) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text("Guardar", color = colors.textPrimary)
            }
            TextButton(onClick = {}, enabled = false) { Text("Exportar") }
            if (state.isCompiling) {
                OutlinedButton(onClick = onCancel) { Text("■ Cancelar") }
            } else {
                OutlinedButton(onClick = onStepByStep, enabled = state.sourceCode.isNotBlank()) { Text("⏭ Paso a paso") }
                Button(
                    onClick = onCompile,
                    enabled = state.sourceCode.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) { Text("▶ Compilar todo") }
            }
        }
    }
}

//Barra de fases

@Composable
private fun PhaseBar(state: CompilerUiState, onPhaseSelected: (CompilerPhase) -> Unit) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.phases.forEachIndexed { index, phaseState ->
            if (index > 0) Text("→", color = colors.textMuted, fontSize = 12.sp)
            PhaseChip(phaseState, enabled = state.isRevealed(phaseState.phase), onClick = { onPhaseSelected(phaseState.phase) })
        }
    }
}

@Composable
private fun PhaseChip(phaseState: PhaseState, enabled: Boolean, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    val phaseColor = colors.phase(phaseState.phase)
    val (statusText, statusColor) = when (phaseState.status) {
        PhaseStatus.PENDING -> "—" to colors.textMuted
        PhaseStatus.RUNNING -> "…" to colors.textSecondary
        PhaseStatus.SUCCESS -> "✓" to colors.optimization
        PhaseStatus.FAILED -> "✗ ${phaseState.errorCount}" to colors.errorLexical
        PhaseStatus.BLOCKED -> "bloqueada" to colors.textMuted
    }
    val borderColor = when (phaseState.status) {
        PhaseStatus.FAILED -> colors.errorLexical
        PhaseStatus.SUCCESS -> phaseColor.copy(alpha = 0.6f)
        else -> colors.border
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(if (phaseState.status == PhaseStatus.FAILED) colors.errorLexical.copy(alpha = 0.08f) else colors.surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).background(if (phaseState.status == PhaseStatus.BLOCKED) colors.textMuted else phaseColor, CircleShape))
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(phaseState.phase.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                if (phaseState.status == PhaseStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = phaseColor)
                } else {
                    Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor)
                }
            }
            if (phaseState.summary.isNotEmpty()) {
                Text(
                    phaseState.summary,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp)
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    phaseState: PhaseState,
    index: Int,
    total: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    val colors = JAutomataTheme.colors
    val phaseColor = colors.phase(phaseState.phase)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(phaseColor.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Paso ${index + 1} de $total", fontSize = 12.sp, color = phaseColor, fontWeight = FontWeight.Bold)
        Text(
            "${phaseState.phase.displayName}: ${phaseState.summary}",
            fontSize = 13.sp,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onPrevious, enabled = !isFirst) { Text("◀ Anterior") }
        if (isLast) {
            Button(onClick = onFinish) { Text("Terminar") }
        } else {
            Button(onClick = onNext) { Text("Siguiente ▶") }
            TextButton(onClick = onFinish) { Text("Ver todo") }
        }
    }
}

//Panel de resultados (base del Paso 3.3)

@Composable
private fun ResultsPanel(state: CompilerUiState, onTabSelected: (ResultTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    Column(modifier.background(colors.surface1)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface2).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ResultTab.entries.forEach { tab ->
                val available = tab in state.availableTabs
                val selected = tab == state.selectedTab
                Text(
                    text = tab.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        !available -> colors.textMuted
                        selected -> colors.textPrimary
                        else -> colors.textSecondary
                    },
                    modifier = Modifier
                        .clickable(enabled = available) { onTabSelected(tab) }
                        .background(if (selected) colors.surface1 else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        HorizontalDivider(color = colors.border)
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            val report = state.report
            val (title, detail) = when {
                state.isCompiling -> "Compilando…" to "El analizador está recorriendo las fases."
                report == null -> "Sin resultados todavía" to
                        "Escribe o abre un programa y presiona ▶ Compilar todo (F5), o ⏭ Paso a paso (F6) para ver cada fase."
                else -> {
                    val phase = when (state.selectedTab) {
                        ResultTab.TOKENS -> CompilerPhase.LEXICAL
                        ResultTab.TREE -> CompilerPhase.SYNTACTIC
                        ResultTab.SYMBOLS -> CompilerPhase.SEMANTIC
                        ResultTab.INTERMEDIATE_CODE -> CompilerPhase.INTERMEDIATE_CODE
                    }
                    val phaseState = state.phaseState(phase)
                    "${state.selectedTab.displayName} · ${phase.displayName}" to
                            "${phaseState.summary}\n\nEl panel completo de esta pestaña se incorpora en el Paso 3.3."
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
                Text(detail, fontSize = 13.sp, color = colors.textSecondary, lineHeight = 19.sp)
            }
        }
    }
}

//Lista de errores

@Composable
private fun ErrorStrip(
    rows: List<ErrorRow>,
    isLive: Boolean,
    isStale: Boolean,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
    onRowClick: (ErrorRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    Column(modifier.background(colors.surface1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .clickable(enabled = collapsible, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (collapsible) Text(if (expanded) "▾" else "▸", color = colors.textSecondary, fontSize = 12.sp)
            Text("Errores (${rows.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            for (type in ErrorPhaseType.entries) {
                val count = rows.count { it.type == type }
                if (count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ErrorTypeMarker(type, size = 8.dp)
                        Text("$count ${type.displayName.lowercase()}", fontSize = 12.sp, color = colors.error(type))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    isLive && rows.isNotEmpty() -> "Errores léxicos detectados al escribir · compila para ver todos"
                    isLive -> "Compila para generar la Tabla de Errores"
                    isStale -> "El código cambió: compila de nuevo"
                    else -> ""
                },
                fontSize = 11.sp,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded && rows.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (collapsible) 170.dp else 2000.dp)) {
                itemsIndexed(rows) { index, row ->
                    if (index > 0) HorizontalDivider(color = colors.border)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRowClick(row) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ErrorTypeMarker(row.type, size = 9.dp)
                        Text(
                            text = "${row.line}:${row.column}",
                            fontFamily = JAutomataFonts.code,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(row.type.displayName, fontSize = 12.sp, color = colors.error(row.type), modifier = Modifier.width(76.dp))
                        Text(
                            text = row.lexeme.ifEmpty { "—" },
                            fontFamily = JAutomataFonts.code,
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            text = row.message.lineSequence().first(),
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

//Diseño compacto (teléfono)

private enum class CompactTab(val label: String) { EDITOR("Editor"), RESULTS("Resultados"), ERRORS("Errores") }

@Composable
private fun CompactWorkspace(
    errorCount: Int,
    focusRequestId: Long?,
    editor: @Composable (Modifier) -> Unit,
    results: @Composable (Modifier) -> Unit,
    errors: @Composable (Modifier) -> Unit
) {
    val colors = JAutomataTheme.colors
    var tab by remember { mutableStateOf(CompactTab.EDITOR) }
    // Tocar un error lleva al editor, que selecciona el fragmento al aparecer.
    LaunchedEffect(focusRequestId) {
        if (focusRequestId != null) tab = CompactTab.EDITOR
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                CompactTab.EDITOR -> editor(Modifier.fillMaxSize())
                CompactTab.RESULTS -> results(Modifier.fillMaxSize())
                CompactTab.ERRORS -> errors(Modifier.fillMaxSize())
            }
        }
        HorizontalDivider(color = colors.border)
        Row(Modifier.fillMaxWidth().background(colors.surface2)) {
            CompactTab.entries.forEach { entry ->
                val selected = entry == tab
                Box(
                    modifier = Modifier.weight(1f).clickable { tab = entry }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (entry == CompactTab.ERRORS && errorCount > 0) "${entry.label} ($errorCount)" else entry.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            selected -> colors.accent
                            entry == CompactTab.ERRORS && errorCount > 0 -> colors.errorLexical
                            else -> colors.textSecondary
                        }
                    )
                }
            }
        }
    }
}

//Diálogos

@Composable
private fun UnsavedChangesDialog(
    title: String,
    isSaving: Boolean,
    onDiscard: () -> Unit,
    onSaveAndContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Cambios sin guardar") },
        text = { Text("“$title” tiene cambios que no están en el historial. ¿Qué quieres hacer?") },
        confirmButton = {
            Button(onClick = onSaveAndContinue, enabled = !isSaving) { Text("Guardar y continuar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel) { Text("Cancelar") }
                TextButton(onClick = onDiscard) { Text("Descartar") }
            }
        }
    )
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre de la sesión") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "").take(CompilerViewModel.MAX_TITLE_LENGTH) },
                singleLine = true,
                label = { Text("Nombre") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private const val DEFAULT_SPLIT = 0.56f
private const val MIN_SPLIT = 0.28f
private const val MAX_SPLIT = 0.8f
