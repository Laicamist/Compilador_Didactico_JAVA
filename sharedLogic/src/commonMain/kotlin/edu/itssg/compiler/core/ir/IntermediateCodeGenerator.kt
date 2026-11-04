package edu.itssg.compiler.core.ir

import edu.itssg.compiler.core.semantic.SemanticAstNode
import edu.itssg.compiler.core.semantic.SemanticDataType
import edu.itssg.compiler.core.semantic.SemanticNodeKind

/**
 * Generador de Códigos Intermedios: "representación secuencial de las operaciones/acciones del árbol abstracto".
 *
 * - **Triplos** `n (val1, ope, val2)` y **Cuádruplos** `n (val1, ope, val2, res)`: un renglón por cada acción del
 *   árbol, en el orden de ejecución que el Analizador Semántico ya numeró (abajo → arriba, izquierda → derecha).
 *   Cierre: `ID1 PMAIN -> [n]`.
 * - **Tres Direcciones** `Tn. (val1, ope, val2)` con `1 <= n <= 3`: reutiliza los registros temporales y los
 *   registros de las variables (`ID2 = 3`, `T1. (ID2, +, 1)`, `ID3 = T1`). Un temporal se libera cuando se consume
 *   (`free T1`, `free all`). Cuando faltan registros, la operación queda **pendiente** (`P3`) y se completa en
 *   cuanto se libera uno (ejemplo `A` de la U2).
 */
class IntermediateCodeGenerator {

    fun generate(semanticRoot: SemanticAstNode): IntermediateCodeBundle {
        val header = "${semanticRoot.primaryLabel} PMAIN"
        val operationNodes = ArrayList<SemanticAstNode>()
        val collector = DeepRecursiveFunction<SemanticAstNode, Unit> { node ->
            for (c in node.children) callRecursive(c)
            if (node.kind.isOperation && node.evaluationStepNumber != null) operationNodes += node
        }
        collector(semanticRoot)
        operationNodes.sortBy { it.evaluationStepNumber }
        val stepOfNode = operationNodes.associate { it.id to it.evaluationStepNumber!! }

        val ops = operationNodes.map { node -> toOperation(node, stepOfNode) }
        return fromOperations(ops, header)
    }

    private fun operandOf(node: SemanticAstNode?, stepOfNode: Map<Int, Int>): IrOperand {
        if (node == null) return IrOperand.None
        stepOfNode[node.id]?.let { return IrOperand.StepRef(it) }
        node.referencedNodeId?.let { ref -> stepOfNode[ref]?.let { return IrOperand.StepRef(it) } }
        return when (node.kind) {
            SemanticNodeKind.LITERAL_VALUE -> IrOperand.Constant(
                text = if (node.castTypeAnnotation != null) node.evaluatedValue.formatForTreeAndQuadruple() else node.primaryLabel,
                value = node.evaluatedValue
            )
            else -> IrOperand.Symbol(node.primaryLabel)
        }
    }

    private fun toOperation(node: SemanticAstNode, stepOfNode: Map<Int, Int>): IrOperation {
        val step = stepOfNode.getValue(node.id)
        val result = node.evaluatedValue
        val c0 = node.children.getOrNull(0)
        return when (node.kind) {
            SemanticNodeKind.ASSIGNMENT_LINK -> IrOperation(
                step, IrOpKind.ASSIGNMENT, IrOperand.Symbol(node.primaryLabel), "=", operandOf(c0, stepOfNode), result
            )
            SemanticNodeKind.BINARY_OPERATOR -> IrOperation(
                step, IrOpKind.BINARY, operandOf(c0, stepOfNode), node.primaryLabel,
                operandOf(node.children.getOrNull(1), stepOfNode), result
            )
            SemanticNodeKind.UNARY_OPERATOR -> IrOperation(
                step, IrOpKind.UNARY, IrOperand.None, node.primaryLabel, operandOf(c0, stepOfNode), result
            )
            SemanticNodeKind.MEMBER_ACCESS -> {
                val kind = if (c0?.resolvedType == SemanticDataType.CLASS_INSTANCE) IrOpKind.ACCESS_CLASS else IrOpKind.ACCESS_JAVA
                IrOperation(step, kind, operandOf(c0, stepOfNode), ".", operandOf(node.children.lastOrNull(), stepOfNode), result)
            }
            SemanticNodeKind.METHOD_RETURN -> IrOperation(
                step, IrOpKind.RETURN, IrOperand.Symbol(node.secondaryLabel ?: "RTN"), "RTN", operandOf(c0, stepOfNode), result
            )
            SemanticNodeKind.OUTPUT_STATEMENT -> IrOperation(
                step, IrOpKind.OUTPUT, IrOperand.Symbol(node.secondaryLabel ?: "PMAIN"), "SOUT", operandOf(c0, stepOfNode), result
            )
            else -> IrOperation(step, IrOpKind.BINARY, IrOperand.None, node.primaryLabel, IrOperand.None, result)
        }
    }

