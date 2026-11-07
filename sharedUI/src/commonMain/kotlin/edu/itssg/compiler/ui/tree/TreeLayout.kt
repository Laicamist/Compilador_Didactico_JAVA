package edu.itssg.compiler.ui.tree

import kotlin.math.max
import kotlin.math.min

/**
 * Medidas del dibujo de los nodos, en píxeles del "mundo" (la escala 1 del Canvas).
 *
 * El texto de los nodos usa una fuente monoespaciada: el ancho de un renglón es `caracteres × ancho de un carácter`.
 * Así el layout no necesita medir cada texto con la plataforma (se mide un solo carácter por estilo) y se puede probar
 * en commonTest.
 */
data class NodeMetrics(
    val titleCharWidth: Float,
    val bodyCharWidth: Float,
    val annotationCharWidth: Float,
    val titleLineHeight: Float,
    val bodyLineHeight: Float,
    val annotationLineHeight: Float,
    val paddingH: Float,
    val paddingV: Float,
    val minBoxWidth: Float,
    /** Espacio entre el recuadro y su anotación (TD · valor). */
    val annotationGap: Float,
    /** Separación horizontal entre hermanos. */
    val siblingGap: Float,
    /** Separación horizontal entre subárboles en niveles inferiores (primos). */
    val subtreeGap: Float,
    /** Separación vertical entre niveles. */
    val levelGap: Float
) {
    companion object {
        /** Medidas aproximadas para pruebas (1 px = 1 dp, fuente de 12 px). */
        val Default = NodeMetrics(
            titleCharWidth = 7.4f, bodyCharWidth = 6.8f, annotationCharWidth = 6.2f,
            titleLineHeight = 16f, bodyLineHeight = 15f, annotationLineHeight = 13f,
            paddingH = 10f, paddingV = 6f, minBoxWidth = 34f, annotationGap = 3f,
            siblingGap = 14f, subtreeGap = 20f, levelGap = 46f
        )
    }
}

/**
 * Resultado del layout: un árbol ordenado de arriba hacia abajo, con cada padre centrado sobre sus hijos y sin
 * encimar subárboles (método de contornos, al estilo Reingold–Tilford, con anchos variables).
 *
 * Los arreglos se indexan por índice de nodo; los nodos ocultos (dentro de un nodo plegado) tienen `NaN`.
 *
 * @property visible Índices de los nodos dibujados, en preorden.
 * @property centerX Centro horizontal del recuadro.
 * @property top Borde superior del recuadro.
 */
class TreeLayout(
    val visible: IntArray,
    val centerX: FloatArray,
    val top: FloatArray,
    val boxWidth: FloatArray,
    val boxHeight: FloatArray,
    val footprintWidth: FloatArray,
    val footprintHeight: FloatArray,
    /** Hijos dibujados de cada nodo (vacío si está plegado o es hoja). */
    val shownChildren: Array<IntArray>,
    /** Borde inferior del nivel del nodo (el recuadro más alto del nivel, con su anotación). */
    val rowBottom: FloatArray,
    val bounds: WorldRect
) {
    fun isVisible(index: Int): Boolean = index in centerX.indices && !centerX[index].isNaN()

    fun left(index: Int): Float = centerX[index] - boxWidth[index] / 2f
    fun right(index: Int): Float = centerX[index] + boxWidth[index] / 2f
    fun bottom(index: Int): Float = top[index] + boxHeight[index]

    /** Centro del recuadro del nodo. */
    fun centerY(index: Int): Float = top[index] + boxHeight[index] / 2f

    /**
     * Las aristas salen debajo del recuadro (y de su anotación), bajan en vertical hasta el fondo del nivel y de ahí
     * van al hijo. Así una arista de un recuadro bajo no cruza los recuadros más altos de sus vecinos.
     */
    fun edgeStartY(index: Int): Float = top[index] + footprintHeight[index]

    fun boxRect(index: Int): WorldRect = WorldRect(left(index), top[index], right(index), bottom(index))

    /** Nodo cuyo recuadro contiene el punto (del mundo), o `null`. */
    fun nodeAt(x: Float, y: Float, slop: Float = 0f): Int? {
        for (k in visible.indices.reversed()) {
            val i = visible[k]
            if (x >= left(i) - slop && x <= right(i) + slop && y >= top[i] - slop && y <= bottom(i) + slop) return i
        }
        return null
    }
}

