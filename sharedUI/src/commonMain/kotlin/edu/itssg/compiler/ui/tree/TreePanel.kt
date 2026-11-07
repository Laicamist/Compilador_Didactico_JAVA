package edu.itssg.compiler.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.ui.state.TreeKind
import edu.itssg.compiler.ui.tables.rememberTextClipboard
import edu.itssg.compiler.ui.theme.JAutomataColors
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Paso 3.4: pestaña Árbol del panel de resultados.
 *
 * Selector Sintáctico / Abstracto-semántico, Canvas con zoom y desplazamiento, nodos plegables, color por tipo con
 * leyenda, minimapa, búsqueda, tarjeta del nodo seleccionado, pantalla completa y el recorrido animado (Derivación,
 * Cuádruplos y 3 Direcciones). Tocar un nodo resalta en el editor el fragmento de código que deriva.
 *
 * @param semanticAvailable La fase Semántica ya se muestra (en Paso a paso puede no haberse alcanzado).
 * @param executionBlockedReason Motivo por el que no se puede recorrer la ejecución (errores o Paso a paso antes de
 *   Código intermedio), o `null` si se puede.
 * @param onHighlight Resalta un fragmento en el editor sin quitarle el foco al visor (`null` = quitar el resaltado).
 * @param onRevealInCode Lleva al editor y selecciona el fragmento (botón "Ver en el código").
 */
@Composable
fun TreePanel(
    report: CompilationReport,
    registry: TreeViewerRegistry,
    treeKind: TreeKind,
    onTreeKindChange: (TreeKind) -> Unit,
    semanticAvailable: Boolean,
    executionBlockedReason: String?,
    isStale: Boolean,
    animationsEnabled: Boolean,
    onHighlight: (SourceSpan?) -> Unit,
    onRevealInCode: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = if (treeKind == TreeKind.SEMANTIC && !semanticAvailable) TreeKind.SYNTACTIC else treeKind
    val state = remember(report, kind) { registry.stateFor(report, kind) }
    var fullscreen by remember { mutableStateOf(false) }
    val currentOnHighlight by rememberUpdatedState(onHighlight)

    // Al volver a la pestaña (o cambiar de árbol) se vuelve a marcar en el editor lo que estaba elegido.
    LaunchedEffect(state) {
        val span = state.currentStep?.span ?: state.selected?.let { state.tree[it].span }
        if (span != null) currentOnHighlight(span)
    }

    // Un recorrido de ejecución que quedó bloqueado (p. ej. al regresar en Paso a paso) se cierra.
    LaunchedEffect(state, executionBlockedReason) {
        if (executionBlockedReason != null && state.walkMode != null && state.walkMode != WalkthroughMode.DERIVATION) {
            state.stopWalkthrough()
        }
    }

    // Reproducción automática (una sola vez aunque el visor esté en pantalla completa).
    LaunchedEffect(state, state.isPlaying, state.walkStepIndex, state.speed, state.walkMode) {
        if (!state.isPlaying) return@LaunchedEffect
        delay((STEP_MILLIS / state.speed).toLong())
        state.stepForward()
    }

    // Cada paso del recorrido ilumina a la vez el nodo, su renglón y el fragmento del código.
    LaunchedEffect(state, state.walkMode, state.walkStepIndex) {
        val step = state.currentStep ?: return@LaunchedEffect
        step.nodeIndex?.let { node ->
            state.reveal(node, includeChildren = true)
            state.requestFocus(node, minScale = TreeViewerState.READABLE_SCALE * 0.85f, onlyIfHidden = true)
        }
        currentOnHighlight(step.span)
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        val target = state.currentStep?.nodeIndex ?: state.selected
        if (target != null) state.requestFocus(target, onlyIfHidden = true) else state.requestFit()
    }

    val body: @Composable (Modifier, Boolean) -> Unit = { bodyModifier, isFullscreen ->
        TreeViewerBody(
            state = state,
            report = report,
            semanticAvailable = semanticAvailable,
            onTreeKindChange = onTreeKindChange,
            executionBlockedReason = executionBlockedReason,
            isStale = isStale,
            animationsEnabled = animationsEnabled,
            isFullscreen = isFullscreen,
            onToggleFullscreen = ::toggleFullscreen,
            onHighlight = onHighlight,
            onRevealInCode = { span ->
                if (fullscreen) fullscreen = false
                onRevealInCode(span)
            },
            onMessage = onMessage,
            modifier = bodyModifier
        )
    }

    if (fullscreen) {
        FullscreenPlaceholder(onExit = ::toggleFullscreen, modifier = modifier)
        Dialog(
            onDismissRequest = ::toggleFullscreen,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            body(Modifier.fillMaxSize().background(JAutomataTheme.colors.background), true)
        }
    } else {
        body(modifier, false)
    }
}

