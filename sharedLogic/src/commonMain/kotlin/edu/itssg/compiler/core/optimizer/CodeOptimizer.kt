package edu.itssg.compiler.core.optimizer

import edu.itssg.compiler.core.ir.IntermediateCodeBundle
import edu.itssg.compiler.core.ir.IntermediateCodeGenerator
import edu.itssg.compiler.core.ir.IrOpKind
import edu.itssg.compiler.core.ir.IrOperand
import edu.itssg.compiler.core.ir.IrOperation
import edu.itssg.compiler.core.ir.OptimizationMark
import edu.itssg.compiler.core.ir.OptimizationTag
import edu.itssg.compiler.core.ir.QuadrupleInstruction
import edu.itssg.compiler.core.ir.TripleInstruction
import edu.itssg.compiler.core.semantic.EvaluatedValue

/**
 * Renglón de la tabla de optimización de la U3:
 * CUÁDRUPLO ORIGINAL | OPTIMIZACIÓN | CUÁDRUPLO AFECTADO | CUÁDRUPLO MODIFICADO.
 *
 * @property stepNumber Número del cuádruplo original.
 * @property tag Técnica aplicada.
 * @property isDerived Optimización derivada (código con `D`): solo fue posible por un cambio previo en el registro.
 * @property affectedStep Cuádruplo al que se propaga el resultado (`FO 2`), o `null` (ej. `CM`).
 * @property beforeInstruction Cuádruplo original.
 * @property modifiedInstruction Cuádruplo después de recibir los cambios de optimizaciones previas (`null` si no cambió).
 * @property afterAction Explicación de la transformación.
 */
data class OptimizationLogEntry(
    val stepNumber: Int,
    val tag: OptimizationTag,
    val isDerived: Boolean,
    val affectedStep: Int?,
    val beforeInstruction: String,
    val modifiedInstruction: String?,
    val afterAction: String
) {
    val code: String get() = tag.codeFor(isDerived)
}

/**
 * Resultado de la fase de Optimización de Código Intermedio.
 *
 * @property annotatedQuadruples Cuádruplos originales con su marca (`FO 2 -> ELIMINAR`, `PCD 6 -> ELIMINAR`).
 * @property annotatedTriples Triplos originales con las mismas marcas.
 * @property optimizedBundle CI optimizado (Triplos, Cuádruplos y Tres Direcciones) renumerado.
 * @property appliedOptimizations Bitácora de cada técnica aplicada (tabla de la U3).
 */
data class OptimizationResult(
    val annotatedQuadruples: List<QuadrupleInstruction>,
    val annotatedTriples: List<TripleInstruction>,
    val optimizedBundle: IntermediateCodeBundle,
    val appliedOptimizations: List<OptimizationLogEntry>
)

/**
 * Optimizador local de Código Intermedio (Unidad 3).
 *
 * Recorre los cuádruplos de arriba hacia abajo, como la tabla del ejemplo `Facil`. A cada cuádruplo primero se le
 * aplican los cambios que dejaron las optimizaciones anteriores (su "cuádruplo modificado") y después la primera
 * técnica que corresponda:
 * 1. **RS** Reducción de subexpresiones comunes: la operación ya se calculó antes → se reutiliza ese resultado.
 * 2. **SE** Simplificación de expresiones: `x/1`, `x*1`, `1*x`, `x+0`, `0+x`, `x-0` → `x`.
 * 3. **FO** Folding: operación entre constantes → se ejecuta y el resultado pasa al cuádruplo que la usa.
 * 4. **PC** Propagación de constantes: asignación de un valor constante → la variable se sustituye por la constante.
 * 5. **CM** Código muerto (al final): lo que no tiene impacto sobre la salida (`SOUT`, `RTN`, accesos a la clase).
 *
 * El cuádruplo optimizado se ELIMINA y su resultado se propaga al "cuádruplo afectado" (el primero que lo usa).
 * **Optimización derivada (D)**: si la técnica NO se podía aplicar al cuádruplo original y sí al modificado, el código
 * lleva `D` (`FOD`, `SED`, `PCD`, `RSD`, `CMD`). Ej.: `(ID4, =, [2], 109.0)` se vuelve `(ID4, =, 109, 109.0)` por el
 * FO/SE anterior y entonces se propaga: `PCD`. En cambio `([1], /, 1)` ya tenía `/1` en el original: `SE`.
 *
 * `Math.random()` nunca se reduce con RS: cada llamada produce un valor distinto.
 */
