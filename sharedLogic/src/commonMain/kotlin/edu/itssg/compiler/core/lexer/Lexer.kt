package edu.itssg.compiler.core.lexer

import edu.itssg.compiler.core.error.AcademicErrorTemplates
import edu.itssg.compiler.core.io.CommentSkipResult
import edu.itssg.compiler.core.io.SafeCharCursor
import edu.itssg.compiler.core.io.SecurityQuotaException
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SecurityViolationCode
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.symboltable.SymbolTable

data class LexicalAnomaly(
    val lexeme: String,
    val justification: String,
    val span: SourceSpan
)

/** Resultado inmutable de la ejecución del Analizador Léxico. */
data class LexerResult(
    val tokens: List<Token>,
    val symbolTable: SymbolTable,
    val lexicalAnomalies: List<LexicalAnomaly>,
    val quotaAbortedMessage: String? = null
)

/**
 * Analizador Léxico determinista (AFD manual O(N), libre de Regex y de ReDoS).
 *
 * 1. Limpia el código fuente descartando espacios y comentarios (`//` y `/* ... */`).
 * 2. Clasifica cada lexema según la tabla oficial de tokens (LstTokens.pdf).
 * 3. Puebla la [SymbolTable] numerando los identificadores (`ID1`, `ID2`, ...).
 * 4. Detecta errores léxicos (símbolos ajenos al alfabeto, IDs mal formados, cadenas/caracteres sin cerrar)
 *    sin detener la ejecución (modo pánico léxico).
 *
 * Reglas de la tabla de tokens que merecen mención:
 * - `main` es `PMAIN` **solo** cuando es el método principal (`static void main`); en otro contexto es un `ID`.
 * - Las comillas tipográficas de las diapositivas (`“ ” ‘ ’`) se aceptan, pero una cadena abierta con comilla
 *   recta (`"`) solo se cierra con comilla recta, igual que en Java.
 *
 * Todas las listas de salida terminan SIEMPRE con un token `EOF` (incluso si se abortó por cuota), lo que
 * garantiza que el Parser nunca quede en un bucle esperando fin de archivo.
 */
