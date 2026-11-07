package edu.itssg.compiler.ui.tree

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.theme.JAutomataColors
import edu.itssg.compiler.ui.theme.JAutomataFonts
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ============================================================================ Estilos y medidas

/** Estilos del texto de los nodos (monoespaciados: el layout calcula anchos sin medir cada texto). */
@Immutable
internal class NodeTextStyles(
    val title: TextStyle,
    val body: TextStyle,
    val annotation: TextStyle,
    val badge: TextStyle
)

internal fun nodeTextStyles(): NodeTextStyles = NodeTextStyles(
    title = TextStyle(fontFamily = JAutomataFonts.code, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    body = TextStyle(fontFamily = JAutomataFonts.code, fontSize = 11.sp),
    annotation = TextStyle(fontFamily = JAutomataFonts.code, fontSize = 10.sp),
    badge = TextStyle(fontFamily = JAutomataFonts.code, fontWeight = FontWeight.Bold, fontSize = 9.sp)
)

/** Mide un carácter de cada estilo y arma las medidas del layout en píxeles de la escala 1. */
internal fun measureNodeMetrics(measurer: TextMeasurer, styles: NodeTextStyles, density: Density): NodeMetrics {
    val sample = "MMMMMMMMMM"
    fun charWidth(style: TextStyle) = measurer.measure(sample, style).size.width / sample.length.toFloat()
    fun lineHeight(style: TextStyle) = measurer.measure("Mg|", style).size.height.toFloat()
    return with(density) {
        NodeMetrics(
            titleCharWidth = charWidth(styles.title),
            bodyCharWidth = charWidth(styles.body),
            annotationCharWidth = charWidth(styles.annotation),
            titleLineHeight = lineHeight(styles.title),
            bodyLineHeight = lineHeight(styles.body),
            annotationLineHeight = lineHeight(styles.annotation),
            paddingH = 9.dp.toPx(),
            paddingV = 5.dp.toPx(),
            minBoxWidth = 34.dp.toPx(),
            annotationGap = 3.dp.toPx(),
            siblingGap = 14.dp.toPx(),
            subtreeGap = 22.dp.toPx(),
            levelGap = 40.dp.toPx()
        )
    }
}

/** Colores del visor, tomados de la paleta de la app (un color por fase). */
@Immutable
internal class TreePalette(val colors: JAutomataColors) {
    val edge: Color = colors.borderStrong.copy(alpha = if (colors.isDark) 1f else 0.9f)
    val text: Color = colors.textPrimary
    val muted: Color = colors.textMuted
    val boxBase: Color = colors.surface3
    val select: Color = colors.accent
    val value: Color = colors.optimization
    val collapsedFill: Color = colors.surface5

    fun accent(style: VisualNodeStyle): Color = when (style) {
        VisualNodeStyle.NON_TERMINAL -> colors.syntactic
        VisualNodeStyle.TERMINAL -> colors.lexical
        VisualNodeStyle.EPSILON -> colors.textMuted
        VisualNodeStyle.SYNTAX_ERROR, VisualNodeStyle.RECOVERED -> colors.errorSyntactic
        VisualNodeStyle.SEMANTIC_ROOT -> colors.semantic
        VisualNodeStyle.SEMANTIC_OPERATION -> colors.intermediate
        VisualNodeStyle.SEMANTIC_IDENTIFIER -> colors.lexical
        VisualNodeStyle.SEMANTIC_LITERAL -> colors.optimization
        VisualNodeStyle.SEMANTIC_ERROR -> colors.errorSemantic
    }

    /** Relleno: la superficie con un toque del color del tipo (legible en tema claro y oscuro). */
    fun fill(style: VisualNodeStyle): Color = blend(boxBase, accent(style), if (colors.isDark) 0.16f else 0.12f)

    fun error(type: ErrorPhaseType): Color = colors.error(type)

    /** Color del recorrido: violeta (sintáctico) o ámbar (ejecución, como el código intermedio). */
    fun walk(mode: WalkthroughMode?): Color =
        if (mode == WalkthroughMode.DERIVATION) colors.syntactic else colors.intermediate
}

private fun blend(base: Color, tint: Color, amount: Float): Color = Color(
    red = base.red + (tint.red - base.red) * amount,
    green = base.green + (tint.green - base.green) * amount,
    blue = base.blue + (tint.blue - base.blue) * amount,
    alpha = 1f
)

/** Lo que se ilumina en el Canvas además de la selección. */
@Immutable
internal class TreeHighlights(
    val walkNode: Int? = null,
    val walkRelated: Set<Int> = emptySet(),
    val walkLinks: List<Pair<Int, Int>> = emptyList(),
    val walkMode: WalkthroughMode? = null,
    /** Derivación: nodos ya derivados (los demás se atenúan). `null` = todos visibles. */
    val revealed: BooleanArray? = null,
    val searchMatches: Set<Int> = emptySet(),
    val searchCurrent: Int? = null
)

// ============================================================================ Caché de textos

/**
 * Textos medidos de los nodos (se miden la primera vez que se dibujan y se reutilizan en cada cuadro; el color se
 * aplica al dibujar, así un cambio de tema no obliga a medir otra vez).
 */
internal class NodeTextCache(private val measurer: TextMeasurer, private val styles: NodeTextStyles) {
    private val cache = HashMap<Long, TextLayoutResult>()

    fun line(node: VisualNode, line: Int): TextLayoutResult =
        get(node.index, line) { measurer.measure(node.lines[line], if (line == 0) styles.title else styles.body) }

    fun annotation(node: VisualNode): TextLayoutResult =
        get(node.index, SLOT_ANNOTATION) { measurer.measure(node.annotation.orEmpty(), styles.annotation) }

    fun badge(node: VisualNode): TextLayoutResult =
        get(node.index, SLOT_BADGE) { measurer.measure(node.badge.orEmpty(), styles.badge) }

    fun chip(text: String): TextLayoutResult = chips.getOrPut(text) { measurer.measure(text, styles.badge) }

    private val chips = HashMap<String, TextLayoutResult>()

    private inline fun get(index: Int, slot: Int, measure: () -> TextLayoutResult): TextLayoutResult {
        if (cache.size > MAX_ENTRIES) cache.clear()
        val key = index.toLong() * SLOTS + slot
        return cache.getOrPut(key, measure)
    }

    private companion object {
        const val SLOTS = 16L
        const val SLOT_ANNOTATION = 13
        const val SLOT_BADGE = 14
        const val MAX_ENTRIES = 6_000
    }
}

// ============================================================================ Canvas principal

/**
 * Canvas del árbol con zoom y desplazamiento:
 * - Arrastrar (ratón o un dedo) mueve la vista; pellizcar o Ctrl/⌘ + rueda acerca y aleja alrededor del puntero; la
 *   rueda sola desplaza (Mayús + rueda: horizontal).
 * - Tocar un nodo lo selecciona; tocar su chip "+N" / "−" lo despliega o pliega (el nodo no se mueve de lugar).
 * - Teclado (con el Canvas enfocado): + / − / 0, flechas, Enter (plegar), Espacio (reproducir el recorrido),
 *   ← / → (pasos del recorrido), Esc (quitar selección).
 *
 * Solo se dibuja lo que cae en la vista; con poco zoom se omiten los textos (los recuadros siguen visibles).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TreeCanvas(
    state: TreeViewerState,
    layout: TreeLayout,
    metrics: NodeMetrics,
    textCache: NodeTextCache,
    palette: TreePalette,
    highlights: TreeHighlights,
    animationsEnabled: Boolean,
    onNodeTap: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    val currentLayout by rememberUpdatedState(layout)
    val currentOnTap by rememberUpdatedState(onNodeTap)
    val fitPadding = with(density) { 24.dp.toPx() }
    val clampMargin = with(density) { 48.dp.toPx() }
    val chipHeight = with(density) { 15.dp.toPx() }
    val chipPadding = with(density) { 5.dp.toPx() }

    fun setViewport(v: Viewport) {
        state.viewport = v.clampedTo(currentLayout.bounds, viewSize.width.toFloat(), viewSize.height.toFloat(), clampMargin)
    }

    // Plegar/desplegar: el nodo tocado se queda en el mismo lugar de la pantalla.
    LaunchedEffect(layout) {
        val anchor = state.anchor ?: return@LaunchedEffect
        state.anchor = null
        val v = state.viewport ?: return@LaunchedEffect
        if (!layout.isVisible(anchor.index)) return@LaunchedEffect
        val dx = anchor.screenX - v.toScreenX(layout.centerX[anchor.index])
        val dy = anchor.screenY - v.toScreenY(layout.top[anchor.index])
        state.viewport = v.panBy(dx, dy)
    }

    // Encuadre inicial y movimientos de cámara pedidos por el panel (animados si Ajustes lo permite).
    var handledCameraId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(state.cameraRequest?.id, layout, viewSize) {
        if (viewSize.width == 0 || viewSize.height == 0) return@LaunchedEffect
        val w = viewSize.width.toFloat()
        val h = viewSize.height.toFloat()
        val fit = Viewport.fit(layout.bounds, w, h, fitPadding)
        if (state.viewport == null) state.viewport = fit
        val request = state.cameraRequest ?: return@LaunchedEffect
        if (request.id == handledCameraId) return@LaunchedEffect
        handledCameraId = request.id
        val current = state.viewport ?: fit
        val target: Viewport = when (request) {
            is CameraRequest.FitAll -> fit
            is CameraRequest.Focus -> {
                val i = request.index
                if (!layout.isVisible(i)) return@LaunchedEffect
                val minScale = request.minScale
                val readable = minScale == null || current.scale >= minScale * 0.75f
                if (request.onlyIfHidden && readable && current.shows(layout.boxRect(i), w, h, fitPadding)) {
                    return@LaunchedEffect
                }
                val scale = (minScale?.let { max(current.scale, it) } ?: current.scale)
                    .coerceIn(Viewport.MIN_SCALE, Viewport.MAX_SCALE)
                current.copy(scale = scale).centeredOn(layout.centerX[i], layout.centerY(i), w, h)
            }
        }
        if (!animationsEnabled) {
            state.viewport = target
            return@LaunchedEffect
        }
        // Animación cuadro a cuadro; si el usuario mueve la vista a la mitad, se detiene.
        val start = withFrameNanos { it }
        var last = current
        while (true) {
            val t = withFrameNanos { now -> ((now - start) / CAMERA_ANIMATION_NANOS).toFloat() }.coerceIn(0f, 1f)
            if (state.viewport != last) break
            last = Viewport.interpolate(current, target, FastOutSlowInEasing.transform(t), w, h)
            state.viewport = last
            if (t >= 1f) break
        }
    }

    // Halo que "respira" sobre el nodo del recorrido.
    val pulse: Float = if (animationsEnabled && highlights.walkNode != null) {
        val transition = rememberInfiniteTransition()
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart)
        )
        value
    } else {
        0.35f
    }

    /** Rectángulo (del mundo) del chip "+N" / "−" de un nodo, o `null` si no lleva chip. */
    fun chipRect(i: Int): WorldRect? {
        val node = state.tree[i]
        if (!node.hasChildren) return null
        val collapsed = i in state.collapsed
        if (!collapsed && state.selected != i) return null
        val text = if (collapsed) "+${node.descendantCount}" else "−"
        val width = max(chipHeight, text.length * metrics.annotationCharWidth + 2 * chipPadding)
        val lay = currentLayout
        val x = lay.right(i)
        val y = lay.top[i]
        return WorldRect(x - width / 2f, y - chipHeight / 2f, x + width / 2f, y + chipHeight / 2f)
    }

    Canvas(
        modifier
            // El Canvas no recorta por sí mismo: sin esto, los nodos fuera de la vista se dibujan encima del editor,
            // de la barra de herramientas y de los paneles vecinos.
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val v = state.viewport ?: return@onKeyEvent false
                val w = viewSize.width.toFloat()
                val h = viewSize.height.toFloat()
                val panStep = with(density) { 60.dp.toPx() }
                val walking = state.walkMode != null
                when (event.key) {
                    Key.Plus, Key.Equals, Key.NumPadAdd -> { setViewport(v.zoomAt(KEY_ZOOM, w / 2f, h / 2f)); true }
                    Key.Minus, Key.NumPadSubtract -> { setViewport(v.zoomAt(1f / KEY_ZOOM, w / 2f, h / 2f)); true }
                    Key.Zero, Key.NumPad0 -> { state.requestFit(); true }
                    Key.DirectionLeft -> {
                        if (walking && !event.isShiftPressed) state.stepBack() else setViewport(v.panBy(panStep, 0f))
                        true
                    }
                    Key.DirectionRight -> {
                        if (walking && !event.isShiftPressed) state.stepForward() else setViewport(v.panBy(-panStep, 0f))
                        true
                    }
                    Key.DirectionUp -> { setViewport(v.panBy(0f, panStep)); true }
                    Key.DirectionDown -> { setViewport(v.panBy(0f, -panStep)); true }
                    Key.Spacebar -> { if (walking) state.togglePlay(); walking }
                    Key.Enter, Key.NumPadEnter -> {
                        val sel = state.selected
                        if (sel != null && state.tree[sel].hasChildren) {
                            state.toggle(sel, ScreenAnchor(sel, v.toScreenX(layout.centerX[sel]), v.toScreenY(layout.top[sel])))
                            true
                        } else {
                            false
                        }
                    }
                    Key.Escape -> {
                        if (state.selected != null) { currentOnTap(null); true } else false
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    runCatching { focusRequester.requestFocus() }
                    val v = state.viewport ?: return@detectTapGestures
                    val wx = v.toWorldX(position.x)
                    val wy = v.toWorldY(position.y)
                    val lay = currentLayout
                    // Primero los chips de plegado (están encima de los recuadros).
                    val chipHit = lay.visible.lastOrNull { i -> chipRect(i)?.inflate(4.dp.toPx() / v.scale)?.contains(wx, wy) == true }
                    if (chipHit != null) {
                        state.toggle(chipHit, ScreenAnchor(chipHit, v.toScreenX(lay.centerX[chipHit]), v.toScreenY(lay.top[chipHit])))
                        return@detectTapGestures
                    }
                    currentOnTap(lay.nodeAt(wx, wy, slop = 3.dp.toPx() / v.scale))
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = state.viewport ?: return@detectTransformGestures
                    var next = v.panBy(pan.x, pan.y)
                    if (zoom != 1f) next = next.zoomAt(zoom, centroid.x, centroid.y)
                    setViewport(next)
                }
            }
            .pointerInput(Unit) {
                // Rueda del ratón / panel táctil del escritorio.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val v = state.viewport ?: continue
                        val delta = change.scrollDelta
                        val mods = event.keyboardModifiers
                        if (mods.isCtrlPressed || mods.isMetaPressed) {
                            val factor = WHEEL_ZOOM.pow(-delta.y)
                            setViewport(v.zoomAt(factor, change.position.x, change.position.y))
                        } else {
                            val step = 40.dp.toPx()
                            val (dx, dy) = if (mods.isShiftPressed && delta.x == 0f) delta.y to 0f else delta.x to delta.y
                            setViewport(v.panBy(-dx * step, -dy * step))
                        }
                        change.consume()
                    }
                }
            }
    ) {
        val v = state.viewport ?: return@Canvas
        drawTree(
            state = state,
            layout = layout,
            metrics = metrics,
            textCache = textCache,
            palette = palette,
            highlights = highlights,
            viewport = v,
            pulse = pulse,
            chipRect = ::chipRect,
            chipPadding = chipPadding
        )
    }
}

