package edu.itssg.compiler.ui.export

import kotlin.math.max

/**
 * Generador mínimo de PDF 1.4 (solo texto y líneas), sin bibliotecas: funciona igual en Desktop, Android e iOS.
 *
 * - Usa las 14 fuentes estándar de PDF (Helvetica y Courier), que todo lector trae: el archivo no lleva fuentes.
 * - Texto en WinAnsi (Windows-1252), que cubre el español (á, ñ, ¿, “ ”…). Los símbolos que no existen ahí
 *   (→, ε, ✓, ①…) se cambian por un equivalente legible ([toWinAnsi]).
 * - Página A4 vertical; el texto se acomoda por palabras y salta de página solo. Al final se agrega el pie
 *   "título · página n de N".
 */
internal class PdfDocument(
    private val footerTitle: String,
    private val pageWidth: Float = 595.28f,
    private val pageHeight: Float = 841.89f,
    private val margin: Float = 42f
) {
    enum class Font(val resource: String, val baseFont: String) {
        REGULAR("F1", "Helvetica"),
        BOLD("F2", "Helvetica-Bold"),
        MONO("F3", "Courier"),
        MONO_BOLD("F4", "Courier-Bold")
    }

    /** Color RGB de 0 a 1. */
    data class Rgb(val r: Float, val g: Float, val b: Float) {
        companion object {
            val TEXT = Rgb(0.10f, 0.10f, 0.12f)
            val MUTED = Rgb(0.42f, 0.42f, 0.46f)
            val RULE = Rgb(0.80f, 0.80f, 0.84f)
        }
    }

    private val pages = ArrayList<StringBuilder>()
    private var page = StringBuilder()
    private var cursorY = 0f
    private val contentWidth get() = pageWidth - 2 * margin
    private val bottomLimit get() = margin + FOOTER_SPACE

    init {
        newPage()
    }

    val pageCount: Int get() = pages.size

    fun newPage() {
        page = StringBuilder()
        pages += page
        cursorY = pageHeight - margin
    }

    /** Espacio vertical. */
    fun space(points: Float) {
        cursorY -= points
        if (cursorY < bottomLimit) newPage()
    }

    /** Párrafo acomodado por palabras al ancho de la página. */
    fun paragraph(
        text: String,
        font: Font = Font.REGULAR,
        size: Float = 9.5f,
        color: Rgb = Rgb.TEXT,
        indent: Float = 0f,
        spacingAfter: Float = 3f
    ) {
        val lineHeight = size * 1.32f
        for (rawLine in text.split('\n')) {
            val lines = wrap(rawLine, font, size, contentWidth - indent)
            for (line in lines) {
                ensureSpace(lineHeight)
                cursorY -= lineHeight
                drawText(line, margin + indent, cursorY + size * 0.25f, font, size, color)
            }
        }
        space(spacingAfter)
    }

    /** Título de sección con una línea debajo; empieza en página nueva si queda poco espacio. */
    fun heading(text: String, size: Float = 13f) {
        if (cursorY - size * 4 < bottomLimit) newPage() else space(size * 0.6f)
        paragraph(text, Font.BOLD, size, Rgb.TEXT, spacingAfter = 0f)
        line(margin, cursorY - 2f, pageWidth - margin, cursorY - 2f, Rgb.RULE)
        space(8f)
    }

    /**
     * Renglones monoespaciados (código, árboles, código intermedio), sin acomodar por palabras: lo que no cabe se
     * corta a renglones de continuación que empiezan con "↪".
     */
    fun monoLines(lines: List<String>, size: Float = 8f, color: Rgb = Rgb.TEXT, boldFirst: Boolean = false) {
        val lineHeight = size * 1.25f
        val maxChars = maxMonoChars(size)
        lines.forEachIndexed { index, original ->
            val font = if (boldFirst && index == 0) Font.MONO_BOLD else Font.MONO
            val chunks = chunkMono(original, maxChars)
            for (chunk in chunks) {
                ensureSpace(lineHeight)
                cursorY -= lineHeight
                drawText(chunk, margin, cursorY + size * 0.25f, font, size, color)
            }
        }
        space(4f)
    }

    /** Caracteres de Courier que caben en el ancho de la página con el tamaño [size]. */
    fun maxMonoChars(size: Float): Int = (contentWidth / (size * MONO_WIDTH)).toInt().coerceAtLeast(20)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Rgb, width: Float = 0.6f) {
        page.append("${n(width)} w ${n(color.r)} ${n(color.g)} ${n(color.b)} RG ${n(x1)} ${n(y1)} m ${n(x2)} ${n(y2)} l S\n")
    }

    private fun ensureSpace(height: Float) {
        if (cursorY - height < bottomLimit) newPage()
    }

    private fun drawText(text: String, x: Float, y: Float, font: Font, size: Float, color: Rgb) {
        if (text.isEmpty()) return
        page.append("BT /${font.resource} ${n(size)} Tf ${n(color.r)} ${n(color.g)} ${n(color.b)} rg ")
        page.append("1 0 0 1 ${n(x)} ${n(y)} Tm (")
        page.append(pdfString(text))
        page.append(") Tj ET\n")
    }

    /** Acomoda [text] por palabras al ancho [width]; una palabra más larga que el ancho se corta. */
    private fun wrap(text: String, font: Font, size: Float, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (textWidth(candidate, font, size) <= width) {
                current.setLength(0)
                current.append(candidate)
                continue
            }
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
            var rest = word
            while (textWidth(rest, font, size) > width && rest.length > 1) {
                var cut = rest.length - 1
                while (cut > 1 && textWidth(rest.substring(0, cut), font, size) > width) cut--
                result += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            current.append(rest)
        }
        if (current.isNotEmpty() || result.isEmpty()) result += current.toString()
        return result
    }

    /** Arma el archivo: catálogo, páginas, fuentes, contenidos, tabla xref e información del documento. */
    fun build(title: String): ByteArray {
        // Pie de página en cada hoja (ahora ya se sabe el total).
        pages.forEachIndexed { index, content ->
            val footer = "$footerTitle  ·  página ${index + 1} de ${pages.size}"
            content.append("BT /F1 7.5 Tf ${n(Rgb.MUTED.r)} ${n(Rgb.MUTED.g)} ${n(Rgb.MUTED.b)} rg ")
            content.append("1 0 0 1 ${n(margin)} ${n(margin - 14f)} Tm (").append(pdfString(footer)).append(") Tj ET\n")
        }

        val out = PdfBytes()
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += out.size
            out.ascii("${offsets.size} 0 obj\n$body\nendobj\n")
        }

        out.ascii("%PDF-1.4\n")
        out.raw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        val fontBase = 3
        val firstPage = fontBase + Font.entries.size
        val pageIds = pages.indices.map { firstPage + it * 2 }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        val fontRefs = Font.entries.mapIndexed { i, f -> "/${f.resource} ${fontBase + i} 0 R" }.joinToString(" ")
        for (f in Font.entries) {
            obj("<< /Type /Font /Subtype /Type1 /BaseFont /${f.baseFont} /Encoding /WinAnsiEncoding >>")
        }
        pages.forEachIndexed { i, content ->
            val contentId = pageIds[i] + 1
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] " +
                    "/Resources << /Font << $fontRefs >> >> /Contents $contentId 0 R >>"
            )
            val bytes = content.toString().encodeToByteArray() // solo ASCII: lo no ASCII ya va como \ooo
            offsets += out.size
            out.ascii("${offsets.size} 0 obj\n<< /Length ${bytes.size} >>\nstream\n")
            out.raw(bytes)
            out.ascii("\nendstream\nendobj\n")
        }
        val producer = pdfTextString("J-Autómata Studio")
        obj("<< /Title ${pdfTextString(title)} /Producer $producer /Creator $producer >>")
        val infoId = offsets.size

        val xrefStart = out.size
        out.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) out.ascii("${offset.toString().padStart(10, '0')} 00000 n \n")
        out.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $infoId 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")
        return out.toByteArray()
    }

    companion object {
        /** Ancho de un carácter de Courier en relación con el tamaño de letra. */
        const val MONO_WIDTH = 0.6f
        private const val FOOTER_SPACE = 6f

        fun textWidth(text: String, font: Font, size: Float): Float = when (font) {
            Font.MONO, Font.MONO_BOLD -> text.length * MONO_WIDTH * size
            Font.REGULAR -> text.sumOf { helveticaWidth(it) } * size / 1000f
            Font.BOLD -> text.sumOf { helveticaWidth(it) } * size / 1000f * 1.06f
        }

        /** Corta un renglón monoespaciado en pedazos de [maxChars]; los de continuación empiezan con "↪ ". */
        fun chunkMono(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val chunks = ArrayList<String>()
            chunks += text.substring(0, maxChars)
            var rest = text.substring(maxChars)
            val width = max(1, maxChars - 2)
            while (rest.isNotEmpty()) {
                chunks += "↪ " + rest.take(width)
                rest = rest.drop(width)
            }
            return chunks
        }
    }
}