/** Tiempo base entre pasos del recorrido (a velocidad 1×). */
private const val STEP_MILLIS = 1_600.0
private val SPEEDS = listOf(0.5f, 1f, 2f, 4f)

@Composable
private fun TreeViewerBody(
    state: TreeViewerState,
    report: CompilationReport,
    semanticAvailable: Boolean,
    onTreeKindChange: (TreeKind) -> Unit,
    executionBlockedReason: String?,
    isStale: Boolean,
    animationsEnabled: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onHighlight: (SourceSpan?) -> Unit,
    onRevealInCode: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier
) {
    val colors = JAutomataTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val styles = remember { nodeTextStyles() }
    val metrics = remember(density, measurer) { measureNodeMetrics(measurer, styles, density) }
    val textCache = remember(state.tree, measurer, density) { NodeTextCache(measurer, styles) }
    val palette = remember(colors) { TreePalette(colors) }
    val tree = state.tree
    val layout = remember(tree, state.collapsed, state.hideEpsilon, metrics) {
        computeTreeLayout(tree, state.collapsed, state.hideEpsilon, metrics)
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val walk = state.currentWalkthrough
    val step = state.currentStep
    val highlights = remember(state.walkMode, state.walkStepIndex, state.searchMatches, state.searchCursor, walk) {
        TreeHighlights(
            walkNode = step?.nodeIndex,
            walkRelated = step?.relatedIndices?.toSet().orEmpty(),
            walkLinks = step?.links.orEmpty(),
            walkMode = state.walkMode,
            revealed = if (walk != null && walk.mode == WalkthroughMode.DERIVATION) {
                WalkthroughBuilder.derivationRevealed(tree, walk, state.walkStepIndex)
            } else {
                null
            },
            searchMatches = state.searchMatches.toSet(),
            searchCurrent = state.searchMatches.getOrNull(state.searchCursor)
        )
    }

    fun selectNode(index: Int?) {
        state.select(index)
        onHighlight(index?.let { tree[it].span })
    }

    Column(modifier.background(colors.surface1)) {
        TreeToolbar(
            state = state,
            semanticAvailable = semanticAvailable,
            onTreeKindChange = onTreeKindChange,
            canvasSize = canvasSize,
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onMessage = onMessage
        )
        HorizontalDivider(color = colors.border)
        TreeNotices(state, report, isStale)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideList = walk != null && maxWidth >= SIDE_LIST_MIN_WIDTH
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { canvasSize = it }) {
                    TreeCanvas(
                        state = state,
                        layout = layout,
                        metrics = metrics,
                        textCache = textCache,
                        palette = palette,
                        highlights = highlights,
                        animationsEnabled = animationsEnabled,
                        onNodeTap = ::selectNode,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (state.showLegend) {
                        TreeLegend(tree, palette, Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                    if (state.showMinimap && layout.visible.size > 1) {
                        TreeMinimap(
                            state = state,
                            layout = layout,
                            palette = palette,
                            mainViewSize = canvasSize,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(MINIMAP_WIDTH, MINIMAP_HEIGHT)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface2.copy(alpha = 0.92f))
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        )
                    }
                    state.selected?.takeIf { it < tree.size && layout.isVisible(it) }?.let { selected ->
                        NodeDetailCard(
                            node = tree[selected],
                            tree = tree,
                            source = report.sourceCode,
                            isCollapsed = state.isCollapsed(selected),
                            onClose = { selectNode(null) },
                            onToggle = { state.toggle(selected) },
                            onCenter = { state.requestFocus(selected, minScale = TreeViewerState.READABLE_SCALE) },
                            onRevealInCode = { onRevealInCode(tree[selected].span) },
                            onSelect = { index ->
                                state.reveal(index)
                                selectNode(index)
                                state.requestFocus(index, onlyIfHidden = true)
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .widthIn(max = 300.dp)
                                .heightIn(max = (this@BoxWithConstraints.maxHeight * 0.7f).coerceAtLeast(160.dp))
                        )
                    }
                    if (state.selected == null && walk == null) {
                        HintText(
                            text = "Arrastra para mover · Ctrl + rueda o pellizca para acercar · toca un nodo para ver su código",
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        )
                    }
                }
                if (sideList && walk != null) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                    WalkLinesList(
                        walk = walk,
                        current = state.walkStepIndex,
                        color = palette.walk(walk.mode),
                        onSelect = state::goToStep,
                        modifier = Modifier.width(SIDE_LIST_WIDTH).fillMaxHeight()
                    )
                }
            }
        }
        HorizontalDivider(color = colors.border)
        WalkthroughBar(
            state = state,
            report = report,
            palette = palette,
            executionBlockedReason = executionBlockedReason,
            onStop = {
                state.stopWalkthrough()
                onHighlight(state.selected?.let { tree[it].span })
            }
        )
    }
}

