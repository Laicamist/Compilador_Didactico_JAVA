package edu.itssg.compiler.ui.editor

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.lexer.Lexer
import edu.itssg.compiler.core.lexer.TokenCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Resaltado y marcas de error del editor contra el Analizador Léxico y el compilador reales. */
class TokenSpansTest {

    private val lexer = Lexer()

    private val program = """
        public class Main{
            // comentario con "comillas"
            public static void main(String []args){
                String s = "a // no es comentario";
                int x = 5; /* bloque */
                System.out.println(x);
            }
        }
    """.trimIndent()

    @Test
    fun everyTokenIsPlacedOnItsLexeme() {
        val tokens = lexer.tokenize(program).tokens
        val spans = TokenSpans.locate(program, tokens)
        val tokenSpans = spans.filterNot { it.isComment }
        assertEquals(tokens.count { it.category != TokenCategory.EOF }, tokenSpans.size)
        for (colored in tokenSpans) {
            val lexeme = program.substring(colored.span.start, colored.span.end)
            assertTrue(lexeme.isNotBlank(), "Token vacío en ${colored.span}")
        }
        val stringToken = tokenSpans.first { it.category == TokenCategory.VTS }
        assertEquals("\"a // no es comentario\"", program.substring(stringToken.span.start, stringToken.span.end))
    }

    @Test
    fun commentsAreFoundOnlyBetweenTokens() {
        val spans = TokenSpans.locate(program, lexer.tokenize(program).tokens)
        val comments = spans.filter { it.isComment }.map { program.substring(it.span.start, it.span.end) }
        assertEquals(listOf("// comentario con \"comillas\"", "/* bloque */"), comments)
    }

    @Test
    fun staleTokensAreShiftedAfterAnInsertion() {
        val oldText = "int x = 5;"
        val tokens = lexer.tokenize(oldText).tokens
        val newText = "  " + oldText // se escribió al inicio y los tokens aún no se recalculan
        val spans = TokenSpans.locate(newText, tokens)
        assertEquals(5, spans.size)
        assertEquals(TextSpan(2, 5), spans.first().span)
        assertEquals("5", newText.substring(spans[3].span.start, spans[3].span.end))
    }

    @Test
    fun tokensThatNoLongerMatchAreSkipped() {
        val tokens = lexer.tokenize("int x = 5;").tokens
        val spans = TokenSpans.locate("abc", tokens)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun bracketPairsFollowNesting() {
        val text = "class A{ void f(){ x = (1+2); } }"
        val tokens = lexer.tokenize(text).tokens
        val outerOpen = text.indexOf('{')
        val outerClose = text.lastIndexOf('}')
        assertEquals(outerOpen to outerClose, TokenSpans.matchingBracket(text, tokens, outerOpen))
        assertEquals(outerOpen to outerClose, TokenSpans.matchingBracket(text, tokens, outerClose + 1))
        val parenOpen = text.indexOf("(1")
        assertEquals(parenOpen to text.indexOf(");"), TokenSpans.matchingBracket(text, tokens, parenOpen + 1))
        assertNull(TokenSpans.matchingBracket(text, tokens, text.indexOf('x')))
    }

    @Test
    fun bracketMatchingNeedsUpToDateTokens() {
        val tokens = lexer.tokenize("f(){}").tokens
        assertNull(TokenSpans.matchingBracket("f(){} ", tokens, 1))
    }

    @Test
    fun compilerErrorsUnderlineTheOffendingSymbol() {
        val source = """
            public class Main{
                public static void main(String []args){
                    int a = 5 @ 3;
                }
            }
        """.trimIndent()
        val report = JavaDidacticCompiler().compileSynchronously(source)
        val diagnostics = EditorDiagnostics.fromErrors(report.errors, source.length)
        val lexical = assertNotNull(diagnostics.firstOrNull { it.type == ErrorPhaseType.LEXICAL })
        assertEquals(source.indexOf('@'), lexical.span.start)
        assertEquals("@", source.substring(lexical.span.start, lexical.span.end))
        assertEquals(3, lexical.line)
        assertNotNull(lexical.number)
        assertEquals(lexical, EditorDiagnostics.at(diagnostics, source.indexOf('@')))
    }

    @Test
    fun liveLexicalErrorsComeFromTheHighlightLexer() {
        val source = "int a = 5;\nint #b = 2;"
        val anomalies = lexer.tokenize(source).lexicalAnomalies
        val diagnostics = EditorDiagnostics.fromAnomalies(anomalies, source.length)
        val first = diagnostics.first()
        assertEquals(2, first.line)
        assertNull(first.number)
        assertTrue(first.span.start >= source.indexOf('\n'))
    }

    @Test
    fun spansAreClampedToTheText() {
        val report = JavaDidacticCompiler().compileSynchronously("public class Main{")
        val diagnostics = EditorDiagnostics.fromErrors(report.errors, 5)
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.span.start in 0..5 && it.span.end in it.span.start..5 })
    }
}