/** Número con hasta 2 decimales y punto decimal (PDF no acepta coma ni notación científica). */
private fun n(value: Float): String {
    val rounded = kotlin.math.round(value * 100f) / 100f
    val asLong = rounded.toLong()
    return if (rounded == asLong.toFloat()) asLong.toString() else rounded.toString()
}

/** Contenido de una cadena literal `( … )`: bytes WinAnsi, con `\ ( )` escapados y lo no ASCII en octal. */
internal fun pdfString(text: String): String = buildString {
    for (b in toWinAnsi(text)) {
        val v = b.toInt() and 0xFF
        when {
            v == '\\'.code || v == '('.code || v == ')'.code -> append('\\').append(v.toChar())
            v in 32..126 -> append(v.toChar())
            else -> append('\\').append(v.toString(8).padStart(3, '0'))
        }
    }
}

/** Cadena de texto de PDF en UTF-16BE (para /Title, que puede tener cualquier carácter). */
private fun pdfTextString(text: String): String = buildString {
    append("<FEFF")
    for (c in text) append(c.code.toString(16).padStart(4, '0').uppercase())
    append('>')
}

/**
 * Convierte a bytes de Windows-1252 (WinAnsiEncoding). Latin-1 pasa directo; los caracteres de 0x80–0x9F que
 * tiene Windows-1252 (… – — “ ” ‘ ’ •) se mapean; los símbolos que no existen se reemplazan por texto.
 */
