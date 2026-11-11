package edu.itssg.compiler.ui.tree

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.parser.SyntaxTreeNode
import edu.itssg.compiler.core.parser.SyntaxTreeTraversals
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.ui.state.TreeKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Paso 3.4: lógica del visor de árboles (modelo, layout, vista, búsqueda y recorridos) con el compilador real. */
class TreeViewerTest {

    private fun compile(source: String) = JavaDidacticCompiler().compileSynchronously(source)

    private val uno = """
        public class Uno{
        float dos = 3;
        float tres = dos+ 1;
        public void cuatro(int cinco){
        System.out.println( 45.0 + ( cinco * tres) );}
        public static void main(String []args){
        new Uno().cuatro('@');}}
    """.trimIndent()

    private val claseA = """
        public class A{
        public int a= 35/4+10/3 ;
        int uno = ++a;
        private double dos= uno*(5-10);
        double tres= "hola".length()*30;
        public float cuatro=(float)( dos+tres*dos);
        public static void main(String [] q){
        System.out.println(new A().cuatro);}
        }
    """.trimIndent()

    private val withErrors = "public class A{ int x = ; public static void main(String[] a){ System.out.println(y); } }"

    private val reuse = """
        public class Rs{
        public static void main(String [] x){
        double b= Math.random();
        double a=b+5*2;
        System.out.println(a);}}
    """.trimIndent()

    private fun syntaxTree(source: String) = compile(source).let { VisualTreeBuilder.fromSyntax(it.syntaxTree, it.errors) }

    private fun semanticTree(source: String) =
        compile(source).let { VisualTreeBuilder.fromSemantic(it.semanticTree, it.errors, it.symbolEntries) }

    //Modelo

    @Test
    fun elArbolSintacticoConservaTodosLosNodosEnPreorden() {
        val r = compile(uno)
        val tree = VisualTreeBuilder.fromSyntax(r.syntaxTree, r.errors)
        assertEquals(TreeKind.SYNTACTIC, tree.kind)
        assertEquals(SyntaxTreeTraversals.countNodesSafely(r.syntaxTree), tree.size)
        assertFalse(tree.isTruncated)
        assertEquals("JAVA", tree[0].title)
        assertEquals("JAVA → DPACKAGE IMPORTS CLASE", tree[0].production)
        for (node in tree.nodes) {
            for (c in node.children) {
                assertEquals(node.index, tree[c].parent)
                assertEquals(node.depth + 1, tree[c].depth)
                assertTrue(c > node.index, "Preorden: el hijo va después del padre")
            }
        }
        // Las hojas del árbol, en orden, son los tokens del programa (sin EOF).
        val leaves = tree.nodes.filter { it.style == VisualNodeStyle.TERMINAL }.map { it.lines[1] }
        val tokens = SyntaxTreeTraversals.collectLeafTokens(r.syntaxTree).map { it.lexeme }
        assertEquals(tokens, leaves)
        val dos = tree.nodes.first { it.tokenCategory == TokenCategory.TD }
        assertEquals(listOf("TD", "float"), dos.lines)
        assertEquals(tree.size - 1, tree[0].descendantCount)
    }

    @Test
    fun elArbolSemanticoTraePasosTiposYValores() {
        val tree = semanticTree(uno)
        assertEquals(listOf("ID1", "PMAIN"), tree[0].lines)
        assertEquals(VisualNodeStyle.SEMANTIC_ROOT, tree[0].style)
        // Un nodo por cada renglón de los cuádruplos, con su número de paso.
        assertEquals((1..8).toList(), (1..8).mapNotNull { tree.nodeForStep(it) }.map { tree[it].step })
        val plus = tree[assertNotNull(tree.nodeForStep(2))]
        assertEquals(VisualNodeStyle.SEMANTIC_OPERATION, plus.style)
        assertEquals("2", plus.badge)
        assertEquals("VTF · 4.0f", plus.annotation)
        val literal = tree.nodes.first { it.title == "'@'" }
        assertEquals(VisualNodeStyle.SEMANTIC_LITERAL, literal.style)
    }

    @Test
    fun losErroresSeMarcanEnElNodoMasEspecifico() {
        val r = compile(withErrors)
        val syntax = VisualTreeBuilder.fromSyntax(r.syntaxTree, r.errors)
        val placeholder = syntax.nodes.first { it.style == VisualNodeStyle.SYNTAX_ERROR }
        assertEquals(listOf("ERROR", "EXP"), placeholder.lines)
        assertEquals(1, placeholder.errors.size)
        val semantic = VisualTreeBuilder.fromSemantic(r.semanticTree, r.errors, r.symbolEntries)
        val y = semantic.nodes.first { it.title == "y" }
        assertEquals(VisualNodeStyle.SEMANTIC_ERROR, y.style)
        assertTrue(y.errors.single().justification.contains("no ha sido declarado"))
        assertEquals(1, semantic.errorCount)
    }

