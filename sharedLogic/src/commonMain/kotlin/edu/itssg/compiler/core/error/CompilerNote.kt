package edu.itssg.compiler.core.error

import edu.itssg.compiler.core.security.SourceSpan

/** Tipo de observación didáctica. */
enum class CompilerNoteKind(val displayName: String) {
    /** Una estructura de control se validó, pero no se representa en el árbol ni en el código intermedio. */
    CONTROL_FLOW_OMITTED("Estructura de control"),

    /** División o módulo entre cero de enteros: Java compila, pero lanza `ArithmeticException` al ejecutar. */
    DIVISION_BY_ZERO("División entre cero"),

    /** Una cadena de llamadas (recursión) superó el límite de niveles que se expanden en el árbol. */
    CALL_DEPTH_LIMIT("Recursión")
}

/**
 * Observación que **no es un error**: el programa es válido para Java, pero algo de lo que se muestra
 * (árbol abstracto-semántico o código intermedio) necesita una aclaración. No bloquea el código intermedio
 * ni aparece en la Tabla de Errores.
 */
data class CompilerNote(
    val kind: CompilerNoteKind,
    val message: String,
    val span: SourceSpan
) {
    val line: Int get() = span.start.line
    val column: Int get() = span.start.column
}
