package edu.itssg.compiler.ui.export

import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.tree.NodeMetrics
import edu.itssg.compiler.ui.tree.VisualNodeStyle
import edu.itssg.compiler.ui.tree.VisualTree
import edu.itssg.compiler.ui.tree.computeTreeLayout
import kotlin.math.max

/**
 * Dibujo de un árbol listo para exportar, como figuras simples en píxeles (fondo blanco, para imprimir). Lo usan
 * tanto el SVG ([toSvg], lógica pura) como el PNG (se pinta con Compose), así los dos salen idénticos.
 *
 * Todos los nodos van desplegados (sin el plegado del visor) y con el mismo layout del visor ([computeTreeLayout]).
 */
internal class TreePicture(val width: Float, val height: Float, val shapes: List<PictureShape>)

internal sealed interface PictureShape {
    data class Rect(
        val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float,
        val fill: Long, val stroke: Long, val strokeWidth: Float, val dashed: Boolean
    ) : PictureShape

    data class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val color: Long, val width: Float, val dashed: Boolean = false
    ) : PictureShape

    data class Circle(val cx: Float, val cy: Float, val r: Float, val fill: Long) : PictureShape

    /** Texto centrado en [cx]; [top] es el borde superior del renglón y [size] el tamaño en píxeles. */
    data class Text(
        val cx: Float, val top: Float, val text: String, val size: Float,
        val bold: Boolean, val color: Long, val centered: Boolean = true
    ) : PictureShape
}

/** Medidas del dibujo exportado (fuente monoespaciada de 12 / 11 / 10 px). */
internal val ExportTreeMetrics = NodeMetrics(
    titleCharWidth = 7.5f, bodyCharWidth = 6.8f, annotationCharWidth = 6.2f,
    titleLineHeight = 16f, bodyLineHeight = 15f, annotationLineHeight = 13f,
    paddingH = 10f, paddingV = 6f, minBoxWidth = 34f, annotationGap = 3f,
    siblingGap = 14f, subtreeGap = 20f, levelGap = 46f
)

internal const val TITLE_FONT_PX = 12f
internal const val BODY_FONT_PX = 11f
internal const val ANNOTATION_FONT_PX = 10f
private const val CAPTION_FONT_PX = 14f
private const val PAD = 28f
private const val CAPTION_SPACE = 34f

/** Paleta clara (la del tema claro de la app), en ARGB. */
internal object PrintPalette {
    const val BACKGROUND = 0xFFFFFFFFL
    const val TEXT = 0xFF1A1A1DL
    const val MUTED = 0xFF8A8A93L
    const val EDGE = 0xFF9A9AA3L
    const val VALUE = 0xFF2E7D4FL
    const val BADGE = 0xFFC98A00L
    const val LINK = 0x99C98A00L

    fun accent(style: VisualNodeStyle): Long = when (style) {
        VisualNodeStyle.NON_TERMINAL -> 0xFF6D4BD8L
        VisualNodeStyle.TERMINAL -> 0xFF2F6FDBL
        VisualNodeStyle.EPSILON -> MUTED
        VisualNodeStyle.SYNTAX_ERROR, VisualNodeStyle.RECOVERED -> 0xFFE07B1AL
        VisualNodeStyle.SEMANTIC_ROOT -> 0xFF138A8AL
        VisualNodeStyle.SEMANTIC_OPERATION -> 0xFFC98A00L
        VisualNodeStyle.SEMANTIC_IDENTIFIER -> 0xFF2F6FDBL
        VisualNodeStyle.SEMANTIC_LITERAL -> 0xFF2E9E5BL
        VisualNodeStyle.SEMANTIC_ERROR -> 0xFFD6407AL
    }

    /** Relleno: 12 % del color sobre blanco. */
    fun fill(style: VisualNodeStyle): Long = blendOverWhite(accent(style), 0.12f)

