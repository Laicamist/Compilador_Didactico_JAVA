package edu.itssg.compiler.ui.tree

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.ui.state.TreeKind

/** Movimiento de cámara que pide el panel y ejecuta el Canvas (que conoce su tamaño y el layout). */
sealed interface CameraRequest {
    val id: Long

    /** Encuadrar todo el árbol visible. */
    data class FitAll(override val id: Long) : CameraRequest

    /**
     * Centrar el nodo [index]. Con [onlyIfHidden] solo se mueve si el nodo no se ve completo; [minScale] acerca la vista
     * si está muy lejos para leer el nodo.
     */
    data class Focus(override val id: Long, val index: Int, val minScale: Float? = null, val onlyIfHidden: Boolean = false) : CameraRequest
}

/** Nodo que debe quedarse en el mismo lugar de la pantalla después de plegar/desplegar. */
data class ScreenAnchor(val index: Int, val screenX: Float, val screenY: Float)

/**
 * Estado del visor de UN árbol de UN reporte: plegado, vista, selección, búsqueda y recorrido.
 *
 * Vive en [TreeViewerRegistry] (dentro del ViewModel) para conservarse al cambiar de pestaña, al pasar a pantalla
 * completa y al volver del Inicio. Se descarta cuando hay un reporte nuevo.
 */
