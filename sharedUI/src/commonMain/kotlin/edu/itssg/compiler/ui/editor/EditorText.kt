package edu.itssg.compiler.ui.editor

import edu.itssg.compiler.core.lexer.TokenCategory

/**
 * Texto y selección del editor, sin dependencias de Compose (se convierte a `TextFieldValue` en [CodeEditor]).
 * Así toda la lógica de edición se puede probar en commonTest.
 *
 * @property selectionStart Ancla de la selección (puede ser mayor que [selectionEnd] si se seleccionó hacia atrás).
 * @property selectionEnd Posición del cursor.
 */
data class EditorSnapshot(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart
) {
    val selectionMin: Int get() = minOf(selectionStart, selectionEnd)
    val selectionMax: Int get() = maxOf(selectionStart, selectionEnd)
    val hasSelection: Boolean get() = selectionStart != selectionEnd
    val caret: Int get() = selectionEnd

    /** Copia con la selección dentro de los límites del texto. */
    fun clamped(): EditorSnapshot {
        val length = text.length
        val start = selectionStart.coerceIn(0, length)
        val end = selectionEnd.coerceIn(0, length)
        return if (start == selectionStart && end == selectionEnd) this else copy(selectionStart = start, selectionEnd = end)
    }
}

/** Rango `[start, end)` dentro del texto del editor. */
data class TextSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
    operator fun contains(offset: Int): Boolean = offset in start until end
}

/** Inserción de un solo bloque de texto detectada entre dos estados del editor. */
internal data class Insertion(val position: Int, val inserted: String, val replacedLength: Int)

/** Sugerencia del autocompletado. */
data class CompletionItem(val label: String, val detail: String, val kind: CompletionKind)

enum class CompletionKind { IDENTIFIER, KEYWORD, LIBRARY }

/**
 * Operaciones de edición del editor de código: limpieza de lo que entra, autoindentación, sangría con Tab,
 * búsqueda y reemplazo, y autocompletado. Todas son funciones puras sobre [EditorSnapshot].
 */
internal object EditorText {

    const val INDENT_WIDTH: Int = 4
    val INDENT: String = " ".repeat(INDENT_WIDTH)

    /** Máximo de coincidencias que se marcan al buscar (protege el resaltado en archivos muy grandes). */
    const val MAX_FIND_MATCHES: Int = 5_000

    /** Máximo de sugerencias visibles en el autocompletado. */
    const val MAX_COMPLETIONS: Int = 8

    //Limpieza

    /**
     * Lo que entra al editor (tecleado o pegado) se limpia igual que lo hace la carga de archivos:
     * saltos de línea `\r\n` y `\r` → `\n` y sin caracteres nulos (el Analizador Léxico rechaza `\u0000`).
     * La selección se recalcula sobre el texto limpio.
     */
    fun sanitize(snapshot: EditorSnapshot): EditorSnapshot {
        val text = snapshot.text
        if (text.indexOf('\r') < 0 && text.indexOf(NUL) < 0) return snapshot
        val cleaned = clean(text)
        fun map(offset: Int): Int = clean(text.substring(0, offset.coerceIn(0, text.length))).length
            .coerceAtMost(cleaned.length)
        return EditorSnapshot(cleaned, map(snapshot.selectionStart), map(snapshot.selectionEnd))
    }

