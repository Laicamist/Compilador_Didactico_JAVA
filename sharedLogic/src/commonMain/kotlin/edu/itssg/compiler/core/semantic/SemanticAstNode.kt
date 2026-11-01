package edu.itssg.compiler.core.semantic

import edu.itssg.compiler.core.security.SourceSpan
import kotlin.math.abs
import kotlin.math.round

/**
 * Tipos de datos (TD) que maneja el compilador didáctico.
 *
 * @property numericRank Orden de promoción numérica de Java (byte < short/char < int < long < float < double).
 */
enum class SemanticDataType(
    val keyword: String,
    val tokenMnemonic: String,
    val numericRank: Int
) {
    BYTE("byte", "VTE", 1),
    SHORT("short", "VTE", 2),
    CHAR("char", "VTC", 2),
    INT("int", "VTE", 3),
    LONG("long", "VTE", 4),
    FLOAT("float", "VTF", 5),
    DOUBLE("double", "VTD", 6),
    BOOLEAN("boolean", "VTB", -1),
    STRING("String", "VTS", -1),
    VOID("void", "TM", -1),
    CLASS_INSTANCE("Object", "ID", -1),
    NULL("null", "_NU", -1),
    UNKNOWN("unknown", "ERROR", -1);

    val isNumeric: Boolean get() = numericRank > 0

    val isIntegral: Boolean get() = this == BYTE || this == SHORT || this == CHAR || this == INT || this == LONG

    /** Valor por defecto de un atributo sin inicializar (ej. `float numero;` → `0.0f`, ejemplo `Nueva`). */
    fun defaultValue(): EvaluatedValue = when (this) {
        BYTE, SHORT, INT, LONG -> EvaluatedValue.IntVal(0, this)
        CHAR -> EvaluatedValue.CharVal(0)
        FLOAT -> EvaluatedValue.FloatVal(0.0f)
        DOUBLE -> EvaluatedValue.DoubleVal(0.0)
        BOOLEAN -> EvaluatedValue.BoolVal(false)
        STRING, CLASS_INSTANCE, NULL -> EvaluatedValue.NullVal
        else -> EvaluatedValue.UnknownVal
    }

    /** Literal con el que se escribe el valor por defecto en el código intermedio (`0.0f`, `0`, `0.0`). */
    fun defaultLiteralText(): String = when (this) {
        BYTE, SHORT, INT, LONG -> "0"
        CHAR -> "'\\u0000'"
        FLOAT -> "0.0f"
        DOUBLE -> "0.0"
        BOOLEAN -> "false"
        else -> "null"
    }

    companion object {
        fun fromTypeName(name: String?): SemanticDataType = when (name?.trim()) {
            "byte", "Byte" -> BYTE
            "short", "Short" -> SHORT
            "char", "Character" -> CHAR
            "int", "Integer" -> INT
            "long", "Long" -> LONG
            "float", "Float" -> FLOAT
            "double", "Double" -> DOUBLE
            "boolean", "Boolean" -> BOOLEAN
            "String" -> STRING
            "void" -> VOID
            null, "" -> UNKNOWN
            else -> CLASS_INSTANCE
        }

        /**
         * ¿Se puede asignar un valor de tipo [source] a una variable de tipo [target]? (conversión de ampliación de Java).
         * La conversión de reducción de constantes enteras (`char b = 99/10;`, ejemplo `Lunes`) se valida aparte,
         * porque depende del valor.
         */
        fun isAssignable(target: SemanticDataType, source: SemanticDataType): Boolean {
            if (target == UNKNOWN || source == UNKNOWN) return true
            if (target == source) return true
            if (source == NULL) return target == STRING || target == CLASS_INSTANCE
            if (target.isNumeric && source.isNumeric) {
                if (target == CHAR) return false // solo constantes (ver isConstantNarrowingAllowed)
                if (source == CHAR) return target.numericRank >= INT.numericRank
                if (target == SHORT && source == BYTE) return true
                return target.numericRank >= source.numericRank && !(target == SHORT && source == CHAR)
            }
            return false
        }

        /** Java permite asignar una constante entera a `byte`, `short` o `char` si el valor cabe en el tipo. */
        fun isConstantNarrowingAllowed(target: SemanticDataType, value: EvaluatedValue): Boolean {
            val v = when (value) {
                is EvaluatedValue.IntVal -> if (value.type == LONG) return false else value.value
                is EvaluatedValue.CharVal -> value.code.toLong()
                else -> return false
            }
            return when (target) {
                BYTE -> v in -128..127
                SHORT -> v in -32768..32767
                CHAR -> v in 0..65535
                else -> false
            }
        }

        /** Promoción numérica binaria de Java. */
        fun numericPromotion(left: SemanticDataType, right: SemanticDataType): SemanticDataType = when {
            left == DOUBLE || right == DOUBLE -> DOUBLE
            left == FLOAT || right == FLOAT -> FLOAT
            left == LONG || right == LONG -> LONG
            else -> INT
        }

        /** Tipo resultante de `left op right`, o [UNKNOWN] si el operador no admite esos TD. */
        fun binaryResultType(left: SemanticDataType, operator: String, right: SemanticDataType): SemanticDataType {
            if (left == UNKNOWN || right == UNKNOWN) return UNKNOWN
            return when (operator) {
                "+" -> when {
                    left == STRING || right == STRING -> if (left == VOID || right == VOID) UNKNOWN else STRING
                    left.isNumeric && right.isNumeric -> numericPromotion(left, right)
                    else -> UNKNOWN
                }
                "-", "*", "/", "%" ->
                    if (left.isNumeric && right.isNumeric) numericPromotion(left, right) else UNKNOWN
                "<", ">", "<=", ">=" ->
                    if (left.isNumeric && right.isNumeric) BOOLEAN else UNKNOWN
                "==", "!=" -> when {
                    left.isNumeric && right.isNumeric -> BOOLEAN
                    left == right -> BOOLEAN
                    left == NULL || right == NULL -> BOOLEAN
                    else -> UNKNOWN
                }
                "&", "|", "&&", "||", "^" -> when {
                    left == BOOLEAN && right == BOOLEAN -> BOOLEAN
                    operator.length == 1 && left.isIntegral && right.isIntegral -> numericPromotion(left, right)
                    else -> UNKNOWN
                }
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Valor evaluado de un nodo del Árbol Abstracto-Semántico y columna `res` de los Cuádruplos:
 * enteros (`64` para `'@'`), flotantes con `f` (`3.0f`), dobles (`301.0`), caracteres (`'R'`, `TAB`),
 * cadenas (`"110"`) y valores simbólicos de `Math.random()` (`X.0`, `15X.0`, `10+X.0`).
 */
sealed interface EvaluatedValue {
    val dataType: SemanticDataType
    fun formatForTreeAndQuadruple(): String

    /** Valor numérico exacto si es una constante numérica. */
    fun asDoubleOrNull(): Double? = null

    /** `true` si el valor es una constante conocida (apto para Folding y Propagación de Constantes). */
    val isConstant: Boolean get() = false

    data class IntVal(val value: Long, val type: SemanticDataType = SemanticDataType.INT) : EvaluatedValue {
        override val dataType: SemanticDataType get() = type
        override fun formatForTreeAndQuadruple(): String = value.toString()
        override fun asDoubleOrNull(): Double = value.toDouble()
        override val isConstant: Boolean get() = true
    }

    /** Carácter (`'@'` = 64). Se muestra como en las láminas: `'R'`, o el nombre del control (`TAB` para 9). */
    data class CharVal(val code: Int) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.CHAR
        override fun formatForTreeAndQuadruple(): String = when (code) {
            0 -> "NUL"
            8 -> "BS"
            9 -> "TAB"
            10 -> "LF"
            13 -> "CR"
            32 -> "' '"
            else -> if (code in 33..126 || code > 160) "'${code.toChar()}'" else "CHR($code)"
        }
        override fun asDoubleOrNull(): Double = code.toDouble()
        override val isConstant: Boolean get() = true
    }

    data class FloatVal(val value: Float) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.FLOAT
        override fun formatForTreeAndQuadruple(): String = "${formatDecimal(value.toDouble())}f"
        override fun asDoubleOrNull(): Double = value.toDouble()
        override val isConstant: Boolean get() = true
    }

    data class DoubleVal(val value: Double) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.DOUBLE
        override fun formatForTreeAndQuadruple(): String = formatDecimal(value)
        override fun asDoubleOrNull(): Double = value
        override val isConstant: Boolean get() = true
    }

    data class StringVal(val value: String) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.STRING
        override fun formatForTreeAndQuadruple(): String = "\"$value\""
        override val isConstant: Boolean get() = true
    }

    data class BoolVal(val value: Boolean) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.BOOLEAN
        override fun formatForTreeAndQuadruple(): String = value.toString()
        override val isConstant: Boolean get() = true
    }

    /**
     * Valor desconocido en compilación que depende de `Math.random()`: forma lineal `constant + coefficient·X`.
     * Se muestra como en las láminas de la U2/U3: `X.0`, `15X.0`, `5X.0`, `10+X.0`, `57.3248X`.
     */
    data class SymbolicVal(
        val coefficient: Double = 1.0,
        val constant: Double = 0.0,
        val type: SemanticDataType = SemanticDataType.DOUBLE
    ) : EvaluatedValue {
        override val dataType: SemanticDataType get() = type
        override fun formatForTreeAndQuadruple(): String {
            val integralSuffix = type == SemanticDataType.DOUBLE || type == SemanticDataType.FLOAT
            val xPart = when {
                abs(coefficient - 1.0) < 1e-9 -> if (integralSuffix) "X.0" else "X"
                isWhole(coefficient) -> "${coefficient.toLong()}X" + if (integralSuffix) ".0" else ""
                else -> "${roundTo4(coefficient)}X"
            }
            if (abs(constant) < 1e-9) return xPart
            val c = if (isWhole(constant)) constant.toLong().toString() else roundTo4(constant).toString()
            return "$c+$xPart"
        }
    }

    /** Referencia a un objeto de la clase (`new Uno()`). */
    data class ObjectVal(val className: String) : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.CLASS_INSTANCE
        override fun formatForTreeAndQuadruple(): String = className
    }

    data object NullVal : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.NULL
        override fun formatForTreeAndQuadruple(): String = "null"
    }

    data object UnknownVal : EvaluatedValue {
        override val dataType: SemanticDataType get() = SemanticDataType.UNKNOWN
        override fun formatForTreeAndQuadruple(): String = "?"
    }

    companion object {
        private fun isWhole(number: Double): Boolean = abs(number - round(number)) < 1e-9 && abs(number) < 1e15

        private fun roundTo4(number: Double): Double = round(number * 10000.0) / 10000.0

        /** `4.0`, `57.3248`: máximo 4 decimales para evitar ruido de coma flotante (`109.60000000000001`). */
        fun formatDecimal(number: Double): String {
            if (number.isNaN()) return "NaN"
            if (number.isInfinite()) return if (number > 0) "Infinity" else "-Infinity"
            if (isWhole(number)) return "${round(number).toLong()}.0"
            return roundTo4(number).toString()
        }

        /** Representación de un valor al concatenarse con un `String` (reglas de Java: `"1" + 10` → `"110"`). */
        fun toConcatText(value: EvaluatedValue): String? = when (value) {
            is IntVal -> value.value.toString()
            is CharVal -> value.code.toChar().toString()
            is FloatVal -> formatDecimal(value.value.toDouble())
            is DoubleVal -> formatDecimal(value.value)
            is StringVal -> value.value
            is BoolVal -> value.value.toString()
            is NullVal -> "null"
            else -> null
        }
    }
}

