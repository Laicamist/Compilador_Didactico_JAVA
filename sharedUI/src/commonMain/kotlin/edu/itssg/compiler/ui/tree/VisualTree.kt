package edu.itssg.compiler.ui.tree

import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.parser.GlcV7
import edu.itssg.compiler.core.parser.SyntaxTreeNode
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.semantic.EvaluatedValue
import edu.itssg.compiler.core.semantic.SemanticAstNode
import edu.itssg.compiler.core.semantic.SemanticDataType
import edu.itssg.compiler.core.semantic.SemanticNodeKind
import edu.itssg.compiler.core.symboltable.SymbolEntry
import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.tables.normalizeForSearch

/**
 * Paso 3.4: forma "plana" de los dos árboles para dibujarlos en el Canvas.
 *
 * Los nodos se guardan en **preorden** (raíz = índice 0): los hijos de un nodo aparecen después de él y en orden de
 * izquierda a derecha. Así el layout recorre el arreglo al revés para tener cada hijo listo antes que su padre, sin
 * recursión (los árboles profundos no desbordan la pila en ninguna plataforma).
 */
enum class VisualNodeStyle(val displayName: String) {
    NON_TERMINAL("No terminal"),
    TERMINAL("Terminal"),
    EPSILON("Producción vacía (ε)"),
    SYNTAX_ERROR("Error sintáctico"),
    RECOVERED("Recuperado (modo pánico)"),
    SEMANTIC_ROOT("Clase principal"),
    SEMANTIC_OPERATION("Operación"),
    SEMANTIC_IDENTIFIER("Identificador"),
    SEMANTIC_LITERAL("Valor"),
    SEMANTIC_ERROR("Error semántico");

    val isNonTerminal: Boolean get() = this == NON_TERMINAL || this == RECOVERED
}

/** Renglón "Etiqueta: valor" de la tarjeta de detalle del nodo. */
data class NodeDetail(val label: String, val value: String)

/**
 * Nodo listo para dibujar.
 *
 * @property sourceId `id` del nodo en el árbol del backend.
 * @property parent Índice del padre (−1 en la raíz).
 * @property children Índices de los hijos, de izquierda a derecha.
 * @property lines Texto del recuadro; el primer renglón va en negritas (regla, token u operación).
 * @property annotation Texto bajo el recuadro (árbol semántico: TD y valor evaluado, como en las láminas).
 * @property badge Número de paso de ejecución (árbol semántico), dibujado como ① en la esquina.
 * @property ruleName No terminal de la GLC v7 (solo en el árbol sintáctico).
 * @property production Producción aplicada (`DEC → TY ID ODA EXP FI`), solo en no terminales.
 * @property step `evaluationStepNumber` (renglón de Triplos/Cuádruplos) de las operaciones.
 * @property referencedIndex Nodo que ya calculó el valor que reutiliza esta hoja `IDn` (−1 si no aplica).
 * @property errors Errores de la tabla que caen en este nodo (el nodo más específico que contiene su posición).
 * @property descendantCount Nodos debajo de este (para el aviso "+N" al plegar).
 * @property cutByLimit Se omitieron hijos por el límite de nodos dibujables.
 */
class VisualNode(
    val index: Int,
    val sourceId: Int,
    val parent: Int,
    val depth: Int,
    val children: IntArray,
    val lines: List<String>,
    val annotation: String?,
    val badge: String?,
    val style: VisualNodeStyle,
    val span: SourceSpan,
    val tokenCategory: TokenCategory?,
    val ruleName: String?,
    val production: String?,
    val step: Int?,
    val referencedIndex: Int,
    val errors: List<CompilerError>,
    val details: List<NodeDetail>,
    val descendantCount: Int,
    val cutByLimit: Boolean,
    internal val searchText: String
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
    val title: String get() = lines.first()
}

/**
 * @property totalNodes Nodos del árbol original (puede ser mayor que [nodes] si se alcanzó el límite).
 * @property maxDepth Profundidad máxima (la raíz tiene profundidad 0).
 */
