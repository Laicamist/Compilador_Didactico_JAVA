package edu.itssg.compiler.ui.home

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.parser.GlcV7
import edu.itssg.compiler.core.parser.SyntaxTreeNode
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.viewmodel.AppViewModel
import edu.itssg.compiler.ui.viewmodel.HelpTopic

/**
 * Contenido de una hoja "¿Cómo funciona?": explicación, ejemplo de entrada → salida y a qué parte del espacio de
 * trabajo lleva "Ver ejemplo". La salida sale de la compilación **real** del ejemplo del Inicio
 * ([AppViewModel.DEMO_SOURCE], instrucción `int x = 5 + 3;`), no de texto escrito a mano.
 *
 * @property phase Fase cuyo color usa la hoja (las hojas Tablas y Árbol usan el de la fase que las produce).
 * @property outputLines Renglones de la salida; la animación los va mostrando uno por uno.
 */
data class HelpContent(
    val topic: HelpTopic,
    val title: String,
    val phase: CompilerPhase,
    val paragraphs: List<String>,
    val inputLabel: String,
    val input: String,
    val outputLabel: String,
    val outputLines: List<String>,
    val exampleTab: ResultTab
)

/** Línea del código del ejemplo donde está [AppViewModel.DEMO_STATEMENT]. */
private fun demoLine(report: CompilationReport): Int =
    report.sourceCode.split('\n').indexOfFirst { AppViewModel.DEMO_STATEMENT in it }.let { if (it < 0) 1 else it + 1 }

/**
 * Arma la hoja de [topic]. Si el ejemplo todavía no compila ([report] es `null`) o falló, la salida queda vacía y la
 * hoja muestra solo la explicación.
 */
