package edu.itssg.compiler.ui.workspace

import edu.itssg.compiler.core.error.CompilerNote
import edu.itssg.compiler.core.error.CompilerNoteKind

/**
 * Aviso de una línea para el panel de Código intermedio cuando hay estructuras de control que no se representan,
 * o `null` si no hace falta.
 */
internal fun intermediateCodeNotice(notes: List<CompilerNote>): String? {
    val omitted = notes.count { it.kind == CompilerNoteKind.CONTROL_FLOW_OMITTED }
    if (omitted == 0) return null
    val what = if (omitted == 1) "1 estructura de control" else "$omitted estructuras de control"
    return "Este código no incluye $what (IF, WHILE, FOR…): las láminas no definen su representación. " +
        "Las variables que modifican quedan con letra; el detalle está en Observaciones, junto a la Tabla de Errores."
}
