package edu.itssg.compiler.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.ui.data.AppSettings
import edu.itssg.compiler.ui.state.EditorFocusRequest
import edu.itssg.compiler.ui.theme.JAutomataColors
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Editor de código fuente del espacio de trabajo (Paso 3.2).
 *
 * - Numeración de líneas con íconos de error por tipo (círculo léxico, triángulo sintáctico, rombo semántico).
 * - Resaltado por categoría de token usando el `Lexer` del proyecto ([SyntaxHighlighter]).
 * - Errores: subrayado ondulado del color de su tipo y globo con la justificación al pasar el cursor; en
 *   pantallas táctiles, tocando el ícono de la línea.
 * - Deshacer/Rehacer propio (Ctrl+Z, Ctrl+Y / Ctrl+Shift+Z), Buscar y reemplazar (Ctrl+F, Ctrl+H, F3),
 *   tamaño de letra (Ctrl + / Ctrl − / Ctrl 0), autocompletado (al teclear o con Ctrl+Espacio),
 *   autoindentación y sangría con Tab / Shift+Tab, y resaltado de la llave o paréntesis pareja.
 *
 * El editor es el dueño del texto mientras [documentKey] no cambie; cada cambio se informa con [onTextChange].
 * Al cambiar [documentKey] (otro documento) se reinician el texto, el cursor y el historial de Deshacer.
 *
 * @param tokens Tokens del resaltado (pueden llegar con unos ms de atraso respecto del texto).
 * @param diagnostics Errores a marcar; sus rangos ya corresponden al texto del editor.
 * @param maxLength Límite de caracteres de `CompilerSecurityConfig`; un cambio que lo exceda se descarta.
 * @param codeHighlight Fragmento marcado desde el visor de árboles (nodo elegido o paso del recorrido), con fondo del
 *   color de [codeHighlightColor]; no mueve el cursor ni la selección.
 */