    private fun clean(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(NUL.toString(), "")

    //Líneas

    /** Índice donde empieza la línea que contiene [offset]. */
    fun lineStartOf(text: String, offset: Int): Int {
        val safe = offset.coerceIn(0, text.length)
        if (safe == 0) return 0
        return text.lastIndexOf('\n', safe - 1) + 1
    }

    /** Índice del `\n` que termina la línea de [offset] (o el final del texto). */
    fun lineEndOf(text: String, offset: Int): Int {
        val safe = offset.coerceIn(0, text.length)
        val index = text.indexOf('\n', safe)
        return if (index < 0) text.length else index
    }

    /** Número de línea (base 1) de [offset]. */
    fun lineNumberOf(text: String, offset: Int): Int {
        val safe = offset.coerceIn(0, text.length)
        var count = 1
        for (i in 0 until safe) if (text[i] == '\n') count++
        return count
    }

    /** Desplazamiento donde empieza la línea [lineNumber] (base 1), limitado al texto. */
    fun offsetOfLine(text: String, lineNumber: Int): Int {
        if (lineNumber <= 1) return 0
        var line = 1
        for (i in text.indices) {
            if (text[i] == '\n') {
                line++
                if (line == lineNumber) return i + 1
            }
        }
        return text.length
    }

    //Detección de cambios

    /**
     * Si [new] es [old] con la selección reemplazada por un solo bloque de texto (lo normal al teclear o pegar)
     * y el cursor quedó al final de lo insertado, devuelve esa inserción.
     */
    fun detectInsertion(old: EditorSnapshot, new: EditorSnapshot): Insertion? {
        val start = old.selectionMin
        val end = old.selectionMax
        val replaced = end - start
        val insertedLength = new.text.length - (old.text.length - replaced)
        if (insertedLength <= 0) return null
        if (new.hasSelection || new.caret != start + insertedLength) return null
        if (!regionEquals(new.text, 0, old.text, 0, start)) return null
        if (!regionEquals(new.text, start + insertedLength, old.text, end, old.text.length - end)) return null
        return Insertion(start, new.text.substring(start, start + insertedLength), replaced)
    }

    /** `true` si [new] es [old] con exactamente un carácter borrado (Retroceso o Supr sin selección). */
    fun isSingleCharDeletion(old: EditorSnapshot, new: EditorSnapshot): Boolean {
        if (old.hasSelection || new.hasSelection) return false
        if (new.text.length != old.text.length - 1) return false
        val at = new.caret
        if (at < 0 || at > new.text.length) return false
        return regionEquals(new.text, 0, old.text, 0, at) &&
                regionEquals(new.text, at, old.text, at + 1, new.text.length - at)
    }

    //Asistencias al teclear

    /**
     * Autoindentación, aplicada después de cada cambio:
     * - Enter conserva la sangría de la línea; después de `{` agrega un nivel, y entre `{` y `}` abre el bloque
     *   dejando la `}` en su propia línea.
     * - Teclear `}` en una línea que solo tiene espacios quita un nivel de sangría.
     *
     * Funciona igual con teclado físico y con el teclado en pantalla porque solo compara los dos estados.
     * @return el estado corregido, o `null` si no hay nada que ajustar.
     */
    fun applyTypingAssists(old: EditorSnapshot, new: EditorSnapshot): EditorSnapshot? {
        val insertion = detectInsertion(old, new) ?: return null
        return when (insertion.inserted) {
            "\n" -> autoIndentNewLine(old, new, insertion.position)
            "}" -> dedentClosingBrace(new, insertion.position)
            else -> null
        }
    }

    private fun autoIndentNewLine(old: EditorSnapshot, new: EditorSnapshot, insertAt: Int): EditorSnapshot? {
        val lineStart = lineStartOf(old.text, insertAt)
        val lineBefore = old.text.substring(lineStart, insertAt)
        val baseIndent = lineBefore.takeWhile { it == ' ' || it == '\t' }
        val opensBlock = lineBefore.trimEnd().endsWith('{')

        val prefix = new.text.substring(0, insertAt + 1)
        val rawSuffix = new.text.substring(insertAt + 1)
        val suffix = rawSuffix.dropWhile { it == ' ' || it == '\t' }
        val closesNext = suffix.startsWith('}')

        val result = when {
            opensBlock && closesNext -> {
                val inner = baseIndent + INDENT
                val text = prefix + inner + "\n" + baseIndent + suffix
                EditorSnapshot(text, prefix.length + inner.length)
            }
            opensBlock -> {
                val inner = baseIndent + INDENT
                EditorSnapshot(prefix + inner + suffix, prefix.length + inner.length)
            }
            else -> EditorSnapshot(prefix + baseIndent + suffix, prefix.length + baseIndent.length)
        }
        return result.takeIf { it != new }
    }

    private fun dedentClosingBrace(new: EditorSnapshot, braceAt: Int): EditorSnapshot? {
        val lineStart = lineStartOf(new.text, braceAt)
        val indent = new.text.substring(lineStart, braceAt)
        if (indent.isEmpty() || indent.any { it != ' ' && it != '\t' }) return null
        val remove = when {
            indent.last() == '\t' -> 1
            else -> {
                val trailingSpaces = indent.takeLastWhile { it == ' ' }.length
                val excess = trailingSpaces % INDENT_WIDTH
                minOf(trailingSpaces, if (excess == 0) INDENT_WIDTH else excess)
            }
        }
        if (remove <= 0) return null
        val text = new.text.removeRange(braceAt - remove, braceAt)
        return EditorSnapshot(text, new.caret - remove)
    }

    //Sangría con Tab / Shift+Tab

    /**
     * Tab: sin selección (o dentro de una sola línea) inserta espacios hasta el siguiente múltiplo de
     * [INDENT_WIDTH]; con una selección de varias líneas, agrega un nivel de sangría a cada una.
     */
    fun indent(snapshot: EditorSnapshot): EditorSnapshot {
        val s = snapshot.clamped()
        val lines = selectedLineStarts(s)
        if (lines.size <= 1 && !selectionCoversWholeLine(s)) {
            val column = s.selectionMin - lineStartOf(s.text, s.selectionMin)
            val spaces = " ".repeat(INDENT_WIDTH - column % INDENT_WIDTH)
            val text = s.text.replaceRange(s.selectionMin, s.selectionMax, spaces)
            return EditorSnapshot(text, s.selectionMin + spaces.length)
        }
        val builder = StringBuilder(s.text.length + lines.size * INDENT_WIDTH)
        var cursor = 0
        for (start in lines) {
            builder.appendRange(s.text, cursor, start).append(INDENT)
            cursor = start
        }
        builder.appendRange(s.text, cursor, s.text.length)
        fun map(pos: Int): Int = pos + INDENT_WIDTH * lines.count { it < pos }
        return EditorSnapshot(builder.toString(), map(s.selectionStart), map(s.selectionEnd))
    }

    /** Shift+Tab: quita hasta un nivel de sangría (o un tabulador) a cada línea de la selección. */
    fun dedent(snapshot: EditorSnapshot): EditorSnapshot {
        val s = snapshot.clamped()
        val lines = selectedLineStarts(s)
        val removals = lines.map { start ->
            val leading = s.text.substring(start, lineEndOf(s.text, start))
            val count = when {
                leading.startsWith('\t') -> 1
                else -> leading.takeWhile { it == ' ' }.length.coerceAtMost(INDENT_WIDTH)
            }
            start to count
        }.filter { it.second > 0 }
        if (removals.isEmpty()) return s

        val builder = StringBuilder(s.text.length)
        var cursor = 0
        for ((start, count) in removals) {
            builder.appendRange(s.text, cursor, start)
            cursor = start + count
        }
        builder.appendRange(s.text, cursor, s.text.length)
        fun map(pos: Int): Int = pos - removals.sumOf { (start, count) ->
            if (start < pos) minOf(count, pos - start) else 0
        }
        return EditorSnapshot(builder.toString(), map(s.selectionStart), map(s.selectionEnd))
    }

    /** Inicios de las líneas tocadas por la selección (la línea final no cuenta si la selección acaba en su inicio). */
    private fun selectedLineStarts(s: EditorSnapshot): List<Int> {
        val first = lineStartOf(s.text, s.selectionMin)
        var lastOffset = s.selectionMax
        if (s.hasSelection && lastOffset > first && lineStartOf(s.text, lastOffset) == lastOffset) lastOffset--
        val result = mutableListOf(first)
        var i = s.text.indexOf('\n', first)
        while (i in 0 until lastOffset) {
            result.add(i + 1)
            i = s.text.indexOf('\n', i + 1)
        }
        return result
    }

    private fun selectionCoversWholeLine(s: EditorSnapshot): Boolean {
        if (!s.hasSelection) return false
        val start = lineStartOf(s.text, s.selectionMin)
        val end = lineEndOf(s.text, s.selectionMin)
        return s.selectionMin == start && s.selectionMax >= end && end > start
    }

    //Buscar y reemplazar

    /**
     * Coincidencias de [query] en [text] sin traslape.
     * @param wholeWord solo coincidencias que no estén pegadas a letras, dígitos, `_` o `$`.
     */
    fun findMatches(
        text: String,
        query: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        limit: Int = MAX_FIND_MATCHES
    ): List<TextSpan> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val result = ArrayList<TextSpan>()
        var from = 0
        while (from <= text.length - query.length && result.size < limit) {
            val index = text.indexOf(query, from, ignoreCase = !caseSensitive)
            if (index < 0) break
            val end = index + query.length
            val isWord = !wholeWord || (
                    (index == 0 || !isIdentifierChar(text[index - 1])) &&
                            (end == text.length || !isIdentifierChar(text[end]))
                    )
            if (isWord) {
                result.add(TextSpan(index, end))
                from = end
            } else {
                from = index + 1
            }
        }
        return result
    }