    @Test
    fun elIdDelMainSeDerivaDondeSeUsaSinLineaPunteada() {
        // `b` se declara en el main y se usa en `a = b + 5*2`: su rama `ID3 =` cuelga de ese `+`, no de la raíz.
        val tree = semanticTree(reuse)
        assertTrue(tree.nodes.none { it.referencedIndex >= 0 }, "Ningún nodo se enlaza con línea punteada")
        assertTrue(tree.nodes.none { n -> n.details.any { it.label == "Reutiliza" } })
        val root = tree[0]
        assertEquals(listOf("SOUT"), root.children.map { tree[it].title })
        val b = tree.nodes.single { it.lines == listOf("ID3", "=") }
        assertEquals("+", tree[b.parent].title)
        assertNotNull(b.step)
    }

    @Test
    fun elLimiteDeNodosRecortaSinPerderElConteo() {
        val r = compile(claseA)
        val total = SyntaxTreeTraversals.countNodesSafely(r.syntaxTree)
        val tree = VisualTreeBuilder.fromSyntax(r.syntaxTree, r.errors, maxNodes = 50)
        assertEquals(50, tree.size)
        assertEquals(total, tree.totalNodes)
        assertTrue(tree.isTruncated)
        assertTrue(tree.nodes.any { it.cutByLimit })
        assertTrue(tree.nodes.filter { it.cutByLimit && it.style.isNonTerminal }.all { it.production!!.endsWith("…") })
    }

    @Test
    fun busquedaSinMayusculasNiAcentos() {
        val tree = syntaxTree(uno)
        val decs = tree.search("d_glob")
        assertEquals(2, decs.size)
        assertTrue(tree.search("TRES").isNotEmpty(), "Encuentra el lexema")
        assertTrue(tree.search("   ").isEmpty())
        assertTrue(tree.search("ε").isNotEmpty())
    }

    @Test
    fun copiarElArbolComoTextoConSangria() {
        val text = semanticTree(uno).toIndentedText()
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals("ID1 PMAIN  · VTD · 301.0", lines.first())
        assertTrue(lines.any { it.startsWith("    [7] SOUT ID4") }, text)
        val syntax = syntaxTree(withErrors).toIndentedText()
        assertTrue(syntax.contains("ERROR EXP  ⚠ Sintáctico"), syntax)
    }

    //Plegado

    @Test
    fun plegadoInicialSoloEnArbolesGrandes() {
        val small = syntaxTree(uno)
        assertTrue(small.initialCollapse(budget = 1_000).isEmpty())
        val collapsed = small.initialCollapse(budget = 30)
        assertTrue(collapsed.isNotEmpty())
        val layout = computeTreeLayout(small, collapsed, hideEpsilon = false, m = NodeMetrics.Default)
        assertTrue(layout.visible.size <= 30 + small.nodes.count { it.index in collapsed }, "${layout.visible.size}")
        // Mostrar un nodo profundo despliega sus ancestros.
        val deep = small.nodes.maxBy { it.depth }.index
        assertFalse(small.isShown(deep, collapsed))
        val expanded = small.expandedToShow(deep, collapsed)
        assertTrue(small.isShown(deep, expanded))
        assertTrue(small.collapseAll().none { it == 0 })
    }

    //Layout

    @Test
    fun layoutSinEncimarNodosYConPadresCentrados() {
        for (source in listOf(uno, claseA, withErrors)) {
            val tree = syntaxTree(source)
            val layout = computeTreeLayout(tree, emptySet(), hideEpsilon = false, m = NodeMetrics.Default)
            assertEquals(tree.size, layout.visible.size)
            assertNoOverlaps(tree, layout)
            for (i in layout.visible) {
                val kids = layout.shownChildren[i]
                if (kids.isEmpty()) continue
                val mid = (layout.centerX[kids.first()] + layout.centerX[kids.last()]) / 2f
                assertTrue(abs(mid - layout.centerX[i]) < 0.01f, "Padre centrado")
                for (c in kids) assertTrue(layout.top[c] > layout.bottom(i), "Hijo debajo del padre")
            }
            // Los hermanos quedan de izquierda a derecha en el orden del árbol.
            for (i in layout.visible) {
                val kids = layout.shownChildren[i]
                for (k in 1 until kids.size) assertTrue(layout.centerX[kids[k]] > layout.centerX[kids[k - 1]])
            }
        }
    }