private val SIDE_LIST_MIN_WIDTH = 700.dp
private val SIDE_LIST_WIDTH = 250.dp
private val MINIMAP_WIDTH = 168.dp
private val MINIMAP_HEIGHT = 112.dp

// ============================================================================ Barra de herramientas

@Composable
private fun TreeToolbar(
    state: TreeViewerState,
    semanticAvailable: Boolean,
    onTreeKindChange: (TreeKind) -> Unit,
    canvasSize: IntSize,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onMessage: (String, Boolean) -> Unit
) {
    val colors = JAutomataTheme.colors
    val tree = state.tree
    val clipboard = rememberTextClipboard()
    var menuOpen by remember { mutableStateOf(false) }

    fun zoomBy(factor: Float) {
        val v = state.viewport ?: return
        state.viewport = v.zoomAt(factor, canvasSize.width / 2f, canvasSize.height / 2f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TreeKindSelector(state.kind, semanticAvailable, onTreeKindChange)
        Column {
            Text(
                "${tree.totalNodes} nodos · ${tree.maxDepth + 1} niveles",
                fontSize = 11.5.sp,
                color = colors.textSecondary,
                maxLines = 1
            )
            if (tree.errorCount > 0) {
                Text(
                    "${tree.errorCount} ${if (tree.errorCount == 1) "nodo con error" else "nodos con error"}",
                    fontSize = 10.5.sp,
                    color = if (state.kind == TreeKind.SYNTACTIC) colors.errorSyntactic else colors.errorSemantic,
                    maxLines = 1
                )
            }
        }
        NodeSearchField(state)
        ToolbarGroup {
            ToolButton("−", "Alejar") { zoomBy(1f / 1.25f) }
            ZoomLabel(state) {
                val v = state.viewport ?: return@ZoomLabel
                state.viewport = v.zoomAt(1f / v.scale, canvasSize.width / 2f, canvasSize.height / 2f)
            }
            ToolButton("+", "Acercar") { zoomBy(1.25f) }
        }
        ToolButton("⤢ Ajustar", "Encuadrar todo el árbol (0)") { state.requestFit() }
        Box {
            ToolButton("Opciones ▾", "Plegar, ε, minimapa, leyenda, copiar") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem("Expandir todo") { state.expandAll(); menuOpen = false }
                MenuItem("Plegar todo") { state.collapseAll(); menuOpen = false }
                for (level in listOf(2, 3, 4, 6)) {
                    if (level <= tree.maxDepth) MenuItem("Mostrar hasta el nivel $level") { state.collapseToLevel(level); menuOpen = false }
                }
                if (state.kind == TreeKind.SYNTACTIC) {
                    HorizontalDivider(color = colors.border)
                    MenuItem(if (state.hideEpsilon) "Mostrar las ε" else "Ocultar las ε (producciones vacías)") {
                        state.setEpsilonHidden(!state.hideEpsilon)
                        menuOpen = false
                    }
                }
                HorizontalDivider(color = colors.border)
                MenuItem(if (state.showMinimap) "Ocultar minimapa" else "Mostrar minimapa") {
                    state.showMinimap = !state.showMinimap
                    menuOpen = false
                }
                MenuItem(if (state.showLegend) "Ocultar leyenda" else "Mostrar leyenda") {
                    state.showLegend = !state.showLegend
                    menuOpen = false
                }
                HorizontalDivider(color = colors.border)
                MenuItem("Copiar el árbol como texto") {
                    val ok = clipboard.copy(tree.toIndentedText())
                    onMessage(if (ok) "Se copió el árbol ${state.kind.displayName.lowercase()} como texto." else "No se pudo copiar al portapapeles.", !ok)
                    menuOpen = false
                }
            }
        }
        ToolButton(
            if (isFullscreen) "✕ Salir de pantalla completa" else "⛶ Pantalla completa",
            "Pantalla completa (Esc para salir)",
            emphasized = isFullscreen,
            onClick = onToggleFullscreen
        )
    }
}

