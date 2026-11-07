package edu.itssg.compiler.core.semantic

import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.semantic.EvaluatedValue.BoolVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.CharVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.DoubleVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.FloatVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.IntVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.StringVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.SymbolicVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.UnknownVal

/**
 * Evaluación de valores con las reglas de Java:
 * - `'@'` vale 64 y `'F'` vale 70 al operar (char → int).
 * - División entera: `15/4` = 3, `99/10` = 9.
 * - Promoción: `45.0 + 256.0f` = `301.0` (double), `1 + 0.0f` = `1.0f`.
 * - Concatenación: `"1" + 10` = `"110"`; `"110".length()` = 3.
 * - `Math.random()` es un valor simbólico `X.0`: `15 * X.0` = `15X.0`, `10 + X.0` = `10+X.0`.
 */
object ValueOps {

    /** Valor de un literal (`VAL`). Acepta las comillas tipográficas de las diapositivas (`‘@’`, `“hola”`). */
    fun literalValue(token: Token): EvaluatedValue {
        val lexeme = token.lexeme
        return when (token.category) {
            TokenCategory.VTE -> lexeme.toLongOrNull()?.let { v ->
                if (v in Int.MIN_VALUE..Int.MAX_VALUE) IntVal(v) else IntVal(v, SemanticDataType.LONG)
            } ?: UnknownVal
            TokenCategory.VTD -> lexeme.toDoubleOrNull()?.let { DoubleVal(it) } ?: UnknownVal
            TokenCategory.VTF -> lexeme.trimEnd('f', 'F').let { if (it.endsWith(".")) it + "0" else it }
                .toFloatOrNull()?.let { FloatVal(it) } ?: UnknownVal
            TokenCategory.VTC -> charLiteralCode(lexeme)?.let { CharVal(it) } ?: UnknownVal
            TokenCategory.VTS -> StringVal(unescape(stripQuotes(lexeme)))
            TokenCategory.VTB -> BoolVal(lexeme == "true")
            TokenCategory._NU -> EvaluatedValue.NullVal
            else -> UnknownVal
        }
    }

    fun literalType(category: TokenCategory, lexeme: String = ""): SemanticDataType = when (category) {
        TokenCategory.VTE -> if ((lexeme.toLongOrNull() ?: 0L) in Int.MIN_VALUE..Int.MAX_VALUE) SemanticDataType.INT else SemanticDataType.LONG
        TokenCategory.VTD -> SemanticDataType.DOUBLE
        TokenCategory.VTF -> SemanticDataType.FLOAT
        TokenCategory.VTC -> SemanticDataType.CHAR
        TokenCategory.VTS -> SemanticDataType.STRING
        TokenCategory.VTB -> SemanticDataType.BOOLEAN
        TokenCategory._NU -> SemanticDataType.NULL
        else -> SemanticDataType.UNKNOWN
    }

    fun stripQuotes(raw: String): String {
        if (raw.length >= 2) {
            val first = raw.first()
            val last = raw.last()
            if ((first == '"' || first == '“' || first == '”') && (last == '"' || last == '”' || last == '“')) {
                return raw.substring(1, raw.length - 1)
            }
        }
        return raw
    }

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '0' -> sb.append('\u0000')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun charLiteralCode(lexeme: String): Int? {
        if (lexeme.length < 3) return null
        val body = lexeme.substring(1, lexeme.length - 1)
        if (body.isEmpty()) return null
        if (body[0] != '\\') return body[0].code
        if (body.length < 2) return null
        return when (body[1]) {
            'n' -> 10
            't' -> 9
            'r' -> 13
            'b' -> 8
            'f' -> 12
            '0' -> 0
            'u' -> body.drop(2).take(4).toIntOrNull(16)
            else -> body[1].code
        }
    }

    // ---------------------------------------------------------------------- Aritmética

    private fun wrapInt(v: Long, type: SemanticDataType): EvaluatedValue = when (type) {
        SemanticDataType.LONG -> IntVal(v, SemanticDataType.LONG)
        else -> IntVal(v.toInt().toLong(), SemanticDataType.INT)
    }

    private fun integralOf(v: EvaluatedValue): Long? = when (v) {
        is IntVal -> v.value
        is CharVal -> v.code.toLong()
        else -> null
    }