    /** Índice de la primera coincidencia que empieza en o después de [offset] (da la vuelta al final). */
    fun matchIndexAtOrAfter(matches: List<TextSpan>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        val index = matches.indexOfFirst { it.start >= offset }
        return if (index < 0) 0 else index
    }

    /** Reemplaza todas las [matches] (ya calculadas sobre [text]) por [replacement]. */
    fun replaceAll(text: String, matches: List<TextSpan>, replacement: String): String {
        if (matches.isEmpty()) return text
        val builder = StringBuilder(text.length + matches.size * (replacement.length - matches[0].length).coerceAtLeast(0))
        var cursor = 0
        for (match in matches) {
            builder.appendRange(text, cursor, match.start).append(replacement)
            cursor = match.end
        }
        builder.appendRange(text, cursor, text.length)
        return builder.toString()
    }

    //Autocompletado

    fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * Prefijo de identificador que termina en [caret] (por ejemplo `pub` en `pub|`).
     * @return inicio del prefijo y el prefijo, o `null` si no hay uno válido (vacío o empieza con dígito).
     */
    fun identifierPrefixAt(text: String, caret: Int): Pair<Int, String>? {
        val end = caret.coerceIn(0, text.length)
        var start = end
        while (start > 0 && isIdentifierChar(text[start - 1])) start--
        if (start == end || text[start].isDigit()) return null
        return start to text.substring(start, end)
    }