private const val CAMERA_ANIMATION_NANOS = 380_000_000.0
private const val KEY_ZOOM = 1.2f
private const val WHEEL_ZOOM = 1.12f

/** Debajo de esta altura de letra en pantalla (px) se omiten los textos: no se leerían y saturan el dibujo. */
private const val MIN_READABLE_TEXT_PX = 5.5f

// ============================================================================ Dibujo

private fun DrawScope.drawTree(
    state: TreeViewerState,
    layout: TreeLayout,
    metrics: NodeMetrics,
    textCache: NodeTextCache,
    palette: TreePalette,
    highlights: TreeHighlights,
    viewport: Viewport,
    pulse: Float,
    chipRect: (Int) -> WorldRect?,
    chipPadding: Float
) {
    val tree = state.tree
    val z = viewport.scale
    val view = viewport.visibleWorld(size.width, size.height).inflate(40f / z)
    val hair = 1.2.dp.toPx() / z
    val thick = 2.4.dp.toPx() / z
    val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    val showText = metrics.titleLineHeight * z >= MIN_READABLE_TEXT_PX
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx() / z, 3.dp.toPx() / z))
    val revealed = highlights.revealed
    val walkColor = palette.walk(highlights.walkMode)
    val selected = state.selected

    fun dimmed(i: Int): Boolean = revealed != null && !revealed[i]

    withTransform({
        translate(viewport.offsetX, viewport.offsetY)
        scale(z, z, pivot = Offset.Zero)
    }) {
        // ---------------------------------------------------------- Aristas
        for (i in layout.visible) {
            val kids = layout.shownChildren[i]
            if (kids.isEmpty()) continue
            val px = layout.centerX[i]
            val startY = layout.edgeStartY(i)
            val busY = layout.rowBottom[i]
            var minX = px
            var maxX = px
            var maxY = busY
            for (c in kids) {
                minX = min(minX, layout.centerX[c])
                maxX = max(maxX, layout.centerX[c])
                maxY = max(maxY, layout.top[c])
            }
            if (!view.intersects(WorldRect(minX, startY, maxX, maxY))) continue
            val parentActive = i == highlights.walkNode
            val stemColor = if (dimmed(i)) palette.edge.copy(alpha = 0.25f) else palette.edge
            if (busY > startY) {
                drawLine(stemColor, Offset(px, startY), Offset(px, busY), strokeWidth = hair)
            }
            for (c in kids) {
                val active = parentActive && c in highlights.walkRelated
                val color = when {
                    active -> walkColor
                    dimmed(c) -> palette.edge.copy(alpha = 0.18f)
                    else -> palette.edge
                }
                drawLine(
                    color,
                    Offset(px, busY),
                    Offset(layout.centerX[c], layout.top[c]),
                    strokeWidth = if (active) thick else hair,
                    cap = StrokeCap.Round
                )
            }
        }

        // ---------------------------------------------------------- Conexiones IDn → nodo que calculó el valor
        for (i in layout.visible) {
            val ref = tree[i].referencedIndex
            if (ref < 0 || !layout.isVisible(ref)) continue
            val active = highlights.walkLinks.any { it.first == i && it.second == ref }
            drawReferenceLink(layout, i, ref, if (active) walkColor else palette.colors.intermediate.copy(alpha = 0.45f),
                if (active) thick else hair, dash)
        }

        // ---------------------------------------------------------- Nodos
        for (i in layout.visible) {
            val rect = layout.boxRect(i)
            val footprint = WorldRect(rect.left, rect.top, rect.right, layout.top[i] + layout.footprintHeight[i])
            if (!view.intersects(footprint.inflate(10f))) continue
            val node = tree[i]
            val alpha = if (dimmed(i)) 0.16f else 1f
            val accent = palette.accent(node.style)
            val topLeft = Offset(rect.left, rect.top)
            val boxSize = Size(rect.width, rect.height)
            val collapsed = i in state.collapsed

            // Halos (debajo del recuadro)
            if (i == highlights.walkNode) {
                val grow = (5f + 7f * pulse).dp.toPx() / z
                drawRoundRect(
                    color = walkColor.copy(alpha = 0.38f * (1f - pulse) + 0.12f),
                    topLeft = Offset(rect.left - grow, rect.top - grow),
                    size = Size(rect.width + 2 * grow, rect.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow, corner.y + grow)
                )
            }
            if (i == selected) {
                val grow = 4.dp.toPx() / z
                drawRoundRect(
                    color = palette.select.copy(alpha = 0.22f),
                    topLeft = Offset(rect.left - grow, rect.top - grow),
                    size = Size(rect.width + 2 * grow, rect.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow, corner.y + grow)
                )
            }
            if (i in highlights.searchMatches) {
                val grow = 3.dp.toPx() / z
                drawRoundRect(
                    color = palette.colors.findMatch.copy(alpha = if (i == highlights.searchCurrent) 0.9f else 0.55f),
                    topLeft = Offset(rect.left - grow, rect.top - grow),
                    size = Size(rect.width + 2 * grow, rect.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow, corner.y + grow)
                )
            }

            // Recuadro
            drawRoundRect(
                color = if (collapsed) palette.collapsedFill else palette.fill(node.style),
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = corner,
                alpha = alpha,
                style = Fill
            )
            val dashed = node.style == VisualNodeStyle.EPSILON || node.style == VisualNodeStyle.SYNTAX_ERROR || collapsed
            val borderColor = when {
                i == selected -> palette.select
                i == highlights.walkNode -> walkColor
                node.errors.isNotEmpty() -> palette.error(node.errors.first().type)
                else -> accent
            }
            val borderWidth = if (i == selected || i == highlights.walkNode || node.errors.isNotEmpty()) thick else hair
            drawRoundRect(
                color = borderColor,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = corner,
                alpha = alpha,
                style = Stroke(width = borderWidth, pathEffect = if (dashed && i != selected) dash else null)
            )
            if (i != highlights.walkNode && i in highlights.walkRelated) {
                val grow = 2.dp.toPx() / z
                drawRoundRect(
                    color = walkColor,
                    topLeft = Offset(rect.left - grow, rect.top - grow),
                    size = Size(rect.width + 2 * grow, rect.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow, corner.y + grow),
                    style = Stroke(width = hair * 1.4f, pathEffect = dash)
                )
            }

            if (showText) {
                // Renglones del recuadro
                var y = rect.top + metrics.paddingV
                for (line in node.lines.indices) {
                    val text = textCache.line(node, line)
                    val color = when {
                        line == 1 && node.tokenCategory != null -> palette.colors.syntax.forCategory(node.tokenCategory)
                        node.style == VisualNodeStyle.EPSILON -> palette.muted
                        else -> palette.text
                    }
                    drawText(
                        textLayoutResult = text,
                        color = color,
                        topLeft = Offset(rect.centerX - text.size.width / 2f, y),
                        alpha = alpha
                    )
                    y += if (line == 0) metrics.titleLineHeight else metrics.bodyLineHeight
                }
                // TD · valor bajo el recuadro
                if (node.annotation != null) {
                    val text = textCache.annotation(node)
                    drawText(
                        textLayoutResult = text,
                        color = if (node.style == VisualNodeStyle.SEMANTIC_ERROR) palette.error(ErrorPhaseType.SEMANTIC) else palette.value,
                        topLeft = Offset(rect.centerX - text.size.width / 2f, rect.bottom + metrics.annotationGap),
                        alpha = alpha
                    )
                }
            }

            // Paso de ejecución ① en la esquina superior izquierda
            if (node.badge != null) {
                val radius = max(8.dp.toPx(), metrics.annotationCharWidth * node.badge.length / 2f + 3.dp.toPx())
                val center = Offset(rect.left, rect.top)
                val badgeColor = if (i == highlights.walkNode) walkColor else palette.colors.intermediate
                drawCircle(badgeColor, radius = radius, center = center, alpha = alpha)
                if (showText) {
                    val text = textCache.badge(node)
                    drawText(
                        textLayoutResult = text,
                        color = palette.colors.onAccent.takeIf { palette.colors.isDark } ?: Color.White,
                        topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f),
                        alpha = alpha
                    )
                }
            }

            // Indicador de error (esquina inferior derecha)
            if (node.errors.isNotEmpty()) {
                val r = 5.dp.toPx()
                drawCircle(palette.error(node.errors.first().type), radius = r, center = Offset(rect.right, rect.bottom), alpha = alpha)
            }

            // Chip "+N" (plegado) o "−" (seleccionado con hijos)
            chipRect(i)?.let { chip ->
                drawRoundRect(
                    color = if (collapsed) palette.colors.syntactic.takeIf { tree.kind == TreeKind.SYNTACTIC }
                        ?: palette.colors.semantic else palette.select,
                    topLeft = Offset(chip.left, chip.top),
                    size = Size(chip.width, chip.height),
                    cornerRadius = CornerRadius(chip.height / 2f, chip.height / 2f),
                    alpha = alpha
                )
                if (showText) {
                    val label = if (collapsed) "+${node.descendantCount}" else "−"
                    val text = textCache.chip(label)
                    drawText(
                        textLayoutResult = text,
                        color = if (palette.colors.isDark) palette.colors.background else Color.White,
                        topLeft = Offset(chip.centerX - text.size.width / 2f, chip.centerY - text.size.height / 2f)
                    )
                }
            }
            if (node.cutByLimit && showText) {
                val text = textCache.chip("…")
                drawText(text, palette.muted, Offset(rect.centerX - text.size.width / 2f, rect.bottom + chipPadding))
            }
        }
    }
}

