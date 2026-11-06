package edu.itssg.compiler.ui.editor

import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory

/** Tramo coloreable del texto: un token de la categoría dada o, si [category] es `null`, un comentario. */
internal data class ColoredSpan(val span: TextSpan, val category: TokenCategory?) {
    val isComment: Boolean get() = category == null
}

/**
 * Ubica en el texto ACTUAL del editor los tokens del Analizador Léxico (sin dependencias de Compose, para
 * poder probarlo con el Lexer real en commonTest).
 *
 * Los tokens llegan 150 ms después de la última tecla. Mientras tanto, cada token se valida contra el texto
 * actual y, si no coincide, se prueba desplazado por la diferencia de longitud (lo que pasa con todo lo que
 * está después del punto donde se escribió); así los colores no parpadean al teclear.
 *
 * Los comentarios no son tokens (el Analizador Léxico los descarta), así que se localizan en los huecos entre
 * tokens, donde no pueden confundirse con un `//` dentro de una cadena.
 */
internal object TokenSpans {

    fun locate(text: String, tokens: List<Token>): List<ColoredSpan> {
        if (tokens.isEmpty()) return emptyList()
        val result = ArrayList<ColoredSpan>(tokens.size)
        val tokensSourceLength = tokens.last().span.end.offset
        val delta = text.length - tokensSourceLength
        var gapStart = 0
        for (token in tokens) {
            if (token.category == TokenCategory.EOF || token.lexeme.isEmpty()) continue
            val start = positionOf(text, token, delta) ?: continue
            val end = start + token.lexeme.length
            if (start >= gapStart) collectComments(text, gapStart, start, result)
            gapStart = maxOf(gapStart, end)
            result.add(ColoredSpan(TextSpan(start, end), token.category))
        }
        collectComments(text, gapStart, text.length, result)
        return result
    }

    /** Posición actual del lexema de [token] en [text], o `null` si el token ya no corresponde al texto. */
    private fun positionOf(text: String, token: Token, delta: Int): Int? {
        val original = token.span.start.offset
        if (matchesAt(text, token.lexeme, original)) return original
        val shifted = original + delta
        if (delta != 0 && matchesAt(text, token.lexeme, shifted)) return shifted
        return null
    }

    private fun matchesAt(text: String, lexeme: String, offset: Int): Boolean =
        offset >= 0 && offset + lexeme.length <= text.length && text.startsWith(lexeme, offset)

    /** Comentarios `//` y `/* */` entre [from] y [to] (un hueco sin tokens). */
    private fun collectComments(text: String, from: Int, to: Int, out: MutableList<ColoredSpan>) {
        var i = from
        val limit = minOf(to, text.length)
        while (i < limit - 1) {
            if (text[i] == '/' && text[i + 1] == '/') {
                val newline = text.indexOf('\n', i)
                val end = if (newline < 0 || newline > limit) limit else newline
                out.add(ColoredSpan(TextSpan(i, end), null))
                i = end
            } else if (text[i] == '/' && text[i + 1] == '*') {
                val close = text.indexOf("*/", i + 2)
                val end = if (close < 0 || close + 2 > limit) limit else close + 2
                out.add(ColoredSpan(TextSpan(i, end), null))
                i = end
            } else {
                i++
            }
        }
    }

    /**
     * Pareja de la llave/paréntesis/corchete junto al cursor, usando los tokens LLA/LLC, PA/PC y COA/COC.
     * Solo con tokens al día (con tokens viejos las posiciones no son confiables).
     * @return posiciones (apertura, cierre), o `null` si no hay pareja.
     */
    fun matchingBracket(text: String, tokens: List<Token>, caret: Int): Pair<Int, Int>? {
        if (tokens.isEmpty() || tokens.last().span.end.offset != text.length) return null
        val brackets = tokens.filter { it.category in OPENERS || it.category in CLOSERS }
        fun find(offset: Int, kinds: Set<TokenCategory>): Int =
            brackets.indexOfFirst { it.span.start.offset == offset && it.category in kinds }
        // Prioridad: cierre a la izquierda del cursor, apertura a la derecha, apertura a la izquierda, cierre a la derecha.
        val index = listOf(
            find(caret - 1, CLOSERS),
            find(caret, OPENERS),
            find(caret - 1, OPENERS),
            find(caret, CLOSERS)
        ).firstOrNull { it >= 0 } ?: return null

        val token = brackets[index]
        if (token.category in OPENERS) {
            val closer = PAIRS.getValue(token.category)
            var depth = 0
            for (i in index + 1 until brackets.size) {
                val other = brackets[i].category
                if (other == token.category) depth++
                if (other == closer) {
                    if (depth == 0) return token.span.start.offset to brackets[i].span.start.offset
                    depth--
                }
            }
        } else {
            val opener = PAIRS.entries.first { it.value == token.category }.key
            var depth = 0
            for (i in index - 1 downTo 0) {
                val other = brackets[i].category
                if (other == token.category) depth++
                if (other == opener) {
                    if (depth == 0) return brackets[i].span.start.offset to token.span.start.offset
                    depth--
                }
            }
        }
        return null
    }

    private val PAIRS: Map<TokenCategory, TokenCategory> = mapOf(
        TokenCategory.LLA to TokenCategory.LLC,
        TokenCategory.PA to TokenCategory.PC,
        TokenCategory.COA to TokenCategory.COC
    )
    private val OPENERS: Set<TokenCategory> = PAIRS.keys
    private val CLOSERS: Set<TokenCategory> = PAIRS.values.toSet()
}
