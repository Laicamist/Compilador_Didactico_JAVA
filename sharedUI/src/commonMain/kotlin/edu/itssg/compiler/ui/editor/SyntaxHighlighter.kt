package edu.itssg.compiler.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.ui.theme.JAutomataColors

/**
 * Texto coloreado del editor.
 * @property commentSpans Comentarios encontrados (el autocompletado no se abre dentro de ellos).
 */
internal class HighlightResult(val annotated: AnnotatedString, val commentSpans: List<TextSpan>)

/**
 * Resaltado de sintaxis con los tokens REALES del Analizador Léxico del proyecto (no con expresiones regulares):
 * cada lexema toma el color de su categoría de la tabla de tokens. La ubicación de tokens y comentarios la
 * calcula [TokenSpans]; aquí solo se les da estilo y se agregan las coincidencias de búsqueda y la pareja de
 * llaves.
 */
internal object SyntaxHighlighter {

    /** Arriba de este tamaño solo se marcan las búsquedas: colorear cientos de miles de tokens por tecla es lento. */
    const val MAX_HIGHLIGHT_CHARS: Int = 200_000

    private val BOLD_CATEGORIES: Set<TokenCategory> = setOf(
        TokenCategory.MA, TokenCategory.CL, TokenCategory.ST, TokenCategory.TM, TokenCategory.RTN,
        TokenCategory.NW, TokenCategory.PMAIN
    )

    fun highlight(
        text: String,
        tokens: List<Token>,
        colors: JAutomataColors,
        findMatches: List<TextSpan>,
        currentMatch: Int,
        bracketPair: Pair<Int, Int>?
    ): HighlightResult {
        val spans = if (text.length <= MAX_HIGHLIGHT_CHARS) TokenSpans.locate(text, tokens) else emptyList()
        val commentStyle = SpanStyle(color = colors.syntax.comment, fontStyle = FontStyle.Italic)
        val annotated = buildAnnotatedString {
            append(text)
            for (colored in spans) {
                val category = colored.category
                val style = if (category == null) {
                    commentStyle
                } else if (category in BOLD_CATEGORIES) {
                    SpanStyle(color = colors.syntax.forCategory(category), fontWeight = FontWeight.SemiBold)
                } else {
                    SpanStyle(color = colors.syntax.forCategory(category))
                }
                addStyle(style, colored.span.start, colored.span.end)
            }

            val matchStyle = SpanStyle(background = colors.findMatch)
            val currentStyle = SpanStyle(background = colors.findMatchCurrent)
            findMatches.forEachIndexed { index, match ->
                if (match.end <= text.length) {
                    addStyle(if (index == currentMatch) currentStyle else matchStyle, match.start, match.end)
                }
            }

            bracketPair?.let { (open, close) ->
                val style = SpanStyle(background = colors.accent.copy(alpha = 0.22f), fontWeight = FontWeight.Bold)
                if (open in text.indices) addStyle(style, open, open + 1)
                if (close in text.indices) addStyle(style, close, close + 1)
            }
        }
        return HighlightResult(annotated, spans.filter { it.isComment }.map { it.span })
    }

    fun matchingBracket(text: String, tokens: List<Token>, caret: Int): Pair<Int, Int>? =
        if (text.length > MAX_HIGHLIGHT_CHARS) null else TokenSpans.matchingBracket(text, tokens, caret)
}

/** Muestra el texto coloreado sin cambiar posiciones (mapeo identidad). */
internal class HighlightTransformation(private val annotated: AnnotatedString) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        if (text.text == annotated.text) {
            TransformedText(annotated, OffsetMapping.Identity)
        } else {
            TransformedText(text, OffsetMapping.Identity)
        }

    override fun equals(other: Any?): Boolean = other is HighlightTransformation && other.annotated == annotated

    override fun hashCode(): Int = annotated.hashCode()
}
