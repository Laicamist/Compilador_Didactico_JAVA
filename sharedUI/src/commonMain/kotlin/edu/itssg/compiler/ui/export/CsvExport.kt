package edu.itssg.compiler.ui.export

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.ir.IrFormatKind
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

/**
 * CSV (RFC 4180): separador coma, comillas dobles cuando hace falta y UTF-8 **con BOM** para que Excel en español
 * muestre bien los acentos al abrirlo con doble clic.
 */
internal fun csvOf(header: List<String>, rows: List<List<String>>): ByteArray {
    val text = buildString {
        append(header.joinToString(",") { csvCell(it) })
        for (row in rows) {
            append("\r\n")
            append(row.joinToString(",") { csvCell(it) })
        }
        append("\r\n")
    }
    return UTF8_BOM + text.encodeToByteArray()
}

internal fun <T> csvOfColumns(columns: List<TableColumnSpec<T>>, rows: List<T>): ByteArray =
    csvOf(columns.map { it.title }, rows.map { row -> columns.map { it.text(row) } })

/** Celda CSV: entre comillas si tiene coma, comillas o saltos; las comillas internas se duplican. */
internal fun csvCell(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
        value.startsWith(' ') || value.endsWith(' ')
    return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private const val CSV_MIME = "text/csv"

/**
 * Archivos CSV de una compilación, con nombres `Base_tokens.csv`, `Base_simbolos.csv`… El código intermedio solo se
 * exporta si no está bloqueado por errores (misma regla que la app).
 */
internal fun buildCsvFiles(report: CompilationReport, base: String): List<ExportFile> {
    val files = ArrayList<ExportFile>()
    files += ExportFile("${base}_tokens.csv", CSV_MIME, csvOfColumns(TokenTable.columns, buildTokenRows(report)))
    files += ExportFile("${base}_simbolos.csv", CSV_MIME, csvOfColumns(SymbolTableSpec.columns, buildSymbolRows(report)))
    files += ExportFile("${base}_errores.csv", CSV_MIME, csvOfColumns(ErrorTableSpec.columns, errorRowsFromReport(report.errors)))
    if (report.notes.isNotEmpty()) {
        files += ExportFile(
            "${base}_observaciones.csv", CSV_MIME,
            csvOf(
                listOf("Línea", "Columna", "Tipo", "Observación"),
                report.notes.map { listOf(it.line.toString(), it.column.toString(), it.kind.displayName, it.message) }
            )
        )
    }
    if (report.intermediateCodeBlockReason() != null) return files

    for (format in IrFormatKind.entries) {
        val comparison = buildIrComparison(report, format, optimizationRevealed = true)
        val slug = when (format) {
            IrFormatKind.TRIPLE -> "triplos"
            IrFormatKind.QUADRUPLE -> "cuadruplos"
            IrFormatKind.THREE_ADDRESS -> "tres_direcciones"
        }
        val rows = comparison.rows.map { row ->
            buildList {
                add(row.originalStep?.toString().orEmpty())
                add(row.originalText.orEmpty())
                add(row.originalNote.orEmpty())
                if (comparison.includesOptimized) {
                    add(
                        row.applied?.let { a ->
                            a.displayCode + (a.affectedStep?.let { " $it" } ?: "") + if (row.isRemoved) " -> ELIMINAR" else ""
                        }.orEmpty()
                    )
                    add(row.optimizedStep?.toString().orEmpty())
                    add(row.optimizedText.orEmpty())
                }
            }
        }
        val header = buildList {
            add("Paso"); add(format.displayName); add("Nota")
            if (comparison.includesOptimized) {
                add("Optimización"); add("Paso optimizado"); add("${format.displayName} optimizado")
            }
        }
        files += ExportFile("${base}_$slug.csv", CSV_MIME, csvOf(header, rows))
        if (comparison.optimizationTable.isNotEmpty()) {
            files += ExportFile(
                "${base}_optimizacion_$slug.csv", CSV_MIME,
                csvOfColumns(OptimizationTableSpec.columns(format), comparison.optimizationTable)
            )
        }
    }
    return files
}
