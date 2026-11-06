package edu.itssg.compiler.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.text.iterator

class EditorHistoryTest {

    private var clock = 0L
    private fun history(maxEntries: Int = 200, maxChars: Long = 4_000_000L) =
        EditorHistory(maxEntries = maxEntries, maxTotalChars = maxChars, coalesceWindowMs = 500, nowMillis = { clock })

    /** Teclea [chars] uno por uno a partir de [start], registrando cada cambio. */
    private fun EditorHistory.type(start: EditorSnapshot, chars: String): EditorSnapshot {
        var current = start
        for (c in chars) {
            val next = EditorSnapshot(current.text.substring(0, current.caret) + c + current.text.substring(current.caret), current.caret + 1)
            record(current, next)
            current = next
            clock += 50
        }
        return current
    }

    @Test
    fun typingAWordIsOneUndoStep() {
        val h = history()
        val empty = EditorSnapshot("")
        val typed = h.type(empty, "int")
        assertEquals(empty, h.undo(typed))
        assertFalse(h.canUndo)
    }

    @Test
    fun pauseStartsANewStep() {
        val h = history()
        val first = h.type(EditorSnapshot(""), "ab")
        clock += 2_000
        val second = h.type(first, "cd")
        assertEquals(first, h.undo(second))
        assertEquals(EditorSnapshot(""), h.undo(first))
    }

    @Test
    fun enterStartsANewStep() {
        val h = history()
        val line = h.type(EditorSnapshot(""), "x;")
        val withEnter = EditorSnapshot("x;\n", 3)
        h.record(line, withEnter)
        assertEquals(line, h.undo(withEnter))
    }

    @Test
    fun redoRestoresAndNewEditClearsRedo() {
        val h = history()
        val typed = h.type(EditorSnapshot(""), "a")
        val undone = h.undo(typed)!!
        assertTrue(h.canRedo)
        assertEquals(typed, h.redo(undone))
        val again = h.undo(typed)!!
        h.record(again, EditorSnapshot("z", 1))
        assertFalse(h.canRedo)
        assertNull(h.redo(EditorSnapshot("z", 1)))
    }

    @Test
    fun deletingAfterTypingIsASeparateStep() {
        val h = history()
        val typed = h.type(EditorSnapshot(""), "abc")
        val deleted = EditorSnapshot("ab", 2)
        h.record(typed, deleted)
        assertEquals(typed, h.undo(deleted))
    }

    @Test
    fun oldestStepsAreDroppedAtTheLimit() {
        val h = history(maxEntries = 3)
        var current = EditorSnapshot("")
        repeat(5) {
            val next = EditorSnapshot(current.text + "\n", current.text.length + 1)
            h.record(current, next)
            current = next
        }
        var undos = 0
        while (true) {
            current = h.undo(current) ?: break
            undos++
        }
        assertEquals(3, undos)
    }
}