class CodeOptimizer(
    private val irGenerator: IntermediateCodeGenerator = IntermediateCodeGenerator()
) {

    private class Decision(
        val tag: OptimizationTag,
        val isDerived: Boolean,
        val replacement: IrOperand,
        val description: String
    )

    fun optimize(originalBundle: IntermediateCodeBundle): OptimizationResult {
        val original = originalBundle.operations
        if (original.isEmpty()) {
            return OptimizationResult(
                annotatedQuadruples = originalBundle.quadruples,
                annotatedTriples = originalBundle.triples,
                optimizedBundle = originalBundle,
                appliedOptimizations = emptyList()
            )
        }

        val substitution = HashMap<Int, IrOperand>()     // renglón eliminado → con qué se sustituye
        val current = LinkedHashMap<Int, IrOperation>()  // renglón → forma modificada vigente
        val eliminated = LinkedHashMap<Int, Decision>()
        val logs = ArrayList<OptimizationLogEntry>()

        fun resolve(operand: IrOperand): IrOperand {
            var o = operand
            var guard = 0
            while (o is IrOperand.StepRef && substitution.containsKey(o.step) && guard++ < 10_000) {
                o = substitution.getValue(o.step)
            }
            return o
        }

        val originalByStep = original.associateBy { it.step }
        val seenExpressions = HashMap<String, Int>() // expresión (ya sustituida) → primer renglón que la calcula
        for (op in original) {
            val modified = op.copy(arg1 = resolve(op.arg1), arg2 = resolve(op.arg2))
            current[op.step] = modified
            val decision = decide(op, modified, seenExpressions, originalByStep)
            if (decision != null) {
                eliminated[op.step] = decision
                substitution[op.step] = decision.replacement
            }
            if (op.kind == IrOpKind.BINARY || op.kind == IrOpKind.ACCESS_JAVA || op.kind == IrOpKind.UNARY) {
                for (key in expressionKeys(modified)) seenExpressions.getOrPut(key) { op.step }
            }
        }

        // ---------------------------------------------------- Código Muerto (CM / CMD)
        val liveNow = liveSteps(current.values.filter { it.step !in eliminated })
        val liveOriginally = liveSteps(original)
        for (op in original) {
            if (op.step in eliminated || op.step in liveNow) continue
            val derived = op.step in liveOriginally
            eliminated[op.step] = Decision(
                tag = OptimizationTag.DEAD_CODE,
                isDerived = derived,
                replacement = IrOperand.StepRef(op.step),
                description = if (derived) "Dejó de usarse por las optimizaciones anteriores: no impacta la salida"
                else "No se usa: no tiene impacto sobre la salida del programa"
            )
        }

        // ---------------------------------------------------- Tabla anotada y bitácora
        val firstConsumerOf = HashMap<Int, Int>()
        for (op in original) for (operand in op.operands) {
            if (operand is IrOperand.StepRef && operand.step < op.step) firstConsumerOf.getOrPut(operand.step) { op.step }
        }
        val annotatedQuads = ArrayList<QuadrupleInstruction>()
        val annotatedTriples = ArrayList<TripleInstruction>()
        for (op in original) {
            val mod = current.getValue(op.step)
            val wasModified = mod != op
            val decision = eliminated[op.step]
            val affected = decision?.let { firstConsumerOf[op.step] }
            val modifiedText = if (wasModified) mod.toQuadruple().formatAcademic().substringAfter(". ") else null
            val annotation = buildString {
                if (decision != null) {
                    append(OptimizationMark(decision.tag, decision.isDerived, affected, eliminates = true).formatAcademic())
                }
                if (modifiedText != null) {
                    if (isNotEmpty()) append("  ")
                    append("[modificado: ").append(modifiedText).append("]")
                }
            }.ifEmpty { null }
            annotatedQuads += op.toQuadruple(annotation, deleted = decision != null, modified = wasModified)
            annotatedTriples += op.toTriple(annotation, deleted = decision != null, modified = wasModified)
            if (decision != null) {
                logs += OptimizationLogEntry(
                    stepNumber = op.step,
                    tag = decision.tag,
                    isDerived = decision.isDerived,
                    affectedStep = affected,
                    beforeInstruction = op.toQuadruple().formatAcademic(),
                    modifiedInstruction = modifiedText,
                    afterAction = decision.description
                )
            }
        }

        // ---------------------------------------------------- CI optimizado (renumerado)
        val survivors = original.map { current.getValue(it.step) }.filter { it.step !in eliminated }
        val newNumber = HashMap<Int, Int>()
        survivors.forEachIndexed { i, op -> newNumber[op.step] = i + 1 }
        fun renumber(o: IrOperand): IrOperand {
            val r = resolve(o)
            return if (r is IrOperand.StepRef) newNumber[r.step]?.let { IrOperand.StepRef(it) } ?: r else r
        }
        val optimizedOps = survivors.map { op ->
            op.copy(step = newNumber.getValue(op.step), arg1 = renumber(op.arg1), arg2 = renumber(op.arg2))
        }
        val optimizedBundle = irGenerator.fromOperations(optimizedOps, originalBundle.rootHeader)

        return OptimizationResult(
            annotatedQuadruples = annotatedQuads,
            annotatedTriples = annotatedTriples,
            optimizedBundle = optimizedBundle,
            appliedOptimizations = logs
        )
    }

    /** Elige la técnica para el renglón [op] (ya con sus operandos sustituidos en [modified]). */
    private fun decide(
        op: IrOperation,
        modified: IrOperation,
        seenExpressions: Map<String, Int>,
        originalByStep: Map<Int, IrOperation>
    ): Decision? {
        when (op.kind) {
            IrOpKind.BINARY, IrOpKind.ACCESS_JAVA, IrOpKind.UNARY -> {
                // 1. RS: misma operación ya calculada en un renglón anterior
                if (isDeterministic(modified)) {
                    val previousStep = seenExpressions[expressionKeys(modified).first()]
                    if (previousStep != null) {
                        val previousOriginal = originalByStep.getValue(previousStep)
                        val derived = !sameExpression(previousOriginal, op)
                        return Decision(
                            OptimizationTag.SUBEXPRESSION_REDUCTION, derived, IrOperand.StepRef(previousStep),
                            "La operación ya se calculó en el cuádruplo $previousStep: se reutiliza [$previousStep]"
                        )
                    }
                }
                if (op.kind != IrOpKind.BINARY) {
                    return foldIfConstant(op, modified)
                }
                // 2. SE: identidades algebraicas
                val identityNow = identityOperand(modified)
                if (identityNow != null) {
                    val derived = identityOperand(op) == null
                    return Decision(
                        OptimizationTag.EXPRESSION_SIMPLIFICATION, derived, identityNow,
                        "${modified.operator} con elemento neutro: el resultado es ${identityNow.display}"
                    )
                }
                // 3. FO: operación entre constantes
                return foldIfConstant(op, modified)
            }
            IrOpKind.ASSIGNMENT -> {
                // 4. PC: la variable recibe una constante
                val value = modified.arg2
                if (value is IrOperand.Constant && value.value.isConstant && op.result.isConstant) {
                    val derived = op.arg2 !is IrOperand.Constant
                    val constant = constantOf(op.result)
                    return Decision(
                        OptimizationTag.CONSTANT_PROPAGATION, derived, constant,
                        "${(op.arg1 as? IrOperand.Symbol)?.text ?: "La variable"} vale siempre ${constant.display}: " +
                            "se usa la constante en lugar de la variable"
                    )
                }
                return null
            }
            else -> return null
        }
    }

    private fun foldIfConstant(op: IrOperation, modified: IrOperation): Decision? {
        val allConstant = modified.operands.filter { it != IrOperand.None }.all { it.isFoldable() } &&
            (modified.kind != IrOpKind.ACCESS_JAVA || modified.arg1.isFoldable())
        if (!allConstant || !op.result.isConstant) return null
        val derived = !op.operands.filter { it != IrOperand.None }.all { it.isFoldable() }
        val constant = constantOf(op.result)
        return Decision(
            OptimizationTag.FOLDING, derived, constant,
            "Operación entre constantes: ${modified.arg1.display} ${modified.operator} ${modified.arg2.display} = ${constant.display}"
        )
    }

    /** En un acceso de Java el miembro (`length()`) es un nombre, no un valor: basta con que el objeto sea constante. */
    private fun IrOperand.isFoldable(): Boolean = when (this) {
        is IrOperand.Constant -> value.isConstant
        is IrOperand.Symbol -> text.endsWith("()") && text != "random()"
        else -> false
    }

    private fun constantOf(value: EvaluatedValue): IrOperand.Constant =
        IrOperand.Constant(value.formatForTreeAndQuadruple(), value)

    private fun isDeterministic(op: IrOperation): Boolean =
        op.operands.none { it is IrOperand.Symbol && it.text == "random()" }

    /** Claves de búsqueda de la expresión (dos órdenes si es conmutativa: `a*b` = `b*a`). */
    private fun expressionKeys(op: IrOperation): List<String> {
        fun k(o: IrOperand): String = when (o) {
            is IrOperand.Constant -> if (o.value.isConstant) "C:${o.value}" else "T:${o.text}"
            is IrOperand.StepRef -> "R:${o.step}"
            is IrOperand.Symbol -> "S:${o.text}"
            IrOperand.None -> "-"
        }
        val direct = "${op.kind}|${op.operator}|${k(op.arg1)}|${k(op.arg2)}"
        val commutative = op.kind == IrOpKind.BINARY && (op.operator == "*" ||
            (op.operator == "+" && op.result !is EvaluatedValue.StringVal))
        return if (commutative) listOf(direct, "${op.kind}|${op.operator}|${k(op.arg2)}|${k(op.arg1)}") else listOf(direct)
    }

    private fun sameExpression(a: IrOperation, b: IrOperation): Boolean {
        if (a.operator != b.operator) return false
        if (sameOperand(a.arg1, b.arg1) && sameOperand(a.arg2, b.arg2)) return true
        val commutative = a.kind == IrOpKind.BINARY && (a.operator == "*" ||
            (a.operator == "+" && a.result !is EvaluatedValue.StringVal && b.result !is EvaluatedValue.StringVal))
        return commutative && sameOperand(a.arg1, b.arg2) && sameOperand(a.arg2, b.arg1)
    }

    private fun sameOperand(a: IrOperand, b: IrOperand): Boolean = when {
        a is IrOperand.Constant && b is IrOperand.Constant -> a.text == b.text || (a.value.isConstant && a.value == b.value)
        else -> a == b
    }

    private fun identityOperand(op: IrOperation): IrOperand? {
        val a = op.arg1
        val b = op.arg2
        if (op.result is EvaluatedValue.StringVal) return null
        return when (op.operator) {
            "+" -> when {
                isNumber(b, 0.0) -> a
                isNumber(a, 0.0) -> b
                else -> null
            }
            "-" -> if (isNumber(b, 0.0)) a else null
            "*" -> when {
                isNumber(b, 1.0) -> a
                isNumber(a, 1.0) -> b
                else -> null
            }
            "/" -> if (isNumber(b, 1.0)) a else null
            else -> null
        }
    }

    private fun isNumber(o: IrOperand, n: Double): Boolean =
        o is IrOperand.Constant && o.value !is EvaluatedValue.CharVal && o.value.asDoubleOrNull() == n

    /** Renglones que influyen en la salida: SOUT, RTN y accesos a la clase, más todo lo que ellos usan. */
    private fun liveSteps(ops: List<IrOperation>): Set<Int> {
        val byStep = ops.associateBy { it.step }
        val live = HashSet<Int>()
        val work = ArrayDeque<Int>()
        for (op in ops) {
            if (op.kind == IrOpKind.OUTPUT || op.kind == IrOpKind.RETURN || op.kind == IrOpKind.ACCESS_CLASS) {
                work.addLast(op.step)
            }
        }
        while (work.isNotEmpty()) {
            val s = work.removeFirst()
            if (!live.add(s)) continue
            val op = byStep[s] ?: continue
            for (operand in op.operands) {
                if (operand is IrOperand.StepRef && operand.step in byStep) work.addLast(operand.step)
            }
        }
        return live
    }
}