    private fun blendOverWhite(color: Long, amount: Float): Long {
        fun ch(shift: Int): Long {
            val c = (color shr shift) and 0xFF
            return (255 + (c - 255) * amount).toLong().coerceIn(0, 255)
        }
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Arma el dibujo del árbol completo con un título arriba ("Árbol sintáctico · Uno.java"). */
internal fun buildTreePicture(tree: VisualTree, caption: String): TreePicture {
    val m = ExportTreeMetrics
    val layout = computeTreeLayout(tree, collapsed = emptySet(), hideEpsilon = false, m = m)
    val bounds = layout.bounds
    val dx = PAD - bounds.left
    val dy = PAD + CAPTION_SPACE - bounds.top
    val captionWidth = caption.length * CAPTION_FONT_PX * 0.62f
    val width = max(bounds.width + 2 * PAD, captionWidth + 2 * PAD)
    val height = bounds.height + 2 * PAD + CAPTION_SPACE
    val shapes = ArrayList<PictureShape>()
    shapes += PictureShape.Text(PAD, PAD - 4f, caption, CAPTION_FONT_PX, bold = true, color = PrintPalette.TEXT, centered = false)

    // Aristas: bajan del recuadro al fondo de su nivel y de ahí a cada hijo.
    for (i in layout.visible) {
        val kids = layout.shownChildren[i]
        if (kids.isEmpty()) continue
        val px = layout.centerX[i] + dx
        val startY = layout.edgeStartY(i) + dy
        val busY = layout.rowBottom[i] + dy
        if (busY > startY) shapes += PictureShape.Line(px, startY, px, busY, PrintPalette.EDGE, 1f)
        for (c in kids) {
            shapes += PictureShape.Line(px, busY, layout.centerX[c] + dx, layout.top[c] + dy, PrintPalette.EDGE, 1f)
        }
    }
    // IDn que reutiliza un valor → nodo que lo calculó (punteado).
    for (i in layout.visible) {
        val ref = tree[i].referencedIndex
        if (ref < 0 || !layout.isVisible(ref)) continue
        shapes += PictureShape.Line(
            layout.centerX[i] + dx, layout.top[i] + dy,
            layout.centerX[ref] + dx, layout.bottom(ref) + dy,
            PrintPalette.LINK, 1f, dashed = true
        )
    }
    // Recuadros, textos, anotaciones y pasos.
    for (i in layout.visible) {
        val node = tree[i]
        val left = layout.left(i) + dx
        val top = layout.top[i] + dy
        val w = layout.boxWidth[i]
        val h = layout.boxHeight[i]
        val cx = left + w / 2f
        val accent = PrintPalette.accent(node.style)
        val hasError = node.errors.isNotEmpty()
        shapes += PictureShape.Rect(
            x = left, y = top, w = w, h = h, radius = 6f,
            fill = PrintPalette.fill(node.style),
            stroke = if (hasError) PrintPalette.accent(errorStyle(tree.kind)) else accent,
            strokeWidth = if (hasError) 2f else 1f,
            dashed = node.style == VisualNodeStyle.EPSILON || node.style == VisualNodeStyle.SYNTAX_ERROR
        )
        var y = top + m.paddingV
        node.lines.forEachIndexed { index, line ->
            shapes += PictureShape.Text(
                cx, y, line,
                size = if (index == 0) TITLE_FONT_PX else BODY_FONT_PX,
                bold = index == 0,
                color = if (node.style == VisualNodeStyle.EPSILON) PrintPalette.MUTED else PrintPalette.TEXT
            )
            y += if (index == 0) m.titleLineHeight else m.bodyLineHeight
        }
        node.annotation?.let { annotation ->
            shapes += PictureShape.Text(
                cx, top + h + m.annotationGap, annotation, ANNOTATION_FONT_PX, bold = false,
                color = if (node.style == VisualNodeStyle.SEMANTIC_ERROR) PrintPalette.accent(VisualNodeStyle.SEMANTIC_ERROR) else PrintPalette.VALUE
            )
        }
        node.badge?.let { badge ->
            val r = max(8f, m.annotationCharWidth * badge.length / 2f + 3f)
            shapes += PictureShape.Circle(left, top, r, PrintPalette.BADGE)
            shapes += PictureShape.Text(left, top - 6f, badge, 9f, bold = true, color = PrintPalette.BACKGROUND)
        }
    }
    return TreePicture(width, height, shapes)
}

private fun errorStyle(kind: TreeKind): VisualNodeStyle =
    if (kind == TreeKind.SYNTACTIC) VisualNodeStyle.SYNTAX_ERROR else VisualNodeStyle.SEMANTIC_ERROR

/** SVG del dibujo (texto UTF-8). Fuente monoespaciada del sistema; se ve igual en navegadores e Inkscape. */
internal fun TreePicture.toSvg(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${f(width)}\" height=\"${f(height)}\" ")
    append("viewBox=\"0 0 ${f(width)} ${f(height)}\" ")
    append("font-family=\"'JetBrains Mono', Consolas, Menlo, 'DejaVu Sans Mono', monospace\">\n")
    append("<rect width=\"100%\" height=\"100%\" fill=\"${hex(PrintPalette.BACKGROUND)}\"/>\n")
    for (s in shapes) {
        when (s) {
            is PictureShape.Rect -> append(
                "<rect x=\"${f(s.x)}\" y=\"${f(s.y)}\" width=\"${f(s.w)}\" height=\"${f(s.h)}\" rx=\"${f(s.radius)}\" " +
                    "fill=\"${hex(s.fill)}\" stroke=\"${hex(s.stroke)}\" stroke-width=\"${f(s.strokeWidth)}\"" +
                    (if (s.dashed) " stroke-dasharray=\"4 3\"" else "") + "/>\n"
            )
            is PictureShape.Line -> append(
                "<line x1=\"${f(s.x1)}\" y1=\"${f(s.y1)}\" x2=\"${f(s.x2)}\" y2=\"${f(s.y2)}\" " +
                    "stroke=\"${hex(s.color)}\"${opacity("stroke-opacity", s.color)} stroke-width=\"${f(s.width)}\"" +
                    (if (s.dashed) " stroke-dasharray=\"4 3\"" else "") + "/>\n"
            )
            is PictureShape.Circle -> append(
                "<circle cx=\"${f(s.cx)}\" cy=\"${f(s.cy)}\" r=\"${f(s.r)}\" fill=\"${hex(s.fill)}\"/>\n"
            )
            is PictureShape.Text -> append(
                "<text x=\"${f(s.cx)}\" y=\"${f(s.top + s.size * 0.95f)}\" font-size=\"${f(s.size)}\" " +
                    (if (s.centered) "text-anchor=\"middle\" " else "") +
                    (if (s.bold) "font-weight=\"bold\" " else "") +
                    "fill=\"${hex(s.color)}\" xml:space=\"preserve\">${xmlEscape(s.text)}</text>\n"
            )
        }
    }
    append("</svg>\n")
}

private fun f(v: Float): String {
    val r = kotlin.math.round(v * 10f) / 10f
    val l = r.toLong()
    return if (r == l.toFloat()) l.toString() else r.toString()
}

private fun hex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

private fun opacity(attribute: String, argb: Long): String {
    val alpha = (argb shr 24) and 0xFF
    return if (alpha == 0xFFL) "" else " $attribute=\"${f(alpha / 255f)}\""
}

internal fun xmlEscape(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (c.code < 32) append(' ') else append(c)
        }
    }
}