@Composable
fun CodeEditor(
    documentKey: Int,
    initialText: String,
    onTextChange: (String) -> Unit,
    tokens: List<Token>,
    diagnostics: List<EditorDiagnostic>,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    maxLength: Int,
    onTextTooLong: () -> Unit,
    focusRequest: EditorFocusRequest?,
    onFocusRequestHandled: (Long) -> Unit,
    modifier: Modifier = Modifier,
    codeHighlight: TextRange? = null,
    codeHighlightColor: Color = JAutomataTheme.colors.syntactic
) {
    val colors = JAutomataTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    // ---------------------------------------------------------------- Texto e historial
    var value by remember(documentKey) { mutableStateOf(TextFieldValue(initialText, TextRange(0))) }
    val history = remember(documentKey) { EditorHistory() }
    var historyRevision by remember(documentKey) { mutableIntStateOf(0) }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val editorFocus = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    // ---------------------------------------------------------------- Buscar y reemplazar
    var findVisible by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var currentMatch by remember { mutableIntStateOf(-1) }
    val findFocus = remember { FocusRequester() }
    val matches = remember(value.text, query, caseSensitive, wholeWord, findVisible) {
        if (findVisible) EditorText.findMatches(value.text, query, caseSensitive, wholeWord) else emptyList()
    }

    // ---------------------------------------------------------------- Autocompletado y globos
    var completion by remember(documentKey) { mutableStateOf<CompletionState?>(null) }
    var textHover by remember { mutableStateOf<HoverPopup?>(null) }
    var gutterHover by remember { mutableStateOf<HoverPopup?>(null) }
    var pinnedGutter by remember(documentKey) { mutableStateOf<HoverPopup?>(null) }

    val declaredIdentifiers = remember(tokens) {
        tokens.asSequence().filter { it.category == TokenCategory.ID }.map { it.lexeme }.distinct().toList()
    }
    val bracketPair = remember(value.text, value.selection, tokens) {
        if (value.selection.collapsed) SyntaxHighlighter.matchingBracket(value.text, tokens, value.selection.end) else null
    }
    val highlight = remember(value.text, tokens, colors, matches, currentMatch, bracketPair) {
        SyntaxHighlighter.highlight(value.text, tokens, colors, matches, currentMatch, bracketPair)
    }
    val transformation = remember(highlight) { HighlightTransformation(highlight.annotated) }
    val diagnosticsByLine = remember(diagnostics) { EditorDiagnostics.byLine(diagnostics) }

    val textStyle = remember(fontSizeSp, colors) {
        TextStyle(
            fontFamily = JAutomataFonts.code,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * LINE_HEIGHT_FACTOR).sp,
            color = colors.syntax.identifier
        )
    }
    val gutterStyle = remember(textStyle, colors) { textStyle.copy(color = colors.gutterText) }
    val padTopPx = with(density) { CONTENT_PADDING_TOP.toPx() }
    val padStartPx = with(density) { CONTENT_PADDING_START.toPx() }

    // ---------------------------------------------------------------- Operaciones

    fun currentSnapshot(): EditorSnapshot = value.toSnapshot()

    /** Aplica un cambio hecho por un comando (Tab, Deshacer, Reemplazar…), no por el teclado normal. */
    fun applyCommand(next: EditorSnapshot, recordFrom: EditorSnapshot?) {
        if (next.text.length > maxLength) {
            onTextTooLong()
            return
        }
        if (recordFrom != null) history.record(recordFrom, next)
        history.breakRun()
        value = next.toTextFieldValue()
        historyRevision++
        completion = null
        textHover = null
        if (next.text != recordFrom?.text) onTextChange(next.text)
    }

    fun insideStringOrComment(snapshot: EditorSnapshot): Boolean {
        val caret = snapshot.caret
        if (highlight.commentSpans.any { caret > it.start && caret <= it.end }) return true
        val lineStart = EditorText.lineStartOf(snapshot.text, caret)
        val linePrefix = snapshot.text.substring(lineStart, caret)
        var quotes = 0
        var i = 0
        while (i < linePrefix.length) {
            when (linePrefix[i]) {
                '\\' -> i++
                '"' -> quotes++
                '/' -> if (quotes % 2 == 0 && i + 1 < linePrefix.length && linePrefix[i + 1] == '/') return true
            }
            i++
        }
        return quotes % 2 == 1
    }

    fun computeCompletion(snapshot: EditorSnapshot, force: Boolean): CompletionState? {
        if (snapshot.hasSelection) return null
        val (start, prefix) = EditorText.identifierPrefixAt(snapshot.text, snapshot.caret) ?: return null
        if (!force && prefix.length < MIN_AUTO_COMPLETION_PREFIX) return null
        if (insideStringOrComment(snapshot)) return null
        val items = EditorText.completionCandidates(prefix, declaredIdentifiers)
        return if (items.isEmpty()) null else CompletionState(items, 0, start)
    }

    fun acceptCompletion(item: CompletionItem) {
        val before = currentSnapshot()
        applyCommand(EditorText.applyCompletion(before, item), recordFrom = before)
    }

    fun undo() {
        val previous = history.undo(currentSnapshot()) ?: return
        applyCommand(previous, recordFrom = null)
    }

    fun redo() {
        val next = history.redo(currentSnapshot()) ?: return
        applyCommand(next, recordFrom = null)
    }

    suspend fun reveal(offset: Int, animate: Boolean) {
        val result = layout ?: return
        if (viewportSize.height <= 0) return
        val safe = offset.coerceIn(0, result.layoutInput.text.length)
        val line = result.getLineForOffset(safe)
        val top = result.getLineTop(line) + padTopPx
        val bottom = result.getLineBottom(line) + padTopPx
        val viewHeight = viewportSize.height
        if (top < verticalScroll.value || bottom > verticalScroll.value + viewHeight) {
            val target = if (animate) (top - viewHeight / 3f) else if (top < verticalScroll.value) top - padTopPx else bottom - viewHeight + padTopPx
            val clamped = target.roundToInt().coerceAtLeast(0)
            if (animate) verticalScroll.animateScrollTo(clamped) else verticalScroll.scrollTo(clamped)
        }
        val x = result.getHorizontalPosition(safe, true) + padStartPx
        val viewWidth = viewportSize.width
        val margin = with(density) { 24.dp.toPx() }
        if (x < horizontalScroll.value + padStartPx || x > horizontalScroll.value + viewWidth - margin) {
            val target = (x - if (animate) viewWidth / 3f else viewWidth - margin * 2).roundToInt().coerceAtLeast(0)
            if (animate) horizontalScroll.animateScrollTo(target) else horizontalScroll.scrollTo(target)
        }
    }

    fun selectRange(start: Int, end: Int, animate: Boolean = true) {
        val length = value.text.length
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        value = value.copy(selection = TextRange(s, e))
        history.breakRun()
        completion = null
        scope.launch { reveal(s, animate) }
    }

    fun selectMatch(index: Int) {
        val match = matches.getOrNull(index) ?: return
        currentMatch = index
        selectRange(match.start, match.end)
    }

    fun findNext(forward: Boolean) {
        if (matches.isEmpty()) return
        val selection = value.selection
        val index = if (forward) {
            val from = if (selection.collapsed) selection.min else selection.max
            EditorText.matchIndexAtOrAfter(matches, from)
        } else {
            val before = matches.indexOfLast { it.start < selection.min }
            if (before < 0) matches.lastIndex else before
        }
        selectMatch(index)
    }

    fun openFind(withReplace: Boolean) {
        val selection = value.selection
        if (!selection.collapsed) {
            val selected = value.text.substring(selection.min, selection.max)
            if ('\n' !in selected && selected.length <= 200) query = selected
        }
        showReplace = withReplace || showReplace
        findVisible = true
        scope.launch {
            // Espera a que la barra aparezca antes de darle el foco.
            kotlinx.coroutines.yield()
            runCatching { findFocus.requestFocus() }
        }
    }

    fun closeFind() {
        findVisible = false
        currentMatch = -1
        runCatching { editorFocus.requestFocus() }
    }

    fun replaceCurrent() {
        val match = matches.getOrNull(currentMatch)
        val selection = value.selection
        if (match == null || selection.min != match.start || selection.max != match.end) {
            findNext(true)
            return
        }
        val before = currentSnapshot()
        val text = before.text.replaceRange(match.start, match.end, replacement)
        applyCommand(EditorSnapshot(text, match.start + replacement.length), recordFrom = before)
    }

    fun replaceAll() {
        if (matches.isEmpty()) return
        val before = currentSnapshot()
        val text = EditorText.replaceAll(before.text, matches, replacement)
        applyCommand(EditorSnapshot(text, before.caret.coerceAtMost(text.length)), recordFrom = before)
    }

    fun changeFontSize(delta: Int) = onFontSizeChange(fontSizeSp + delta)

    fun showDiagnostic(diagnostic: EditorDiagnostic) = selectRange(diagnostic.span.start, diagnostic.span.end)

    //Teclado

    val onValueChange: (TextFieldValue) -> Unit = { incoming ->
        val old = currentSnapshot()
        if (incoming.text == value.text) {
            if (incoming.selection != value.selection) {
                history.breakRun()
                completion = null
            }
            value = incoming
        } else {
            val raw = incoming.toSnapshot()
            var next = EditorText.sanitize(raw)
            EditorText.applyTypingAssists(old, next)?.let { next = it }
            if (next.text.length > maxLength) {
                onTextTooLong()
            } else {
                history.record(old, next)
                // Si no se corrigió nada se conserva el valor original (con la composición del teclado en pantalla).
                value = if (next == raw) incoming else next.toTextFieldValue()
                historyRevision++
                textHover = null
                pinnedGutter = null
                onTextChange(next.text)

                val insertion = EditorText.detectInsertion(old, next)
                val typedIdentifierChar = insertion?.inserted?.singleOrNull()?.let { EditorText.isIdentifierChar(it) } == true
                completion = when {
                    typedIdentifierChar -> computeCompletion(next, force = completion != null)
                    completion != null && EditorText.isSingleCharDeletion(old, next) -> computeCompletion(next, force = true)
                    else -> null
                }
            }
        }
    }

    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed

        val activeCompletion = completion
        if (activeCompletion != null) {
            when (event.key) {
                Key.DirectionDown -> {
                    completion = activeCompletion.move(1)
                    return@handler true
                }
                Key.DirectionUp -> {
                    completion = activeCompletion.move(-1)
                    return@handler true
                }
                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    acceptCompletion(activeCompletion.selected)
                    return@handler true
                }
                Key.Escape -> {
                    completion = null
                    return@handler true
                }
                else -> Unit
            }
        }

        when {
            ctrl && event.key == Key.Z && !shift -> { undo(); true }
            ctrl && (event.key == Key.Y || (event.key == Key.Z && shift)) -> { redo(); true }
            ctrl && event.key == Key.F -> { openFind(withReplace = false); true }
            ctrl && event.key == Key.H -> { openFind(withReplace = true); true }
            ctrl && event.key == Key.Spacebar -> {
                completion = computeCompletion(currentSnapshot(), force = true)
                true
            }
            ctrl && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> { changeFontSize(+1); true }
            ctrl && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> { changeFontSize(-1); true }
            ctrl && (event.key == Key.Zero || event.key == Key.NumPad0) -> {
                onFontSizeChange(AppSettings.DEFAULT_EDITOR_FONT_SP)
                true
            }
            event.key == Key.F3 -> {
                if (!findVisible) openFind(withReplace = false) else findNext(forward = !shift)
                true
            }
            event.key == Key.Tab && !ctrl -> {
                val before = currentSnapshot()
                applyCommand(if (shift) EditorText.dedent(before) else EditorText.indent(before), recordFrom = before)
                true
            }
            event.key == Key.Escape && (pinnedGutter != null || textHover != null) -> {
                pinnedGutter = null
                textHover = null
                true
            }
            event.key == Key.Escape && findVisible -> { closeFind(); true }
            else -> false
        }
    }

    //Efectos

    LaunchedEffect(documentKey) {
        verticalScroll.scrollTo(0)
        horizontalScroll.scrollTo(0)
    }

    // Mantiene visible el cursor al escribir o moverse con el teclado.
    LaunchedEffect(value.selection, layout) {
        if (isFocused) reveal(value.selection.end, animate = false)
    }

    // Coincidencia actual: la más cercana al cursor. Solo se desplaza la vista cuando cambia lo que se busca,
    // no cada vez que se edita el texto con la barra abierta.
    LaunchedEffect(matches) {
        currentMatch = if (matches.isEmpty()) -1 else EditorText.matchIndexAtOrAfter(matches, value.selection.min)
    }
    LaunchedEffect(query, caseSensitive, wholeWord, findVisible) {
        if (!findVisible || query.isEmpty()) return@LaunchedEffect
        val target = matches.getOrNull(EditorText.matchIndexAtOrAfter(matches, value.selection.min))
        target?.let { reveal(it.start, animate = true) }
    }

    // Clic en una fila de una tabla, un error o un nodo del árbol: selecciona ese fragmento.
    val currentOnFocusHandled by rememberUpdatedState(onFocusRequestHandled)
    LaunchedEffect(focusRequest?.requestId) {
        val request = focusRequest ?: return@LaunchedEffect
        if (request.takeFocus) {
            selectRange(request.span.start.offset, request.span.end.offset)
            runCatching { editorFocus.requestFocus() }
            currentOnFocusHandled(request.requestId)
        } else {
            // Visor de árboles: solo llevar el fragmento a la vista (el cursor y el foco no se tocan). Se lanza en el
            // alcance del editor porque marcar la petición como atendida reinicia este efecto.
            scope.launch { reveal(request.span.start.offset, animate = true) }
            currentOnFocusHandled(request.requestId)
        }
    }

    //Interfaz

    val caret = value.selection.end
    val caretLine = layout?.let { if (caret <= it.layoutInput.text.length) it.getLineForOffset(caret) else null } ?: 0
    val caretColumn = layout?.let { caret - it.getLineStart(caretLine) + 1 } ?: 1
    val lineCount = layout?.lineCount ?: 1

    Column(modifier.background(colors.editorBackground)) {
        EditorToolbar(
            canUndo = historyRevision >= 0 && history.canUndo,
            canRedo = historyRevision >= 0 && history.canRedo,
            onUndo = ::undo,
            onRedo = ::redo,
            onFind = { openFind(withReplace = false) },
            onReplace = { openFind(withReplace = true) },
            fontSizeSp = fontSizeSp,
            onFontSmaller = { changeFontSize(-1) },
            onFontLarger = { changeFontSize(+1) }
        )
        HorizontalDivider(color = colors.border)

        if (findVisible) {
            FindReplaceBar(
                query = query,
                onQueryChange = { query = it },
                replacement = replacement,
                onReplacementChange = { replacement = it },
                showReplace = showReplace,
                onToggleReplace = { showReplace = !showReplace },
                caseSensitive = caseSensitive,
                onToggleCaseSensitive = { caseSensitive = !caseSensitive },
                wholeWord = wholeWord,
                onToggleWholeWord = { wholeWord = !wholeWord },
                matchCount = matches.size,
                currentMatch = currentMatch,
                limitReached = matches.size >= EditorText.MAX_FIND_MATCHES,
                onNext = { findNext(true) },
                onPrevious = { findNext(false) },
                onReplace = ::replaceCurrent,
                onReplaceAll = ::replaceAll,
                onClose = ::closeFind,
                queryFocusRequester = findFocus
            )
            HorizontalDivider(color = colors.border)
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            val digits = maxOf(2, lineCount.toString().length)
            val digitWidth = remember(gutterStyle) { textMeasurer.measure("0", gutterStyle).size.width }
            val markerAreaPx = with(density) { GUTTER_MARKER_AREA.toPx() }
            val gutterEndPaddingPx = with(density) { GUTTER_END_PADDING.toPx() }
            val gutterWidthPx = markerAreaPx + digits * digitWidth + gutterEndPaddingPx
            val gutterWidth = with(density) { gutterWidthPx.toDp() }

            //Margen con números de línea
            Box(Modifier.width(gutterWidth).fillMaxHeight()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .background(colors.gutterBackground)
                        .pointerInput(diagnosticsByLine, gutterWidthPx) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    val result = layout
                                    val hits = if (
                                        change != null && result != null && change.type == PointerType.Mouse &&
                                        event.type != PointerEventType.Exit
                                    ) {
                                        val line = result.getLineForVerticalPosition(change.position.y + verticalScroll.value - padTopPx)
                                        diagnosticsByLine[line + 1]?.let { list -> line to list }
                                    } else {
                                        null
                                    }
                                    // Solo se actualiza al cambiar de línea, no con cada movimiento del ratón.
                                    if (hits?.second != gutterHover?.diagnostics) {
                                        gutterHover = hits?.let { (line, list) ->
                                            val y = result!!.getLineBottom(line) + padTopPx - verticalScroll.value
                                            HoverPopup(list, IntOffset(gutterWidthPx.roundToInt() + 4, y.roundToInt() + 2))
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(diagnosticsByLine, gutterWidthPx) {
                            detectTapGestures { position ->
                                val result = layout ?: return@detectTapGestures
                                val line = result.getLineForVerticalPosition(position.y + verticalScroll.value - padTopPx)
                                val list = diagnosticsByLine[line + 1]
                                if (list == null) {
                                    pinnedGutter = null
                                } else {
                                    val y = result.getLineBottom(line) + padTopPx - verticalScroll.value
                                    pinnedGutter = HoverPopup(list, IntOffset(gutterWidthPx.roundToInt() + 4, y.roundToInt() + 2))
                                    showDiagnostic(list.first())
                                }
                            }
                        }
                ) {
                    val result = layout ?: return@Canvas
                    val scroll = verticalScroll.value.toFloat()
                    val first = result.getLineForVerticalPosition((scroll - padTopPx).coerceAtLeast(0f))
                    val last = result.getLineForVerticalPosition(scroll - padTopPx + size.height)
                    for (line in first..last) {
                        val top = result.getLineTop(line) + padTopPx - scroll
                        val bottom = result.getLineBottom(line) + padTopPx - scroll
                        val lineHeight = bottom - top
                        val number = textMeasurer.measure(
                            (line + 1).toString(),
                            if (line == caretLine) gutterStyle.copy(color = colors.gutterTextActive) else gutterStyle
                        )
                        drawText(
                            number,
                            topLeft = Offset(
                                size.width - gutterEndPaddingPx - number.size.width,
                                top + (lineHeight - number.size.height) / 2f
                            )
                        )
                        diagnosticsByLine[line + 1]?.firstOrNull()?.let { diagnostic ->
                            drawErrorMarker(
                                type = diagnostic.type,
                                center = Offset(markerAreaPx / 2f + 2f, top + lineHeight / 2f),
                                radius = minOf(markerAreaPx, lineHeight) * 0.26f,
                                color = colors.error(diagnostic.type)
                            )
                        }
                    }
                }
                (pinnedGutter ?: gutterHover)?.let { popup ->
                    DiagnosticPopup(popup.diagnostics, popup.offset, onDismiss = { pinnedGutter = null })
                }
            }

            //Área de texto
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { viewportSize = it }
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
            ) {
                val viewportWidthDp = with(density) { viewportSize.width.toDp() }
                val viewportHeightDp = with(density) { viewportSize.height.toDp() }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .padding(start = CONTENT_PADDING_START, top = CONTENT_PADDING_TOP, end = 48.dp, bottom = 0.dp)
                        .widthIn(min = (viewportWidthDp - CONTENT_PADDING_START - 48.dp).coerceAtLeast(0.dp))
                        .heightIn(min = (viewportHeightDp - CONTENT_PADDING_TOP).coerceAtLeast(0.dp))
                        .padding(bottom = viewportHeightDp / 2)
                        .focusRequester(editorFocus)
                        .onFocusChanged { isFocused = it.isFocused }
                        .onPreviewKeyEvent(onKey)
                        .pointerInput(diagnostics) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    val result = layout
                                    val hits = if (
                                        change != null && result != null && change.type == PointerType.Mouse &&
                                        event.type != PointerEventType.Exit
                                    ) {
                                        val minWidth = 6.dp.toPx()
                                        diagnostics.filter { diagnostic ->
                                            diagnosticSegments(result, diagnostic, minWidth).any { it.rect.contains(change.position) }
                                        }
                                    } else {
                                        emptyList()
                                    }
                                    // El globo se queda donde apareció mientras el cursor siga sobre el mismo error.
                                    if (hits.isEmpty()) {
                                        if (textHover != null) textHover = null
                                    } else if (hits != textHover?.diagnostics) {
                                        val position = change!!.position
                                        textHover = HoverPopup(
                                            hits,
                                            IntOffset(position.x.roundToInt() + 12, position.y.roundToInt() + 18)
                                        )
                                    }
                                }
                            }
                        }
                        .drawBehind {
                            val result = layout ?: return@drawBehind
                            if (caret <= result.layoutInput.text.length) {
                                val line = result.getLineForOffset(caret)
                                drawLineBackground(result, line, colors.currentLine)
                            }
                            codeHighlight?.let { range ->
                                val length = result.layoutInput.text.length
                                val start = range.min.coerceIn(0, length)
                                val end = range.max.coerceIn(start, length)
                                if (end > start) {
                                    drawPath(result.getPathForRange(start, end), codeHighlightColor.copy(alpha = 0.28f))
                                } else {
                                    // Producción vacía (ε) o token faltante: una barra en la posición.
                                    val rect = result.getCursorRect(start)
                                    drawRect(codeHighlightColor, Offset(rect.left - 1.dp.toPx(), rect.top), Size(3.dp.toPx(), rect.height))
                                }
                            }
                            for ((line, list) in diagnosticsByLine) {
                                val index = line - 1
                                if (index in 0 until result.lineCount) {
                                    drawLineBackground(result, index, colors.error(list.first().type).copy(alpha = 0.07f))
                                }
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            val result = layout ?: return@drawWithContent
                            val minWidth = 6.dp.toPx()
                            for (diagnostic in diagnostics) {
                                val color = colors.error(diagnostic.type)
                                for (segment in diagnosticSegments(result, diagnostic, minWidth)) {
                                    drawSquiggle(segment.rect.left, segment.rect.right, segment.baseline + 2.dp.toPx(), color)
                                }
                            }
                            bracketPair?.let { (open, close) ->
                                drawBracketBox(result, open, colors)
                                drawBracketBox(result, close, colors)
                            }
                        },
                    decorationBox = { innerTextField ->
                        Box {
                            innerTextField()
                            completion?.let { state ->
                                val result = layout
                                if (result != null && caret <= result.layoutInput.text.length) {
                                    val rect = result.getCursorRect(caret)
                                    CompletionPopup(
                                        state = state,
                                        offset = IntOffset(rect.left.roundToInt(), rect.bottom.roundToInt() + 4),
                                        onSelect = ::acceptCompletion
                                    )
                                }
                            }
                            textHover?.let { popup -> DiagnosticPopup(popup.diagnostics, popup.offset) }
                        }
                    }
                )
            }
        }

        HorizontalDivider(color = colors.border)
        EditorStatusBar(
            line = caretLine + 1,
            column = caretColumn,
            lineCount = lineCount,
            selectionLength = value.selection.length,
            diagnostics = diagnostics,
            highlightDisabled = value.text.length > SyntaxHighlighter.MAX_HIGHLIGHT_CHARS
        )
    }
}