    @Test
    fun plegarYOcultarEpsilonQuitaNodosDelLayout() {
        val tree = syntaxTree(uno)
        val clase = tree.nodes.first { it.title == "CLASE" }.index
        val layout = computeTreeLayout(tree, setOf(clase), hideEpsilon = false, m = NodeMetrics.Default)
        assertTrue(layout.isVisible(clase))
        assertTrue(layout.shownChildren[clase].isEmpty())
        assertTrue(tree[clase].children.none { layout.isVisible(it) })
        assertEquals(tree.size - tree[clase].descendantCount, layout.visible.size)

        val noEpsilon = computeTreeLayout(tree, emptySet(), hideEpsilon = true, m = NodeMetrics.Default)
        val epsilonCount = tree.nodes.count { it.style == VisualNodeStyle.EPSILON }
        assertTrue(epsilonCount > 0)
        assertEquals(tree.size - epsilonCount, noEpsilon.visible.size)
        assertNoOverlaps(tree, noEpsilon)
    }

    @Test
    fun layoutDeUnArbolMuyProfundoSinDesbordarLaPila() {
        val depth = 6_000
        val pos = SourcePosition(1, 1, 0)
        val span = SourceSpan(pos, pos)
        var node: SyntaxTreeNode = SyntaxTreeNode.Epsilon(depth + 1, "X", span)
        for (d in depth downTo 1) {
            val extra = SyntaxTreeNode.Epsilon(-d, "X", span)
            node = SyntaxTreeNode.NonTerminal(d, "X", listOf(node, extra), span)
        }
        val tree = VisualTreeBuilder.fromSyntax(node, emptyList())
        assertEquals(2 * depth + 1, tree.size)
        val layout = computeTreeLayout(tree, emptySet(), hideEpsilon = false, m = NodeMetrics.Default)
        assertEquals(tree.size, layout.visible.size)
        assertTrue(layout.bounds.height > depth * NodeMetrics.Default.levelGap)
        assertEquals(depth, WalkthroughBuilder.derivation(tree).steps.size)
    }

    @Test
    fun clicSobreUnNodo() {
        val tree = syntaxTree(uno)
        val layout = computeTreeLayout(tree, emptySet(), hideEpsilon = false, m = NodeMetrics.Default)
        val target = tree.nodes.first { it.title == "CLASE" }.index
        assertEquals(target, layout.nodeAt(layout.centerX[target], layout.centerY(target)))
        assertEquals(null, layout.nodeAt(layout.bounds.right + 500f, layout.bounds.bottom + 500f))
    }

    //Vista (zoom y desplazamiento)

    @Test
    fun zoomAlrededorDelPunteroYEncuadre() {
        val v = Viewport(1f, 30f, 40f)
        val zoomed = v.zoomAt(2f, 200f, 100f)
        assertEquals(2f, zoomed.scale)
        assertTrue(abs(v.toWorldX(200f) - zoomed.toWorldX(200f)) < 1e-3f)
        assertTrue(abs(v.toWorldY(100f) - zoomed.toWorldY(100f)) < 1e-3f)
        assertEquals(Viewport.MAX_SCALE, v.zoomAt(1_000f, 0f, 0f).scale)
        assertEquals(Viewport.MIN_SCALE, v.zoomAt(0.0001f, 0f, 0f).scale)

        val content = WorldRect(-500f, 0f, 500f, 300f)
        val fit = Viewport.fit(content, 800f, 600f, padding = 20f)
        assertTrue(fit.shows(content, 800f, 600f, margin = 19f))
        assertTrue(abs(fit.toScreenX(content.centerX) - 400f) < 0.01f)

        val lost = Viewport(1f, -5_000f, 0f).clampedTo(content, 800f, 600f, margin = 40f)
        assertTrue(lost.toScreenX(content.right) >= 39.9f, "Queda una orilla del árbol a la vista")

        val mid = Viewport.interpolate(Viewport(1f, 0f, 0f), Viewport(4f, 0f, 0f), 0.5f, 800f, 600f)
        assertTrue(abs(mid.scale - 2f) < 1e-3f, "Escala geométrica")
        assertEquals(Viewport(4f, 10f, 20f), Viewport.interpolate(Viewport(1f, 0f, 0f), Viewport(4f, 10f, 20f), 1f, 800f, 600f).let {
            Viewport(it.scale, kotlin.math.round(it.offsetX), kotlin.math.round(it.offsetY))
        })
    }

    //Recorridos

