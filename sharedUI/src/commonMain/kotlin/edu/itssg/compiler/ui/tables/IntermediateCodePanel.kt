package edu.itssg.compiler.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.core.ir.OptimizationTag
import edu.itssg.compiler.core.optimizer.OptimizationTableRow
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.ui.theme.JAutomataColors
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Panel de Código Intermedio (Paso 3.3): selector [Triplos | Cuádruplos | Tres Direcciones], comparación lado a lado
 * del código original y el optimizado (cada cambio con su técnica: FO, PC, RS, SE, CM y sus derivadas).
 *
 * Clic en un renglón: resalta en el editor el fragmento del código que lo genera.
 *
 * @param blockedReason Si no es `null`, el código intermedio está bloqueado (hubo errores o se excedió un límite).
 * @param includeOptimized `false` en Paso a paso antes de llegar a la fase de Optimización. Tres Direcciones nunca
 *   muestra columna optimizada (no se optimiza, por regla de la materia).
 * @param firstError Primer error de la tabla, para el acceso directo del panel bloqueado.
 */
@Composable
fun IntermediateCodePanel(
    report: CompilationReport,
    format: IrFormatKind,
    onFormatChange: (IrFormatKind) -> Unit,
    blockedReason: String?,
    includeOptimized: Boolean,
    isStale: Boolean,
    firstError: CompilerError?,
    onFocus: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    if (blockedReason != null) {
        BlockedIntermediateCode(blockedReason, report, firstError, onFocus, modifier)
        return
    }
    val comparison = remember(report, format, includeOptimized) { buildIrComparison(report, format, optimizationRevealed = includeOptimized) }
    var activeKey by remember(report, format) { mutableStateOf<String?>(null) }
    val clipboard = rememberTextClipboard()

    Column(modifier.background(colors.surface1)) {
        if (isStale) {
            TableNotice(
                "El código cambió desde la última compilación: este código intermedio corresponde a la versión compilada.",
                colors.intermediate
            )
        }
        // Barra: formato, resumen y copiar.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FormatSelector(format, onFormatChange)
            Spacer(Modifier.weight(1f))
            Text(
                text = summaryText(comparison),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (comparison.includesOptimized && comparison.removedCount > 0) colors.optimization else colors.textSecondary,
                maxLines = 1
            )
            SmallAction("⧉ Copiar", colors.accent, onClick = {
                val ok = clipboard.copy(irComparisonToText(comparison))
                onMessage(
                    if (ok) "Se copió el código intermedio (${format.displayName})." else "No se pudo copiar al portapapeles.",
                    !ok
                )
            })
        }
        if (comparison.techniques.isNotEmpty()) TechniqueLegend(comparison)
        when {
            !comparison.isOptimizable -> TableNotice(
                "Tres Direcciones no se optimiza (indicación de las reglas del compilador): la optimización se aplica " +
                    "solo a Triplos y Cuádruplos.",
                colors.textSecondary
            )
            !comparison.includesOptimized -> TableNotice(
                "La columna del código optimizado aparece en el siguiente paso (Optimización).",
                colors.optimization
            )
            !comparison.isAligned -> TableNotice(
                "Los renglones se muestran en orden: no se pudo emparejar cada uno con su versión optimizada.",
                colors.textSecondary
            )
        }
        HorizontalDivider(color = colors.border)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideBySide = comparison.includesOptimized && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH
            Column(Modifier.fillMaxSize()) {
                if (sideBySide) ColumnHeaders()
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    item { BoundaryLine(comparison.header, comparison.header, sideBySide, comparison.includesOptimized) }
                    items(comparison.rows, key = { it.key }) { row ->
                        IrRowView(
                            row = row,
                            format = format,
                            sideBySide = sideBySide,
                            includeOptimized = comparison.includesOptimized,
                            active = row.key == activeKey,
                            onClick = {
                                activeKey = row.key
                                row.span?.let(onFocus)
                            }
                        )
                        HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                    }
                    item {
                        BoundaryLine(comparison.originalClosing, comparison.optimizedClosing, sideBySide, comparison.includesOptimized)
                    }
                    if (comparison.optimizationTable.isNotEmpty()) {
                        item { SectionTitle("Tabla de optimización (U3)", colors.optimization) }
                        item {
                            OptimizationTable(
                                rows = comparison.optimizationTable,
                                format = format,
                                spanOf = { step -> comparison.rows.firstOrNull { it.originalStep == step }?.span },
                                onFocus = onFocus
                            )
                        }
                    }
                    if (report.principalExpressionNotations.isNotEmpty()) {
                        item { SectionTitle("Notación de la expresión principal (U2)", colors.intermediate) }
                        item { NotationList(report.principalExpressionNotations) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

private fun summaryText(c: IrComparison): String = when {
    !c.includesOptimized -> "${c.originalCount} ${if (c.originalCount == 1) "instrucción" else "instrucciones"}"
    c.removedCount == 0 -> "Sin cambios (${c.originalCount} ${if (c.originalCount == 1) "instrucción" else "instrucciones"})"
    else -> "${c.originalCount} → ${c.optimizedCount} instrucciones (−${c.reductionPercent} %)"
}

@Composable
private fun FormatSelector(selected: IrFormatKind, onSelect: (IrFormatKind) -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(1.dp, colors.border, shape).background(colors.surface3)) {
        IrFormatKind.entries.forEach { format ->
            val isSelected = format == selected
            Text(
                text = format.displayName,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) colors.onAccent else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .background(if (isSelected) colors.intermediate else Color.Transparent)
                    .clickable { onSelect(format) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun TechniqueLegend(comparison: IrComparison) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Técnicas aplicadas:", fontSize = 11.sp, color = colors.textMuted)
        comparison.techniques.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Badge(t.code, techniqueColor(t.tag, colors))
                Text("${t.description} ×${t.count}", fontSize = 11.5.sp, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnHeaders() {
    val colors = JAutomataTheme.colors
    Row(Modifier.fillMaxWidth().background(colors.surface2).padding(vertical = 6.dp)) {
        Text(
            "Código original",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.intermediate,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Text(
            "Código optimizado",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.optimization,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
    }
}

/** Encabezado (`ID1 PMAIN`) o cierre (`ID1 PMAIN -> [8]`) del código. */
@Composable
private fun BoundaryLine(original: String, optimized: String, sideBySide: Boolean, includeOptimized: Boolean) {
    val colors = JAutomataTheme.colors
    if (original.isBlank() && optimized.isBlank()) return
    Row(Modifier.fillMaxWidth().background(colors.surface2.copy(alpha = 0.5f)).padding(vertical = 5.dp)) {
        Text(
            original,
            fontFamily = JAutomataFonts.code,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        if (sideBySide) {
            Text(
                optimized,
                fontFamily = JAutomataFonts.code,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
        } else if (includeOptimized && optimized != original) {
            Text(
                "⤷ $optimized",
                fontFamily = JAutomataFonts.code,
                fontSize = 12.sp,
                color = colors.optimization,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun IrRowView(
    row: IrComparisonRow,
    format: IrFormatKind,
    sideBySide: Boolean,
    includeOptimized: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val colors = JAutomataTheme.colors
    val accent = row.applied?.let { techniqueColor(it.tag, colors) }
        ?: row.received.firstOrNull()?.let { techniqueColor(it.tag, colors) }
    val background = when {
        active -> colors.selection
        row.isRemoved && accent != null -> accent.copy(alpha = 0.07f)
        row.received.isNotEmpty() -> colors.optimization.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    val base = Modifier
        .fillMaxWidth()
        .background(background)
        .clickable(enabled = row.span != null, onClick = onClick)

    if (sideBySide) {
        Row(base.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (row.isRemoved) accent ?: Color.Transparent else Color.Transparent))
            Box(Modifier.weight(1f).padding(horizontal = 9.dp)) { OriginalCell(row, format, showExplanation = false) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { OptimizedCell(row, format) }
        }
    } else {
        Column(base.padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OriginalCell(row, format, showExplanation = false)
            if (includeOptimized) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("⤷ ", fontSize = 12.sp, color = colors.optimization, fontFamily = JAutomataFonts.code)
                    OptimizedCell(row, format)
                }
            }
        }
    }
}

@Composable
private fun OriginalCell(row: IrComparisonRow, format: IrFormatKind, showExplanation: Boolean) {
    val colors = JAutomataTheme.colors
    val text = row.originalText ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepNumber(row.originalStep, format)
            Text(
                text = text,
                fontFamily = JAutomataFonts.code,
                fontSize = 12.5.sp,
                color = if (row.isRemoved) colors.textSecondary else colors.textPrimary,
                textDecoration = if (row.isRemoved) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false)
            )
            row.applied?.let { applied ->
                Badge(
                    text = buildString {
                        append(applied.displayCode)
                        applied.affectedStep?.let { append(' ').append(it) }
                        if (row.isRemoved) append(" → ELIMINAR")
                    },
                    color = techniqueColor(applied.tag, colors)
                )
            }
        }
        row.originalNote?.let { note -> NoteText(if (format == IrFormatKind.THREE_ADDRESS) "→ $note" else note) }
        if (showExplanation) row.applied?.let { NoteText(it.explanation) }
    }
}

@Composable
private fun OptimizedCell(row: IrComparisonRow, format: IrFormatKind) {
    val colors = JAutomataTheme.colors
    val text = row.optimizedText
    if (text == null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (row.isRemoved) {
                Text("— eliminado", fontSize = 12.sp, fontStyle = FontStyle.Italic, color = colors.textMuted)
                row.applied?.let { applied ->
                    Text(
                        text = "${applied.description}: ${applied.explanation}",
                        fontSize = 11.5.sp,
                        color = techniqueColor(applied.tag, colors),
                        lineHeight = 15.sp
                    )
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepNumber(row.optimizedStep, format)
            Text(
                text = text,
                fontFamily = JAutomataFonts.code,
                fontSize = 12.5.sp,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f, fill = false)
            )
            row.received.forEach { received ->
                Badge("← ${received.displayCode} ${received.sourceStep}", techniqueColor(received.tag, colors))
            }
        }
        if (row.received.isNotEmpty()) {
            NoteText(
                row.received.joinToString(" · ") { "recibe el resultado de ${it.description.lowercase()} (renglón ${it.sourceStep})" }
            )
        }
        row.optimizedNote?.let { note -> NoteText(if (format == IrFormatKind.THREE_ADDRESS) "→ $note" else note) }
    }
}

@Composable
private fun StepNumber(step: Int?, format: IrFormatKind) {
    val colors = JAutomataTheme.colors
    Text(
        text = step?.let { if (format == IrFormatKind.THREE_ADDRESS) "$it" else "$it." } ?: "",
        fontFamily = JAutomataFonts.code,
        fontSize = 12.sp,
        color = colors.textMuted,
        textAlign = TextAlign.End,
        modifier = Modifier.width(28.dp)
    )
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontFamily = JAutomataFonts.code,
        color = JAutomataTheme.colors.textMuted,
        lineHeight = 14.sp,
        modifier = Modifier.padding(start = 34.dp)
    )
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.width(4.dp).height(14.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = JAutomataTheme.colors.textPrimary)
    }
}

/**
 * Tabla de optimización con el formato de los ejercicios: Cuádruplo original | Optimización | Cuádruplo afectado |
 * Cuádruplo modificado. La celda del cuádruplo final se pinta de verde translúcido (se lee en ambos temas); los
 * eliminados muestran su forma adaptada y "→ Eliminado". Clic en un renglón: resalta el código que lo genera.
 */
@Composable
private fun OptimizationTable(
    rows: List<OptimizationTableRow>,
    format: IrFormatKind,
    spanOf: (Int) -> SourceSpan?,
    onFocus: (SourceSpan) -> Unit
) {
    val colors = JAutomataTheme.colors
    val specs = OptimizationTableSpec.columns(format)
    val widths: List<Dp> = listOf(250.dp, 96.dp, 84.dp, 320.dp)
    val totalWidth = widths.fold(0.dp) { a, w -> a + w }
    val finalFill = colors.optimization.copy(alpha = FINAL_CELL_ALPHA)
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.width(18.dp).height(12.dp)
                    .background(finalFill, RoundedCornerShape(3.dp))
                    .border(1.dp, colors.optimization.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            )
            Text("${OptimizationTableSpec.instructionName(format)} final", fontSize = 11.5.sp, color = colors.textSecondary)
            Text("·  ~ sin cambio", fontSize = 11.5.sp, color = colors.textMuted)
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
        ) {
            Row(Modifier.background(colors.surface2)) {
                specs.forEachIndexed { i, spec ->
                    Text(
                        spec.title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                        modifier = Modifier.width(widths[i]).padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            rows.forEach { row ->
                HorizontalDivider(color = colors.border.copy(alpha = 0.6f), modifier = Modifier.width(totalWidth))
                val span = spanOf(row.stepNumber)
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .clickable(enabled = span != null) { span?.let(onFocus) }
                ) {
                    // Original
                    TableCell(widths[0]) {
                        Text(
                            OptimizationTableSpec.originalText(row, format),
                            fontSize = 12.sp,
                            fontFamily = JAutomataFonts.code,
                            color = colors.textPrimary
                        )
                    }
                    CellDivider()
                    // Optimización
                    TableCell(widths[1]) {
                        val tag = row.tag
                        if (tag != null) Badge(row.displayCode.orEmpty(), techniqueColor(tag, colors))
                        else Text(OptimizationTableSpec.UNCHANGED, fontSize = 13.sp, color = colors.textMuted)
                    }
                    CellDivider()
                    // Afectado
                    TableCell(widths[2]) {
                        Text(
                            row.affectedStep?.toString() ?: OptimizationTableSpec.UNCHANGED,
                            fontSize = 12.5.sp,
                            fontFamily = JAutomataFonts.code,
                            color = if (row.affectedStep != null) colors.textPrimary else colors.textMuted
                        )
                    }
                    CellDivider()
                    // Modificado
                    TableCell(widths[3], background = if (row.isFinal) finalFill else Color.Transparent) {
                        when {
                            row.isUnchanged -> Text(OptimizationTableSpec.UNCHANGED, fontSize = 13.sp, color = colors.textPrimary)
                            row.isFinal -> Text(
                                OptimizationTableSpec.modifiedInstruction(row, format),
                                fontSize = 12.sp,
                                fontFamily = JAutomataFonts.code,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        OptimizationTableSpec.modifiedInstruction(row, format),
                                        fontSize = 12.sp,
                                        fontFamily = JAutomataFonts.code,
                                        color = colors.textSecondary
                                    )
                                    Text(
                                        "→ Eliminado",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = row.tag?.let { techniqueColor(it, colors) } ?: colors.textSecondary
                                    )
                                }
                                row.explanation?.let {
                                    Text(it, fontSize = 11.sp, color = colors.textMuted, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(width: Dp, background: Color = Color.Transparent, content: @Composable () -> Unit) {
    Box(
        Modifier.width(width).fillMaxHeight().background(background).padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
private fun CellDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(JAutomataTheme.colors.border.copy(alpha = 0.6f)))
}

@Composable
private fun NotationList(lines: List<String>) {
    val colors = JAutomataTheme.colors
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface3)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { line ->
            val label = line.substringBefore(":", missingDelimiterValue = "")
            if (label.isNotEmpty() && label.length <= 24) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$label:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary, modifier = Modifier.widthIn(min = 132.dp))
                    Text(line.substringAfter(":").trim(), fontSize = 12.5.sp, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
                }
            } else {
                Text(line, fontSize = 12.5.sp, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun BlockedIntermediateCode(
    reason: String,
    report: CompilationReport,
    firstError: CompilerError?,
    onFocus: (SourceSpan) -> Unit,
    modifier: Modifier
) {
    val colors = JAutomataTheme.colors
    Box(modifier.background(colors.surface1).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 460.dp)
        ) {
            Text(
                "Código intermedio bloqueado",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary
            )
            Text(reason, fontSize = 13.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
            val counts = listOf(
                report.lexicalErrorCount to "léxicos",
                report.syntacticErrorCount to "sintácticos",
                report.semanticErrorCount to "semánticos"
            ).filter { it.first > 0 }
            if (counts.isNotEmpty()) {
                Text(
                    counts.joinToString(" · ") { "${it.first} ${it.second}" },
                    fontSize = 12.sp,
                    color = colors.errorLexical
                )
            }
            if (firstError != null) {
                Button(
                    onClick = { onFocus(firstError.span) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) { Text("Ir al primer error (línea ${firstError.line})") }
            }
            Text(
                "Regla del compilador: cualquier error léxico, sintáctico o semántico impide generar el código intermedio y su optimización.",
                fontSize = 11.5.sp,
                color = colors.textMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Color de cada técnica de optimización. */
internal fun techniqueColor(tag: OptimizationTag, colors: JAutomataColors): Color = when (tag) {
    OptimizationTag.EXPRESSION_SIMPLIFICATION -> colors.lexical
    OptimizationTag.FOLDING -> colors.intermediate
    OptimizationTag.CONSTANT_PROPAGATION -> colors.semantic
    OptimizationTag.SUBEXPRESSION_REDUCTION -> colors.syntactic
    OptimizationTag.DEAD_CODE -> colors.errorLexical
}

private val SIDE_BY_SIDE_MIN_WIDTH = 560.dp

/** Verde translúcido del cuádruplo final: deja leer el texto tanto en el tema claro como en el oscuro. */
private const val FINAL_CELL_ALPHA = 0.24f