class Lexer(
    private val config: CompilerSecurityConfig = CompilerSecurityConfig()
) {

    fun tokenize(sourceCode: String): LexerResult {
        val symbolTable = SymbolTable()
        val tokens = ArrayList<Token>()
        val anomalies = ArrayList<LexicalAnomaly>()

        val cursorResult = SafeCharCursor.create(rawSource = sourceCode, config = config)
        val cursor = cursorResult.getOrElse { error ->
            val violationPos = (error as? SecurityQuotaException)?.violation?.position ?: SourcePosition.START
            return LexerResult(
                tokens = listOf(eofToken(SourcePosition.START)),
                symbolTable = symbolTable,
                lexicalAnomalies = listOf(
                    LexicalAnomaly(
                        lexeme = "",
                        justification = error.message ?: "Violación de cuota de entrada.",
                        span = SourceSpan(violationPos, violationPos)
                    )
                ),
                quotaAbortedMessage = error.message
            )
        }

        var abortMessage: String? = null
        try {
            while (!cursor.isAtEnd) {
                when (val skipResult = cursor.skipWhitespaceAndComments()) {
                    is CommentSkipResult.UnclosedBlockComment -> {
                        recordAnomaly(
                            anomalies = anomalies,
                            lexeme = "/*",
                            justification = "Comentario multilínea '/*' no fue cerrado antes del final del archivo",
                            span = skipResult.span
                        )
                        break
                    }
                    CommentSkipResult.Success -> Unit
                }

                if (cursor.isAtEnd) break

                if (tokens.size >= config.maxTokenCount) {
                    val message = "Se alcanzó el límite máximo seguro de ${config.maxTokenCount} tokens."
                    abortMessage = message
                    val here = cursor.currentPosition
                    recordAnomaly(anomalies, lexeme = "", justification = message, span = SourceSpan(here, here))
                    break
                }

                val iterationStartOffset = cursor.currentPosition.offset
                scanSingleToken(cursor, symbolTable, tokens, anomalies)
                cursor.enforceForwardProgress(iterationStartOffset)
            }
        } catch (quotaEx: SecurityQuotaException) {
            abortMessage = quotaEx.violation.message
            recordAnomaly(
                anomalies = anomalies,
                lexeme = "",
                justification = quotaEx.violation.message,
                span = SourceSpan(quotaEx.violation.position, quotaEx.violation.position)
            )
        }

        tokens.add(eofToken(cursor.currentPosition))
        return LexerResult(
            tokens = tokens,
            symbolTable = symbolTable,
            lexicalAnomalies = anomalies,
            quotaAbortedMessage = abortMessage
        )
    }

    private fun eofToken(pos: SourcePosition): Token =
        Token(category = TokenCategory.EOF, lexeme = "", span = SourceSpan(pos, pos))

    private fun scanSingleToken(
        cursor: SafeCharCursor,
        symbolTable: SymbolTable,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        val startPos = cursor.currentPosition
        val ch = cursor.peek()

        when {
            isIdentifierStart(ch) -> scanIdentifierOrKeyword(cursor, startPos, symbolTable, tokens, anomalies)
            isAsciiDigit(ch) -> scanNumberOrInvalidId(cursor, startPos, tokens, anomalies)
            ch == '"' || ch == '“' || ch == '”' -> scanStringLiteral(cursor, startPos, tokens, anomalies)
            ch == '\'' || ch == '‘' || ch == '’' -> scanCharLiteral(cursor, startPos, tokens, anomalies)
            else -> scanOperatorOrDelimiter(cursor, startPos, tokens, anomalies)
        }
    }

    /** AFD de Identificadores (`ID`) y Palabras Reservadas (`PCK`, `MA`, `CL`, `TD`, `STR`, ...). */
    private fun scanIdentifierOrKeyword(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        symbolTable: SymbolTable,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        while (!cursor.isAtEnd && isIdentifierPart(cursor.peek())) {
            cursor.advance()
        }

        val slice = sliceOrReport(cursor, startPos, anomalies) ?: return
        val span = SourceSpan(startPos, cursor.currentPosition)

        if (slice == TokenCategory.MAIN_LEXEME && isMainMethodContext(tokens)) {
            tokens.add(Token(category = TokenCategory.PMAIN, lexeme = slice, span = span))
            return
        }

        // Desde Java 9, `_` solo es una palabra reservada y no puede usarse como ID.
        if (slice == "_") {
            recordAnomaly(anomalies, slice, AcademicErrorTemplates.invalidIdentifier(slice), span)
            return
        }

        val keywordCategory = TokenCategory.KEYWORDS[slice]
        if (keywordCategory != null) {
            tokens.add(Token(category = keywordCategory, lexeme = slice, span = span))
            return
        }

        val (symbolEntry, isFirst) = symbolTable.registerOrLookupIdentifier(lexeme = slice, position = startPos)
        tokens.add(
            Token(
                category = TokenCategory.ID,
                lexeme = slice,
                symbolId = symbolEntry.symbolId,
                isFirstDeclaration = isFirst,
                span = span
            )
        )
    }

    /** `main` es PMAIN únicamente en `MAIN ::= MA ST TM PMAIN ...`, es decir, tras `static void`. */
    private fun isMainMethodContext(tokens: List<Token>): Boolean {
        val n = tokens.size
        return n >= 2 &&
                tokens[n - 1].category == TokenCategory.TM &&
                tokens[n - 2].category == TokenCategory.ST
    }

    /**
     * AFD de Números: enteros `VTE`, decimales `VTD` (incluye `8.`), decimales con sufijo `VTF` (`3.0f`, `3f`, `3.f`).
     * Un número seguido de letras (`9variable`) es un error léxico de ID mal formado.
     */
    private fun scanNumberOrInvalidId(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        while (!cursor.isAtEnd && isAsciiDigit(cursor.peek())) {
            cursor.advance()
        }

        var isDecimal = false
        var isFloatSuffix = false

        if (!cursor.isAtEnd && cursor.peek() == '.') {
            val next = cursor.peekAhead(1)
            if (isAsciiDigit(next)) {
                isDecimal = true
                cursor.advance() // '.'
                while (!cursor.isAtEnd && isAsciiDigit(cursor.peek())) {
                    cursor.advance()
                }
            } else if ((next == 'f' || next == 'F') && !isIdentifierPart(cursor.peekAhead(2))) {
                // "3.f": decimal con parte fraccionaria vacía y sufijo (el sufijo se consume más abajo)
                isDecimal = true
                cursor.advance() // '.'
            } else if (!isIdentifierStart(next) && next != '.') {
                // "8." (decimal con parte fraccionaria vacía): válido en Java y es un VTD
                isDecimal = true
                cursor.advance()
            }
        }

        if (!cursor.isAtEnd && (cursor.peek() == 'f' || cursor.peek() == 'F')) {
            val afterF = cursor.peekAhead(1)
            if (!isIdentifierPart(afterF)) {
                isFloatSuffix = true
                cursor.advance()
            }
        }

        if (!cursor.isAtEnd && isIdentifierStart(cursor.peek())) {
            while (!cursor.isAtEnd && isIdentifierPart(cursor.peek())) {
                cursor.advance()
            }
            val invalidLexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
            recordAnomaly(
                anomalies = anomalies,
                lexeme = invalidLexeme,
                justification = AcademicErrorTemplates.invalidIdentifier(invalidLexeme),
                span = SourceSpan(startPos, cursor.currentPosition)
            )
            return
        }

        val lexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
        val category = when {
            isFloatSuffix -> TokenCategory.VTF
            isDecimal -> TokenCategory.VTD
            else -> TokenCategory.VTE
        }
        tokens.add(Token(category = category, lexeme = lexeme, span = SourceSpan(startPos, cursor.currentPosition)))
    }

    /**
     * AFD de `VTS`. Una cadena abierta con comilla recta `"` solo se cierra con `"`; una abierta con comilla
     * tipográfica (`“` o `”`, como en las diapositivas) se cierra con `”` o `"`.
     */
    private fun scanStringLiteral(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        val opener = cursor.advance()
        val straightOpener = opener == '"'
        var closed = false

        while (!cursor.isAtEnd) {
            val ch = cursor.peek()
            if (ch == '\n' || ch == '\r') break
            if (ch == '\\') {
                cursor.advance()
                if (!cursor.isAtEnd && cursor.peek() != '\n' && cursor.peek() != '\r') cursor.advance()
                continue
            }
            val isCloser = if (straightOpener) ch == '"' else (ch == '”' || ch == '"')
            if (isCloser) {
                cursor.advance()
                closed = true
                break
            }
            cursor.advance()
        }

        val lexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
        val span = SourceSpan(startPos, cursor.currentPosition)

        if (!closed) {
            recordAnomaly(
                anomalies = anomalies,
                lexeme = lexeme,
                justification = "Literal de cadena (VTS) no cerrado con comillas dobles",
                span = span
            )
            return
        }
        tokens.add(Token(category = TokenCategory.VTS, lexeme = lexeme, span = span))
    }

    /** AFD de `VTC`: soporta comillas simples rectas `'a'` y tipográficas `‘@’`. */
    private fun scanCharLiteral(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        cursor.advance() // comilla de apertura

        if (cursor.isAtEnd || cursor.peek() == '\n' || cursor.peek() == '\r') {
            val lexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
            recordAnomaly(
                anomalies = anomalies,
                lexeme = lexeme,
                justification = "Literal de carácter (VTC) incompleto o sin cerrar",
                span = SourceSpan(startPos, cursor.currentPosition)
            )
            return
        }

        if (cursor.peek() == '\\') {
            cursor.advance()
            if (!cursor.isAtEnd && cursor.peek() != '\n' && cursor.peek() != '\r') {
                val escaped = cursor.advance()
                if (escaped == 'u') {
                    // Escape Unicode '\uXXXX'
                    var hexDigits = 0
                    while (hexDigits < 4 && !cursor.isAtEnd && isHexDigit(cursor.peek())) {
                        cursor.advance()
                        hexDigits++
                    }
                }
            }
        } else if (cursor.peek() != '\'' && cursor.peek() != '’') {
            cursor.advance()
        }

        val closing = cursor.peek()
        if (closing == '\'' || closing == '’' || closing == '‘') {
            cursor.advance()
            val lexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
            val span = SourceSpan(startPos, cursor.currentPosition)
            if (lexeme.length < 3) {
                recordAnomaly(
                    anomalies = anomalies,
                    lexeme = lexeme,
                    justification = "Literal de carácter (VTC) vacío no permitido en JAVA",
                    span = span
                )
            } else {
                tokens.add(Token(category = TokenCategory.VTC, lexeme = lexeme, span = span))
            }
        } else {
            while (!cursor.isAtEnd && !cursor.peek().isWhitespace() &&
                cursor.peek() != ';' && cursor.peek() != '\'' && cursor.peek() != '’'
            ) {
                cursor.advance()
            }
            if (!cursor.isAtEnd && (cursor.peek() == '\'' || cursor.peek() == '’')) {
                cursor.advance()
            }
            val badLexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
            recordAnomaly(
                anomalies = anomalies,
                lexeme = badLexeme,
                justification = "El literal '$badLexeme' no cumple con el formato VTC (un solo carácter entre comillas simples)",
                span = SourceSpan(startPos, cursor.currentPosition)
            )
        }
    }

    /**
     * AFD de operadores compuestos (`++`, `--`, `==`, `<=`, `>=`, `!=`, `&&`, `||`), operadores simples y delimitadores.
     * Todo símbolo fuera de la tabla se reporta con la justificación oficial.
     */
    private fun scanOperatorOrDelimiter(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        tokens: MutableList<Token>,
        anomalies: MutableList<LexicalAnomaly>
    ) {
        val ch = cursor.advance()

        // Un emoji (par sustituto UTF-16) es UN símbolo ajeno al alfabeto, no dos.
        if (ch.isHighSurrogate() && !cursor.isAtEnd && cursor.peek().isLowSurrogate()) {
            cursor.advance()
        }

        val category: TokenCategory? = when (ch) {
            '=' -> if (cursor.match('=')) TokenCategory.OPR else TokenCategory.ODA
            '+' -> if (cursor.match('+')) TokenCategory.INC else TokenCategory.OPA
            '-' -> if (cursor.match('-')) TokenCategory.DEC else TokenCategory.OPA
            '/', '*', '%' -> TokenCategory.OPA
            ';' -> TokenCategory.FI
            '{' -> TokenCategory.LLA
            '}' -> TokenCategory.LLC
            '(' -> TokenCategory.PA
            ')' -> TokenCategory.PC
            '[' -> TokenCategory.COA
            ']' -> TokenCategory.COC
            ':' -> TokenCategory.DOP
            '?' -> TokenCategory.OPT
            '|', '&' -> {
                cursor.match(ch) // `||` y `&&` se emiten como un único OPL
                TokenCategory.OPL
            }
            '<', '>' -> {
                cursor.match('=') // `<=` y `>=` como un único OPR
                TokenCategory.OPR
            }
            '.' -> TokenCategory.CA
            ',' -> TokenCategory.CO
            '!' -> if (cursor.match('=')) TokenCategory.OPR else TokenCategory.OPN
            '~' -> TokenCategory.OPB
            '^' -> TokenCategory.XOR
            else -> null
        }

        val lexeme = sliceOrReport(cursor, startPos, anomalies) ?: return
        val span = SourceSpan(startPos, cursor.currentPosition)

        if (category != null) {
            tokens.add(Token(category = category, lexeme = lexeme, span = span))
        } else {
            recordAnomaly(
                anomalies = anomalies,
                lexeme = lexeme,
                justification = AcademicErrorTemplates.unrecognizedSymbol(lexeme),
                span = span
            )
        }
    }

    /**
     * Extrae el lexema `[startPos, cursor)` respetando la cuota de longitud. Si el lexema es demasiado largo se
     * registra una anomalía (con vista previa acotada), se descarta ese lexema y el análisis CONTINÚA.
     * Cualquier otra violación de cuota (p. ej. bucle infinito) sí se propaga y aborta el escaneo.
     */
    private fun sliceOrReport(
        cursor: SafeCharCursor,
        startPos: SourcePosition,
        anomalies: MutableList<LexicalAnomaly>
    ): String? {
        return cursor.sliceFrom(startPos.offset).getOrElse { error ->
            if (error is SecurityQuotaException &&
                error.violation.code == SecurityViolationCode.LEXEME_LENGTH_EXCEEDED
            ) {
                recordAnomaly(
                    anomalies = anomalies,
                    lexeme = cursor.previewFrom(startPos.offset) + "…",
                    justification = error.violation.message,
                    span = SourceSpan(startPos, cursor.currentPosition)
                )
                null
            } else {
                throw error
            }
        }
    }

    private fun recordAnomaly(
        anomalies: MutableList<LexicalAnomaly>,
        lexeme: String,
        justification: String,
        span: SourceSpan
    ) {
        if (anomalies.size < config.maxErrorCount) {
            anomalies.add(LexicalAnomaly(lexeme = lexeme, justification = justification, span = span))
        }
    }

    private fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

    private fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    /** Se permiten letras con tilde/ñ (`números`, `asesorías`) como en las diapositivas, además de `_` y `$`. */
    private fun isIdentifierStart(ch: Char): Boolean = ch.isLetter() || ch == '_' || ch == '$'

    private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'
}