    /** Construye las tres representaciones a partir de renglones estructurados (también para el CI optimizado). */
    fun fromOperations(ops: List<IrOperation>, rootHeader: String): IntermediateCodeBundle {
        val closingRef = ops.lastOrNull()?.let { "[${it.step}]" } ?: "[ ]"
        val (threeAddress, threeAddressResult) = buildThreeAddress(ops)
        return IntermediateCodeBundle(
            rootHeader = rootHeader,
            triples = ops.map { it.toTriple() },
            triplesClosing = "$rootHeader -> $closingRef",
            quadruples = ops.map { it.toQuadruple() },
            quadruplesClosing = "$rootHeader -> $closingRef",
            threeAddress = threeAddress,
            threeAddressClosing = "$rootHeader -> ${threeAddressResult ?: "[ ]"}",
            operations = ops
        )
    }

    // Tres Direcciones

    /** Nivel de flexibilidad al asignar el último temporal libre (ver [buildThreeAddress]). */
    private enum class Policy { STRICT, RELAXED, FORCE }

    /**
     * Tres direcciones con 3 temporales. Para no quedarse sin registros (ejemplo `A` de la U2), una operación que
     * tomaría el ÚLTIMO temporal libre se deja **pendiente** (`P3`) salvo que quien use su resultado lo guarde en un
     * registro de variable o de método (`ID2 = T3`, `ID5 RTN T1`), porque así el temporal se libera enseguida. Las
     * operaciones pendientes se completan, en orden, en cuanto se libera un temporal. Si aun así no hay salida, se
     * relaja la regla: primero las operaciones que liberan registros y, si hace falta, el resultado se escribe en el
     * temporal de un operando que ya no se usa (`T1. (T1, +, T2)`). Solo una expresión que por su forma necesita más
     * de 3 registros (número de Sethi-Ullman > 3) usa un cuarto temporal, indicándolo en la nota.
     */
    private fun buildThreeAddress(ops: List<IrOperation>): Pair<List<ThreeAddressInstruction>, String?> {
        val byStep = ops.associateBy { it.step }
        val lastUse = HashMap<Int, Int>()
        val consumerOf = HashMap<Int, IrOperation>()
        for (op in ops) for (operand in op.operands) {
            if (operand is IrOperand.StepRef) {
                lastUse[operand.step] = maxOf(lastUse[operand.step] ?: 0, op.step)
                if (operand.step !in consumerOf) consumerOf[operand.step] = op
            }
        }
        val finalStep = ops.lastOrNull()?.step

        val out = ArrayList<ThreeAddressInstruction>()
        val location = HashMap<Int, String>()          // renglón → dónde quedó su valor (T1, ID2...)
        val tempHolder = arrayOfNulls<Int>(MAX_TEMPS + 1) // índice de temporal → renglón que lo ocupa
        var overflowTemps = MAX_TEMPS
        val emitted = HashSet<Int>()
        val pendingQueue = ArrayList<IrOperation>()

        fun render(operand: IrOperand): String = when (operand) {
            is IrOperand.StepRef -> location[operand.step] ?: operand.display
            else -> operand.display
        }

        fun needsTemp(op: IrOperation): Boolean = when (op.kind) {
            IrOpKind.ASSIGNMENT, IrOpKind.RETURN -> false
            IrOpKind.OUTPUT -> (op.arg1 as? IrOperand.Symbol)?.text == "PMAIN"
            else -> true
        }

        fun freeTemps(): List<Int> = (1..MAX_TEMPS).filter { tempHolder[it] == null }

        /** Temporal ocupado por un operando de [op] que se consume por última vez en [op]. */
        fun reusableTemp(op: IrOperation): Int? = (1..MAX_TEMPS).firstOrNull { t ->
            val holder = tempHolder[t]
            holder != null && op.operands.any { it is IrOperand.StepRef && it.step == holder } &&
                (lastUse[holder] ?: 0) <= op.step
        }

        fun ready(op: IrOperation): Boolean =
            op.operands.all { it !is IrOperand.StepRef || it.step in emitted || it.step !in byStep }

        fun emit(op: IrOperation, wasPending: Boolean, policy: Policy): Boolean {
            if (!ready(op)) return false
            var tempIndex: Int? = null
            if (needsTemp(op)) {
                val free = freeTemps()
                val consumer = consumerOf[op.step]
                val consumerStoresInRegister = consumer == null || !needsTemp(consumer)
                val reusable = reusableTemp(op)
                tempIndex = when {
                    free.size >= 2 -> free.first()
                    free.size == 1 && (consumerStoresInRegister || reusable != null || policy != Policy.STRICT) -> free.first()
                    // Sin temporales libres: el resultado se escribe en el temporal de un operando que ya no se usa.
                    free.isEmpty() && reusable != null && policy != Policy.STRICT -> reusable
                    policy == Policy.FORCE -> {
                        overflowTemps++
                        overflowTemps
                    }
                    else -> return false
                }
            }
            val a = render(op.arg1)
            val b = render(op.arg2)
            val ownerText = (op.arg1 as? IrOperand.Symbol)?.text ?: a
            val instruction = when {
                op.kind == IrOpKind.ASSIGNMENT -> {
                    location[op.step] = ownerText
                    ThreeAddressInstruction(out.size + 1, "$ownerText = $b", isDirectAssignmentOrReturn = true)
                }
                op.kind == IrOpKind.RETURN -> {
                    location[op.step] = ownerText
                    ThreeAddressInstruction(out.size + 1, "$ownerText RTN $b", isDirectAssignmentOrReturn = true)
                }
                op.kind == IrOpKind.OUTPUT && tempIndex == null -> {
                    location[op.step] = ownerText
                    ThreeAddressInstruction(out.size + 1, "$ownerText SOUT $b", isDirectAssignmentOrReturn = true)
                }
                else -> {
                    val label = "T$tempIndex"
                    if (tempIndex!! <= MAX_TEMPS) tempHolder[tempIndex] = op.step
                    location[op.step] = label
                    ThreeAddressInstruction(out.size + 1, label, a, op.operator, b)
                }
            }
            emitted += op.step
            // Liberar los temporales consumidos en este renglón (y el resultado si nadie lo usa)
            val freed = ArrayList<String>()
            for (t in 1..MAX_TEMPS) {
                val holder = tempHolder[t] ?: continue
                val consumedNow = op.operands.any { it is IrOperand.StepRef && it.step == holder } &&
                    (lastUse[holder] ?: 0) <= op.step
                val unused = holder == op.step && lastUse[holder] == null && holder != finalStep
                if (consumedNow || unused) {
                    tempHolder[t] = null
                    freed += "T$t"
                }
            }
            val notes = ArrayList<String>()
            if (wasPending) notes += "P${op.step}"
            if (tempIndex != null && tempIndex > MAX_TEMPS) notes += "excede los 3 registros temporales"
            if (freed.isNotEmpty()) {
                notes += if ((1..MAX_TEMPS).all { tempHolder[it] == null }) "free all" else "free ${freed.joinToString(", ")}"
            }
            out += instruction.copy(freeNote = notes.joinToString("  ").ifEmpty { null })
            return true
        }

        fun drainPending(policy: Policy): Boolean {
            var any = false
            var progress = true
            while (progress && pendingQueue.isNotEmpty()) {
                progress = false
                // Con la regla relajada, primero las operaciones que liberan un temporal.
                val candidates = if (policy == Policy.STRICT) pendingQueue.toList()
                else pendingQueue.sortedBy { if (reusableTemp(it) != null) 0 else 1 }
                for (p in candidates) {
                    if (emit(p, wasPending = true, policy = policy)) {
                        pendingQueue.remove(p)
                        progress = true
                        any = true
                        break
                    }
                }
                if (progress && policy != Policy.STRICT) break
            }
            return any
        }

        for (op in ops) {
            if (emit(op, wasPending = false, policy = Policy.STRICT)) {
                drainPending(Policy.STRICT)
            } else {
                pendingQueue += op
                out += ThreeAddressInstruction(
                    index = out.size + 1,
                    targetLabel = "P${op.step}",
                    isDirectAssignmentOrReturn = true,
                    freeNote = "pendiente"
                )
            }
        }
        while (pendingQueue.isNotEmpty()) {
            if (drainPending(Policy.STRICT)) continue
            if (drainPending(Policy.RELAXED)) continue
            val p = pendingQueue.first { ready(it) }
            pendingQueue.remove(p)
            emit(p, wasPending = true, policy = Policy.FORCE)
        }
        val finalLocation = finalStep?.let { location[it] ?: byStep[it]?.let { op -> render(op.arg1) } }
        return out to finalLocation
    }

    private companion object {
        const val MAX_TEMPS = 3
    }
}