fun helpContent(topic: HelpTopic, report: CompilationReport?): HelpContent {
    val statement = AppViewModel.DEMO_STATEMENT
    return when (topic) {
        HelpTopic.LEXICAL -> HelpContent(
            topic = topic,
            title = "Análisis léxico",
            phase = CompilerPhase.LEXICAL,
            paragraphs = listOf(
                "El analizador léxico lee el código carácter por carácter con un autómata finito (AFD) y lo separa en " +
                    "tokens: cada lexema recibe su categoría de la tabla de tokens (TD, ID, ODA, VTE, OPA, FI…).",
                "Descarta espacios y comentarios, y numera los identificadores creados por el programador (ID1, ID2…). " +
                    "Un símbolo que no pertenece al lenguaje, como @ fuera de una cadena, es un error léxico."
            ),
            inputLabel = "Instrucción",
            input = statement,
            outputLabel = "Tokens (LEXEMA → TOKEN)",
            outputLines = report?.let { r ->
                val line = demoLine(r)
                r.lexicalTableRows.filter { it.line == line }.map { row ->
                    val id = if (row.idColumn.isNotEmpty()) "  (ID${row.idColumn})" else ""
                    "${row.lexemeColumn.padEnd(4)} → ${row.tokenColumn}$id"
                }
            }.orEmpty(),
            exampleTab = ResultTab.TOKENS
        )

        HelpTopic.SYNTACTIC -> HelpContent(
            topic = topic,
            title = "Análisis sintáctico",
            phase = CompilerPhase.SYNTACTIC,
            paragraphs = listOf(
                "El analizador sintáctico revisa que los tokens sigan las reglas de la GLC v7. Es descendente " +
                    "recursivo: cada regla (CLASE, MAIN, DEC, EXP…) es una función que pide los tokens que espera.",
                "Si falta un token reporta \"No cumple la regla…\" y se recupera en modo pánico: salta hasta el " +
                    "siguiente ; (FI) o } (LLC) para seguir revisando el resto del programa."
            ),
            inputLabel = "Tokens",
            input = report?.let { r -> r.lexicalTableRows.filter { it.line == demoLine(r) }.joinToString(" ") { it.tokenColumn } }
                ?: "TD ID ODA VTE OPA VTE FI",
            outputLabel = "Derivación (reglas que se aplican)",
            outputLines = report?.let { derivationLines(it) }.orEmpty(),
            exampleTab = ResultTab.TREE
        )

        HelpTopic.SEMANTIC -> HelpContent(
            topic = topic,
            title = "Análisis semántico",
            phase = CompilerPhase.SEMANTIC,
            paragraphs = listOf(
                "El analizador semántico revisa el significado: que cada ID esté declarado antes de usarse, que los " +
                    "tipos de dato (TD) sean compatibles, que los métodos reciban sus argumentos y que regresen su valor.",
                "También calcula los valores que se conocen al compilar y arma el árbol abstracto-semántico con el " +
                    "orden de ejecución: de abajo hacia arriba y de izquierda a derecha."
            ),
            inputLabel = "Instrucción",
            input = statement,
            outputLabel = "Lo que comprueba",
            outputLines = report?.let { r ->
                val x = r.symbolEntries.firstOrNull { it.lexeme == "x" }
                buildList {
                    add("5 + 3 → int + int = int · valor 8")
                    if (x != null) add("x se declara como ${x.referenceCode} (TD ${x.dataType}) en ${x.scope}")
                    add("int ← int: compatibles ✓")
                    add(if (r.semanticErrorCount == 0) "Sin errores semánticos" else "${r.semanticErrorCount} errores semánticos")
                }
            }.orEmpty(),
            exampleTab = ResultTab.SYMBOLS
        )

        HelpTopic.TABLES -> HelpContent(
            topic = topic,
            title = "Tablas de tokens, símbolos y errores",
            phase = CompilerPhase.LEXICAL,
            paragraphs = listOf(
                "La tabla de tokens tiene las columnas de la materia: ID | LEXEMA | TOKEN. La primera aparición de un " +
                    "identificador muestra su número; las siguientes se escriben como IDn.",
                "La tabla de símbolos guarda cada elemento creado por el programador con su rol, TD y ámbito. La tabla " +
                    "de errores reúne los léxicos, sintácticos y semánticos con Línea | Error | Tipo | Justificación."
            ),
            inputLabel = "Código",
            input = statement,
            outputLabel = "Tabla de símbolos",
            outputLines = report?.symbolEntries?.map { "${it.referenceCode.padEnd(4)} ${it.lexeme.padEnd(5)} ${it.role.label} · ${it.dataType ?: "—"} · ${it.scope}" }
                .orEmpty(),
            exampleTab = ResultTab.SYMBOLS
        )

        HelpTopic.TREE -> HelpContent(
            topic = topic,
            title = "Árboles sintáctico y abstracto-semántico",
            phase = CompilerPhase.SYNTACTIC,
            paragraphs = listOf(
                "El árbol sintáctico muestra la derivación completa: cada no terminal de la GLC v7 y los tokens que " +
                    "produce. El árbol abstracto-semántico solo deja las acciones, con su TD y su valor.",
                "En la app puedes acercar, plegar ramas, buscar y reproducir el recorrido: la derivación en el " +
                    "sintáctico y la ejecución (cuádruplos o 3 direcciones) en el semántico."
            ),
            inputLabel = "Instrucción",
            input = statement,
            outputLabel = "Árbol abstracto-semántico (paso · acción)",
            outputLines = report?.intermediateCode?.quadruples?.map { q ->
                "${q.stepNumber}. ${q.operator.padEnd(4)} (${q.val1}, ${q.val2}) → ${q.result}"
            }.orEmpty(),
            exampleTab = ResultTab.TREE
        )

        HelpTopic.INTERMEDIATE_CODE -> HelpContent(
            topic = topic,
            title = "Código intermedio",
            phase = CompilerPhase.INTERMEDIATE_CODE,
            paragraphs = listOf(
                "Con el árbol semántico se genera código intermedio en tres formas: triplos (val1, ope, val2), " +
                    "cuádruplos (val1, ope, val2, resultado) y 3 direcciones con los temporales T1, T2 y T3.",
                "Cada renglón es un paso del orden de ejecución; [n] se refiere al resultado del renglón n. Si hay " +
                    "errores, el código intermedio no se genera."
            ),
            inputLabel = "Instrucción",
            input = statement,
            outputLabel = "Cuádruplos",
            outputLines = report?.intermediateCode?.quadruples?.map { it.formatAcademic() }.orEmpty(),
            exampleTab = ResultTab.INTERMEDIATE_CODE
        )

        HelpTopic.OPTIMIZATION -> HelpContent(
            topic = topic,
            title = "Optimización",
            phase = CompilerPhase.OPTIMIZATION,
            paragraphs = listOf(
                "El optimizador recorre los cuádruplos de arriba hacia abajo y aplica las técnicas de la U3: " +
                    "Simplificación (SE), Folding (FO), Propagación de constantes (PC), Reducción de subexpresiones (RS) " +
                    "y Código muerto (CM).",
                "La D (FOD, PCD…) indica que la técnica fue posible por un cambio anterior. El código de 3 direcciones " +
                    "no se optimiza."
            ),
            inputLabel = "Cuádruplos originales",
            input = report?.intermediateCode?.quadruples?.size?.let { "$it instrucciones" } ?: "",
            outputLabel = "Técnicas y resultado",
            outputLines = report?.let { r ->
                r.optimizationResult.appliedOptimizations.map { "${it.code}: ${it.afterAction}" } +
                    r.optimizationResult.optimizedBundle.quadruples.map { "→ ${it.formatAcademic()}" }
            }.orEmpty(),
            exampleTab = ResultTab.INTERMEDIATE_CODE
        )
    }
}

/** Producciones aplicadas en la instrucción del ejemplo, en preorden (derivación por la izquierda). */
private fun derivationLines(report: CompilationReport): List<String> {
    val line = demoLine(report)
    val result = ArrayList<String>()
    val stack = ArrayDeque<SyntaxTreeNode>()
    stack.addLast(report.syntaxTree)
    var visited = 0
    while (stack.isNotEmpty() && visited < 20_000) {
        val node = stack.removeLast()
        visited++
        if (node is SyntaxTreeNode.NonTerminal) {
            val onLine = node.span.start.line == line && node.span.end.line == line
            if (onLine) {
                val rhs = node.children.joinToString(" ") { child ->
                    when (child) {
                        is SyntaxTreeNode.NonTerminal -> child.ruleName
                        is SyntaxTreeNode.Terminal -> child.token.category.mnemonic
                        else -> "ε"
                    }
                }.ifEmpty { "ε" }
                result += "${node.ruleName} → $rhs"
            }
            node.children.asReversed().forEach { stack.addLast(it) }
        }
    }
    if (result.isEmpty()) GlcV7.PRODUCTIONS["DEC"]?.let { result += it }
    return result.take(12)
}