data class WorldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersects(other: WorldRect): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun inflate(amount: Float): WorldRect = WorldRect(left - amount, top - amount, right + amount, bottom + amount)

    companion object {
        val Empty = WorldRect(0f, 0f, 0f, 0f)
    }
}

/** Tamaño del recuadro: renglón de título (negritas) + renglones normales, con relleno. */
fun boxSize(node: VisualNode, m: NodeMetrics): Pair<Float, Float> {
    var width = node.lines.first().length * m.titleCharWidth
    var height = m.titleLineHeight
    for (k in 1 until node.lines.size) {
        width = max(width, node.lines[k].length * m.bodyCharWidth)
        height += m.bodyLineHeight
    }
    return max(width + 2 * m.paddingH, m.minBoxWidth) to (height + 2 * m.paddingV)
}

/** Ancho de la anotación (TD · valor) bajo el recuadro; 0 si no tiene. */
fun annotationWidth(node: VisualNode, m: NodeMetrics): Float =
    node.annotation?.let { it.length * m.annotationCharWidth } ?: 0f

/**
 * Calcula posiciones para los nodos visibles.
 *
 * @param collapsed Nodos plegados: se dibujan como hojas y sus descendientes quedan ocultos.
 * @param hideEpsilon Oculta las hojas ε (producciones vacías) para aligerar el árbol sintáctico.
 */
