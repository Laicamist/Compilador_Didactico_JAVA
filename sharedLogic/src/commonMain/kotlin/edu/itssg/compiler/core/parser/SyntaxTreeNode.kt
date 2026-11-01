package edu.itssg.compiler.core.parser
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.security.SourceSpan

/**
 * Representa un nodo inmutable dentro del Árbol Sintáctico (Concrete Syntax Tree / Derivation Tree)
 * generado a partir de la Gramática Libre de Contexto (GLC v7).
 *
 * Basado en el diseño visual de los árboles del proyecto:
 * - Los nodos interiores son No-Terminales de la GLC v7 (`JAVA`, `CLASE`, `MAIN`, `DEC`, `EXP`, `EXPC`, `ACC`, `CLA`, `OT`, `VAL`, `OP`, `ARGS`, etc.).
 * - Las hojas son Terminales que muestran el Token (ej. `TD`, `ID`, `ODA`, `FI`) y su lexema asociado (`int`, `a`, `=`, `;`).
 */
sealed interface SyntaxTreeNode {
    val id: Int
    val label: String
    val span: SourceSpan
    val children: List<SyntaxTreeNode>

    /**
     * Nodo No-Terminal que representa la aplicación de una regla de producción de la GLC v7.
     *
     * @property ruleName Nombre exacto del No-Terminal en la GLC v7 (ej. "JAVA", "CLASE", "DEC", "EXP").
     * @property children Hijos ordenados de izquierda a derecha según la derivación gramatical.
     * @property isRecoveredWithError Indica si esta regla requirió sincronización en Modo Pánico.
     */
    data class NonTerminal(
        override val id: Int,
        val ruleName: String,
        override val children: List<SyntaxTreeNode>,
        override val span: SourceSpan,
        val isRecoveredWithError: Boolean = false
    ) : SyntaxTreeNode {
        override val label: String
            get() = ruleName
    }

    /**
     * Nodo Hoja Terminal que envuelve un [Token] real consumido del flujo léxico.
     */
    data class Terminal(
        override val id: Int,
        val token: Token
    ) : SyntaxTreeNode {
        override val label: String
            get() = token.category.mnemonic

        override val span: SourceSpan
            get() = token.span

        override val children: List<SyntaxTreeNode>
            get() = emptyList()

        val lexemeDisplay: String
            get() = token.tableDisplayLexeme
    }

    /**
     * Nodo Hoja para producciones vacías (Épsilon / `ε`) definidas en la GLC v7
     * (ej. `DPACKAGE ::= ε`, `IMPORTS ::= ε`, `EXPC ::= ε`).
     */
    data class Epsilon(
        override val id: Int,
        val parentRule: String,
        override val span: SourceSpan
    ) : SyntaxTreeNode {
        override val label: String = "ε"
        override val children: List<SyntaxTreeNode> = emptyList()
    }

    /**
     * Nodo marcador de error cuando falta un token obligatorio y el Parser se recupera sin abortar.
     */
    data class ErrorPlaceholder(
        override val id: Int,
        val expectedToken: String,
        val ruleName: String,
        override val span: SourceSpan
    ) : SyntaxTreeNode {
        override val label: String = "ERROR($expectedToken)"
        override val children: List<SyntaxTreeNode> = emptyList()
    }
}

/**
 * Utilidades de recorrido seguro en memoria dinámica (Heap) mediante [DeepRecursiveFunction]
 * para evitar `StackOverflowError` en iOS (Kotlin/Native) y Android al inspeccionar árboles profundos.
 */
object SyntaxTreeTraversals {

    /**
     * Cuenta el número total de nodos del Árbol Sintáctico sin usar recursión en el Call Stack nativo.
     */
    fun countNodesSafely(root: SyntaxTreeNode): Int {
        val counter = DeepRecursiveFunction<SyntaxTreeNode, Int> { node ->
            var total = 1
            for (child in node.children) {
                total += callRecursive(child)
            }
            total
        }
        return counter(root)
    }

    /**
     * Busca todos los subárboles No-Terminales que coincidan con [targetRuleName] (ej. "DEC", "OBJ", "MAIN").
     */
    fun filterNonTerminalsByRule(
        root: SyntaxTreeNode,
        targetRuleName: String
    ): List<SyntaxTreeNode.NonTerminal> {
        val results = ArrayList<SyntaxTreeNode.NonTerminal>()
        val finder = DeepRecursiveFunction<SyntaxTreeNode, Unit> { node ->
            if (node is SyntaxTreeNode.NonTerminal && node.ruleName == targetRuleName) {
                results.add(node)
            }
            for (child in node.children) {
                callRecursive(child)
            }
        }
        finder(root)
        return results
    }

    /**
     * Extrae en orden todos los tokens terminales contenidos debajo de un nodo dado.
     */
    fun collectLeafTokens(root: SyntaxTreeNode): List<Token> {
        val leaves = ArrayList<Token>()
        val collector = DeepRecursiveFunction<SyntaxTreeNode, Unit> { node ->
            when (node) {
                is SyntaxTreeNode.Terminal -> leaves.add(node.token)
                is SyntaxTreeNode.NonTerminal -> {
                    for (child in node.children) {
                        callRecursive(child)
                    }
                }
                is SyntaxTreeNode.Epsilon,
                is SyntaxTreeNode.ErrorPlaceholder -> Unit
            }
        }
        collector(root)
        return leaves
    }

    /**
     * Genera una representación textual indentada del Árbol Sintáctico para depuración rápida.
     */
    fun toPrettyString(root: SyntaxTreeNode): String {
        val sb = StringBuilder()
        val printer = DeepRecursiveFunction<Pair<SyntaxTreeNode, Int>, Unit> { (node, depth) ->
            val indent = "  ".repeat(depth)
            when (node) {
                is SyntaxTreeNode.NonTerminal -> {
                    sb.append(indent).append("[").append(node.ruleName).append("]\n")
                    for (child in node.children) {
                        callRecursive(child to (depth + 1))
                    }
                }
                is SyntaxTreeNode.Terminal -> {
                    sb.append(indent)
                        .append(node.token.category.mnemonic)
                        .append(" -> ")
                        .append(node.token.lexeme)
                        .append(" (")
                        .append(node.token.tableDisplayLexeme)
                        .append(")\n")
                }
                is SyntaxTreeNode.Epsilon -> {
                    sb.append(indent).append("ε\n")
                }
                is SyntaxTreeNode.ErrorPlaceholder -> {
                    sb.append(indent).append("<!Esperado: ").append(node.expectedToken).append("!>\n")
                }
            }
        }
        printer(root to 0)
        return sb.toString()
    }
}