@Composable
private fun TreeKindSelector(selected: TreeKind, semanticAvailable: Boolean, onSelect: (TreeKind) -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(1.dp, colors.border, shape).background(colors.surface3)) {
        TreeKind.entries.forEach { kind ->
            val enabled = kind == TreeKind.SYNTACTIC || semanticAvailable
            val isSelected = kind == selected
            val tint = if (kind == TreeKind.SYNTACTIC) colors.syntactic else colors.semantic
            Text(
                text = kind.displayName,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> if (colors.isDark) colors.background else Color.White
                    enabled -> colors.textSecondary
                    else -> colors.textMuted
                },
                maxLines = 1,
                modifier = Modifier
                    .background(if (isSelected) tint else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(kind) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

/** Porcentaje de zoom; solo esta pieza se recompone mientras se acerca o aleja. Tocarlo vuelve a 100 %. */
@Composable
private fun ZoomLabel(state: TreeViewerState, onReset: () -> Unit) {
    val colors = JAutomataTheme.colors
    val percent by remember(state) { derivedStateOf { ((state.viewport?.scale ?: 1f) * 100f).roundToInt() } }
    Text(
        "$percent %",
        fontSize = 12.sp,
        fontFamily = JAutomataFonts.code,
        color = colors.textPrimary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onReset).padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun NodeSearchField(state: TreeViewerState) {
    val colors = JAutomataTheme.colors
    val query = state.searchQuery
    val matches = state.searchMatches
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.surface3)
            .border(1.dp, colors.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("⌕", fontSize = 13.sp, color = colors.textMuted)
        Box(Modifier.width(130.dp)) {
            if (query.isEmpty()) Text("Buscar nodo…", fontSize = 12.5.sp, color = colors.textMuted, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = { state.updateSearch(it.replace("\n", "")) },
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.5.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        state.nextMatch(forward = !event.isShiftPressed)
                        true
                    } else {
                        false
                    }
                }
            )
        }
        if (query.isNotEmpty()) {
            Text(
                when {
                    matches.isEmpty() -> "0"
                    state.searchCursor < 0 -> "${matches.size}"
                    else -> "${state.searchCursor + 1}/${matches.size}"
                },
                fontSize = 11.sp,
                color = if (matches.isEmpty()) colors.errorLexical else colors.textSecondary,
                maxLines = 1
            )
            SmallIcon("▲", enabled = matches.isNotEmpty()) { state.nextMatch(forward = false) }
            SmallIcon("▼", enabled = matches.isNotEmpty()) { state.nextMatch(forward = true) }
            SmallIcon("✕") { state.updateSearch("") }
        }
    }
}

@Composable
private fun ToolbarGroup(content: @Composable () -> Unit) {
    val colors = JAutomataTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, colors.border, RoundedCornerShape(7.dp)).background(colors.surface3),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun ToolButton(text: String, description: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (emphasized) colors.accent else colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun SmallIcon(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    Text(
        text,
        fontSize = 11.sp,
        color = if (enabled) colors.textSecondary else colors.textMuted,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 3.dp)
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = onClick)
}

// ============================================================================ Avisos, leyenda y tarjeta del nodo

@Composable
private fun TreeNotices(state: TreeViewerState, report: CompilationReport, isStale: Boolean) {
    val colors = JAutomataTheme.colors
    val tree = state.tree
    val notices = buildList {
        if (isStale) add("El código cambió desde la última compilación: el árbol corresponde a la versión compilada (F5 para actualizar)." to colors.intermediate)
        if (tree.isTruncated) add("El árbol tiene ${tree.totalNodes} nodos; el visor dibuja los primeros ${tree.size}." to colors.textSecondary)
        if (state.kind == TreeKind.SYNTACTIC && report.syntacticErrorCount > 0) {
            add("Los nodos ERROR marcan el token que faltó y las reglas en naranja se recuperaron en modo pánico." to colors.errorSyntactic)
        }
        if (state.kind == TreeKind.SEMANTIC && report.syntacticErrorCount > 0) {
            add("Hubo errores sintácticos: el árbol abstracto-semántico puede estar incompleto." to colors.errorSyntactic)
        }
    }
    notices.take(2).forEach { (text, color) ->
        Text(
            text = text,
            fontSize = 12.sp,
            color = color,
            modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.09f)).padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TreeLegend(tree: VisualTree, palette: TreePalette, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    val styles = if (tree.kind == TreeKind.SYNTACTIC) {
        listOf(VisualNodeStyle.NON_TERMINAL, VisualNodeStyle.TERMINAL, VisualNodeStyle.EPSILON, VisualNodeStyle.SYNTAX_ERROR, VisualNodeStyle.RECOVERED)
    } else {
        listOf(
            VisualNodeStyle.SEMANTIC_ROOT, VisualNodeStyle.SEMANTIC_OPERATION, VisualNodeStyle.SEMANTIC_IDENTIFIER,
            VisualNodeStyle.SEMANTIC_LITERAL, VisualNodeStyle.SEMANTIC_ERROR
        )
    }
    val present = remember(tree) { tree.nodes.mapTo(HashSet()) { it.style } }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2.copy(alpha = 0.92f))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        styles.filter { it in present || it == styles.first() || it == styles[1] }.forEach { style ->
            LegendRow(palette.fill(style), palette.accent(style), style.displayName, dashed = style == VisualNodeStyle.EPSILON)
        }
        LegendRow(palette.collapsedFill, if (tree.kind == TreeKind.SYNTACTIC) colors.syntactic else colors.semantic, "Plegado (+N nodos)", dashed = true)
        if (tree.kind == TreeKind.SEMANTIC) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(colors.intermediate))
                Text("Paso de ejecución = renglón del CI", fontSize = 10.5.sp, color = colors.textSecondary)
            }
            Text("TD · valor bajo cada nodo", fontSize = 10.5.sp, color = palette.value)
        }
    }
}

