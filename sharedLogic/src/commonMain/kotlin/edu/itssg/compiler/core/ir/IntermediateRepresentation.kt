package edu.itssg.compiler.core.ir

import edu.itssg.compiler.core.semantic.EvaluatedValue

/**
 * Tipo de representación de Código Intermedio (CI).
 */
enum class IrFormatKind(val displayName: String) {
    TRIPLE("Triplos"),
    QUADRUPLE("Cuádruplos"),
    THREE_ADDRESS("Tres Direcciones")
}

/**
 * Etiquetas oficiales de optimización local.
 *
 * Códigos base:
 * `SE` Simplificación de expresiones, `FO` Folding, `PC` Propagación de constantes,
 * `RS` Reducción de subexpresiones comunes, `CM` Código muerto.
 *
 * **Optimización Derivada:** cuando la optimización es producida por un cambio previo en el
 * registro (el cuádruplo fue intervenido por otra optimización y gracias a ese cambio se vuelve
 * optimizable), se agrega una `D` al código: `SED`, `FOD`, `PCD`, `RSD`, `CMD`.
 * La variante derivada NO es una técnica distinta, por eso se modela con [codeFor] y no como
 * entradas extra de la enum.
 */
enum class OptimizationTag(val code: String, val description: String) {
    EXPRESSION_SIMPLIFICATION("SE", "Simplificación de expresiones"),
    FOLDING("FO", "Folding"),
    CONSTANT_PROPAGATION("PC", "Propagación de constantes"),
    SUBEXPRESSION_REDUCTION("RS", "Reducción de subexpresiones comunes"),
    DEAD_CODE("CM", "Código muerto");

    /** Código de la variante derivada (`SED`, `FOD`, `PCD`, `RSD`, `CMD`). */
    val derivedCode: String get() = code + DERIVED_SUFFIX

    /** Devuelve el código base o el derivado según corresponda. */
    fun codeFor(isDerived: Boolean): String = if (isDerived) derivedCode else code

    /** Descripción legible, indicando si se trata de la variante derivada. */
    fun descriptionFor(isDerived: Boolean): String =
        if (isDerived) "$description (derivada)" else description

    companion object {
        const val DERIVED_SUFFIX = "D"

        /**
         * Interpreta un código académico (`PC`, `PCD`, `FO`, `FOD`, `RS[1]`, …). El cuádruplo repetido entre
         * corchetes de RS se ignora. Ningún código base termina en `D`, por lo que la lectura no es ambigua.
         *
         * @return la etiqueta y si es derivada, o `null` si el código no es válido.
         */
        fun parse(rawCode: String): OptimizationMarkCode? {
            val normalized = rawCode.trim().uppercase().substringBefore('[').trim()
            entries.firstOrNull { it.code == normalized }?.let {
                return OptimizationMarkCode(it, isDerived = false)
            }
            entries.firstOrNull { it.derivedCode == normalized }?.let {
                return OptimizationMarkCode(it, isDerived = true)
            }
            return null
        }
    }
}

/**
 * Código que se escribe en la tabla: el de la técnica y, para Reducción de Subexpresiones, el cuádruplo que se
 * repite entre corchetes (`RS[1]`, `RSD[4]`), como lo pide la materia.
 *
 * @param repeatsStep Cuádruplo original que ya calculaba la misma operación (solo RS/RSD).
 */
fun OptimizationTag.displayCodeFor(isDerived: Boolean, repeatsStep: Int?): String {
    val base = codeFor(isDerived)
    return if (this == OptimizationTag.SUBEXPRESSION_REDUCTION && repeatsStep != null) "$base[$repeatsStep]" else base
}

/** Par etiqueta + bandera de derivación, resultado de [OptimizationTag.parse]. */
data class OptimizationMarkCode(
    val tag: OptimizationTag,
    val isDerived: Boolean
) {
    val code: String get() = tag.codeFor(isDerived)
}

