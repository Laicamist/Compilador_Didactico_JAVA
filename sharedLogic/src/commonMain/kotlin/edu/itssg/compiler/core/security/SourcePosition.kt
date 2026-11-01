package edu.itssg.compiler.core.security

/**
 * Representa una coordenada exacta e inmutable dentro del archivo de código fuente analizado.
 * Se utiliza para vincular cada Token de la Tabla de Símbolos y cada registro de la Tabla de Errores
 * con su ubicación real en el editor de Compose Multiplatform.
 *
 * @property line Número de línea (1-indexado para visualización humana).
 * @property column Número de columna (1-indexado para visualización humana).
 * @property offset Índice absoluto de base 0 dentro de la cadena de entrada.
 */
data class SourcePosition(
    val line: Int,
    val column: Int,
    val offset: Int
) : Comparable<SourcePosition> {

    init {
        require(line >= 1) { "El número de línea debe ser mayor o igual a 1 (recibido: $line)." }
        require(column >= 1) { "El número de columna debe ser mayor o igual a 1 (recibido: $column)." }
        require(offset >= 0) { "El offset debe ser mayor o igual a 0 (recibido: $offset)." }
    }

    override fun compareTo(other: SourcePosition): Int = this.offset.compareTo(other.offset)

    override fun toString(): String = "Línea $line, Columna $column"

    companion object {
        /**
         * Posición inicial por defecto al arrancar el escaneo de un archivo fuente.
         */
        val START = SourcePosition(line = 1, column = 1, offset = 0)
    }
}

/**
 * Representa un rango continuo de texto en el código fuente desde [start] hasta [end].
 * Útil para subrayar lexemas completos o errores sintácticos/semánticos en el editor.
 */
data class SourceSpan(
    val start: SourcePosition,
    val end: SourcePosition
) {
    init {
        require(end.offset >= start.offset) {
            "El offset final (${end.offset}) no puede ser menor que el inicial (${start.offset})."
        }
    }

    val length: Int
        get() = end.offset - start.offset
}