//Piezas de la interfaz

@Composable
private fun EditorToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onReplace: () -> Unit,
    fontSizeSp: Int,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit
) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BarButton("↶ Deshacer", onClick = onUndo, enabled = canUndo)
        BarButton("↷ Rehacer", onClick = onRedo, enabled = canRedo)
        Spacer(Modifier.width(6.dp))
        BarButton("Buscar", onClick = onFind)
        BarButton("Reemplazar", onClick = onReplace)
        Spacer(Modifier.weight(1f))
        BarButton("A−", onClick = onFontSmaller, enabled = fontSizeSp > AppSettings.MIN_EDITOR_FONT_SP)
        Text("$fontSizeSp", fontSize = 12.sp, color = colors.textSecondary, fontFamily = JAutomataFonts.code)
        BarButton("A+", onClick = onFontLarger, enabled = fontSizeSp < AppSettings.MAX_EDITOR_FONT_SP)
    }
}

@Composable
private fun EditorStatusBar(
    line: Int,
    column: Int,
    lineCount: Int,
    selectionLength: Int,
    diagnostics: List<EditorDiagnostic>,
    highlightDisabled: Boolean
) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp).background(colors.surface2).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val position = buildString {
            append("Lín ").append(line).append(", Col ").append(column)
            if (selectionLength > 0) append(" (").append(selectionLength).append(" sel.)")
        }
        Text(position, fontSize = 11.sp, color = colors.textSecondary, fontFamily = JAutomataFonts.code)
        Text("$lineCount líneas", fontSize = 11.sp, color = colors.textMuted)
        if (highlightDisabled) {
            Text("Archivo grande: resaltado desactivado", fontSize = 11.sp, color = colors.intermediate)
        }
        Spacer(Modifier.weight(1f))
        for (type in ErrorPhaseType.entries) {
            val count = diagnostics.count { it.type == type }
            if (count > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ErrorTypeMarker(type, size = 8.dp)
                    Text("$count ${type.displayName.lowercase()}", fontSize = 11.sp, color = colors.error(type))
                }
            }
        }
        if (diagnostics.isEmpty()) Text("Sin errores marcados", fontSize = 11.sp, color = colors.textMuted)
    }
}