/**
 * Categoría estructural del nodo del Árbol Abstracto-Semántico.
 *
 * Los nodos de las categorías que representan una acción ([isOperation]) generan un renglón de código
 * intermedio; las demás son hojas (VALORES del código).
 */
enum class SemanticNodeKind(val isOperation: Boolean) {
    PROGRAM_ROOT(false),     // "ID1 PMAIN": clase principal conectada únicamente con el método principal
    OUTPUT_STATEMENT(true),  // "SOUT" (no binaria)
    MEMBER_ACCESS(true),     // "." (acceso a atributo/método de la clase o método de Java como length())
    METHOD_RETURN(true),     // "ID5 RTN" (no binaria)
    ASSIGNMENT_LINK(true),   // "ID2 =" (no binaria)
    BINARY_OPERATOR(true),   // "+", "-", "*", "/", "%", relacionales y lógicos (ramas binarias)
    UNARY_OPERATOR(true),    // "!", "~", "-" unario
    IDENTIFIER_REF(false),   // "ID1", "Math", "length()", o referencia a un paso ya calculado
    LITERAL_VALUE(false)     // "15", "2.0", "0.0f", "'@'", "\"LOL\""
}

/**
 * Nodo inmutable del Árbol Abstracto-Semántico.
 *
 * Reglas de construcción (U1, "Elaborar árbol abstracto de código"):
 * - La raíz es la clase principal y se conecta únicamente con el método principal.
 * - Las acciones son ramas binarias y las hojas son los VALORES del código.
 * - Asignaciones, `SOUT`, `return` y métodos de Java NO son ramas binarias.
 * - La ejecución es de abajo hacia arriba y de izquierda a derecha; [evaluationStepNumber] es ese orden
 *   y coincide con el número de renglón del código intermedio.
 *
 * @property referencedNodeId Para hojas `IDn` que reutilizan un valor ya calculado en una instrucción previa del
 *   método principal (p. ej. `a = b + 5*2;` reutiliza el paso de `b`): id del nodo que lo calculó.
 */
data class SemanticAstNode(
    val id: Int,
    val kind: SemanticNodeKind,
    val primaryLabel: String,
    val secondaryLabel: String? = null,
    val castTypeAnnotation: String? = null,
    val resolvedType: SemanticDataType = SemanticDataType.UNKNOWN,
    val evaluatedValue: EvaluatedValue = EvaluatedValue.UnknownVal,
    val evaluationStepNumber: Int? = null,
    val symbolId: Int? = null,
    val referencedNodeId: Int? = null,
    val children: List<SemanticAstNode> = emptyList(),
    val span: SourceSpan
) {
    /** Texto del recuadro del diagrama (ej. `"ID1\nPMAIN"`, `"ID4\n="`, `"RTN (char)"`, `"/ (int)"`). */
    val boxDisplayText: String
        get() = buildString {
            append(primaryLabel)
            if (!secondaryLabel.isNullOrBlank()) append("\n").append(secondaryLabel)
            if (!castTypeAnnotation.isNullOrBlank()) append(" (").append(castTypeAnnotation).append(")")
        }
}
