package edu.itssg.compiler.core.semantic

import edu.itssg.compiler.core.semantic.EvaluatedValue.BoolVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.CharVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.DoubleVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.FloatVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.IntVal
import edu.itssg.compiler.core.semantic.EvaluatedValue.StringVal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Métodos básicos de la biblioteca de Java que reconoce el compilador didáctico: los de `String`, `Scanner`, `Math`
 * y las conversiones (`Integer.parseInt`, `Double.parseDouble`, `String.valueOf`…).
 *
 * Para cada método se conoce cuántos argumentos recibe y su tipo de retorno (lo usa la verificación de tipos). Si el
 * objeto y los argumentos son constantes, [evaluateInstance] / [evaluateStatic] calculan el resultado
 * si no, el analizador representa el resultado con una letra (`x`, `y`, `z`…).
 */
internal object JavaApi {

    /**
     * @property args Cantidades de argumentos aceptadas.
     * @property returns Tipo de retorno; `null` = el de los argumentos con promoción numérica (`Math.abs`, `max`…).
     * @property deterministic `false` si dos llamadas iguales pueden dar resultados distintos (`Math.random()`).
     */
    class MethodSpec(
        val name: String,
        val args: IntRange,
        val returns: SemanticDataType?,
        val deterministic: Boolean = true
    ) {
        fun returnType(argTypes: List<SemanticDataType>): SemanticDataType {
            returns?.let { return it }
            val numeric = argTypes.filter { it.isNumeric }
            if (numeric.isEmpty()) return SemanticDataType.DOUBLE
            return numeric.reduce { a, b -> SemanticDataType.numericPromotion(a, b) }
                .let { if (it == SemanticDataType.BYTE || it == SemanticDataType.SHORT || it == SemanticDataType.CHAR) SemanticDataType.INT else it }
        }

        fun acceptsArgCount(count: Int): Boolean = count in args

        val expectedArgsText: String
            get() = if (args.first == args.last) "${args.first}" else "${args.first} o ${args.last}"
    }

    private fun spec(name: String, args: Int, returns: SemanticDataType?, deterministic: Boolean = true) =
        name to MethodSpec(name, args..args, returns, deterministic)

    private fun spec(name: String, args: IntRange, returns: SemanticDataType?) =
        name to MethodSpec(name, args, returns)

    val STRING_METHODS: Map<String, MethodSpec> = mapOf(
        spec("length", 0, SemanticDataType.INT),
        spec("charAt", 1, SemanticDataType.CHAR),
        spec("substring", 1..2, SemanticDataType.STRING),
        spec("indexOf", 1, SemanticDataType.INT),
        spec("lastIndexOf", 1, SemanticDataType.INT),
        spec("equals", 1, SemanticDataType.BOOLEAN),
        spec("equalsIgnoreCase", 1, SemanticDataType.BOOLEAN),
        spec("compareTo", 1, SemanticDataType.INT),
        spec("contains", 1, SemanticDataType.BOOLEAN),
        spec("startsWith", 1, SemanticDataType.BOOLEAN),
        spec("endsWith", 1, SemanticDataType.BOOLEAN),
        spec("isEmpty", 0, SemanticDataType.BOOLEAN),
        spec("toUpperCase", 0, SemanticDataType.STRING),
        spec("toLowerCase", 0, SemanticDataType.STRING),
        spec("trim", 0, SemanticDataType.STRING),
        spec("concat", 1, SemanticDataType.STRING),
        spec("replace", 2, SemanticDataType.STRING)
    )

    /** Lecturas de `Scanner`: su valor solo se conoce al ejecutar, así que siempre se representa con una letra. */
    val SCANNER_METHODS: Map<String, MethodSpec> = mapOf(
        spec("nextLine", 0, SemanticDataType.STRING),
        spec("next", 0, SemanticDataType.STRING),
        spec("nextInt", 0, SemanticDataType.INT),
        spec("nextDouble", 0, SemanticDataType.DOUBLE),
        spec("nextFloat", 0, SemanticDataType.FLOAT),
        spec("nextLong", 0, SemanticDataType.LONG),
        spec("nextBoolean", 0, SemanticDataType.BOOLEAN),
        spec("close", 0, SemanticDataType.VOID)
    )

