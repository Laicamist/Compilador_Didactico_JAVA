package edu.itssg.compiler.core.symboltable

import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.security.SourcePosition

/**
 * Rol semántico del identificador,
 * ID2 Viernes -> CLASS, ID3 a -> MÉTODO STRING, ID4 x -> INT).
 */
enum class SymbolRole(val label: String) {
    PACKAGE("PACKAGE"),
    CLASS("CLASS"),
    METODO("MÉTODO"),
    VARIABLE("VARIABLE"),
    PARAMETER("PARÁMETRO"),
    OBJECT("OBJETO"),
    EXTERNAL_MEMBER("MIEMBRO_EXTERNO"),
    UNKNOWN("SIN_CLASIFICAR")
}

/**
 * Registro único de un Identificador (`ID`) dentro de la Tabla de Símbolos.
 *
 * @property symbolId Índice secuencial base-1 (ej. 1 para `ID1`, 2 para `ID2`).
 * @property lexeme Nombre original del identificador en el código fuente (ej. `Buscar`, `números`, `Tiempo`).
 * @property tokenCategory Categoría léxica (`TokenCategory.ID`).
 * @property role Clasificación estructural asignada durante el análisis sintáctico/semántico.
 * @property dataType Tipo de dato asociado (ej. `int`, `float`, `double`, `String`, `void`, o nombre de clase).
 * @property scope Ámbito al que pertenece (ej. `GLOBAL`, nombre del método o `main`).
 * @property firstSeen Posición exacta donde apareció por primera vez.
 */
data class SymbolEntry(
    val symbolId: Int,
    val lexeme: String,
    val tokenCategory: TokenCategory = TokenCategory.ID,
    val role: SymbolRole = SymbolRole.UNKNOWN,
    val dataType: String? = null,
    val scope: String = "GLOBAL",
    val firstSeen: SourcePosition
) {
    val referenceCode: String
        get() = "ID$symbolId"
}

/**
 * Fila de visualización para la Tabla Léxica (columnas: `ID` | `LEXEMA` | `TOKEN`),
 */
data class LexicalTableRow(
    val idColumn: String,
    val lexemeColumn: String,
    val tokenColumn: String,
    val line: Int,
    val column: Int
)

/**
 * Tabla de Símbolos inmutable / controlada para registrar identificadores numerados (`ID1`, `ID2`...)
 */
class SymbolTable {
    private val symbolsByName = LinkedHashMap<String, SymbolEntry>()
    private val symbolsById = LinkedHashMap<Int, SymbolEntry>()
    private var nextIdCounter: Int = 1

    /**
     * Registra un identificador (`ID`) si es la primera vez que aparece, o recupera su número de `ID` existente
     * (ej. si `números` fue el ID 3, la siguiente aparición devolverá `symbolId = 3` y `isFirstDeclaration = false`).
     */
    fun registerOrLookupIdentifier(
        lexeme: String,
        position: SourcePosition
    ): Pair<SymbolEntry, Boolean> {
        val existing = symbolsByName[lexeme]
        if (existing != null) {
            return existing to false
        }

        val assignedId = nextIdCounter++
        val newEntry = SymbolEntry(
            symbolId = assignedId,
            lexeme = lexeme,
            firstSeen = position
        )
        symbolsByName[lexeme] = newEntry
        symbolsById[assignedId] = newEntry
        return newEntry to true
    }

    /**
     * Permite a las fases Sintáctica y Semántica enriquecer un símbolo con su tipo de dato (`TD`),
     * rol (`PACKAGE`, `CLASS`, `MÉTODO`, etc.) y ámbito.
     */
    fun updateSymbolMetadata(
        symbolId: Int,
        role: SymbolRole? = null,
        dataType: String? = null,
        scope: String? = null
    ): Boolean {
        val current = symbolsById[symbolId] ?: return false
        val updated = current.copy(
            role = role ?: current.role,
            dataType = dataType ?: current.dataType,
            scope = scope ?: current.scope
        )
        symbolsById[symbolId] = updated
        symbolsByName[current.lexeme] = updated
        return true
    }

    /**
     * Sustituye la numeración provisional del Analizador Léxico por la definitiva del Analizador Semántico
     * ("Escribe en TABLA DE SÍMBOLOS"): un ID por cada elemento creado por el programador, en orden de aparición,
     */
    fun replaceAllSymbols(entries: List<SymbolEntry>) {
        symbolsByName.clear()
        symbolsById.clear()
        for (entry in entries) {
            symbolsById[entry.symbolId] = entry
            if (entry.lexeme !in symbolsByName) symbolsByName[entry.lexeme] = entry
        }
        nextIdCounter = (entries.maxOfOrNull { it.symbolId } ?: 0) + 1
    }

    fun findById(symbolId: Int): SymbolEntry? = symbolsById[symbolId]

    fun findByLexeme(lexeme: String): SymbolEntry? = symbolsByName[lexeme]

    fun getAllSymbols(): List<SymbolEntry> = symbolsById.values.toList()

    /**
     * Construye la tabla secuencial (ID | LEXEMA | TOKEN).
     */
    fun buildLexicalTableRows(
        tokens: List<Token>,
        useVSTAlias: Boolean = false
    ): List<LexicalTableRow> {
        return tokens
            .filter { it.category != TokenCategory.EOF }
            .map { token ->
                val idCol = if (token.category == TokenCategory.ID && token.isFirstDeclaration && token.symbolId != null) {
                    token.symbolId.toString()
                } else {
                    ""
                }
                LexicalTableRow(
                    idColumn = idCol,
                    lexemeColumn = token.tableDisplayLexeme,
                    tokenColumn = token.category.displayMnemonic(useSlideAliasForString = useVSTAlias),
                    line = token.span.start.line,
                    column = token.span.start.column
                )
            }
    }
}