/** Curva punteada con flecha desde la hoja `IDn` hasta el nodo que calculó el valor que reutiliza. */
private fun DrawScope.drawReferenceLink(
    layout: TreeLayout,
    from: Int,
    to: Int,
    color: Color,
    width: Float,
    dash: PathEffect
) {
    val start = Offset(layout.centerX[from], layout.top[from])
    val end = Offset(layout.centerX[to], layout.bottom(to))
    val lift = max(abs(end.x - start.x) * 0.25f, 40f)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(start.x, start.y - lift, end.x, end.y + lift, end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = dash, cap = StrokeCap.Round))
    // Punta de flecha hacia el nodo de destino (la curva llega en vertical, desde abajo).
    val head = 7f * width
    val arrow = Path().apply {
        moveTo(end.x, end.y)
        lineTo(end.x - head * 0.6f, end.y + head)
        lineTo(end.x + head * 0.6f, end.y + head)
        close()
    }
    drawPath(arrow, color)
}

// ============================================================================ Minimapa

/**
 * Minimapa: todo el árbol en miniatura y el rectángulo de la vista actual. Tocar o arrastrar mueve la vista a ese
 * punto.
 */
@Composable
internal fun TreeMinimap(
    state: TreeViewerState,
    layout: TreeLayout,
    palette: TreePalette,
    mainViewSize: IntSize,
    modifier: Modifier = Modifier
) {
    val currentLayout by rememberUpdatedState(layout)
    val currentViewSize by rememberUpdatedState(mainViewSize)

    fun moveTo(position: Offset, width: Float, height: Float) {
        val v = state.viewport ?: return
        val map = minimapTransform(currentLayout.bounds, width, height)
        val wx = (position.x - map.offsetX) / map.scale
        val wy = (position.y - map.offsetY) / map.scale
        state.viewport = v.centeredOn(wx, wy, currentViewSize.width.toFloat(), currentViewSize.height.toFloat())
    }

    // El dibujo vive en drawWithCache (los recuadros se arman una vez por layout), así que basta un Box.
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { moveTo(it, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    moveTo(change.position, size.width.toFloat(), size.height.toFloat())
                }
            }
            .drawWithCache {
                // Los recuadros se arman una vez por layout; en cada cuadro solo se dibuja el rectángulo de la vista.
                val map = minimapTransform(layout.bounds, size.width, size.height)
                val nodes = Path()
                val stride = max(1, layout.visible.size / MAX_MINIMAP_NODES)
                for (k in layout.visible.indices step stride) {
                    val i = layout.visible[k]
                    val l = map.toScreenX(layout.left(i))
                    val t = map.toScreenY(layout.top[i])
                    val w = max(layout.boxWidth[i] * map.scale, 1.5f)
                    val h = max(layout.boxHeight[i] * map.scale, 1.5f)
                    nodes.addRect(androidx.compose.ui.geometry.Rect(l, t, l + w, t + h))
                }
                onDrawBehind {
                    drawPath(nodes, palette.colors.syntactic.takeIf { state.kind == TreeKind.SYNTACTIC }
                        ?: palette.colors.semantic, alpha = 0.7f)
                    val v = state.viewport ?: return@onDrawBehind
                    val visible = v.visibleWorld(mainViewSize.width.toFloat(), mainViewSize.height.toFloat())
                    val l = map.toScreenX(visible.left).coerceIn(0f, size.width)
                    val t = map.toScreenY(visible.top).coerceIn(0f, size.height)
                    val r = map.toScreenX(visible.right).coerceIn(0f, size.width)
                    val b = map.toScreenY(visible.bottom).coerceIn(0f, size.height)
                    if (r > l && b > t) {
                        drawRect(palette.select.copy(alpha = 0.14f), Offset(l, t), Size(r - l, b - t))
                        drawRect(palette.select, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
    )
}

private fun minimapTransform(bounds: WorldRect, width: Float, height: Float): Viewport {
    val pad = 6f
    val scale = min((width - 2 * pad) / max(bounds.width, 1f), (height - 2 * pad) / max(bounds.height, 1f))
    val offsetX = width / 2f - bounds.centerX * scale
    val offsetY = height / 2f - bounds.centerY * scale
    return Viewport(scale, offsetX, offsetY)
}

private const val MAX_MINIMAP_NODES = 6_000