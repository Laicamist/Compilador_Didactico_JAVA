package edu.itssg.compiler.core.parser

import edu.itssg.compiler.core.io.SecurityQuotaException
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import edu.itssg.compiler.core.security.SecurityCheckResult
import edu.itssg.compiler.core.security.SecurityViolationCode
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Cursor inmutable y seguro sobre la lista de [Token] para el Analizador Sintáctico (Parser).
 *
 * Incluye:
 * 1. Lookahead O(1) (`peek`, `peekAhead`, `check`).
 * 2. Guardia contra bucles infinitos (`stepCounter` y `enforceProgress`).
 * 3. Control de profundidad de recursión (`enterRecursion` / `exitRecursion`) para prevenir StackOverflow.
 * 4. Sincronización en Modo Pánico (`synchronizeTo`) usando tokens delimitadores (`FI` `;` y `LLC` `}`).
 */
class SafeTokenCursor(
    private val tokens: List<Token>,
    val config: CompilerSecurityConfig
) {
    private var index: Int = 0
    private var stepCounter: Long = 0L
    private var currentDepth: Int = 0

    /**
     * Presupuesto de lecturas del Parser. Cada decisión gramatical consulta el cursor varias veces (`check`,
     * `peekAhead`, `match`...), así que se asigna un margen holgado por token ([STEPS_PER_TOKEN]); un bucle
     * infinito real lo agota en milisegundos, mientras que un programa válido nunca se acerca al límite.
     */
    private val maxSteps: Long =
        config.calculateMaxAllowedSteps(tokens.size.coerceAtLeast(1) * STEPS_PER_TOKEN)

    val currentIndex: Int
        get() = index

    val isAtEnd: Boolean
        get() = peek().category == TokenCategory.EOF

    /**
     * Devuelve el token actual sin consumirlo.
     */
    fun peek(): Token {
        registerStep()
        if (tokens.isEmpty()) {
            return Token(
                category = TokenCategory.EOF,
                lexeme = "",
                span = SourceSpan(SourcePosition.START, SourcePosition.START)
            )
        }
        return tokens[index.coerceIn(0, tokens.lastIndex)]
    }

    /**
     * Devuelve el token a `offset` posiciones hacia adelante sin consumirlo.
     */
    fun peekAhead(offset: Int = 1): Token {
        require(offset >= 0) { "El lookahead no puede ser negativo." }
        registerStep()
        if (tokens.isEmpty()) return peek()
        val target = (index + offset).coerceIn(0, tokens.lastIndex)
        return tokens[target]
    }

    /**
     * Devuelve el último token consumido (o el primero si aún estamos en el índice 0).
     */
    fun previous(): Token {
        if (tokens.isEmpty()) return peek()
        val prevIdx = (index - 1).coerceIn(0, tokens.lastIndex)
        return tokens[prevIdx]
    }

    /**
     * Verifica si el token actual pertenece a [category].
     */
    fun check(category: TokenCategory): Boolean {
        if (isAtEnd) return category == TokenCategory.EOF
        return peek().category == category
    }

    /**
     * Verifica si el token actual pertenece a cualquiera de las [categories] proporcionadas.
     */
    fun checkAny(vararg categories: TokenCategory): Boolean {
        val currentCat = peek().category
        return categories.any { it == currentCat }
    }

    /**
     * Consume el token actual y avanza el cursor en 1 posición.
     */
    fun advance(): Token {
        registerStep()
        val current = peek()
        if (!isAtEnd && index < tokens.lastIndex) {
            index++
        }
        return current
    }

    /**
     * Si el token actual coincide con [category], lo consume y lo retorna; de lo contrario retorna `null`.
     */
    fun match(category: TokenCategory): Token? {
        if (check(category)) {
            return advance()
        }
        return null
    }

    /**
     * Incrementa y valida la profundidad de anidamiento sintáctico (Anti-StackOverflow en iOS/Android).
     */
    fun enterRule() {
        currentDepth++
        if (currentDepth > config.maxAstDepth) {
            throw SecurityQuotaException(
                SecurityCheckResult.Violation(
                    code = SecurityViolationCode.AST_DEPTH_EXCEEDED,
                    message = "Se excedió la profundidad máxima de anidamiento sintáctico (${config.maxAstDepth} niveles).",
                    position = peek().span.start
                )
            )
        }
    }

    /**
     * Decrementa el contador de profundidad al salir de una producción gramatical.
     */
    fun exitRule() {
        if (currentDepth > 0) {
            currentDepth--
        }
    }

    /**
     * Garantiza que un bucle de repetición gramatical (`DECS_GLOB`, `CUERPO`, `L_CASES`, etc.)
     * haya avanzado al menos 1 token en la iteración actual.
     */
    fun enforceForwardProgress(startIndexOfIteration: Int) {
        if (!isAtEnd && index <= startIndexOfIteration) {
            advance()
        }
    }

    /**
     * Modo Pánico: Avanza de forma segura descartando tokens inesperados hasta encontrar un delimitador
     * de sincronización (`FI` `;`, `LLC` `}`) o el inicio de una nueva sentencia/declaración válida.
     */
    fun synchronizeTo(
        consumeDelimiter: Boolean = false,
        vararg syncTokens: TokenCategory
    ) {
        val syncSet = syncTokens.toSet()
        while (!isAtEnd) {
            val currentCat = peek().category
            if (currentCat in syncSet) {
                if (consumeDelimiter) {
                    advance()
                }
                return
            }
            if (currentCat == TokenCategory.FI) {
                if (consumeDelimiter) {
                    advance()
                }
                return
            }
            if (currentCat == TokenCategory.LLC) {
                return
            }
            advance()
        }
    }

    private fun registerStep() {
        stepCounter++
        if (stepCounter > maxSteps) {
            throw SecurityQuotaException(
                SecurityCheckResult.Violation(
                    code = SecurityViolationCode.INFINITE_LOOP_DETECTED,
                    message = "Se superó el límite de pasos del Analizador Sintáctico ($maxSteps). Posible bucle infinito detenido.",
                    position = if (tokens.isNotEmpty()) tokens[index.coerceIn(0, tokens.lastIndex)].span.start else SourcePosition.START
                )
            )
        }
    }

    private companion object {
        /** Lecturas permitidas por token (antes 4 × factor ≈ 12-32, insuficiente para reglas con mucho lookahead). */
        const val STEPS_PER_TOKEN: Int = 16
    }
}