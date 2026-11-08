package edu.itssg.compiler.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.CompilerUiState
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.state.intermediateCodeBlockReason
import edu.itssg.compiler.ui.tables.DataTableKind
import edu.itssg.compiler.ui.tables.IntermediateCodePanel
import edu.itssg.compiler.ui.tables.SymbolsPanel
import edu.itssg.compiler.ui.tables.TokensPanel
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.tree.TreePanel
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.CompilerViewModel

/**
 * Panel de resultados del espacio de trabajo: pestañas Tokens, Símbolos (Paso 3.3), Árbol (Paso 3.4: visor interactivo
 * con recorrido animado) y Código intermedio.
 *
 * @param animationsEnabled Ajuste "Animaciones": si está apagado, el visor de árboles se mueve sin transiciones.
 *
 * Mientras se compila de nuevo, se sigue mostrando el resultado anterior (la barra de progreso indica el trabajo).
 */
@Composable
internal fun ResultsPanel(
    state: CompilerUiState,
    viewModel: CompilerViewModel,
    animationsEnabled: Boolean,
    onMessage: (text: String, isError: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val report = state.report
    Column(modifier.background(colors.surface1)) {
        ResultTabs(state, report, viewModel::onTabSelected)
        HorizontalDivider(color = colors.border)

        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        when {
            report == null -> Placeholder(
                title = if (state.isCompiling) "Compilando…" else "Sin resultados todavía",
                detail = if (state.isCompiling) {
                    "El analizador está recorriendo las fases."
                } else {
                    "Escribe o abre un programa y presiona ▶ Compilar todo (F5), o ⏭ Paso a paso (F6) para ver cada fase."
                },
                modifier = contentModifier
            )

            state.selectedTab !in state.availableTabs -> Placeholder(
                title = state.selectedTab.displayName,
                detail = "Esta pestaña aparece al llegar a su fase en Paso a paso. Presiona “Siguiente ▶” o “Ver todo”.",
                modifier = contentModifier
            )

            else -> when (state.selectedTab) {
                ResultTab.TOKENS -> TokensPanel(
                    report = report,
                    isStale = state.isReportStale,
                    query = state.tableQuery(DataTableKind.TOKENS),
                    onQueryChange = { viewModel.onTableQueryChange(DataTableKind.TOKENS, it) },
                    onFocus = viewModel::focusOn,
                    onMessage = onMessage,
                    modifier = contentModifier
                )

                ResultTab.SYMBOLS -> SymbolsPanel(
                    report = report,
                    isStale = state.isReportStale,
                    query = state.tableQuery(DataTableKind.SYMBOLS),
                    onQueryChange = { viewModel.onTableQueryChange(DataTableKind.SYMBOLS, it) },
                    onFocus = viewModel::focusOn,
                    onMessage = onMessage,
                    modifier = contentModifier
                )

                ResultTab.TREE -> TreePanel(
                    report = report,
                    registry = viewModel.treeViewers,
                    treeKind = state.treeKind,
                    onTreeKindChange = viewModel::onTreeKindSelected,
                    semanticAvailable = state.isRevealed(CompilerPhase.SEMANTIC),
                    executionBlockedReason = executionWalkthroughBlockReason(state, report),
                    isStale = state.isReportStale,
                    animationsEnabled = animationsEnabled,
                    onHighlight = viewModel::highlightInCode,
                    onRevealInCode = { span -> viewModel.focusOn(span, takeFocus = true) },
                    onMessage = onMessage,
                    modifier = contentModifier
                )

                ResultTab.INTERMEDIATE_CODE -> IntermediateCodePanel(
                    report = report,
                    format = state.irFormat,
                    onFormatChange = viewModel::onIrFormatSelected,
                    blockedReason = report.intermediateCodeBlockReason(),
                    includeOptimized = state.isRevealed(CompilerPhase.OPTIMIZATION),
                    isStale = state.isReportStale,
                    firstError = state.visibleErrors.firstOrNull() ?: report.errors.firstOrNull(),
                    notice = intermediateCodeNotice(state.visibleNotes),
                    onFocus = viewModel::focusOn,
                    onMessage = onMessage,
                    modifier = contentModifier
                )
            }
        }
    }
}

@Composable
private fun ResultTabs(state: CompilerUiState, report: CompilationReport?, onTabSelected: (ResultTab) -> Unit) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface2).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ResultTab.entries.forEach { tab ->
            val available = tab in state.availableTabs
            val selected = tab == state.selectedTab
            val count = if (available && report != null) tabCount(tab, report) else null
            val phaseColor = colors.phase(tab.phase())
            Row(
                modifier = Modifier
                    .clickable(enabled = available) { onTabSelected(tab) }
                    .background(if (selected) colors.surface1 else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .background(if (available) phaseColor else colors.textMuted, CircleShape)
                        .padding(3.dp)
                )
                Text(
                    text = tab.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        !available -> colors.textMuted
                        selected -> colors.textPrimary
                        else -> colors.textSecondary
                    }
                )
                if (count != null) Text("$count", fontSize = 11.sp, color = colors.textMuted)
            }
        }
    }
}

/**
 * El recorrido de ejecución (Cuádruplos / 3 Direcciones) sigue la misma regla que el código intermedio: necesita una
 * compilación sin errores y, en Paso a paso, haber llegado a la fase de Código intermedio.
 */
private fun executionWalkthroughBlockReason(state: CompilerUiState, report: CompilationReport): String? = when {
    report.intermediateCodeBlockReason() != null ->
        "El recorrido de la ejecución necesita una compilación sin errores, igual que el código intermedio."
    !state.isRevealed(CompilerPhase.INTERMEDIATE_CODE) ->
        "El recorrido de la ejecución se habilita al llegar a Código intermedio en Paso a paso."
    else -> null
}

private fun ResultTab.phase(): CompilerPhase = when (this) {
    ResultTab.TOKENS -> CompilerPhase.LEXICAL
    ResultTab.TREE -> CompilerPhase.SYNTACTIC
    ResultTab.SYMBOLS -> CompilerPhase.SEMANTIC
    ResultTab.INTERMEDIATE_CODE -> CompilerPhase.INTERMEDIATE_CODE
}

/** Número que acompaña a la pestaña: tokens, símbolos o instrucciones del código intermedio (si no está bloqueado). */
private fun tabCount(tab: ResultTab, report: CompilationReport): Int? = when (tab) {
    ResultTab.TOKENS -> report.tokens.count { it.category != TokenCategory.EOF }
    ResultTab.SYMBOLS -> report.symbolEntries.size
    ResultTab.TREE -> null
    ResultTab.INTERMEDIATE_CODE -> if (report.intermediateCodeBlockReason() == null) report.intermediateCode.quadruples.size else null
}

@Composable
private fun Placeholder(title: String, detail: String, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
            Text(detail, fontSize = 13.sp, color = colors.textSecondary, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
    }
}