    val MATH_METHODS: Map<String, MethodSpec> = mapOf(
        spec("random", 0, SemanticDataType.DOUBLE, deterministic = false),
        spec("abs", 1, null),
        spec("max", 2, null),
        spec("min", 2, null),
        spec("pow", 2, SemanticDataType.DOUBLE),
        spec("sqrt", 1, SemanticDataType.DOUBLE),
        spec("floor", 1, SemanticDataType.DOUBLE),
        spec("ceil", 1, SemanticDataType.DOUBLE),
        spec("round", 1, SemanticDataType.LONG)
    )

    /** Métodos estáticos de conversión, por clase. */
    val CONVERSION_METHODS: Map<String, Map<String, MethodSpec>> = mapOf(
        "Integer" to mapOf(
            spec("parseInt", 1, SemanticDataType.INT),
            spec("valueOf", 1, SemanticDataType.INT),
            spec("toString", 1, SemanticDataType.STRING)
        ),
        "Double" to mapOf(
            spec("parseDouble", 1, SemanticDataType.DOUBLE),
            spec("valueOf", 1, SemanticDataType.DOUBLE),
            spec("toString", 1, SemanticDataType.STRING)
        ),
        "Float" to mapOf(spec("parseFloat", 1, SemanticDataType.FLOAT)),
        "Long" to mapOf(spec("parseLong", 1, SemanticDataType.LONG)),
        "Boolean" to mapOf(spec("parseBoolean", 1, SemanticDataType.BOOLEAN)),
        "String" to mapOf(spec("valueOf", 1, SemanticDataType.STRING))
    )

    /** Clases que se usan sin objeto: `Math.random()`, `Integer.parseInt(...)`. */
    val STATIC_CLASSES: Set<String> = setOf("Math") + CONVERSION_METHODS.keys

    /** Constantes de clase: `Math.PI`. */
    fun staticField(className: String, name: String): EvaluatedValue? =
        if (className == "Math" && name == "PI") DoubleVal(kotlin.math.PI) else null

    fun staticMethod(className: String, name: String): MethodSpec? =
        if (className == "Math") MATH_METHODS[name] else CONVERSION_METHODS[className]?.get(name)

    /** Clase de Java del objeto (`"String"`, `"Scanner"`) o `null` si no es de las que se conocen. */
    fun instanceMethod(receiverClass: String?, name: String): MethodSpec? = when (receiverClass) {
        "String" -> STRING_METHODS[name]
        "Scanner" -> SCANNER_METHODS[name]
        else -> null
    }

    /** Todos los nombres de la biblioteca (no se numeran en la Tabla de Símbolos). */
    val ALL_NAMES: Set<String> = STRING_METHODS.keys + SCANNER_METHODS.keys + MATH_METHODS.keys +
        CONVERSION_METHODS.values.flatMap { it.keys } + setOf("PI", "out", "in", "println", "print", "java", "util")

    //Evaluación con valores conocidos

    /** Resultado de `objeto.método(args)` con valores constantes, o `null` si no se puede conocer al compilar. */
    fun evaluateInstance(receiverClass: String?, receiver: EvaluatedValue, name: String, args: List<EvaluatedValue>): EvaluatedValue? {
        if (receiverClass != "String") return null // Scanner: se lee al ejecutar.
        if (STRING_METHODS[name]?.acceptsArgCount(args.size) != true) return null // El error ya se reportó.
        val text = (receiver as? StringVal)?.value ?: return null
        if (args.any { !it.isConstant }) return null
        return when (name) {
            "length" -> IntVal(text.length.toLong())
            "charAt" -> intArg(args, 0)?.let { i -> if (i in text.indices) CharVal(text[i].code) else null }
            "substring" -> {
                val begin = intArg(args, 0) ?: return null
                val end = if (args.size > 1) intArg(args, 1) ?: return null else text.length
                if (begin < 0 || end > text.length || begin > end) null else StringVal(text.substring(begin, end))
            }
            "indexOf" -> textArg(args, 0)?.let { IntVal(text.indexOf(it).toLong()) }
            "lastIndexOf" -> textArg(args, 0)?.let { IntVal(text.lastIndexOf(it).toLong()) }
            "equals" -> BoolVal(args[0] is StringVal && (args[0] as StringVal).value == text)
            "equalsIgnoreCase" -> (args[0] as? StringVal)?.let { BoolVal(it.value.equals(text, ignoreCase = true)) }
            "compareTo" -> (args[0] as? StringVal)?.let { IntVal(javaCompareTo(text, it.value).toLong()) }
            "contains" -> textArg(args, 0)?.let { BoolVal(text.contains(it)) }
            "startsWith" -> textArg(args, 0)?.let { BoolVal(text.startsWith(it)) }
            "endsWith" -> textArg(args, 0)?.let { BoolVal(text.endsWith(it)) }
            "isEmpty" -> BoolVal(text.isEmpty())
            "toUpperCase" -> StringVal(text.uppercase())
            "toLowerCase" -> StringVal(text.lowercase())
            "trim" -> StringVal(text.trim())
            "concat" -> (args[0] as? StringVal)?.let { StringVal(text + it.value) }
            "replace" -> {
                val from = textArg(args, 0) ?: return null
                val to = textArg(args, 1) ?: return null
                StringVal(text.replace(from, to))
            }
            else -> null
        }
    }

