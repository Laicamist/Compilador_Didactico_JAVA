package edu.itssg.compiler.ui.export

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.ui.tree.VisualTreeBuilder

/**
 * Resultado de preparar una exportación.
 *
 * @property folderName Carpeta donde se guardan los archivos (`Uno_2026-11-07_1826`).
 * @property warnings Avisos para el usuario (por ejemplo, un árbol demasiado grande para PNG).
 */
class ExportBundle(val folderName: String, val files: List<ExportFile>, val warnings: List<String>)

/**
 * Prepara los archivos de [kinds] para la compilación [report]. Todo es lógica común salvo el PNG, que pinta la
 * plataforma con [pngEncoder] (recibe el dibujo del árbol y regresa los bytes PNG, o `null` si es demasiado grande).
 */
internal fun buildExportBundle(
    report: CompilationReport,
    title: String,
    kinds: Set<ExportKind>,
    nowMillis: Long,
    dateText: String = reportDateText(nowMillis),
    folderName: String = exportFolderName(title, nowMillis),
    pngEncoder: ((TreePicture) -> ByteArray?)? = null
): ExportBundle {
    val base = exportBaseName(title)
    val files = ArrayList<ExportFile>()
    val warnings = ArrayList<String>()

    if (ExportKind.PDF_REPORT in kinds) {
        files += ExportFile("${base}_reporte.pdf", "application/pdf", buildReportPdf(report, title, dateText))
    }
    if (ExportKind.CSV_TABLES in kinds) files += buildCsvFiles(report, base)
    if (ExportKind.SOURCE_JAVA in kinds) {
        files += ExportFile("$base.java", "text/x-java-source", report.sourceCode.encodeToByteArray())
    }
    if (ExportKind.TREE_PNG in kinds || ExportKind.TREE_SVG in kinds) {
        val trees = listOf(
            Triple("arbol_sintactico", "Árbol sintáctico", VisualTreeBuilder.fromSyntax(report.syntaxTree, report.errors)),
            Triple(
                "arbol_semantico", "Árbol abstracto-semántico",
                VisualTreeBuilder.fromSemantic(report.semanticTree, report.errors, report.symbolEntries)
            )
        )
        for ((slug, label, tree) in trees) {
            val picture = buildTreePicture(tree, "$label · $title")
            if (tree.isTruncated) {
                warnings += "El $label tiene ${tree.totalNodes} nodos; se exportaron los primeros ${tree.size}."
            }
            if (ExportKind.TREE_SVG in kinds) {
                files += ExportFile("${base}_$slug.svg", "image/svg+xml", picture.toSvg().encodeToByteArray())
            }
            if (ExportKind.TREE_PNG in kinds) {
                val png = pngEncoder?.invoke(picture)
                if (png != null) {
                    files += ExportFile("${base}_$slug.png", "image/png", png)
                } else {
                    warnings += "El $label es demasiado grande para PNG; expórtalo en SVG."
                }
            }
        }
    }
    return ExportBundle(folderName, files, warnings)
}