//Dibujo

/** Globo de errores anclado en [offset] (coordenadas del componente que lo contiene). */
private data class HoverPopup(val diagnostics: List<EditorDiagnostic>, val offset: IntOffset)

/** Tramo de un error en una línea: rectángulo de la línea y la línea base del texto. */
private class DiagnosticSegment(val rect: Rect, val baseline: Float)

/** Rectángulos que ocupa [diagnostic] en el texto (uno por línea; los errores sin ancho miden [minWidth]). */
private fun diagnosticSegments(layout: TextLayoutResult, diagnostic: EditorDiagnostic, minWidth: Float): List<DiagnosticSegment> {
    val length = layout.layoutInput.text.length
    val start = diagnostic.span.start.coerceIn(0, length)
    val end = diagnostic.span.end.coerceIn(start, length)
    val firstLine = layout.getLineForOffset(start)
    val lastLine = if (end > start) layout.getLineForOffset(end - 1) else firstLine
    val segments = ArrayList<DiagnosticSegment>()
    for (line in firstLine..minOf(lastLine, firstLine + MAX_SQUIGGLE_LINES)) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, visibleEnd = true)
        val segmentStart = maxOf(start, lineStart)
        val segmentEnd = minOf(end, lineEnd)
        val left = layout.getHorizontalPosition(segmentStart, true)
        var right = if (segmentEnd > segmentStart) layout.getHorizontalPosition(segmentEnd, true) else left
        if (right - left < minWidth) right = left + minWidth
        segments += DiagnosticSegment(
            rect = Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line)),
            baseline = layout.getLineBaseline(line)
        )
    }
    return segments
}

