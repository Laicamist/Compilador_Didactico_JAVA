package edu.itssg.compiler.ui.tree

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.ir.IrOpKind
import edu.itssg.compiler.core.ir.IrOperand
import edu.itssg.compiler.core.ir.IrOperation
import edu.itssg.compiler.core.ir.ThreeAddressInstruction
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Recorrido animado del visor de árboles (decidido el 2 nov 2026):
 * - [DERIVATION]: árbol sintáctico, regla por regla en el orden en que se aplicó la GLC v7 (derivación por la
 *   izquierda = preorden de los no terminales).
 * - [QUADRUPLES] y [THREE_ADDRESS]: árbol abstracto-semántico en orden de ejecución (abajo → arriba, izquierda →
 *   derecha); cada paso es un renglón del código intermedio. Los triplos se omiten (muestran lo mismo que los
 *   cuádruplos).
 */
enum class WalkthroughMode(val displayName: String) {
    DERIVATION("Derivación"),
    QUADRUPLES("Cuádruplos"),
    THREE_ADDRESS("3 Direcciones")
}

/** Renglón de la lista que acompaña al recorrido (producciones o código intermedio). */
data class WalkLine(val number: String, val text: String, val note: String? = null)

/**
 * Estado de un temporal en Tres Direcciones al terminar el paso.
 * @property holderStep Paso de Cuádruplos cuyo resultado guarda, o `null` si está libre.
 * @property changed Cambió respecto del paso anterior (se ilumina en la mini tabla).
 */
data class RegisterCell(val name: String, val holderStep: Int?, val changed: Boolean)

/**
 * Un paso del recorrido.
 *
 * @property nodeIndex Nodo que se ilumina (puede faltar si el renglón no tiene nodo, p. ej. con el límite de nodos).
 * @property relatedIndices Nodos que participan (hijos derivados u operandos).
 * @property links Conexiones a dibujar: hoja `IDn` → nodo que calculó el valor que reutiliza (`referencedNodeId`).
 * @property lineIndex Renglón de [Walkthrough.lines] que corresponde.
 */
data class WalkStep(
    val nodeIndex: Int?,
    val relatedIndices: List<Int>,
    val links: List<Pair<Int, Int>>,
    val lineIndex: Int,
    val title: String,
    val explanation: String,
    val span: SourceSpan?,
    val registers: List<RegisterCell> = emptyList(),
    val pendingSteps: List<Int> = emptyList()
)

class Walkthrough(
    val mode: WalkthroughMode,
    val header: String?,
    val lines: List<WalkLine>,
    val closing: String?,
    val steps: List<WalkStep>
) {
    val isEmpty: Boolean get() = steps.isEmpty()
}

object WalkthroughBuilder {

    //Derivación (árbol sintáctico)

    fun derivation(tree: VisualTree): Walkthrough {
        val steps = ArrayList<WalkStep>()
        val lines = ArrayList<WalkLine>()
        for (node in tree.nodes) {
            if (!node.style.isNonTerminal) continue
            val production = node.production ?: "${node.title} → ε"
            val number = steps.size + 1
            val terminals = node.children.count { tree[it].style == VisualNodeStyle.TERMINAL }
            val nonTerminals = node.children.count { tree[it].style.isNonTerminal }
            val epsilon = node.children.any { tree[it].style == VisualNodeStyle.EPSILON }
            val glc = node.details.firstOrNull { it.label == "Regla de la GLC v7" }?.value
            val explanation = buildString {
                append("Se deriva el no terminal más a la izquierda, ${node.title}")
                if (glc != null) append(", con la regla ").append(glc)
                append(". ")
                append(
                    when {
                        epsilon && node.children.size == 1 -> "Se aplica la producción vacía (ε): no genera símbolos."
                        else -> "Genera ${plural(terminals, "terminal", "terminales")} y " +
                                "${plural(nonTerminals, "no terminal", "no terminales")} por derivar."
                    }
                )
                if (node.style == VisualNodeStyle.RECOVERED) {
                    append(" Esta regla tuvo un error y el analizador se recuperó en modo pánico.")
                }
            }
            lines += WalkLine("$number", production)
            steps += WalkStep(
                nodeIndex = node.index,
                relatedIndices = node.children.toList(),
                links = emptyList(),
                lineIndex = lines.lastIndex,
                title = production,
                explanation = explanation,
                span = node.span
            )
        }
        return Walkthrough(WalkthroughMode.DERIVATION, header = null, lines = lines, closing = null, steps = steps)
    }