    /** Resultado de `Clase.método(args)` con valores constantes, o `null`. */
    fun evaluateStatic(className: String, name: String, args: List<EvaluatedValue>): EvaluatedValue? {
        if (staticMethod(className, name)?.acceptsArgCount(args.size) != true) return null // El error ya se reportó.
        if (args.any { !it.isConstant }) return null
        if (className == "Math") {
            val nums = args.map { it.asDoubleOrNull() ?: return null }
            val type = staticMethod(className, name)?.returnType(args.map { it.dataType }) ?: return null
            return when (name) {
                "abs" -> typed(abs(nums[0]), type)
                "max" -> typed(maxOf(nums[0], nums[1]), type)
                "min" -> typed(minOf(nums[0], nums[1]), type)
                "pow" -> DoubleVal(nums[0].pow(nums[1]))
                "sqrt" -> if (nums[0] < 0) null else DoubleVal(sqrt(nums[0]))
                "floor" -> DoubleVal(floor(nums[0]))
                "ceil" -> DoubleVal(ceil(nums[0]))
                "round" -> IntVal(floor(nums[0] + 0.5).toLong(), SemanticDataType.LONG)
                else -> null // random(): desconocido
            }
        }
        val arg = args.singleOrNull() ?: return null
        val text = EvaluatedValue.toConcatText(arg)
        return when (className to name) {
            "Integer" to "parseInt", "Integer" to "valueOf" ->
                (arg as? StringVal)?.value?.trim()?.toIntOrNull()?.let { IntVal(it.toLong()) }
                    ?: if (name == "valueOf" && arg is IntVal) arg else null
            "Double" to "parseDouble", "Double" to "valueOf" ->
                (arg as? StringVal)?.value?.trim()?.toDoubleOrNull()?.let { DoubleVal(it) }
                    ?: if (name == "valueOf") arg.asDoubleOrNull()?.let { DoubleVal(it) } else null
            "Float" to "parseFloat" -> (arg as? StringVal)?.value?.trim()?.toFloatOrNull()?.let { FloatVal(it) }
            "Long" to "parseLong" -> (arg as? StringVal)?.value?.trim()?.toLongOrNull()?.let { IntVal(it, SemanticDataType.LONG) }
            "Boolean" to "parseBoolean" -> (arg as? StringVal)?.let { BoolVal(it.value.equals("true", ignoreCase = true)) }
            "String" to "valueOf", "Integer" to "toString", "Double" to "toString" -> text?.let { StringVal(it) }
            else -> null
        }
    }

    private fun intArg(args: List<EvaluatedValue>, i: Int): Int? = when (val a = args.getOrNull(i)) {
        is IntVal -> a.value.toInt()
        is CharVal -> a.code
        else -> null
    }

    /** Argumento de texto: un String o un char (`indexOf('M')`). */
    private fun textArg(args: List<EvaluatedValue>, i: Int): String? = when (val a = args.getOrNull(i)) {
        is StringVal -> a.value
        is CharVal -> a.code.toChar().toString()
        else -> null
    }

    private fun typed(value: Double, type: SemanticDataType): EvaluatedValue = when {
        type.isIntegral -> IntVal(round(value).toLong(), if (type == SemanticDataType.LONG) type else SemanticDataType.INT)
        type == SemanticDataType.FLOAT -> FloatVal(value.toFloat())
        else -> DoubleVal(value)
    }

    /** `String.compareTo` de Java: diferencia del primer carácter distinto, o de las longitudes. */
    private fun javaCompareTo(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) if (a[i] != b[i]) return a[i].code - b[i].code
        return a.length - b.length
    }
}