@Composable
private fun LegendRow(fill: Color, border: Color, label: String, dashed: Boolean) {
    val colors = JAutomataTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(width = 18.dp, height = 11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill)
                .border(if (dashed) 1.dp else 1.5.dp, border.copy(alpha = if (dashed) 0.7f else 1f), RoundedCornerShape(3.dp))
        )
        Text(label, fontSize = 10.5.sp, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    Text(
        text,
        fontSize = 10.5.sp,
        color = colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface1.copy(alpha = 0.75f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun NodeDetailCard(
    node: VisualNode,
    tree: VisualTree,
    source: String,
    isCollapsed: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onCenter: () -> Unit,
    onRevealInCode: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val palette = remember(colors) { TreePalette(colors) }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(palette.accent(node.style)))
            Text(
                node.lines.joinToString(" "),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = JAutomataFonts.code,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            SmallIcon("✕", onClick = onClose)
        }
        Text(
            buildString {
                append(node.style.displayName)
                if (node.badge != null) append(" · paso ").append(node.badge)
                append(" · línea ").append(node.span.start.line).append(", col ").append(node.span.start.column)
            },
            fontSize = 11.5.sp,
            color = colors.textSecondary
        )
        node.details.forEach { detail ->
            Column {
                Text(detail.label, fontSize = 10.5.sp, color = colors.textMuted)
                Text(detail.value, fontSize = 12.sp, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
            }
        }
        node.annotation?.let {
            Column {
                Text("TD · valor", fontSize = 10.5.sp, color = colors.textMuted)
                Text(it, fontSize = 12.sp, fontFamily = JAutomataFonts.code, color = palette.value)
            }
        }
        node.errors.forEach { error ->
            val color = colors.error(error.type)
            Text(
                "Error ${error.type.displayName.lowercase()} (línea ${error.line}): ${error.justification}",
                fontSize = 11.5.sp,
                color = color,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.1f)).padding(6.dp)
            )
        }
        sourceFragment(source, node.span)?.let { fragment ->
            Column {
                Text("Código que deriva", fontSize = 10.5.sp, color = colors.textMuted)
                Text(
                    fragment,
                    fontSize = 12.sp,
                    fontFamily = JAutomataFonts.code,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.editorBackground).padding(6.dp)
                )
            }
        }
        if (node.referencedIndex >= 0) {
            val referenced = tree[node.referencedIndex]
            Text(
                "→ Ir al nodo que calculó el valor (${referenced.lines.joinToString(" ")})",
                fontSize = 12.sp,
                color = colors.intermediate,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onSelect(referenced.index) }.padding(vertical = 2.dp)
            )
        }
        if (node.parent >= 0) {
            Text(
                "↑ Padre: ${tree[node.parent].lines.joinToString(" ")}",
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onSelect(node.parent) }.padding(vertical = 2.dp)
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton("Ver en el código", "Seleccionar el fragmento en el editor", emphasized = true, onClick = onRevealInCode)
            if (node.hasChildren) {
                ToolButton(if (isCollapsed) "Desplegar" else "Plegar", "Plegar o desplegar el nodo (Enter)", onClick = onToggle)
            }
            ToolButton("Centrar", "Centrar el nodo en la vista", onClick = onCenter)
        }
    }
}

