package edu.itssg.compiler.ui.export

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.ui.export.PdfDocument.Font
import edu.itssg.compiler.ui.export.PdfDocument.Rgb
import edu.itssg.compiler.ui.state.PhaseStatus
import edu.itssg.compiler.ui.state.buildPhaseStates
import edu.itssg.compiler.ui.state.intermediateCodeBlockReason
import edu.itssg.compiler.ui.tables.ErrorTableSpec
import edu.itssg.compiler.ui.tables.OptimizationTableSpec
import edu.itssg.compiler.ui.tables.SymbolTableSpec
import edu.itssg.compiler.ui.tables.TableColumnSpec
import edu.itssg.compiler.ui.tables.TokenTable
import edu.itssg.compiler.ui.tables.buildIrComparison
import edu.itssg.compiler.ui.tables.buildSymbolRows
import edu.itssg.compiler.ui.tables.buildTokenRows
import edu.itssg.compiler.ui.tables.errorRowsFromReport
import edu.itssg.compiler.ui.tree.VisualTreeBuilder
import edu.itssg.compiler.ui.tree.toIndentedText
import kotlin.math.max
import kotlin.math.min

/**
 * Reporte PDF de una compilación, en el orden de las fases: resumen, código fuente, tabla de tokens, tabla de
 * símbolos, tabla de errores, observaciones, árboles (como texto con sangría), código intermedio (triplos, cuádruplos
 * y 3 direcciones, original y optimizado) y tablas de optimización de la U3.
 */
internal fun buildReportPdf(report: CompilationReport, title: String, dateText: String): ByteArray {
    val pdf = PdfDocument(footerTitle = "J-Autómata Studio · $title")

    pdf.paragraph("J-Autómata Studio", Font.BOLD, 18f, spacingAfter = 0f)
    pdf.paragraph("Reporte de compilación · Compilador didáctico · GLC v7", Font.REGULAR, 10f, Rgb.MUTED, spacingAfter = 6f)
    pdf.paragraph("Sesión: $title", Font.BOLD, 11f, spacingAfter = 0f)
    pdf.paragraph("Fecha: $dateText", Font.REGULAR, 9.5f, Rgb.MUTED, spacingAfter = 8f)

    // Resumen de fases (misma regla de bloqueo que el espacio de trabajo).
    pdf.heading("Resumen")
    for (phase in buildPhaseStates(report, revealedThroughIndex = null)) {
        val mark = when (phase.status) {
            PhaseStatus.SUCCESS -> "Correcta"
            PhaseStatus.FAILED -> "Con errores"
            PhaseStatus.BLOCKED -> "Bloqueada"
            else -> "—"
        }
        pdf.paragraph("${phase.phase.displayName}: $mark · ${phase.summary}", indent = 6f, spacingAfter = 1f)
    }
    pdf.space(4f)

    pdf.heading("Código fuente")
    val sourceLines = report.sourceCode.split('\n')
    val digits = sourceLines.size.toString().length
    pdf.monoLines(sourceLines.mapIndexed { i, line -> "${(i + 1).toString().padStart(digits)}  $line" })

    val tableChars = pdf.maxMonoChars(TABLE_FONT_SIZE)

    pdf.heading("Tabla de tokens")
    pdf.monoLines(monoTableOf(TokenTable.columns, buildTokenRows(report), tableChars), TABLE_FONT_SIZE, boldFirst = true)

    pdf.heading("Tabla de símbolos")
    pdf.monoLines(monoTableOf(SymbolTableSpec.columns, buildSymbolRows(report), tableChars), TABLE_FONT_SIZE, boldFirst = true)

    pdf.heading("Tabla de errores")
    if (report.errors.isEmpty()) {
        pdf.paragraph("Sin errores léxicos, sintácticos ni semánticos.", color = Rgb.MUTED)
    } else {
        pdf.monoLines(monoTableOf(ErrorTableSpec.columns, errorRowsFromReport(report.errors), tableChars), TABLE_FONT_SIZE, boldFirst = true)
    }
    report.quotaAbortedMessage?.let { pdf.paragraph(it, Font.BOLD) }

    if (report.notes.isNotEmpty()) {
        pdf.heading("Observaciones (no son errores)")
        for (note in report.notes) {
            pdf.paragraph("Línea ${note.line}, columna ${note.column} · ${note.kind.displayName}", Font.BOLD, 9f, spacingAfter = 0f)
            pdf.paragraph(note.message, indent = 8f)
        }
    }

    pdf.heading("Árbol sintáctico")
    val syntax = VisualTreeBuilder.fromSyntax(report.syntaxTree, report.errors)
    pdf.monoLines(limitLines(syntax.toIndentedText().trimEnd().split('\n')), TREE_FONT_SIZE)

    pdf.heading("Árbol abstracto-semántico")
    val semantic = VisualTreeBuilder.fromSemantic(report.semanticTree, report.errors, report.symbolEntries)
    pdf.monoLines(limitLines(semantic.toIndentedText().trimEnd().split('\n')), TREE_FONT_SIZE)

    pdf.heading("Código intermedio")
    val blocked = report.intermediateCodeBlockReason()
    if (blocked != null) {
        pdf.paragraph("Bloqueado: $blocked", Font.BOLD)
    } else {
        for (format in IrFormatKind.entries) {
            val comparison = buildIrComparison(report, format, optimizationRevealed = true)
            pdf.paragraph(format.displayName, Font.BOLD, 11f, spacingAfter = 2f)
            val original = buildList {
                add(comparison.header)
                comparison.rows.filter { it.originalText != null }.forEach { row ->
                    val mark = row.applied?.let { a ->
                        "  " + a.displayCode + (a.affectedStep?.let { " $it" } ?: "") + if (row.isRemoved) " -> ELIMINAR" else ""
                    }.orEmpty()
                    val note = row.originalNote?.let { if (format == IrFormatKind.THREE_ADDRESS) " --> $it" else "  $it" }.orEmpty()
                    add("${row.originalStep}. ${row.originalText}$mark$note")
                }
                add(comparison.originalClosing)
            }
            pdf.monoLines(original, IR_FONT_SIZE)
            if (comparison.includesOptimized) {
                pdf.paragraph(
                    "${format.displayName} optimizado · ${comparison.originalCount} → ${comparison.optimizedCount} " +
                        "instrucciones (−${comparison.reductionPercent} %)",
                    Font.BOLD, 9.5f, spacingAfter = 2f
                )
                val optimized = buildList {
                    add(comparison.header)
                    comparison.rows.filter { it.optimizedText != null }.forEach { add("${it.optimizedStep}. ${it.optimizedText}") }
                    add(comparison.optimizedClosing)
                }
                pdf.monoLines(optimized, IR_FONT_SIZE)
                if (comparison.optimizationTable.isNotEmpty()) {
                    pdf.paragraph("Tabla de optimización (U3) · ${format.displayName}", Font.BOLD, 9.5f, spacingAfter = 2f)
                    pdf.monoLines(
                        monoTableOf(OptimizationTableSpec.columns(format), comparison.optimizationTable, tableChars),
                        TABLE_FONT_SIZE,
                        boldFirst = true
                    )
                }
            } else if (format == IrFormatKind.THREE_ADDRESS) {
                pdf.paragraph("El código de 3 direcciones no se optimiza (indicación de la materia).", color = Rgb.MUTED)
            }
        }
    }
    return pdf.build("Reporte de compilación · $title")
}