/**
 * Marca pedagógica que se escribe junto a un cuádruplo/triplo en la tabla de optimización.
 *
 * Formato de la lámina: `<CÓDIGO> <cuádruplo afectado> -> ELIMINAR`
 * Ej. `FO 2 -> ELIMINAR`, `PCD 6 -> ELIMINAR`, `RS[1] 6 -> ELIMINAR`, `CM -> ELIMINAR`.
 *
 * @property affectedStep Cuádruplo al que se propaga el cambio (columna "Cuádruplo afectado"); `null` si no aplica (ej. `CM`).
 * @property eliminates Si la instrucción optimizada se suprime del CI optimizado.
 * @property repeatsStep Cuádruplo que se repite (solo RS/RSD): se escribe `RS[n]`.
 */
data class OptimizationMark(
    val tag: OptimizationTag,
    val isDerived: Boolean = false,
    val affectedStep: Int? = null,
    val eliminates: Boolean = true,
    val repeatsStep: Int? = null
) {
    val code: String get() = tag.codeFor(isDerived)

    /** Código con el cuádruplo repetido (`RS[1]`); igual a [code] en las demás técnicas. */
    val displayCode: String get() = tag.displayCodeFor(isDerived, repeatsStep)

    fun formatAcademic(): String = buildString {
        append(displayCode)
        affectedStep?.let { append(' ').append(it) }
        if (eliminates) append(" -> ELIMINAR")
    }
}

/**
 * @property isModified `true` si el registro fue cambiado por una optimización previa
 *   (base para decidir si una optimización posterior sobre él es derivada).
 */
data class TripleInstruction(
    val stepNumber: Int,
    val val1: String,
    val operator: String,
    val val2: String,
    val annotation: String? = null,
    val isMarkedForDeletion: Boolean = false,
    val isModified: Boolean = false
) {
    fun formatAcademic(): String {
        val base = "$stepNumber. ($val1, $operator, $val2)"
        return if (!annotation.isNullOrBlank()) "$base  $annotation" else base
    }
}

/**
 * @property isModified `true` si el registro fue cambiado por una optimización previa
 *   (base para decidir si una optimización posterior sobre él es derivada).
 */
data class QuadrupleInstruction(
    val stepNumber: Int,
    val val1: String,
    val operator: String,
    val val2: String,
    val result: String,
    val annotation: String? = null,
    val isMarkedForDeletion: Boolean = false,
    val isModified: Boolean = false
) {
    fun formatAcademic(): String {
        val base = "$stepNumber. ($val1, $operator, $val2, $result)"
        return if (!annotation.isNullOrBlank()) "$base  $annotation" else base
    }
}
data class ThreeAddressInstruction(
    val index: Int,
    val targetLabel: String, // Ej. "T1", "T2", "T3", o "ID2 = T3", "ID4 RTN T1"
    val val1: String? = null,
    val operator: String? = null,
    val val2: String? = null,
    val isDirectAssignmentOrReturn: Boolean = false,
    val freeNote: String? = null
) {
    fun formatAcademic(): String {
        val core = if (isDirectAssignmentOrReturn) {
            targetLabel
        } else {
            "$targetLabel. ($val1, $operator, $val2)"
        }
        return if (!freeNote.isNullOrBlank()) "$core --> $freeNote" else core
    }
}

/**
 * Operando estructurado de una operación de código intermedio. Las vistas tabulares (Triplos, Cuádruplos,
 * Tres Direcciones) se construyen a partir de él; el optimizador trabaja sobre esta forma.
 */
sealed interface IrOperand {
    val display: String

    /** Referencia al resultado del renglón `[n]`. */
    data class StepRef(val step: Int) : IrOperand {
        override val display: String get() = "[$step]"
    }

    /** Valor constante tal como se escribe en el código (`3`, `'@'`, `45.0`, `"hola"`, `0.0f`). */
    data class Constant(val text: String, val value: EvaluatedValue) : IrOperand {
        override val display: String get() = text
    }