class VisualTree(
    val kind: TreeKind,
    val nodes: List<VisualNode>,
    val totalNodes: Int,
    val maxDepth: Int
) {
    val size: Int get() = nodes.size
    val isTruncated: Boolean get() = totalNodes > nodes.size
    val errorCount: Int get() = nodes.sumOf { it.errors.size }

    operator fun get(index: Int): VisualNode = nodes[index]

    /** Ancestros de [index], del padre hacia la raíz. */
    fun ancestorsOf(index: Int): List<Int> {
        val result = ArrayList<Int>()
        var current = nodes.getOrNull(index)?.parent ?: -1
        while (current >= 0) {
            result += current
            current = nodes[current].parent
        }
        return result
    }

    /** Índice del nodo que es la operación del renglón [step] de Cuádruplos, si existe. */
    fun nodeForStep(step: Int): Int? = stepIndex[step]

    private val stepIndex: Map<Int, Int> by lazy {
        val map = HashMap<Int, Int>()
        for (node in nodes) {
            if (node.step != null && node.style.isOperationLike()) map.getOrPut(node.step) { node.index }
        }
        map
    }

    /** Nodos cuyo texto contiene [query] (sin mayúsculas ni acentos), en preorden. */
    fun search(query: String): List<Int> {
        val q = normalizeForSearch(query.trim())
        if (q.isEmpty()) return emptyList()
        return nodes.filter { q in it.searchText }.map { it.index }
    }

    private fun VisualNodeStyle.isOperationLike(): Boolean =
        this == VisualNodeStyle.SEMANTIC_OPERATION || this == VisualNodeStyle.SEMANTIC_ERROR
}

/**
 * Árbol como texto con sangría (para copiarlo y pegarlo en un reporte). Una línea por nodo: el texto del recuadro y,
 * en el árbol semántico, el paso y la anotación (`④ ID3 = · VTF · 4.0f`).
 */
fun VisualTree.toIndentedText(maxChars: Int = 500_000): String {
    val sb = StringBuilder()
    for (node in nodes) {
        if (sb.length >= maxChars) {
            sb.append("…\n")
            break
        }
        repeat(node.depth) { sb.append("  ") }
        node.badge?.let { sb.append('[').append(it).append("] ") }
        sb.append(node.lines.joinToString(" "))
        node.annotation?.let { sb.append("  · ").append(it) }
        if (node.errors.isNotEmpty()) sb.append("  ⚠ ").append(node.errors.first().type.displayName)
        sb.append('\n')
    }
    if (isTruncated) sb.append("(se omitieron ${totalNodes - size} nodos por el límite del visor)\n")
    return sb.toString()
}

//Plegado

/** `true` si ningún ancestro de [index] está plegado (y no es una ε oculta). */
fun VisualTree.isShown(index: Int, collapsed: Set<Int>, hideEpsilon: Boolean = false): Boolean {
    if (hideEpsilon && nodes[index].style == VisualNodeStyle.EPSILON) return false
    var current = nodes[index].parent
    while (current >= 0) {
        if (current in collapsed) return false
        current = nodes[current].parent
    }
    return true
}

/** Despliega los ancestros de [index] para que se vea. */
fun VisualTree.expandedToShow(index: Int, collapsed: Set<Int>): Set<Int> {
    if (collapsed.isEmpty()) return collapsed
    val ancestors = ancestorsOf(index).toSet()
    return if (ancestors.none { it in collapsed }) collapsed else collapsed - ancestors
}

/** Pliega todos los nodos con hijos salvo la raíz (al desplegar uno se ven sus hijos plegados). */
fun VisualTree.collapseAll(): Set<Int> =
    nodes.asSequence().filter { it.index != 0 && it.hasChildren }.map { it.index }.toSet()

/** Pliega los nodos con hijos que están en el nivel [level] (todo lo de abajo queda oculto). */
fun VisualTree.collapseAtLevel(level: Int): Set<Int> =
    nodes.asSequence().filter { it.depth == level && it.hasChildren }.map { it.index }.toSet()

/**
 * Plegado inicial: si el árbol es grande, se muestran los primeros niveles completos que quepan en [budget] nodos y
 * se pliega el último nivel visible. Un árbol pequeño se muestra completo.
 */
