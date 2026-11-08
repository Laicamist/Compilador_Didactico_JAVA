package edu.itssg.compiler.ui.export

import edu.itssg.compiler.ui.history.localParts
import edu.itssg.compiler.ui.history.localUtcOffsetMillis

/** Archivo listo para guardar. [bytes] ya tiene la codificación final (UTF-8, PDF, PNG…). */
class ExportFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString(): String = "ExportFile($name, ${bytes.size} bytes)"
}

/** Lo que se puede exportar (diálogo Exportar). */
enum class ExportKind(val displayName: String, val detail: String) {
    PDF_REPORT("Reporte PDF", "Código, tablas, errores, árboles (como texto) y código intermedio con su optimización."),
    CSV_TABLES("Tablas en CSV", "Tokens, símbolos, errores, código intermedio y tabla de optimización (se abren en Excel)."),
    TREE_PNG("Árboles en PNG", "Imagen del árbol sintáctico y del abstracto-semántico."),
    TREE_SVG("Árboles en SVG", "Los mismos árboles como dibujo vectorial (no pierde calidad al ampliar o imprimir)."),
    SOURCE_JAVA("Código fuente (.java)", "El programa tal como está en el editor.")
}

/** Nombre base de los archivos a partir del título: `Uno.java` → `Uno`; sin caracteres prohibidos en Windows. */
internal fun exportBaseName(title: String): String {
    val withoutExtension = title.trim().removeSuffix(".java").removeSuffix(".txt")
    val cleaned = buildString {
        for (c in withoutExtension) {
            append(
                when {
                    c in "\\/:*?\"<>|" || c.code < 32 -> '_'
                    c.isWhitespace() -> '_'
                    else -> c
                }
            )
        }
    }.trim('.', '_').take(60)
    return cleaned.ifEmpty { "Sesion" }
}

/** Carpeta de una exportación: `Uno_2026-11-07_1826` (hora local), para no encimar exportaciones anteriores. */
internal fun exportFolderName(title: String, nowMillis: Long, offsetOf: (Long) -> Long = ::localUtcOffsetMillis): String {
    val p = localParts(nowMillis, offsetOf(nowMillis))
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${exportBaseName(title)}_${p.year}-${two(p.month)}-${two(p.day)}_${two(p.hour)}${two(p.minute)}"
}

/** Fecha legible para el encabezado del reporte: `7/11/2026 18:26`. */
internal fun reportDateText(nowMillis: Long, offsetOf: (Long) -> Long = ::localUtcOffsetMillis): String {
    val p = localParts(nowMillis, offsetOf(nowMillis))
    return "${p.day}/${p.month}/${p.year} ${p.hour.toString().padStart(2, '0')}:${p.minute.toString().padStart(2, '0')}"
}