    @Test
    fun derivacionPorLaIzquierdaHastaLaCadenaDeTokens() {
        val tree = syntaxTree(uno)
        val walk = WalkthroughBuilder.derivation(tree)
        assertEquals(tree.nodes.count { it.style.isNonTerminal }, walk.steps.size)
        assertEquals("JAVA → DPACKAGE IMPORTS CLASE", walk.steps.first().title)
        assertEquals(0, walk.steps.first().nodeIndex)

        val start = WalkthroughBuilder.sententialForm(tree, WalkthroughBuilder.derivationRevealed(tree, walk, -1), null)
        assertEquals(listOf("JAVA"), start.map { it.text })
        val first = WalkthroughBuilder.sententialForm(tree, WalkthroughBuilder.derivationRevealed(tree, walk, 0), 0)
        assertEquals(listOf("DPACKAGE", "IMPORTS", "CLASE"), first.map { it.text })
        assertTrue(first.all { it.isNew && it.isNonTerminal })

        var previousRevealed = 0
        for (k in walk.steps.indices step 7) {
            val count = WalkthroughBuilder.derivationRevealed(tree, walk, k).count { it }
            assertTrue(count >= previousRevealed)
            previousRevealed = count
        }
        // Al final la forma sentencial es la secuencia de tokens del programa.
        val end = WalkthroughBuilder.sententialForm(tree, WalkthroughBuilder.derivationRevealed(tree, walk, walk.steps.lastIndex), null)
        val r = compile(uno)
        assertEquals(SyntaxTreeTraversals.collectLeafTokens(r.syntaxTree).map { it.category.mnemonic }, end.map { it.text })
        assertTrue(end.none { it.isNonTerminal })
    }

    @Test
    fun recorridoPorCuadruplosIluminaElNodoDeCadaRenglon() {
        val r = compile(uno)
        val tree = VisualTreeBuilder.fromSemantic(r.semanticTree, r.errors, r.symbolEntries)
        val walk = WalkthroughBuilder.quadruples(r, tree)
        assertEquals(8, walk.steps.size)
        assertEquals("ID1 PMAIN", walk.header)
        assertEquals("ID1 PMAIN -> [8]", walk.closing)
        walk.steps.forEachIndexed { i, step ->
            val node = assertNotNull(step.nodeIndex)
            assertEquals(i + 1, tree[node].step)
            assertEquals(i, step.lineIndex)
        }
        // Paso 2: ([1], +, 1, 4.0f) usa el resultado del paso 1.
        val second = walk.steps[1]
        assertEquals("2. ([1], +, 1, 4.0f)", second.title)
        assertTrue(second.relatedIndices.contains(tree.nodeForStep(1)))
        assertTrue(second.explanation.contains("el resultado del paso 1 [1]"), second.explanation)
        assertTrue(walk.steps[0].explanation.contains("ID2 (dos)"), walk.steps[0].explanation)
    }

    @Test
    fun recorridoPorTresDireccionesConTemporalesYPendientes() {
        val r = compile(uno)
        val tree = VisualTreeBuilder.fromSemantic(r.semanticTree, r.errors, r.symbolEntries)
        val walk = WalkthroughBuilder.threeAddress(r, tree)
        assertEquals(r.intermediateCode.threeAddress.size, walk.steps.size)
        val second = walk.steps[1] // T1. (ID2, +, 1)
        assertEquals(listOf(2, null, null), second.registers.map { it.holderStep })
        assertTrue(second.registers[0].changed)
        assertEquals(tree.nodeForStep(2), second.nodeIndex)
        val freeAll = walk.steps[3] // ID3 = T1 --> free all
        assertTrue(freeAll.registers.all { it.holderStep == null })
        assertTrue(freeAll.explanation.contains("free all"), freeAll.explanation)

        val ra = compile(claseA)
        val treeA = VisualTreeBuilder.fromSemantic(ra.semanticTree, ra.errors, ra.symbolEntries)
        val walkA = WalkthroughBuilder.threeAddress(ra, treeA)
        val pendingStep = walkA.steps.first { it.title.startsWith("P3") }
        assertTrue(pendingStep.pendingSteps.contains(3))
        assertTrue(pendingStep.explanation.contains("pendiente"))
        val completed = walkA.steps.indexOfFirst { it.explanation.contains("Se completa la operación pendiente P3") }
        assertTrue(completed > walkA.steps.indexOf(pendingStep))
        assertFalse(walkA.steps[completed].pendingSteps.contains(3))
        assertTrue(walkA.steps.all { it.nodeIndex != null })
    }
}

private fun assertNoOverlaps(tree: VisualTree, layout: TreeLayout) {
    val byDepth = layout.visible.groupBy { tree[it].depth }
    for ((_, row) in byDepth) {
        val sorted = row.sortedBy { layout.centerX[it] }
        for (k in 1 until sorted.size) {
            val a = sorted[k - 1]
            val b = sorted[k]
            val rightA = layout.centerX[a] + layout.footprintWidth[a] / 2f
            val leftB = layout.centerX[b] - layout.footprintWidth[b] / 2f
            assertTrue(leftB - rightA >= NodeMetrics.Default.siblingGap - 0.01f, "Nodos encimados: ${tree[a].title} / ${tree[b].title}")
        }
    }
}