fun VisualTree.initialCollapse(budget: Int = INITIAL_VISIBLE_BUDGET): Set<Int> {
    if (size <= budget) return emptySet()
    val perDepth = IntArray(maxDepth + 1)
    for (node in nodes) perDepth[node.depth]++
    var cumulative = 0
    var level = 0
    for (d in 0..maxDepth) {
        if (cumulative + perDepth[d] > budget) break
        cumulative += perDepth[d]
        level = d
    }
    return collapseAtLevel(maxOf(level, 1))
}

const val INITIAL_VISIBLE_BUDGET: Int = 240

//Construcción

object VisualTreeBuilder {

    /** Tope de nodos dibujables (protege la interfaz ante programas enormes; el resto se indica con "…"). */
    const val MAX_VISUAL_NODES: Int = 20_000

    private const val MAX_LINE_CHARS = 26

    /** Árbol sintáctico (derivación de la GLC v7). Se le asocian los errores sintácticos. */
    fun fromSyntax(
        root: SyntaxTreeNode,
        errors: List<CompilerError>,
        maxNodes: Int = MAX_VISUAL_NODES
    ): VisualTree {
        val flat = flatten(root, maxNodes) { it.children }
        val errorsByIndex = attachErrors(flat.items.map { it.span }, errors.filter { it.type == ErrorPhaseType.SYNTACTIC }) { i ->
            flat.items[i] is SyntaxTreeNode.ErrorPlaceholder
        }
        val nodes = flat.items.mapIndexed { i, node ->
            val childIdx = flat.children[i]
            val nodeErrors = errorsByIndex[i].orEmpty()
            when (node) {
                is SyntaxTreeNode.NonTerminal -> {
                    val production = productionOf(node.ruleName, childIdx.map { flat.items[it] }, flat.cut[i])
                    val glc = GlcV7.PRODUCTIONS[node.ruleName]
                    visual(
                        flat, i, node.id,
                        lines = listOf(node.ruleName),
                        annotation = null,
                        badge = null,
                        style = if (node.isRecoveredWithError) VisualNodeStyle.RECOVERED else VisualNodeStyle.NON_TERMINAL,
                        span = node.span,
                        tokenCategory = null,
                        ruleName = node.ruleName,
                        production = production,
                        step = null,
                        errors = nodeErrors,
                        details = buildList {
                            add(NodeDetail("Producción aplicada", production))
                            if (glc != null) add(NodeDetail("Regla de la GLC v7", glc.replace("::=", " ::= ")))
                            if (node.isRecoveredWithError) add(NodeDetail("Recuperación", "Se sincronizó en modo pánico (; / FI o } / LLC)"))
                        },
                        search = node.ruleName
                    )
                }

                is SyntaxTreeNode.Terminal -> {
                    val token = node.token
                    val lexeme = displayLexeme(token.lexeme)
                    visual(
                        flat, i, node.id,
                        lines = listOf(token.category.mnemonic, shorten(lexeme)),
                        annotation = null,
                        badge = null,
                        style = VisualNodeStyle.TERMINAL,
                        span = node.span,
                        tokenCategory = token.category,
                        ruleName = null,
                        production = null,
                        step = null,
                        errors = nodeErrors,
                        details = buildList {
                            add(NodeDetail("Token", "${token.category.mnemonic} · ${token.category.description}"))
                            add(NodeDetail("Lexema", lexeme))
                            if (token.tableDisplayLexeme != token.lexeme) add(NodeDetail("En la tabla", token.tableDisplayLexeme))
                        },
                        search = "${token.category.mnemonic} ${token.lexeme} ${token.tableDisplayLexeme}"
                    )
                }

                is SyntaxTreeNode.Epsilon -> visual(
                    flat, i, node.id,
                    lines = listOf("ε"),
                    annotation = null,
                    badge = null,
                    style = VisualNodeStyle.EPSILON,
                    span = node.span,
                    tokenCategory = null,
                    ruleName = null,
                    production = null,
                    step = null,
                    errors = nodeErrors,
                    details = listOf(NodeDetail("Producción vacía", "${node.parentRule} → ε")),
                    search = "ε epsilon"
                )

                is SyntaxTreeNode.ErrorPlaceholder -> visual(
                    flat, i, node.id,
                    lines = listOf("ERROR", shorten(node.expectedToken)),
                    annotation = null,
                    badge = null,
                    style = VisualNodeStyle.SYNTAX_ERROR,
                    span = node.span,
                    tokenCategory = null,
                    ruleName = node.ruleName,
                    production = null,
                    step = null,
                    errors = nodeErrors,
                    details = listOf(
                        NodeDetail("Se esperaba", node.expectedToken),
                        NodeDetail("Regla", node.ruleName)
                    ),
                    search = "error ${node.expectedToken} ${node.ruleName}"
                )
            }
        }
        return VisualTree(TreeKind.SYNTACTIC, nodes, flat.total, nodes.maxOfOrNull { it.depth } ?: 0)
    }

