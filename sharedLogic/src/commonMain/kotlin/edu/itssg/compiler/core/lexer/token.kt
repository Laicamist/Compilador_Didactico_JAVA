package edu.itssg.compiler.core.lexer

import edu.itssg.compiler.core.security.SourceSpan

/**
 * Token inmutable emitido por el Analizador Léxico.
 *
 * @property category Categoría léxica oficial según la tabla de tokens y GLC v7.
 * @property lexeme Cadena original extraída del código fuente.
 * @property symbolId Identificador secuencial en la Tabla de Símbolos (exclusivo de tokens `ID`).
 * @property isFirstDeclaration `true` si es la primera aparición del `ID` en el programa.
 * @property span Coordenadas exactas de inicio y fin en el código fuente.
 */
data class Token(
    val category: TokenCategory,
    val lexeme: String,
    val symbolId: Int? = null,
    val isFirstDeclaration: Boolean = false,
    val span: SourceSpan
) {
    /**
     * Devuelve `IDn` cuando el identificador ya fue registrado previamente, como en las tablas de las
     * diapositivas (ej. `return números` -> `ID3 ID`).
     */
    val tableDisplayLexeme: String
        get() = if (category == TokenCategory.ID && symbolId != null && !isFirstDeclaration) {
            "ID$symbolId"
        } else {
            lexeme
        }
}