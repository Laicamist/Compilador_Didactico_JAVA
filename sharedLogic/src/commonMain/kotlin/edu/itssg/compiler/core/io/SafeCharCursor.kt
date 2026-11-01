package edu.itssg.compiler.core.io

import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SecurityCheckResult
import edu.itssg.compiler.core.security.SecurityViolationCode
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Cursor de lectura de caracteres de alta seguridad y complejidad O(1) por operación.
 *
 * Funciones base del Análisis Léxico:
 * 1. Normalización de saltos de línea (\r\n y \r -> \n) manteniendo el conteo exacto de líneas y columnas.
 * 2. Limpieza de espacios innecesarios y omisión de comentarios (`//` y `/* ... */`).
 * 3. Invariante de avance obligatorio (Loop Guard) para prevenir bucles infinitos.
 * 4. Detección de bytes nulos (\u0000) y respeto estricto de las cuotas de [CompilerSecurityConfig].
 */
class SafeCharCursor private constructor(
    private val source: String,
    val config: CompilerSecurityConfig
) {
    private var currentOffset: Int = 0
    private var currentLine: Int = 1
    private var currentColumn: Int = 1
    private var stepCounter: Long = 0L
    private val maxAllowedSteps: Long = config.calculateMaxAllowedSteps(source.length)

    /** Indica si el cursor llegó al final del código fuente (EOF). */
    val isAtEnd: Boolean
        get() = currentOffset >= source.length

    /** Coordenada exacta actual del cursor. */
    val currentPosition: SourcePosition
        get() = SourcePosition(line = currentLine, column = currentColumn, offset = currentOffset)

    /** Inspecciona el carácter actual sin consumirlo (devuelve [EOF_CHAR] al final). */
    fun peek(): Char {
        registerStep()
        if (isAtEnd) return EOF_CHAR
        return source[currentOffset]
    }

    /** Inspecciona el carácter desplazado [lookahead] posiciones sin consumirlo. */
    fun peekAhead(lookahead: Int = 1): Char {
        require(lookahead >= 0) { "El lookahead no puede ser negativo." }
        registerStep()
        val targetIndex = currentOffset + lookahead
        if (targetIndex >= source.length) return EOF_CHAR
        return source[targetIndex]
    }

    /**
     * Consume el carácter actual y actualiza línea/columna. CRLF cuenta como un único salto lógico.
     * (Refactor: ya no invoca `peek()` internamente, lo que duplicaba el costo en pasos.)
     */
    fun advance(): Char {
        registerStep()
        if (isAtEnd) return EOF_CHAR

        val ch = source[currentOffset]
        currentOffset++

        return when (ch) {
            '\r' -> {
                if (!isAtEnd && source[currentOffset] == '\n') {
                    currentOffset++
                }
                currentLine++
                currentColumn = 1
                '\n'
            }
            '\n' -> {
                currentLine++
                currentColumn = 1
                '\n'
            }
            '\t' -> {
                currentColumn += TAB_WIDTH
                ch
            }
            else -> {
                currentColumn++
                ch
            }
        }
    }

    /** Consume el carácter actual solo si coincide con [expected]. */
    fun match(expected: Char): Boolean {
        if (isAtEnd || peek() != expected) return false
        advance()
        return true
    }

    /**
     * Limpia espacios en blanco y omite comentarios de línea (`//`) y de bloque (`/* ... */`).
     * @return [CommentSkipResult.UnclosedBlockComment] si un comentario multilínea queda sin cerrar.
     */
    fun skipWhitespaceAndComments(): CommentSkipResult {
        while (!isAtEnd) {
            val ch = peek()
            when {
                // Incluye espacios Unicode (NBSP   al copiar desde PDF/Word), BOM y espacio de ancho cero.
                ch.isWhitespace() || ch == BOM_CHAR || ch == ZERO_WIDTH_SPACE -> advance()
                ch == '/' && peekAhead(1) == '/' -> {
                    advance()
                    advance()
                    while (!isAtEnd && peek() != '\n' && peek() != '\r') {
                        advance()
                    }
                }
                ch == '/' && peekAhead(1) == '*' -> {
                    val commentStart = currentPosition
                    advance()
                    advance()
                    var closed = false
                    while (!isAtEnd) {
                        if (peek() == '*' && peekAhead(1) == '/') {
                            advance()
                            advance()
                            closed = true
                            break
                        }
                        advance()
                    }
                    if (!closed) {
                        return CommentSkipResult.UnclosedBlockComment(
                            span = SourceSpan(start = commentStart, end = currentPosition)
                        )
                    }
                }
                else -> break
            }
        }
        return CommentSkipResult.Success
    }

    /**
     * Extrae de forma segura la subcadena `[startOffset, currentOffset)`, validando la cuota de longitud de lexema.
     */
    fun sliceFrom(startOffset: Int): Result<String> {
        require(startOffset in 0..currentOffset) {
            "startOffset inválido ($startOffset) para currentOffset ($currentOffset)."
        }
        val length = currentOffset - startOffset
        if (length > config.maxLexemeLength) {
            return Result.failure(
                SecurityQuotaException(
                    SecurityCheckResult.Violation(
                        code = SecurityViolationCode.LEXEME_LENGTH_EXCEEDED,
                        message = "El lexema excede la longitud máxima permitida de ${config.maxLexemeLength} caracteres.",
                        position = currentPosition
                    )
                )
            )
        }
        return Result.success(source.substring(startOffset, currentOffset))
    }

    /**
     * Vista previa acotada (máx. [maxChars]) de `[startOffset, currentOffset)`. Nunca falla ni consulta la cuota de
     * lexema: sirve para reportar en la Tabla de Errores un lexema que excedió el límite sin copiarlo completo.
     */
    fun previewFrom(startOffset: Int, maxChars: Int = 40): String {
        val from = startOffset.coerceIn(0, currentOffset)
        val to = minOf(currentOffset, from + maxChars.coerceAtLeast(0))
        return source.substring(from, to)
    }

    /**
     * Invariante de progreso: si en la iteración no se avanzó ningún carácter, fuerza el avance de emergencia.
     * @return `true` si hubo progreso natural.
     */
    fun enforceForwardProgress(previousOffset: Int): Boolean {
        if (!isAtEnd && currentOffset <= previousOffset) {
            advance()
            return false
        }
        return true
    }

    private fun registerStep() {
        stepCounter++
        if (stepCounter > maxAllowedSteps) {
            throw SecurityQuotaException(
                SecurityCheckResult.Violation(
                    code = SecurityViolationCode.INFINITE_LOOP_DETECTED,
                    message = "Se superó el límite de seguridad de operaciones de lectura ($maxAllowedSteps pasos). Posible bucle infinito abortado.",
                    position = currentPosition
                )
            )
        }
    }

    companion object {
        const val EOF_CHAR: Char = '\u0000'
        private const val BOM_CHAR: Char = '﻿'
        private const val ZERO_WIDTH_SPACE: Char = '​'
        private const val TAB_WIDTH: Int = 4

        /** Fábrica segura: valia cuota de tamaño y bytes nulos antes de instanciar el cursor. */
        fun create(
            rawSource: String,
            config: CompilerSecurityConfig = CompilerSecurityConfig()
        ): Result<SafeCharCursor> {
            if (rawSource.length > config.maxSourceLength) {
                return Result.failure(
                    SecurityQuotaException(
                        SecurityCheckResult.Violation(
                            code = SecurityViolationCode.SOURCE_QUOTA_EXCEEDED,
                            message = "El código fuente (${rawSource.length} chars) excede el límite máximo seguro (${config.maxSourceLength} chars).",
                            position = SourcePosition.START
                        )
                    )
                )
            }

            val nullIndex = rawSource.indexOf('\u0000')
            if (nullIndex != -1) {
                return Result.failure(
                    SecurityQuotaException(
                        SecurityCheckResult.Violation(
                            code = SecurityViolationCode.NULL_BYTE_DETECTED,
                            message = "Se detectó un carácter nulo ilegal (\\u0000) en el desplazamiento $nullIndex.",
                            position = SourcePosition(line = 1, column = 1, offset = nullIndex)
                        )
                    )
                )
            }

            return Result.success(SafeCharCursor(source = rawSource, config = config))
        }
    }
}

/** Resultado tipado de la limpieza de espacios y comentarios. */
sealed interface CommentSkipResult {
    data object Success : CommentSkipResult
    data class UnclosedBlockComment(val span: SourceSpan) : CommentSkipResult
}

/**
 * Excepción controlada de dominio cuando se infringe una cuota de seguridad en el motor del compilador.
 * La captura el orquestador para registrarla en la Tabla de Errores sin cerrar la app.
 */
class SecurityQuotaException(
    val violation: SecurityCheckResult.Violation
) : RuntimeException(violation.message)