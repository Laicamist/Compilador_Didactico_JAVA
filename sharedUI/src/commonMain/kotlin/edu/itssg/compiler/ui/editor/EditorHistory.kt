package edu.itssg.compiler.ui.editor

import kotlin.time.TimeSource

/**
 * Historial de Deshacer / Rehacer del editor.
 *
 * - Agrupa lo que se teclea seguido (letra por letra, o borrado por borrado) en un solo paso, como los IDE:
 *   un paso nuevo empieza al cambiar de tipo de edición, al dar Enter, al pegar, o tras [coalesceWindowMs]
 *   sin teclear.
 * - Límite doble para no agotar memoria con archivos grandes: [maxEntries] pasos y [maxTotalChars] caracteres
 *   guardados en total (se descartan los pasos más viejos).
 *
 * @param nowMillis Reloj en milisegundos (se reemplaza en pruebas).
 */
class EditorHistory(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalChars: Long = DEFAULT_MAX_TOTAL_CHARS,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
    private val nowMillis: () -> Long = monotonicMillis()
) {
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var undoChars = 0L
    private var redoChars = 0L

    private var lastKind: EditKind? = null
    private var lastEditAt = Long.MIN_VALUE
    private var lastText: String? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Registra un cambio de texto hecho por el usuario: [before] es el estado justo antes del cambio. */
    fun record(before: EditorSnapshot, after: EditorSnapshot) {
        if (before.text == after.text) return
        val kind = classify(before, after)
        val now = nowMillis()
        val continuesRun = kind != EditKind.OTHER &&
                kind == lastKind &&
                lastText == before.text &&
                now - lastEditAt <= coalesceWindowMs

        if (!continuesRun) pushUndo(before)
        clearRedo()
        lastKind = kind
        lastEditAt = now
        lastText = after.text
    }

    /** @return el estado anterior, o `null` si no hay nada que deshacer. */
    fun undo(current: EditorSnapshot): EditorSnapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        undoChars -= previous.text.length
        redoStack.addLast(current)
        redoChars += current.text.length
        breakRun()
        return previous
    }

    /** @return el estado siguiente, o `null` si no hay nada que rehacer. */
    fun redo(current: EditorSnapshot): EditorSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        redoChars -= next.text.length
        pushUndo(current)
        breakRun()
        return next
    }

    /** Termina el grupo actual: el siguiente cambio empieza un paso nuevo (p. ej., al mover el cursor). */
    fun breakRun() {
        lastKind = null
        lastText = null
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        undoChars = 0
        redoChars = 0
        breakRun()
    }

    private fun pushUndo(snapshot: EditorSnapshot) {
        undoStack.addLast(snapshot)
        undoChars += snapshot.text.length
        while (undoStack.size > maxEntries || (undoChars + redoChars > maxTotalChars && undoStack.size > 1)) {
            val dropped = undoStack.removeFirst()
            undoChars -= dropped.text.length
        }
    }

    private fun clearRedo() {
        redoStack.clear()
        redoChars = 0
    }

    private fun classify(before: EditorSnapshot, after: EditorSnapshot): EditKind {
        val insertion = EditorText.detectInsertion(before, after)
        if (insertion != null) {
            val ch = insertion.inserted.singleOrNull()
            return when {
                insertion.replacedLength > 0 || ch == null || ch == '\n' -> EditKind.OTHER
                else -> EditKind.TYPING
            }
        }
        return if (EditorText.isSingleCharDeletion(before, after)) EditKind.DELETING else EditKind.OTHER
    }

    private enum class EditKind { TYPING, DELETING, OTHER }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 200
        const val DEFAULT_MAX_TOTAL_CHARS: Long = 4_000_000L
        const val DEFAULT_COALESCE_WINDOW_MS: Long = 900L

        private fun monotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