    /** Árbol abstracto-semántico. Se le asocian los errores semánticos. */
    fun fromSemantic(
        root: SemanticAstNode,
        errors: List<CompilerError>,
        symbols: List<SymbolEntry> = emptyList(),
        maxNodes: Int = MAX_VISUAL_NODES
    ): VisualTree {
        val flat = flatten(root, maxNodes) { it.children }
        val errorsByIndex = attachErrors(flat.items.map { it.span }, errors.filter { it.type == ErrorPhaseType.SEMANTIC }) { false }
        val indexById = HashMap<Int, Int>(flat.items.size * 2)
        flat.items.forEachIndexed { i, node -> indexById.getOrPut(node.id) { i } }
        val lexemeById = symbols.associate { it.symbolId to it.lexeme }

        val nodes = flat.items.mapIndexed { i, node ->
            val nodeErrors = errorsByIndex[i].orEmpty()
            val style = when {
                nodeErrors.isNotEmpty() -> VisualNodeStyle.SEMANTIC_ERROR
                node.kind == SemanticNodeKind.PROGRAM_ROOT -> VisualNodeStyle.SEMANTIC_ROOT
                node.kind.isOperation -> VisualNodeStyle.SEMANTIC_OPERATION
                node.kind == SemanticNodeKind.IDENTIFIER_REF -> VisualNodeStyle.SEMANTIC_IDENTIFIER
                else -> VisualNodeStyle.SEMANTIC_LITERAL
            }
            val typeText = typeLabel(node.resolvedType)
            val valueText = node.evaluatedValue.formatForTreeAndQuadruple()
                .takeIf { node.evaluatedValue !is EvaluatedValue.UnknownVal && it != node.primaryLabel }
            val referenced = node.referencedNodeId?.let { indexById[it] } ?: -1
            val boxLines = node.boxDisplayText.split('\n').map { shorten(displayLexeme(it)) }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("·") }
            val symbolLexeme = node.symbolId?.let { lexemeById[it] }
            visual(
                flat, i, node.id,
                lines = boxLines,
                annotation = listOfNotNull(typeText, valueText?.let { shorten(it) }).joinToString(" · ").ifEmpty { null },
                badge = node.evaluationStepNumber?.takeIf { node.kind.isOperation }?.toString(),
                style = style,
                span = node.span,
                tokenCategory = null,
                ruleName = null,
                production = null,
                step = node.evaluationStepNumber?.takeIf { node.kind.isOperation },
                errors = nodeErrors,
                details = buildList {
                    add(NodeDetail("Tipo de nodo", semanticKindLabel(node.kind)))
                    if (symbolLexeme != null) add(NodeDetail("Símbolo", "ID${node.symbolId} = $symbolLexeme"))
                    if (node.resolvedType != SemanticDataType.UNKNOWN) {
                        add(NodeDetail("TD", "${node.resolvedType.keyword} (${node.resolvedType.tokenMnemonic})"))
                    }
                    if (node.evaluatedValue !is EvaluatedValue.UnknownVal) {
                        add(NodeDetail("Valor", node.evaluatedValue.formatForTreeAndQuadruple()))
                    }
                    node.castTypeAnnotation?.let { add(NodeDetail("Conversión", "($it)")) }
                    node.callName?.let { add(NodeDetail("Método de Java", it)) }
                    node.evaluationStepNumber?.takeIf { node.kind.isOperation }?.let {
                        add(NodeDetail("Paso de ejecución", "$it (renglón $it de Triplos y Cuádruplos)"))
                    }
                    if (referenced >= 0) {
                        val step = flat.items[referenced].evaluationStepNumber
                        add(NodeDetail("Reutiliza", if (step != null) "el resultado del paso $step [$step]" else "un valor ya calculado"))
                    }
                },
                search = buildString {
                    append(node.boxDisplayText).append(' ')
                    append(typeText ?: "").append(' ')
                    append(valueText ?: "").append(' ')
                    append(symbolLexeme ?: "")
                }
            ).withReference(referenced)
        }
        return VisualTree(TreeKind.SEMANTIC, nodes, flat.total, nodes.maxOfOrNull { it.depth } ?: 0)
    }

    //Recorrido en preorden con límite

    private class Flat<T>(
        val items: List<T>,
        val parents: IntArray,
        val depths: IntArray,
        val children: List<List<Int>>,
        val descendants: IntArray,
        val cut: BooleanArray,
        val total: Int
    )

    private fun <T> flatten(root: T, maxNodes: Int, childrenOf: (T) -> List<T>): Flat<T> {
        val limit = maxNodes.coerceAtLeast(1)
        val items = ArrayList<T>()
        val parents = ArrayList<Int>()
        val depths = ArrayList<Int>()
        val cutIndexes = HashSet<Int>()
        var total = 0
        // Pila explícita: (nodo, índice del padre, profundidad)
        val stack = ArrayDeque<Triple<T, Int, Int>>()
        stack.addLast(Triple(root, -1, 0))
        while (stack.isNotEmpty()) {
            val (node, parent, depth) = stack.removeLast()
            total++
            if (items.size >= limit) {
                if (parent >= 0) cutIndexes += parent
                // Contar el resto sin guardarlo.
                for (child in childrenOf(node)) stack.addLast(Triple(child, parent, depth + 1))
                continue
            }
            val index = items.size
            items += node
            parents += parent
            depths += depth
            val kids = childrenOf(node)
            for (k in kids.indices.reversed()) stack.addLast(Triple(kids[k], index, depth + 1))
        }
        val n = items.size
        val childLists = List(n) { ArrayList<Int>() }
        for (i in 1 until n) childLists[parents[i]].add(i)
        val descendants = IntArray(n)
        for (i in n - 1 downTo 1) descendants[parents[i]] += descendants[i] + 1
        val cut = BooleanArray(n) { it in cutIndexes }
        return Flat(items, parents.toIntArray(), depths.toIntArray(), childLists, descendants, cut, total)
    }

    private fun <T> visual(
        flat: Flat<T>,
        i: Int,
        sourceId: Int,
        lines: List<String>,
        annotation: String?,
        badge: String?,
        style: VisualNodeStyle,
        span: SourceSpan,
        tokenCategory: TokenCategory?,
        ruleName: String?,
        production: String?,
        step: Int?,
        errors: List<CompilerError>,
        details: List<NodeDetail>,
        search: String
    ): VisualNode = VisualNode(
        index = i,
        sourceId = sourceId,
        parent = flat.parents[i],
        depth = flat.depths[i],
        children = flat.children[i].toIntArray(),
        lines = lines,
        annotation = annotation,
        badge = badge,
        style = style,
        span = span,
        tokenCategory = tokenCategory,
        ruleName = ruleName,
        production = production,
        step = step,
        referencedIndex = -1,
        errors = errors,
        details = details,
        descendantCount = flat.descendants[i],
        cutByLimit = flat.cut[i],
        searchText = normalizeForSearch("${lines.joinToString(" ")} ${annotation ?: ""} $search")
    )

    private fun VisualNode.withReference(referenced: Int): VisualNode =
        if (referenced < 0) this else VisualNode(
            index, sourceId, parent, depth, children, lines, annotation, badge, style, span, tokenCategory, ruleName,
            production, step, referenced, errors, details, descendantCount, cutByLimit, searchText
        )

    /**
     * Cada error se asocia al nodo más específico (el de menor fragmento) que contiene su posición; a igual tamaño
     * gana el preferido por [prefer] y luego el más profundo (el que aparece después en preorden).
     */
    private fun attachErrors(
        spans: List<SourceSpan>,
        errors: List<CompilerError>,
        prefer: (Int) -> Boolean
    ): Map<Int, List<CompilerError>> {
        if (errors.isEmpty()) return emptyMap()
        val result = HashMap<Int, MutableList<CompilerError>>()
        for (error in errors.take(MAX_ATTACHED_ERRORS)) {
            val offset = error.span.start.offset
            var best = -1
            var bestLength = Int.MAX_VALUE
            var bestPreferred = false
            for (i in spans.indices) {
                val span = spans[i]
                if (offset < span.start.offset || offset > span.end.offset) continue
                val length = span.length
                val preferred = prefer(i)
                val better = when {
                    length < bestLength -> true
                    length > bestLength -> false
                    preferred != bestPreferred -> preferred
                    else -> true
                }
                if (better) {
                    best = i
                    bestLength = length
                    bestPreferred = preferred
                }
            }
            if (best >= 0) result.getOrPut(best) { ArrayList() }.add(error)
        }
        return result
    }

    private const val MAX_ATTACHED_ERRORS = 500

    //Textos

    /** `DEC → TY ID ODA EXP FI` (los ε se escriben; si se omitieron hijos por el límite se agrega "…"). */
    private fun productionOf(rule: String, children: List<SyntaxTreeNode>, cut: Boolean): String {
        val symbols = children.map { child ->
            when (child) {
                is SyntaxTreeNode.NonTerminal -> child.ruleName
                is SyntaxTreeNode.Terminal -> child.token.category.mnemonic
                is SyntaxTreeNode.Epsilon -> "ε"
                is SyntaxTreeNode.ErrorPlaceholder -> "ERROR(${child.expectedToken})"
            }
        }.toMutableList()
        if (cut) symbols += "…"
        return "$rule → ${symbols.ifEmpty { listOf("ε") }.joinToString(" ")}"
    }

    private fun typeLabel(type: SemanticDataType): String? = when (type) {
        SemanticDataType.UNKNOWN -> null
        SemanticDataType.CLASS_INSTANCE -> "clase"
        SemanticDataType.VOID -> "void"
        else -> type.tokenMnemonic
    }

    private fun semanticKindLabel(kind: SemanticNodeKind): String = when (kind) {
        SemanticNodeKind.PROGRAM_ROOT -> "Raíz: clase principal → método principal"
        SemanticNodeKind.OUTPUT_STATEMENT -> "Salida (SOUT)"
        SemanticNodeKind.MEMBER_ACCESS -> "Acceso a miembro (.)"
        SemanticNodeKind.METHOD_RETURN -> "Regreso de método (RTN)"
        SemanticNodeKind.ASSIGNMENT_LINK -> "Asignación (=)"
        SemanticNodeKind.BINARY_OPERATOR -> "Operación binaria"
        SemanticNodeKind.UNARY_OPERATOR -> "Operación unaria"
        SemanticNodeKind.IDENTIFIER_REF -> "Identificador"
        SemanticNodeKind.LITERAL_VALUE -> "Valor (hoja)"
    }

    /** Muestra los caracteres de control de forma visible (una cadena con salto de línea no rompe el recuadro). */
    internal fun displayLexeme(text: String): String {
        if (text.none { it < ' ' }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    '\n' -> "⏎"
                    '\t' -> "⇥"
                    else -> if (c < ' ') "·" else c.toString()
                }
            )
        }
        return sb.toString()
    }

    internal fun shorten(text: String): String =
        if (text.length <= MAX_LINE_CHARS) text else text.take(MAX_LINE_CHARS - 1) + "…"
}