private fun DrawScope.drawLineBackground(layout: TextLayoutResult, line: Int, color: Color) {
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    drawRect(color = color, topLeft = Offset(-CONTENT_PADDING_START.toPx(), top), size = Size(size.width + 96.dp.toPx(), bottom - top))
}

/** Subrayado ondulado (zigzag suave) entre [left] y [right]. */
private fun DrawScope.drawSquiggle(left: Float, right: Float, y: Float, color: Color) {
    val amplitude = 1.6.dp.toPx()
    val step = 2.4.dp.toPx()
    val path = Path()
    path.moveTo(left, y)
    var x = left
    var up = true
    while (x < right) {
        x = minOf(x + step, right)
        path.lineTo(x, if (up) y - amplitude else y + amplitude)
        up = !up
    }
    drawPath(path, color, style = Stroke(width = 1.2.dp.toPx()))
}

private fun DrawScope.drawBracketBox(layout: TextLayoutResult, offset: Int, colors: JAutomataColors) {
    if (offset !in 0 until layout.layoutInput.text.length) return
    val box = layout.getBoundingBox(offset)
    drawRect(
        color = colors.accent.copy(alpha = 0.7f),
        topLeft = box.topLeft,
        size = box.size,
        style = Stroke(width = 1.dp.toPx())
    )
}

private fun TextFieldValue.toSnapshot(): EditorSnapshot = EditorSnapshot(text, selection.start, selection.end)

private fun EditorSnapshot.toTextFieldValue(): TextFieldValue =
    TextFieldValue(text, TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length)))

private const val LINE_HEIGHT_FACTOR = 1.55f
private const val MIN_AUTO_COMPLETION_PREFIX = 2
private const val MAX_SQUIGGLE_LINES = 200
private val CONTENT_PADDING_TOP = 8.dp
private val CONTENT_PADDING_START = 10.dp
private val GUTTER_MARKER_AREA = 16.dp
private val GUTTER_END_PADDING = 10.dp