    /** Resultado de `left op right` o [UnknownVal] si no se puede conocer en compilación. */
    fun binary(op: String, left: EvaluatedValue, right: EvaluatedValue): EvaluatedValue {
        // Concatenación de String
        if (op == "+" && (left is StringVal || right is StringVal || left.isTextSymbol() || right.isTextSymbol())) {
            // Con un texto desconocido (lo que lee Scanner) el resultado también es desconocido: el analizador le
            // asigna su propia letra.
            if (left.isTextSymbol() || right.isTextSymbol()) return UnknownVal
            val l = EvaluatedValue.toConcatText(left) ?: (left as? SymbolicVal)?.formatForTreeAndQuadruple()
            val r = EvaluatedValue.toConcatText(right) ?: (right as? SymbolicVal)?.formatForTreeAndQuadruple()
            return if (l != null && r != null) StringVal(l + r) else UnknownVal
        }
        if (left is SymbolicVal || right is SymbolicVal) return symbolic(op, left, right)

        when (op) {
            "==", "!=" -> {
                val eq = when {
                    left.asDoubleOrNull() != null && right.asDoubleOrNull() != null ->
                        left.asDoubleOrNull() == right.asDoubleOrNull()
                    left is BoolVal && right is BoolVal -> left.value == right.value
                    else -> return UnknownVal
                }
                return BoolVal(if (op == "==") eq else !eq)
            }
            "<", ">", "<=", ">=" -> {
                val a = left.asDoubleOrNull() ?: return UnknownVal
                val b = right.asDoubleOrNull() ?: return UnknownVal
                return BoolVal(
                    when (op) {
                        "<" -> a < b
                        ">" -> a > b
                        "<=" -> a <= b
                        else -> a >= b
                    }
                )
            }
            "&&", "||", "&", "|", "^" -> {
                if (left is BoolVal && right is BoolVal) {
                    return BoolVal(
                        when (op) {
                            "&&", "&" -> left.value && right.value
                            "||", "|" -> left.value || right.value
                            else -> left.value != right.value
                        }
                    )
                }
                val a = integralOf(left) ?: return UnknownVal
                val b = integralOf(right) ?: return UnknownVal
                val type = SemanticDataType.numericPromotion(left.dataType, right.dataType)
                return wrapInt(
                    when (op) {
                        "&" -> a and b
                        "|" -> a or b
                        "^" -> a xor b
                        else -> return UnknownVal
                    }, type
                )
            }
        }

        if (!left.dataType.isNumeric || !right.dataType.isNumeric) return UnknownVal
        val type = SemanticDataType.numericPromotion(left.dataType, right.dataType)
        return when (type) {
            SemanticDataType.INT, SemanticDataType.LONG -> {
                val a = integralOf(left) ?: return UnknownVal
                val b = integralOf(right) ?: return UnknownVal
                when (op) {
                    "+" -> wrapInt(a + b, type)
                    "-" -> wrapInt(a - b, type)
                    "*" -> wrapInt(a * b, type)
                    "/" -> if (b == 0L) UnknownVal else wrapInt(a / b, type)
                    "%" -> if (b == 0L) UnknownVal else wrapInt(a % b, type)
                    else -> UnknownVal
                }
            }
            SemanticDataType.FLOAT -> {
                val a = left.asDoubleOrNull()?.toFloat() ?: return UnknownVal
                val b = right.asDoubleOrNull()?.toFloat() ?: return UnknownVal
                when (op) {
                    "+" -> FloatVal(a + b)
                    "-" -> FloatVal(a - b)
                    "*" -> FloatVal(a * b)
                    "/" -> FloatVal(a / b)
                    "%" -> FloatVal(a % b)
                    else -> UnknownVal
                }
            }
            else -> {
                val a = left.asDoubleOrNull() ?: return UnknownVal
                val b = right.asDoubleOrNull() ?: return UnknownVal
                when (op) {
                    "+" -> DoubleVal(a + b)
                    "-" -> DoubleVal(a - b)
                    "*" -> DoubleVal(a * b)
                    "/" -> DoubleVal(a / b)
                    "%" -> DoubleVal(a % b)
                    else -> UnknownVal
                }
            }
        }
    }

    /** `true` si es un valor desconocido no numérico (String, boolean…): no admite aritmética lineal. */
    private fun EvaluatedValue.isTextSymbol(): Boolean =
        this is SymbolicVal && !type.isNumeric

    /**
     * Aritmética lineal con valores desconocidos (letras): `x + 5`, `3·z`, `2y + 3z + 50.0`. Lo que no es lineal
     * (`x·y`, `x / y`, `%`, comparaciones) da [UnknownVal] y el analizador le asigna una letra nueva.
     * Si las letras se cancelan (`x - x`) el resultado es la constante.
     */
    private fun symbolic(op: String, left: EvaluatedValue, right: EvaluatedValue): EvaluatedValue {
        if (left.isTextSymbol() || right.isTextSymbol()) return UnknownVal
        val type = SemanticDataType.numericPromotion(left.dataType, right.dataType)
        val ls = left as? SymbolicVal
        val rs = right as? SymbolicVal
        val lc = left.asDoubleOrNull()
        val rc = right.asDoubleOrNull()
        val result: EvaluatedValue = when {
            ls != null && rs != null -> when (op) {
                "+" -> combine(ls, rs, 1.0, type)
                "-" -> combine(ls, rs, -1.0, type)
                else -> UnknownVal
            }
            ls != null && rc != null -> when (op) {
                "+" -> SymbolicVal(ls.terms, ls.constant + rc, type)
                "-" -> SymbolicVal(ls.terms, ls.constant - rc, type)
                "*" -> scale(ls, rc, type)
                "/" -> if (rc == 0.0) UnknownVal else scale(ls, 1.0 / rc, type)
                else -> UnknownVal
            }
            rs != null && lc != null -> when (op) {
                "+" -> SymbolicVal(rs.terms, rs.constant + lc, type)
                "-" -> SymbolicVal(rs.terms.mapValues { -it.value }, lc - rs.constant, type)
                "*" -> scale(rs, lc, type)
                else -> UnknownVal
            }
            else -> UnknownVal
        }
        return if (result is SymbolicVal) simplify(result) else result
    }