private const val TABLE_FONT_SIZE = 7.2f
private const val TREE_FONT_SIZE = 7.2f
private const val IR_FONT_SIZE = 8f

/** Tope de renglones por árbol en el PDF (un árbol enorme se exporta completo en PNG/SVG). */
private const val MAX_TREE_LINES = 2_500

private fun limitLines(lines: List<String>): List<String> =
    if (lines.size <= MAX_TREE_LINES) lines
    else lines.take(MAX_TREE_LINES) + "… (${lines.size - MAX_TREE_LINES} renglones más; exporta el árbol en PNG o SVG para verlo completo)"

/**
 * Tabla monoespaciada que cabe en [maxChars]: encabezado, separador y filas. Cada columna mide lo de su texto más
 * largo; si no caben, se recortan primero las más anchas y el texto que no cabe pasa a renglones de continuación
 * dentro de su celda.
 */
internal fun <T> monoTableOf(columns: List<TableColumnSpec<T>>, rows: List<T>, maxChars: Int): List<String> {
    val header = columns.map { it.title }
    val cells = rows.map { row -> columns.map { it.text(row).replace('\n', ' ').replace('\t', ' ') } }
    return monoTable(header, cells, maxChars)
}

internal fun monoTable(header: List<String>, rows: List<List<String>>, maxChars: Int): List<String> {
    val gap = 2
    val count = header.size
    if (count == 0) return emptyList()
    val widths = IntArray(count) { c ->
        max(header[c].length, rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0).coerceAtLeast(1)
    }
    val available = maxChars - gap * (count - 1)
    // Reduce la columna más ancha (sin bajar del ancho de su título ni de 6) hasta que todo quepa.
    while (widths.sum() > available) {
        val widest = widths.indices.maxByOrNull { widths[it] } ?: break
        val floor = min(max(6, header[widest].length), widths[widest])
        if (widths[widest] <= floor) {
            // Ya no se puede respetar el título: se reparte lo que hay.
            val each = (available / count).coerceAtLeast(3)
            for (i in widths.indices) widths[i] = min(widths[i], each)
            break
        }
        widths[widest]--
    }

    fun renderRow(values: List<String>): List<String> {
        val wrapped = values.mapIndexed { c, v -> wrapCell(v, widths[c]) }
        val height = wrapped.maxOf { it.size }
        return (0 until height).map { line ->
            (0 until count).joinToString(" ".repeat(gap)) { c ->
                wrapped[c].getOrElse(line) { "" }.padEnd(widths[c])
            }.trimEnd()
        }
    }

    val result = ArrayList<String>()
    result += renderRow(header).first()
    result += (0 until count).joinToString(" ".repeat(gap)) { "-".repeat(widths[it]) }
    for (row in rows) result += renderRow(List(count) { row.getOrElse(it) { "" } })
    return result
}

/** Acomoda [text] en renglones de [width] caracteres, cortando de preferencia en espacios. */
private fun wrapCell(text: String, width: Int): List<String> {
    if (text.length <= width) return listOf(text)
    val lines = ArrayList<String>()
    var rest = text
    while (rest.length > width) {
        val space = rest.lastIndexOf(' ', width)
        val cut = if (space > width / 3) space else width
        lines += rest.substring(0, cut).trimEnd()
        rest = rest.substring(cut).trimStart()
    }
    if (rest.isNotEmpty()) lines += rest
    return lines
}
