package edu.itssg.compiler.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTextTest {

    // ------------------------------------------------------------------ Limpieza

    @Test
    fun sanitizeNormalizesLineBreaksAndKeepsCaret() {
        val pasted = EditorSnapshot("int a;\r\nint b;\rint c;", selectionStart = 22)
        val clean = EditorText.sanitize(pasted)
        assertEquals("int a;\nint b;\nint c;", clean.text)
        assertEquals(clean.text.length, clean.caret)
    }

    @Test
    fun sanitizeRemovesNullCharacters() {
        val clean = EditorText.sanitize(EditorSnapshot("in\u0000t x;", selectionStart = 4))
        assertEquals("int x;", clean.text)
        assertEquals(3, clean.caret)
    }

    @Test
    fun sanitizeReturnsSameInstanceWhenNothingToClean() {
        val snapshot = EditorSnapshot("int x = 5;")
        assertTrue(EditorText.sanitize(snapshot) === snapshot)
    }

    // ------------------------------------------------------------------ Autoindentación

    @Test
    fun enterKeepsIndentationOfCurrentLine() {
        val old = EditorSnapshot("    int x = 5;", selectionStart = 14)
        val new = EditorSnapshot("    int x = 5;\n", selectionStart = 15)
        val result = assertNotNull(EditorText.applyTypingAssists(old, new))
        assertEquals("    int x = 5;\n    ", result.text)
        assertEquals(result.text.length, result.caret)
    }

    @Test
    fun enterAfterOpeningBraceAddsOneLevel() {
        val old = EditorSnapshot("public class Main{", selectionStart = 18)
        val new = EditorSnapshot("public class Main{\n", selectionStart = 19)
        val result = assertNotNull(EditorText.applyTypingAssists(old, new))
        assertEquals("public class Main{\n    ", result.text)
    }

    @Test
    fun enterBetweenBracesOpensBlock() {
        val text = "    if(a){}"
        val old = EditorSnapshot(text, selectionStart = 10)
        val new = EditorSnapshot("    if(a){\n}", selectionStart = 11)
        val result = assertNotNull(EditorText.applyTypingAssists(old, new))
        assertEquals("    if(a){\n        \n    }", result.text)
        assertEquals("    if(a){\n        ".length, result.caret)
    }

    @Test
    fun enterWithoutIndentationNeedsNoChange() {
        val old = EditorSnapshot("int x;", selectionStart = 6)
        val new = EditorSnapshot("int x;\n", selectionStart = 7)
        assertNull(EditorText.applyTypingAssists(old, new))
    }

    @Test
    fun closingBraceOnBlankLineRemovesOneLevel() {
        val old = EditorSnapshot("    if(a){\n        ", selectionStart = 19)
        val new = EditorSnapshot("    if(a){\n        }", selectionStart = 20)
        val result = assertNotNull(EditorText.applyTypingAssists(old, new))
        assertEquals("    if(a){\n    }", result.text)
        assertEquals(result.text.length, result.caret)
    }

    @Test
    fun closingBraceAfterCodeIsLeftAlone() {
        val old = EditorSnapshot("    x = 1;", selectionStart = 10)
        val new = EditorSnapshot("    x = 1;}", selectionStart = 11)
        assertNull(EditorText.applyTypingAssists(old, new))
    }

    // ------------------------------------------------------------------ Tab / Shift+Tab

    @Test
    fun tabInsertsSpacesToNextStop() {
        val result = EditorText.indent(EditorSnapshot("ab", selectionStart = 2))
        assertEquals("ab  ", result.text)
        assertEquals(4, result.caret)
    }

    @Test
    fun tabIndentsEverySelectedLine() {
        val text = "int a;\nint b;\nint c;"
        val result = EditorText.indent(EditorSnapshot(text, selectionStart = 0, selectionEnd = 10))
        assertEquals("    int a;\n    int b;\nint c;", result.text)
        assertEquals(0, result.selectionStart)
        assertEquals(18, result.selectionEnd)
    }

    @Test
    fun selectionEndingAtLineStartDoesNotIndentThatLine() {
        val text = "a\nb\nc"
        val result = EditorText.indent(EditorSnapshot(text, selectionStart = 0, selectionEnd = 4))
        assertEquals("    a\n    b\nc", result.text)
    }

    @Test
    fun shiftTabRemovesUpToOneLevel() {
        val text = "        int a;\n  int b;\nint c;"
        val result = EditorText.dedent(EditorSnapshot(text, selectionStart = 0, selectionEnd = text.length))
        assertEquals("    int a;\nint b;\nint c;", result.text)
        assertEquals(0, result.selectionStart)
        assertEquals(result.text.length, result.selectionEnd)
    }

    @Test
    fun shiftTabMovesCaretInsideIndentation() {
        val result = EditorText.dedent(EditorSnapshot("    x;", selectionStart = 2))
        assertEquals("x;", result.text)
        assertEquals(0, result.caret)
    }

    // ------------------------------------------------------------------ Buscar y reemplazar

    @Test
    fun findIsCaseInsensitiveByDefault() {
        val matches = EditorText.findMatches("Int int INT", "int", caseSensitive = false, wholeWord = false)
        assertEquals(listOf(TextSpan(0, 3), TextSpan(4, 7), TextSpan(8, 11)), matches)
    }

    @Test
    fun findWholeWordSkipsPartsOfIdentifiers() {
        val matches = EditorText.findMatches("x xs ax x_ x", "x", caseSensitive = true, wholeWord = true)
        assertEquals(listOf(TextSpan(0, 1), TextSpan(11, 12)), matches)
    }

    @Test
    fun findRespectsLimit() {
        val matches = EditorText.findMatches("a".repeat(100), "a", caseSensitive = true, wholeWord = false, limit = 10)
        assertEquals(10, matches.size)
    }

    @Test
    fun replaceAllUsesComputedMatches() {
        val text = "int a = a + a;"
        val matches = EditorText.findMatches(text, "a", caseSensitive = true, wholeWord = true)
        assertEquals("int total = total + total;", EditorText.replaceAll(text, matches, "total"))
    }

    @Test
    fun matchIndexWrapsAround() {
        val matches = listOf(TextSpan(2, 3), TextSpan(8, 9))
        assertEquals(1, EditorText.matchIndexAtOrAfter(matches, 5))
        assertEquals(0, EditorText.matchIndexAtOrAfter(matches, 20))
    }

    // ------------------------------------------------------------------ Autocompletado

    @Test
    fun prefixStopsAtNonIdentifierCharacters() {
        assertEquals(4 to "pub", EditorText.identifierPrefixAt("int pub", 7))
        assertNull(EditorText.identifierPrefixAt("x = 9a", 6))
        assertNull(EditorText.identifierPrefixAt("x = ", 4))
    }

    @Test
    fun candidatesIncludeKeywordsWithTheirToken() {
        val items = EditorText.completionCandidates("pub", declaredIdentifiers = emptyList())
        val publicItem = items.first { it.label == "public" }
        assertEquals("MA", publicItem.detail)
        assertEquals(CompletionKind.KEYWORD, publicItem.kind)
    }

    @Test
    fun declaredIdentifiersComeFirst() {
        val items = EditorText.completionCandidates("su", declaredIdentifiers = listOf("suma", "suma"))
        assertEquals("suma", items.first().label)
        assertEquals(1, items.count { it.label == "suma" })
        assertTrue(items.any { it.label == "super" })
    }

    @Test
    fun exactMatchIsNotSuggested() {
        val items = EditorText.completionCandidates("int", declaredIdentifiers = emptyList())
        assertFalse(items.any { it.label == "int" })
    }

    @Test
    fun misspelledDefaultFromTokenTableIsNotSuggested() {
        val items = EditorText.completionCandidates("dea", declaredIdentifiers = emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun applyCompletionReplacesPrefix() {
        val snapshot = EditorSnapshot("    pub", selectionStart = 7)
        val result = EditorText.applyCompletion(snapshot, CompletionItem("public", "MA", CompletionKind.KEYWORD))
        assertEquals("    public", result.text)
        assertEquals(10, result.caret)
    }

    // ------------------------------------------------------------------ Líneas

    @Test
    fun lineHelpers() {
        val text = "a\nbc\n\nd"
        assertEquals(0, EditorText.lineStartOf(text, 1))
        assertEquals(2, EditorText.lineStartOf(text, 3))
        assertEquals(4, EditorText.lineEndOf(text, 2))
        assertEquals(3, EditorText.lineNumberOf(text, 5))
        assertEquals(6, EditorText.offsetOfLine(text, 4))
        assertEquals(text.length, EditorText.offsetOfLine(text, 99))
    }
}