@Stable
class TreeViewerState internal constructor(
    val report: CompilationReport,
    val kind: TreeKind,
    val tree: VisualTree
) {
    var collapsed by mutableStateOf(tree.initialCollapse())
    var hideEpsilon by mutableStateOf(false)

    /** Vista actual; `null` hasta que el Canvas encuadra el árbol por primera vez. */
    var viewport by mutableStateOf<Viewport?>(null)
    var cameraRequest by mutableStateOf<CameraRequest?>(null)
    internal var anchor: ScreenAnchor? = null

    var selected by mutableStateOf<Int?>(null)

    var searchQuery by mutableStateOf("")
        private set
    var searchMatches by mutableStateOf<List<Int>>(emptyList())
        private set
    var searchCursor by mutableIntStateOf(-1)
        private set

    var walkMode by mutableStateOf<WalkthroughMode?>(null)
        private set
    var walkStepIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)

    var showMinimap by mutableStateOf(true)
    var showLegend by mutableStateOf(true)

    private var cameraCounter = 0L
    private val walkthroughs = HashMap<WalkthroughMode, Walkthrough>()

    // ---------------------------------------------------------------- Cámara

    fun requestFit() {
        cameraRequest = CameraRequest.FitAll(++cameraCounter)
    }

    fun requestFocus(index: Int, minScale: Float? = null, onlyIfHidden: Boolean = false) {
        cameraRequest = CameraRequest.Focus(++cameraCounter, index, minScale, onlyIfHidden)
    }

    // ---------------------------------------------------------------- Plegado

    fun isCollapsed(index: Int): Boolean = index in collapsed

    /** Pliega o despliega [index]; [anchor] mantiene el nodo en su lugar de la pantalla. */
    fun toggle(index: Int, anchor: ScreenAnchor? = null) {
        if (!tree[index].hasChildren) return
        this.anchor = anchor
        collapsed = if (index in collapsed) collapsed - index else collapsed + index
    }

    fun expandAll() {
        collapsed = emptySet()
        requestFit()
    }

    fun collapseAll() {
        collapsed = tree.collapseAll()
        selected = selected?.takeIf { tree.isShown(it, collapsed, hideEpsilon) }
        requestFit()
    }

    fun collapseToLevel(level: Int) {
        collapsed = tree.collapseAtLevel(level)
        selected = selected?.takeIf { tree.isShown(it, collapsed, hideEpsilon) }
        requestFit()
    }

    fun setEpsilonHidden(hidden: Boolean) {
        hideEpsilon = hidden
        if (hidden) selected = selected?.takeIf { tree[it].style != VisualNodeStyle.EPSILON }
    }

    /** Despliega lo necesario para que [index] se vea (y, con [includeChildren], también sus hijos). */
    fun reveal(index: Int, includeChildren: Boolean = false) {
        var next = tree.expandedToShow(index, collapsed)
        if (includeChildren && index in next) next = next - index
        if (next != collapsed) collapsed = next
        if (hideEpsilon && tree[index].style == VisualNodeStyle.EPSILON) hideEpsilon = false
    }

    // ---------------------------------------------------------------- Selección y búsqueda

    fun select(index: Int?) {
        selected = index
    }

    fun updateSearch(query: String) {
        searchQuery = query.take(MAX_SEARCH_LENGTH)
        searchMatches = tree.search(searchQuery)
        searchCursor = -1
    }

    /** Va a la coincidencia siguiente (o anterior) y la devuelve para centrarla. */
    fun nextMatch(forward: Boolean = true): Int? {
        val matches = searchMatches
        if (matches.isEmpty()) return null
        searchCursor = when {
            searchCursor < 0 -> if (forward) 0 else matches.lastIndex
            forward -> (searchCursor + 1) % matches.size
            else -> (searchCursor - 1 + matches.size) % matches.size
        }
        val index = matches[searchCursor]
        reveal(index)
        selected = index
        requestFocus(index, minScale = READABLE_SCALE, onlyIfHidden = true)
        return index
    }

    // ---------------------------------------------------------------- Recorrido

    fun walkthrough(mode: WalkthroughMode): Walkthrough = walkthroughs.getOrPut(mode) {
        when (mode) {
            WalkthroughMode.DERIVATION -> WalkthroughBuilder.derivation(tree)
            WalkthroughMode.QUADRUPLES -> WalkthroughBuilder.quadruples(report, tree)
            WalkthroughMode.THREE_ADDRESS -> WalkthroughBuilder.threeAddress(report, tree)
        }
    }

    val currentWalkthrough: Walkthrough? get() = walkMode?.let { walkthrough(it) }

    val currentStep: WalkStep? get() = currentWalkthrough?.steps?.getOrNull(walkStepIndex)

    fun startWalkthrough(mode: WalkthroughMode, autoplay: Boolean = false) {
        val keepPosition = walkMode != null && walkMode != WalkthroughMode.DERIVATION && mode != WalkthroughMode.DERIVATION
        val previousNode = currentStep?.nodeIndex
        walkMode = mode
        val steps = walkthrough(mode).steps
        // Al cambiar entre Cuádruplos y 3 Direcciones se queda en el mismo nodo si se puede.
        walkStepIndex = if (keepPosition && previousNode != null) {
            steps.indexOfFirst { it.nodeIndex == previousNode }.coerceAtLeast(0)
        } else {
            0
        }
        isPlaying = autoplay && steps.size > 1
    }

    fun stopWalkthrough() {
        isPlaying = false
        walkMode = null
        walkStepIndex = 0
    }

    fun goToStep(index: Int) {
        val steps = currentWalkthrough?.steps ?: return
        if (steps.isEmpty()) return
        walkStepIndex = index.coerceIn(0, steps.lastIndex)
    }

    fun stepForward(): Boolean {
        val steps = currentWalkthrough?.steps ?: return false
        if (walkStepIndex >= steps.lastIndex) {
            isPlaying = false
            return false
        }
        walkStepIndex++
        return true
    }

    fun stepBack() {
        if (walkStepIndex > 0) walkStepIndex--
    }

    fun togglePlay() {
        val steps = currentWalkthrough?.steps ?: return
        if (isPlaying) {
            isPlaying = false
        } else {
            if (walkStepIndex >= steps.lastIndex) walkStepIndex = 0
            isPlaying = steps.size > 1
        }
    }

    companion object {
        /** Escala mínima para leer un nodo al centrarlo (búsqueda y recorrido). */
        const val READABLE_SCALE: Float = 0.8f
        private const val MAX_SEARCH_LENGTH = 80

        fun create(report: CompilationReport, kind: TreeKind): TreeViewerState {
            val tree = when (kind) {
                TreeKind.SYNTACTIC -> VisualTreeBuilder.fromSyntax(report.syntaxTree, report.errors)
                TreeKind.SEMANTIC -> VisualTreeBuilder.fromSemantic(report.semanticTree, report.errors, report.symbolEntries)
            }
            return TreeViewerState(report, kind, tree)
        }
    }
}

/**
 * Estados del visor del reporte actual (uno por árbol). Al llegar un reporte nuevo se descartan los anteriores: el
 * plegado y la vista de otro programa no tienen sentido.
 */
class TreeViewerRegistry {
    private var report: CompilationReport? = null
    private val states = HashMap<TreeKind, TreeViewerState>()

    fun stateFor(report: CompilationReport, kind: TreeKind): TreeViewerState {
        if (report !== this.report) {
            states.clear()
            this.report = report
        }
        return states.getOrPut(kind) { TreeViewerState.create(report, kind) }
    }

    fun clear() {
        states.clear()
        report = null
    }
}