    /**
     * Nodos ya visibles después del paso [stepIndex] de la derivación: la raíz y los hijos de cada no terminal derivado
     * hasta ese paso (`stepIndex = -1`: solo la raíz).
     */
    fun derivationRevealed(tree: VisualTree, walk: Walkthrough, stepIndex: Int): BooleanArray {
        val revealed = BooleanArray(tree.size)
        if (tree.size == 0) return revealed
        revealed[0] = true
        val last = stepIndex.coerceAtMost(walk.steps.lastIndex)
        for (k in 0..last) {
            val node = walk.steps[k].nodeIndex ?: continue
            for (c in tree[node].children) revealed[c] = true
        }
        return revealed
    }

    /**
     * Forma sentencial después del paso: los símbolos de la frontera de lo ya derivado, de izquierda a derecha (las ε
     * no se escriben). [FormSymbol.isNew] marca los que acaba de generar el paso.
     */
    fun sententialForm(tree: VisualTree, revealed: BooleanArray, currentNode: Int?): List<FormSymbol> {
        if (tree.size == 0) return emptyList()
        val result = ArrayList<FormSymbol>()
        val newOnes = currentNode?.let { tree[it].children.toSet() }.orEmpty()
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        while (stack.isNotEmpty() && result.size < MAX_FORM_SYMBOLS) {
            val i = stack.removeLast()
            val node = tree[i]
            val expanded = node.hasChildren && revealed[node.children.first()]
            if (expanded) {
                for (k in node.children.indices.reversed()) stack.addLast(node.children[k])
                continue
            }
            if (node.style == VisualNodeStyle.EPSILON) continue
            result += FormSymbol(
                text = node.title,
                isNonTerminal = node.style.isNonTerminal,
                isNew = i in newOnes,
                isError = node.style == VisualNodeStyle.SYNTAX_ERROR
            )
        }
        return result
    }

    data class FormSymbol(val text: String, val isNonTerminal: Boolean, val isNew: Boolean, val isError: Boolean)

    private const val MAX_FORM_SYMBOLS = 4_000

    //Ejecución (árbol abstracto-semántico)

    fun quadruples(report: CompilationReport, tree: VisualTree): Walkthrough {
        val code = report.intermediateCode
        val operations = code.operations.associateBy { it.step }
        val lexemes = report.symbolEntries.associate { "ID${it.symbolId}" to it.lexeme }
        val lines = code.quadruples.map { WalkLine("${it.stepNumber}", "(${it.val1}, ${it.operator}, ${it.val2}, ${it.result})") }
        val steps = code.quadruples.mapIndexed { i, quad ->
            val node = tree.nodeForStep(quad.stepNumber)
            val op = operations[quad.stepNumber]
            val (related, links) = participants(tree, node, op)
            WalkStep(
                nodeIndex = node,
                relatedIndices = related,
                links = links,
                lineIndex = i,
                title = quad.formatAcademic(),
                explanation = op?.let { describe(it, lexemes) } ?: "Renglón ${quad.stepNumber} del código intermedio.",
                span = node?.let { tree[it].span }
            )
        }
        return Walkthrough(WalkthroughMode.QUADRUPLES, code.rootHeader, lines, code.quadruplesClosing, steps)
    }

    fun threeAddress(report: CompilationReport, tree: VisualTree): Walkthrough {
        val code = report.intermediateCode
        val operations = code.operations.associateBy { it.step }
        val lines = code.threeAddress.map { WalkLine("${it.index}", it.core(), it.freeNote?.takeIf { n -> n.isNotBlank() }) }
        val pending = LinkedHashSet<Int>()
        var previous: List<Int?> = List(TEMP_COUNT) { null }
        val steps = code.threeAddress.mapIndexed { i, instruction ->
            val source = instruction.sourceStep
            val node = source?.let { tree.nodeForStep(it) }
            val (related, links) = participants(tree, node, source?.let { operations[it] })
            if (instruction.isPending && source != null) pending += source else if (source != null) pending -= source
            val after = instruction.registersAfter.ifEmpty { previous }
            val registers = (0 until TEMP_COUNT).map { t ->
                val holder = after.getOrNull(t)
                RegisterCell("T${t + 1}", holder, changed = holder != previous.getOrNull(t))
            }
            previous = after
            WalkStep(
                nodeIndex = node,
                relatedIndices = related,
                links = links,
                lineIndex = i,
                title = instruction.formatAcademic(),
                explanation = describe(instruction),
                span = node?.let { tree[it].span },
                registers = registers,
                pendingSteps = pending.toList()
            )
        }
        return Walkthrough(WalkthroughMode.THREE_ADDRESS, code.rootHeader, lines, code.threeAddressClosing, steps)
    }