    /** Palabras reservadas de la GLC v7 (tabla de tokens) con su token, más las piezas de `System.out.println`. */
    val keywordItems: List<CompletionItem> by lazy {
        val reserved = TokenCategory.KEYWORDS
            .filterKeys { it != "deafult" }
            .map { (word, category) -> CompletionItem(word, category.mnemonic, CompletionKind.KEYWORD) }
        val extras = listOf(
            CompletionItem(TokenCategory.MAIN_LEXEME, TokenCategory.PMAIN.mnemonic, CompletionKind.KEYWORD),
            CompletionItem("System", "Java", CompletionKind.LIBRARY),
            CompletionItem("out", "Java", CompletionKind.LIBRARY),
            CompletionItem("println", "Java", CompletionKind.LIBRARY),
            CompletionItem("length", "Java", CompletionKind.LIBRARY)
        )
        (reserved + extras).distinctBy { it.label }
    }

    /**
     * Sugerencias para [prefix]: identificadores declarados en el programa y palabras reservadas.
     * Primero las que coinciden respetando mayúsculas, después las más cortas.
     */
    fun completionCandidates(
        prefix: String,
        declaredIdentifiers: Collection<String>,
        max: Int = MAX_COMPLETIONS
    ): List<CompletionItem> {
        if (prefix.isEmpty()) return emptyList()
        val keywordLabels = keywordItems.mapTo(HashSet()) { it.label }
        val identifiers = declaredIdentifiers.asSequence()
            .filter { it !in keywordLabels }
            .distinct()
            .map { CompletionItem(it, "ID", CompletionKind.IDENTIFIER) }
        return (identifiers + keywordItems.asSequence())
            .filter { it.label != prefix && it.label.startsWith(prefix, ignoreCase = true) }
            .sortedWith(
                compareBy<CompletionItem>(
                    { if (it.label.startsWith(prefix)) 0 else 1 },
                    { it.kind.ordinal },
                    { it.label.length },
                    { it.label }
                )
            )
            .take(max)
            .toList()
    }

    /** Sustituye el prefijo que termina en el cursor por [item] y deja el cursor al final. */
    fun applyCompletion(snapshot: EditorSnapshot, item: CompletionItem): EditorSnapshot {
        val (start, _) = identifierPrefixAt(snapshot.text, snapshot.caret) ?: (snapshot.caret to "")
        val end = snapshot.caret.coerceIn(start, snapshot.text.length)
        val text = snapshot.text.replaceRange(start, end, item.label)
        return EditorSnapshot(text, start + item.label.length)
    }

    //Utilidades

    private const val NUL: Char = '\u0000'

    private fun regionEquals(a: String, aOffset: Int, b: String, bOffset: Int, length: Int): Boolean {
        if (length < 0 || aOffset < 0 || bOffset < 0) return false
        if (aOffset + length > a.length || bOffset + length > b.length) return false
        for (i in 0 until length) if (a[aOffset + i] != b[bOffset + i]) return false
        return true
    }
}