internal fun toWinAnsi(text: String): ByteArray {
    val out = ArrayList<Byte>(text.length)
    fun add(s: String) = s.forEach { out += it.code.toByte() }
    for (c in text) {
        val code = c.code
        val mapped = WIN_ANSI_EXTRAS[c]
        when {
            code in 32..126 || code in 0xA0..0xFF -> out += code.toByte()
            mapped != null -> out += mapped.toByte()
            c == '\t' -> add("    ")
            code in 0x2460..0x2473 -> add("(${code - 0x2460 + 1})") // ① … ⑳
            else -> add(WIN_ANSI_FALLBACK[c] ?: "?")
        }
    }
    return out.toByteArray()
}

private val WIN_ANSI_EXTRAS: Map<Char, Int> = mapOf(
    '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87, 'ˆ' to 0x88,
    '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91, '’' to 0x92, '“' to 0x93,
    '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B,
    'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F
)

private val WIN_ANSI_FALLBACK: Map<Char, String> = mapOf(
    '→' to "->", '⇒' to "=>", '←' to "<-", '↪' to ">", '−' to "-", '≤' to "<=", '≥' to ">=", '≠' to "!=",
    'ε' to "eps", 'λ' to "lambda", 'δ' to "delta", 'Σ' to "Sigma", '✓' to "OK", '✗' to "X", '✕' to "x",
    '⚠' to "!", '▲' to "^", '▼' to "v", '◀' to "<", '▶' to ">", 'ⓘ' to "(i)"
)

/** Anchos de Helvetica (milésimas del tamaño de letra), de la tabla AFM estándar; las letras acentuadas usan su base. */
private fun helveticaWidth(c: Char): Int {
    val base = when (c) {
        in "áàâäãå" -> 'a'
        in "éèêë" -> 'e'
        in "íìîï" -> 'i'
        in "óòôöõ" -> 'o'
        in "úùûü" -> 'u'
        'ñ' -> 'n'
        in "ÁÀÂÄÃÅ" -> 'A'
        in "ÉÈÊË" -> 'E'
        in "ÍÌÎÏ" -> 'I'
        in "ÓÒÔÖÕ" -> 'O'
        in "ÚÙÛÜ" -> 'U'
        'Ñ' -> 'N'
        else -> c
    }
    val code = base.code
    return if (code in 32..126) HELVETICA_WIDTHS[code - 32] else 556
}

private val HELVETICA_WIDTHS = intArrayOf(
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // espacio – /
    556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0 – ?
    1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @ – O
    667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P – _
    333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // ` – o
    556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584       // p – ~
)

/** Bytes del archivo con su posición actual (para la tabla xref). */
private class PdfBytes {
    private val chunks = ArrayList<ByteArray>()
    var size: Int = 0
        private set

    fun ascii(text: String) = raw(text.encodeToByteArray())

    fun raw(bytes: ByteArray) {
        chunks += bytes
        size += bytes.size
    }

    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        var pos = 0
        for (c in chunks) {
            c.copyInto(result, pos)
            pos += c.size
        }
        return result
    }
}