    private fun combine(a: SymbolicVal, b: SymbolicVal, sign: Double, type: SemanticDataType): SymbolicVal {
        val terms = LinkedHashMap(a.terms)
        for ((letter, c) in b.terms) terms[letter] = (terms[letter] ?: 0.0) + sign * c
        return SymbolicVal(terms, a.constant + sign * b.constant, type)
    }

    private fun scale(a: SymbolicVal, factor: Double, type: SemanticDataType): SymbolicVal =
        SymbolicVal(a.terms.mapValues { it.value * factor }, a.constant * factor, type)

    /** Quita las letras con coeficiente 0; si no queda ninguna, devuelve la constante con su tipo. */
    private fun simplify(v: SymbolicVal): EvaluatedValue {
        val terms = v.terms.filterValues { kotlin.math.abs(it) >= 1e-9 }
        if (terms.isNotEmpty()) return SymbolicVal(terms, v.constant, v.type)
        return when {
            v.type.isIntegral -> wrapInt(kotlin.math.round(v.constant).toLong(), v.type)
            v.type == SemanticDataType.FLOAT -> FloatVal(v.constant.toFloat())
            else -> DoubleVal(v.constant)
        }
    }

    fun unary(op: String, value: EvaluatedValue): EvaluatedValue = when (op) {
        "-" -> when (value) {
            is IntVal -> wrapInt(-value.value, value.type)
            is CharVal -> IntVal(-value.code.toLong())
            is FloatVal -> FloatVal(-value.value)
            is DoubleVal -> DoubleVal(-value.value)
            is SymbolicVal -> if (value.type.isNumeric) {
                SymbolicVal(value.terms.mapValues { -it.value }, -value.constant, value.type)
            } else {
                UnknownVal
            }
            else -> UnknownVal
        }
        "+" -> if (value is CharVal) IntVal(value.code.toLong()) else value
        "!" -> if (value is BoolVal) BoolVal(!value.value) else UnknownVal
        "~" -> integralOf(value)?.let { wrapInt(it.inv(), SemanticDataType.numericPromotion(value.dataType, SemanticDataType.INT)) } ?: UnknownVal
        else -> UnknownVal
    }

    /** Conversión explícita `(tipo)valor`. */
    fun cast(value: EvaluatedValue, target: SemanticDataType): EvaluatedValue {
        if (value is SymbolicVal) {
            return if (target.isNumeric) value.copy(type = target) else UnknownVal
        }
        val d = value.asDoubleOrNull()
        return when (target) {
            SemanticDataType.INT -> d?.let { IntVal(truncate(it).toInt().toLong()) } ?: UnknownVal
            SemanticDataType.LONG -> d?.let { IntVal(truncate(it), SemanticDataType.LONG) } ?: UnknownVal
            SemanticDataType.SHORT -> d?.let { IntVal(truncate(it).toInt().toShort().toLong(), SemanticDataType.SHORT) } ?: UnknownVal
            SemanticDataType.BYTE -> d?.let { IntVal(truncate(it).toInt().toByte().toLong(), SemanticDataType.BYTE) } ?: UnknownVal
            SemanticDataType.CHAR -> d?.let { CharVal(truncate(it).toInt() and 0xFFFF) } ?: UnknownVal
            SemanticDataType.FLOAT -> d?.let { FloatVal(it.toFloat()) } ?: UnknownVal
            SemanticDataType.DOUBLE -> d?.let { if (value is FloatVal) DoubleVal(value.value.toDouble()) else DoubleVal(it) } ?: UnknownVal
            else -> if (value.dataType == target) value else UnknownVal
        }
    }

    private fun truncate(d: Double): Long = if (d.isNaN()) 0L else d.toLong()

    /** Conversión implícita al asignar a una variable de tipo [target] (ampliación o constante que cabe). */
    fun coerce(value: EvaluatedValue, target: SemanticDataType): EvaluatedValue {
        if (target == SemanticDataType.UNKNOWN || value == UnknownVal) return value
        if (value is SymbolicVal) return if (target.isNumeric) value.copy(type = target) else value
        if (!target.isNumeric || !value.dataType.isNumeric) return value
        if (value.dataType == target) return value
        return cast(value, target)
    }
}