fun computeTreeLayout(
    tree: VisualTree,
    collapsed: Set<Int>,
    hideEpsilon: Boolean,
    m: NodeMetrics
): TreeLayout {
    val n = tree.size
    val centerX = FloatArray(n) { Float.NaN }
    val top = FloatArray(n) { Float.NaN }
    val boxW = FloatArray(n)
    val boxH = FloatArray(n)
    val footW = FloatArray(n)
    val footH = FloatArray(n)
    val rowBottom = FloatArray(n) { Float.NaN }
    if (n == 0) return TreeLayout(IntArray(0), centerX, top, boxW, boxH, footW, footH, emptyArray(), rowBottom, WorldRect.Empty)

    // 1. Nodos visibles e hijos dibujados (preorden: el padre se decide antes que sus hijos).
    val shown = BooleanArray(n)
    shown[0] = true
    val shownChildren = Array(n) { EMPTY }
    val visibleList = ArrayList<Int>()
    for (i in 0 until n) {
        val node = tree[i]
        if (i > 0) {
            val p = node.parent
            shown[i] = shown[p] && p !in collapsed && !(hideEpsilon && node.style == VisualNodeStyle.EPSILON)
        }
        if (!shown[i]) continue
        visibleList += i
        if (i !in collapsed && node.hasChildren) {
            val kids = node.children.filter { c -> !(hideEpsilon && tree[c].style == VisualNodeStyle.EPSILON) }
            shownChildren[i] = kids.toIntArray()
        }
    }

    // 2. Tamaños y alto de cada nivel.
    val rowHeight = FloatArray(tree.maxDepth + 1)
    for (i in visibleList) {
        val node = tree[i]
        val (w, h) = boxSize(node, m)
        boxW[i] = w
        boxH[i] = h
        val annotation = annotationWidth(node, m)
        footW[i] = max(w, annotation)
        footH[i] = h + if (node.annotation != null) m.annotationGap + m.annotationLineHeight else 0f
        rowHeight[node.depth] = max(rowHeight[node.depth], footH[i])
    }
    val rowTop = FloatArray(tree.maxDepth + 1)
    for (d in 1..tree.maxDepth) rowTop[d] = rowTop[d - 1] + rowHeight[d - 1] + m.levelGap

    // 3. Contornos de abajo hacia arriba (preorden al revés: cada hijo antes que su padre).
    val contours = arrayOfNulls<Contour>(n)
    val offset = FloatArray(n) // posición relativa al centro del padre
    for (k in visibleList.indices.reversed()) {
        val i = visibleList[k]
        val kids = shownChildren[i]
        val half = footW[i] / 2f
        if (kids.isEmpty()) {
            contours[i] = Contour.single(-half, half)
            continue
        }
        val acc = contours[kids[0]]!!
        contours[kids[0]] = null
        val position = FloatArray(kids.size)
        for (c in 1 until kids.size) {
            val child = contours[kids[c]]!!
            contours[kids[c]] = null
            var shift = Float.NEGATIVE_INFINITY
            val common = min(acc.size, child.size)
            for (d in 0 until common) {
                val gap = if (d == 0) m.siblingGap else m.subtreeGap
                shift = max(shift, acc.right[d] - child.left[d] + gap)
            }
            position[c] = shift
            acc.mergeRight(child, shift)
        }
        val mid = (position.first() + position.last()) / 2f
        for (c in kids.indices) offset[kids[c]] = position[c] - mid
        contours[i] = acc.shiftedBelow(-mid, -half, half)
    }

    // 4. Posiciones absolutas (preorden) y límites.
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = 0f
    for (i in visibleList) {
        val node = tree[i]
        centerX[i] = if (i == 0) 0f else centerX[node.parent] + offset[i]
        top[i] = rowTop[node.depth]
        rowBottom[i] = rowTop[node.depth] + rowHeight[node.depth]
        minX = min(minX, centerX[i] - footW[i] / 2f)
        maxX = max(maxX, centerX[i] + footW[i] / 2f)
        maxY = max(maxY, top[i] + footH[i])
    }
    return TreeLayout(
        visible = visibleList.toIntArray(),
        centerX = centerX,
        top = top,
        boxWidth = boxW,
        boxHeight = boxH,
        footprintWidth = footW,
        footprintHeight = footH,
        shownChildren = shownChildren,
        rowBottom = rowBottom,
        bounds = WorldRect(minX, 0f, maxX, maxY)
    )
}

private val EMPTY = IntArray(0)

/**
 * Contorno de un subárbol: para cada nivel (0 = el nivel de su raíz) el borde izquierdo y derecho, relativos al
 * centro de su raíz.
 */
private class Contour(var left: FloatArray, var right: FloatArray, var size: Int) {

    private fun ensure(capacity: Int) {
        if (capacity <= left.size) return
        val newCap = max(capacity, left.size * 2)
        left = left.copyOf(newCap)
        right = right.copyOf(newCap)
    }

    /** Agrega a la derecha el contorno [other] desplazado [shift]. */
    fun mergeRight(other: Contour, shift: Float) {
        ensure(other.size)
        for (d in 0 until other.size) {
            if (d < size) {
                right[d] = max(right[d], other.right[d] + shift)
                left[d] = min(left[d], other.left[d] + shift)
            } else {
                left[d] = other.left[d] + shift
                right[d] = other.right[d] + shift
            }
        }
        size = max(size, other.size)
    }

    /** Contorno del padre: su propio renglón [ownLeft, ownRight] y debajo este contorno desplazado [shift]. */
    fun shiftedBelow(shift: Float, ownLeft: Float, ownRight: Float): Contour {
        val l = FloatArray(size + 1)
        val r = FloatArray(size + 1)
        l[0] = ownLeft
        r[0] = ownRight
        for (d in 0 until size) {
            l[d + 1] = left[d] + shift
            r[d + 1] = right[d] + shift
        }
        return Contour(l, r, size + 1)
    }

    companion object {
        fun single(left: Float, right: Float) = Contour(floatArrayOf(left), floatArrayOf(right), 1)
    }
}