    /** Hijos del nodo y, para las hojas `IDn` que reutilizan un valor, el nodo que lo calculó. */
    private fun participants(tree: VisualTree, node: Int?, op: IrOperation?): Pair<List<Int>, List<Pair<Int, Int>>> {
        if (node == null) return emptyList<Int>() to emptyList()
        val related = LinkedHashSet<Int>()
        val links = ArrayList<Pair<Int, Int>>()
        for (c in tree[node].children) {
            related += c
            val ref = tree[c].referencedIndex
            if (ref >= 0) {
                related += ref
                links += c to ref
            }
        }
        // Operandos [n] que no son hijos directos (p. ej. argumentos de un método de Java).
        op?.operands?.forEach { operand ->
            if (operand is IrOperand.StepRef) tree.nodeForStep(operand.step)?.let { related += it }
        }
        related -= node
        return related.toList() to links
    }

    private fun describe(op: IrOperation, lexemes: Map<String, String>): String {
        fun d(o: IrOperand): String = when (o) {
            is IrOperand.StepRef -> "el resultado del paso ${o.step} [${o.step}]"
            is IrOperand.Symbol -> lexemes[o.text]?.let { "${o.text} ($it)" } ?: o.text
            is IrOperand.Constant -> o.text
            is IrOperand.Call -> o.display
            IrOperand.None -> ""
        }
        val result = op.result.formatForTreeAndQuadruple()
        return when (op.kind) {
            IrOpKind.ASSIGNMENT -> "Se asigna ${d(op.arg2)} a ${d(op.arg1)}; ahora vale $result."
            IrOpKind.BINARY -> "Se calcula ${d(op.arg1)} ${op.operator} ${d(op.arg2)} = $result."
            IrOpKind.UNARY -> "Se aplica ${op.operator} a ${d(op.arg2)} = $result."
            IrOpKind.ACCESS_CLASS -> "Se accede a ${d(op.arg2)} desde ${d(op.arg1)} (miembro de la clase) = $result."
            IrOpKind.ACCESS_JAVA -> "Se usa ${d(op.arg2)} sobre ${d(op.arg1)} (método o atributo de Java) = $result."
            IrOpKind.RETURN -> "El método ${d(op.arg1)} regresa ${d(op.arg2)} = $result."
            IrOpKind.OUTPUT -> "${d(op.arg1)} imprime ${d(op.arg2)} con System.out.println → $result."
        }
    }

    private fun describe(instruction: ThreeAddressInstruction): String {
        val step = instruction.sourceStep
        if (instruction.isPending) {
            return "No hay un temporal libre para la operación del paso $step: queda pendiente (P$step) " +
                    "hasta que se libere un registro."
        }
        val label = instruction.targetLabel
        val main = when {
            !instruction.isDirectAssignmentOrReturn ->
                "Se calcula ${instruction.val1} ${instruction.operator} ${instruction.val2} y el resultado queda en $label" +
                        (step?.let { " (paso $it)" } ?: "") + "."
            " = " in label -> "El valor se guarda en el registro de la variable: $label."
            " RTN " in label -> "El método regresa el valor: $label."
            " SOUT " in label -> "Se imprime el valor: $label."
            else -> label
        }
        val note = instruction.freeNote.orEmpty()
        val extras = buildList {
            if (step != null && Regex("\\bP$step\\b").containsMatchIn(note)) add("Se completa la operación pendiente P$step.")
            if ("excede" in note) add("La expresión necesita más de 3 registros: se usa un temporal adicional.")
            when {
                "free all" in note -> add("Se liberan todos los temporales (free all).")
                else -> Regex("free ((?:T\\d+(?:, )?)+)").find(note)?.groupValues?.get(1)?.let {
                    add(if (", " in it) "Se liberan $it." else "Se libera $it.")
                }
            }
        }
        return (listOf(main) + extras).joinToString(" ")
    }

    private fun ThreeAddressInstruction.core(): String =
        if (isDirectAssignmentOrReturn) targetLabel else "$targetLabel. ($val1, $operator, $val2)"

    private fun plural(count: Int, singular: String, pluralForm: String): String =
        "$count ${if (count == 1) singular else pluralForm}"

    private const val TEMP_COUNT = 3
}