/** Texto del código que corresponde al fragmento (en una línea y recortado). */
private fun sourceFragment(source: String, span: SourceSpan): String? {
    val start = span.start.offset.coerceIn(0, source.length)
    val end = span.end.offset.coerceIn(start, source.length)
    if (end <= start) return null
    val raw = source.substring(start, minOf(end, start + MAX_FRAGMENT_CHARS))
    val text = raw.replace("\r", "").replace('\n', ' ').replace('\t', ' ').replace(Regex(" {2,}"), " ").trim()
    return if (end - start > MAX_FRAGMENT_CHARS) "$text…" else text
}

private const val MAX_FRAGMENT_CHARS = 180

@Composable
private fun FullscreenPlaceholder(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    Box(modifier.background(colors.surface1).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("El árbol está en pantalla completa", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
            OutlinedButton(onClick = onExit) { Text("Salir de pantalla completa") }
        }
    }
}

// ============================================================================ Recorrido animado

@Composable
private fun WalkthroughBar(
    state: TreeViewerState,
    report: CompilationReport,
    palette: TreePalette,
    executionBlockedReason: String?,
    onStop: () -> Unit
) {
    val colors = JAutomataTheme.colors
    val walk = state.currentWalkthrough
    if (walk == null) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface2).horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recorrido animado", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            if (state.kind == TreeKind.SYNTACTIC) {
                StartButton("▶ Derivación", palette.walk(WalkthroughMode.DERIVATION)) {
                    state.startWalkthrough(WalkthroughMode.DERIVATION, autoplay = true)
                }
                Text("La GLC v7 regla por regla (derivación por la izquierda).", fontSize = 11.5.sp, color = colors.textSecondary, maxLines = 1)
            } else if (executionBlockedReason != null) {
                Text(executionBlockedReason, fontSize = 11.5.sp, color = colors.textMuted, maxLines = 2)
            } else {
                StartButton("▶ Cuádruplos", colors.intermediate) { state.startWalkthrough(WalkthroughMode.QUADRUPLES, autoplay = true) }
                StartButton("▶ 3 Direcciones", colors.intermediate) { state.startWalkthrough(WalkthroughMode.THREE_ADDRESS, autoplay = true) }
                Text("Ejecución: de abajo hacia arriba y de izquierda a derecha.", fontSize = 11.5.sp, color = colors.textSecondary, maxLines = 1)
            }
        }
        return
    }

    val color = palette.walk(walk.mode)
    val step = state.currentStep
    val total = walk.steps.size
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.07f))) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (walk.mode == WalkthroughMode.DERIVATION) {
                Text("Derivación", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color)
            } else {
                ModeSelector(walk.mode, color) { state.startWalkthrough(it, autoplay = state.isPlaying) }
            }
            Spacer(Modifier.width(6.dp))
            PlayerButton("⏮", "Primer paso", enabled = state.walkStepIndex > 0) { state.goToStep(0) }
            PlayerButton("◀", "Paso anterior (←)", enabled = state.walkStepIndex > 0) { state.stepBack() }
            PlayerButton(if (state.isPlaying) "⏸" else "▶", if (state.isPlaying) "Pausa (Espacio)" else "Reproducir (Espacio)", emphasized = true, color = color) {
                state.togglePlay()
            }
            PlayerButton("▶|", "Paso siguiente (→)", enabled = state.walkStepIndex < total - 1) { state.stepForward() }
            PlayerButton("⏭", "Último paso", enabled = state.walkStepIndex < total - 1) { state.goToStep(total - 1) }
            Text(
                if (total == 0) "Sin pasos" else "Paso ${state.walkStepIndex + 1} de $total",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            SPEEDS.forEach { speed ->
                val selected = state.speed == speed
                Text(
                    "${if (speed < 1f) "0.5" else speed.toInt().toString()}×",
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) color else colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.speed = speed }.padding(horizontal = 5.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            PlayerButton("✕ Cerrar", "Terminar el recorrido", onClick = onStop)
        }
        if (total > 1) {
            Slider(
                value = state.walkStepIndex.toFloat(),
                onValueChange = {
                    state.isPlaying = false
                    state.goToStep(it.roundToInt())
                },
                valueRange = 0f..(total - 1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = colors.borderStrong),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 32.dp)
            )
        }
        if (step != null) {
            StepCard(state, walk, step, report, color)
        }
    }
}