    /** Nombre: `ID2`, `PMAIN`, `Math`, `length()`, `random()`. */
    data class Symbol(val text: String) : IrOperand {
        override val display: String get() = text
    }

    /**
     * Llamada a un método de Java con sus argumentos: `charAt(0)`, `parseInt([2])`, `nextLine()`. Los argumentos son
     * operandos (pueden ser referencias `[n]`), así que se sustituyen y renumeran al optimizar.
     */
    data class Call(val name: String, val args: List<IrOperand>) : IrOperand {
        override val display: String get() = args.joinToString(",", "$name(", ")") { it.display }
    }

    data object None : IrOperand {
        override val display: String get() = ""
    }
}

/** El operando y, si es una llamada, también sus argumentos (para encontrar todas las referencias `[n]`). */
val IrOperand.flattened: List<IrOperand>
    get() = if (this is IrOperand.Call) listOf(this) + args.flatMap { it.flattened } else listOf(this)

/** Aplica [transform] a cada operando simple, incluidos los argumentos de las llamadas. */
fun IrOperand.mapOperands(transform: (IrOperand) -> IrOperand): IrOperand =
    if (this is IrOperand.Call) IrOperand.Call(name, args.map { it.mapOperands(transform) }) else transform(this)

/** Tipo de acción del renglón de código intermedio. */
enum class IrOpKind {
    /** `(ID2, =, 3, 3.0f)` */
    ASSIGNMENT,

    /** `([1], +, 1, 4.0f)` */
    BINARY,

    /** `(, !, [3], false)` */
    UNARY,

    /** Acceso a un miembro de la clase: `(ID1, ., [7], 301.0)` */
    ACCESS_CLASS,

    /** Método/atributo de Java: `(Math, ., random(), X.0)`, `([6], ., length(), 3)` */
    ACCESS_JAVA,

    /** `(ID5, RTN, [13], 'R')` */
    RETURN,

    /** `(PMAIN, SOUT, [7], 4.0)` o `(ID4, SOUT, [6], 301.0)` dentro de un método */
    OUTPUT
}

/**
 * Renglón estructurado de código intermedio: `step. (arg1, operator, arg2, result)`.
 */
data class IrOperation(
    val step: Int,
    val kind: IrOpKind,
    val arg1: IrOperand,
    val operator: String,
    val arg2: IrOperand,
    val result: EvaluatedValue
) {
    /** Operandos del renglón, incluidos los argumentos de las llamadas a métodos de Java. */
    val operands: List<IrOperand> get() = arg1.flattened + arg2.flattened

    fun toQuadruple(annotation: String? = null, deleted: Boolean = false, modified: Boolean = false) =
        QuadrupleInstruction(
            stepNumber = step,
            val1 = arg1.display,
            operator = operator,
            val2 = arg2.display,
            result = result.formatForTreeAndQuadruple(),
            annotation = annotation,
            isMarkedForDeletion = deleted,
            isModified = modified
        )

    fun toTriple(annotation: String? = null, deleted: Boolean = false, modified: Boolean = false) =
        TripleInstruction(
            stepNumber = step,
            val1 = arg1.display,
            operator = operator,
            val2 = arg2.display,
            annotation = annotation,
            isMarkedForDeletion = deleted,
            isModified = modified
        )
}

/**
 * Paquete completo e inmutable de Código Intermedio generado antes o después de la optimización.
 *
 * @property operations Forma estructurada de los renglones (misma numeración que Triplos y Cuádruplos).
 */
data class IntermediateCodeBundle(
    val rootHeader: String, // Ej. "ID1 PMAIN"
    val triples: List<TripleInstruction>,
    val triplesClosing: String, // Ej. "ID1 PMAIN -> [8]"
    val quadruples: List<QuadrupleInstruction>,
    val quadruplesClosing: String, // Ej. "ID1 PMAIN -> [8]"
    val threeAddress: List<ThreeAddressInstruction>,
    val threeAddressClosing: String, // Ej. "ID1 PMAIN -> T2"
    val operations: List<IrOperation> = emptyList()
)