@Composable
private fun StepCard(state: TreeViewerState, walk: Walkthrough, step: WalkStep, report: CompilationReport, color: Color) {
    val colors = JAutomataTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(step.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = JAutomataFonts.code, color = color)
        Text(step.explanation, fontSize = 12.5.sp, color = colors.textPrimary, lineHeight = 17.sp)
        when (walk.mode) {
            WalkthroughMode.DERIVATION -> {
                val form = remember(walk, state.walkStepIndex) {
                    WalkthroughBuilder.sententialForm(
                        state.tree,
                        WalkthroughBuilder.derivationRevealed(state.tree, walk, state.walkStepIndex),
                        step.nodeIndex
                    )
                }
                SententialFormText(form, colors, color)
            }
            WalkthroughMode.THREE_ADDRESS -> RegisterTable(step, color)
            WalkthroughMode.QUADRUPLES -> Unit
        }
        step.span?.let { span ->
            sourceFragment(report.sourceCode, span)?.let { fragment ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Código:", fontSize = 11.sp, color = colors.textMuted)
                    Text(fragment, fontSize = 11.5.sp, fontFamily = JAutomataFonts.code, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Forma sentencial alrededor de lo que acaba de derivarse (los símbolos nuevos resaltados). */
@Composable
private fun SententialFormText(form: List<WalkthroughBuilder.FormSymbol>, colors: JAutomataColors, color: Color) {
    if (form.isEmpty()) return
    val firstNew = form.indexOfFirst { it.isNew }.let { if (it < 0) 0 else it }
    val from = (firstNew - FORM_CONTEXT).coerceAtLeast(0)
    val to = (firstNew + FORM_WINDOW).coerceAtMost(form.size)
    val text: AnnotatedString = buildAnnotatedString {
        append("⇒ ")
        if (from > 0) append("… ")
        for (k in from until to) {
            val symbol = form[k]
            val style = when {
                symbol.isNew -> SpanStyle(color = color, fontWeight = FontWeight.Bold)
                symbol.isError -> SpanStyle(color = colors.errorSyntactic)
                symbol.isNonTerminal -> SpanStyle(color = colors.syntactic)
                else -> SpanStyle(color = colors.textSecondary)
            }
            withStyle(style) { append(symbol.text) }
            append(' ')
        }
        if (to < form.size) append("…")
    }
    Text(text, fontSize = 12.sp, fontFamily = JAutomataFonts.code, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private const val FORM_CONTEXT = 8
private const val FORM_WINDOW = 28

/** Mini tabla de temporales de Tres Direcciones (ocupado por qué paso / libre) y operaciones pendientes. */
@Composable
private fun RegisterTable(step: WalkStep, color: Color) {
    val colors = JAutomataTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        step.registers.forEach { cell ->
            val busy = cell.holderStep != null
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (cell.changed) color.copy(alpha = 0.22f) else colors.surface3)
                    .border(1.dp, if (cell.changed) color else colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(cell.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
                Text(
                    if (busy) "paso ${cell.holderStep}" else "libre",
                    fontSize = 11.5.sp,
                    color = if (busy) colors.textPrimary else colors.textMuted
                )
            }
        }
        if (step.pendingSteps.isNotEmpty()) {
            Text(
                "Pendientes: " + step.pendingSteps.joinToString(", ") { "P$it" },
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.errorSyntactic
            )
        }
    }
}

@Composable
private fun WalkLinesList(
    walk: Walkthrough,
    current: Int,
    color: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val listState = rememberLazyListState()
    val currentLine = walk.steps.getOrNull(current)?.lineIndex ?: 0
    val headerItems = if (walk.header != null) 1 else 0
    LaunchedEffect(walk, currentLine) {
        listState.animateScrollToItem((currentLine + headerItems - 3).coerceAtLeast(0))
    }
    // Paso que corresponde a cada renglón (para ir a él con un clic).
    val stepOfLine = remember(walk) {
        HashMap<Int, Int>().also { map -> walk.steps.forEachIndexed { i, s -> map.getOrPut(s.lineIndex) { i } } }
    }
    Column(modifier.background(colors.surface1)) {
        Text(
            when (walk.mode) {
                WalkthroughMode.DERIVATION -> "Producciones aplicadas"
                WalkthroughMode.QUADRUPLES -> "Cuádruplos"
                WalkthroughMode.THREE_ADDRESS -> "Tres Direcciones"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = JAutomataFonts.title,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(horizontal = 10.dp, vertical = 6.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            walk.header?.let { header ->
                item { CodeLine("", header, null, active = false, color = color, onClick = null) }
            }
            itemsIndexed(walk.lines) { index, line ->
                CodeLine(
                    number = line.number,
                    text = line.text,
                    note = line.note,
                    active = index == currentLine,
                    color = color,
                    onClick = stepOfLine[index]?.let { s -> { onSelect(s) } }
                )
            }
            walk.closing?.let { closing ->
                item { CodeLine("", closing, null, active = false, color = color, onClick = null) }
            }
        }
    }
}

@Composable
private fun CodeLine(number: String, text: String, note: String?, active: Boolean, color: Color, onClick: (() -> Unit)?) {
    val colors = JAutomataTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) color.copy(alpha = 0.18f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 16.dp).background(if (active) color else Color.Transparent))
        Text(number, fontSize = 11.5.sp, fontFamily = JAutomataFonts.code, color = colors.textMuted, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                fontSize = 11.5.sp,
                fontFamily = JAutomataFonts.code,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = colors.textPrimary
            )
            if (note != null) Text("→ $note", fontSize = 10.5.sp, fontFamily = JAutomataFonts.code, color = colors.textMuted)
        }
    }
}

@Composable
private fun ModeSelector(selected: WalkthroughMode, color: Color, onSelect: (WalkthroughMode) -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(7.dp)
    Row(Modifier.clip(shape).border(1.dp, colors.border, shape).background(colors.surface3)) {
        listOf(WalkthroughMode.QUADRUPLES, WalkthroughMode.THREE_ADDRESS).forEach { mode ->
            val isSelected = mode == selected
            Text(
                mode.displayName,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) (if (colors.isDark) colors.background else Color.White) else colors.textSecondary,
                modifier = Modifier
                    .background(if (isSelected) color else Color.Transparent)
                    .clickable(enabled = !isSelected) { onSelect(mode) }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PlayerButton(
    text: String,
    description: String,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit
) {
    val colors = JAutomataTheme.colors
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = when {
            !enabled -> colors.textMuted
            emphasized && color != null -> if (colors.isDark) colors.background else Color.White
            else -> colors.textPrimary
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasized && color != null) color else Color.Transparent)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun StartButton(text: String, color: Color, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (colors.isDark) colors.background else Color.White),
        contentPadding = ButtonDefaults.ContentPadding
    ) { Text(text, fontSize = 12.5.sp) }
}