package edu.itssg.compiler.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/** Estado del menú de autocompletado. [start] es donde empieza el prefijo que se va a reemplazar. */
internal data class CompletionState(val items: List<CompletionItem>, val selectedIndex: Int, val start: Int) {
    val selected: CompletionItem get() = items[selectedIndex.coerceIn(0, items.lastIndex)]

    fun move(delta: Int): CompletionState =
        copy(selectedIndex = (selectedIndex + delta + items.size) % items.size)
}

/**
 * Figura que identifica el tipo de error además del color (para quien no distingue colores):
 * Léxico = círculo, Sintáctico = triángulo, Semántico = rombo.
 */
internal fun DrawScope.drawErrorMarker(type: ErrorPhaseType, center: Offset, radius: Float, color: Color) {
    when (type) {
        ErrorPhaseType.LEXICAL -> drawCircle(color = color, radius = radius, center = center)
        ErrorPhaseType.SYNTACTIC -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y + radius * 0.8f)
                lineTo(center.x - radius, center.y + radius * 0.8f)
                close()
            }
            drawPath(path, color)
        }
        ErrorPhaseType.SEMANTIC -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius, center.y)
                close()
            }
            drawPath(path, color)
        }
    }
}

@Composable
internal fun ErrorTypeMarker(type: ErrorPhaseType, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val color = JAutomataTheme.colors.error(type)
    Canvas(modifier.size(size)) {
        // Dentro del Canvas, `size` es el tamaño del área de dibujo (DrawScope.size), no el parámetro en dp.
        drawErrorMarker(type, center, minOf(drawContext.size.width, drawContext.size.height) / 2f, color)
    }
}

/** Menú de sugerencias bajo el cursor. No toma el foco: el teclado sigue escribiendo en el editor. */
@Composable
internal fun CompletionPopup(
    state: CompletionState,
    offset: IntOffset,
    onSelect: (CompletionItem) -> Unit
) {
    val colors = JAutomataTheme.colors
    Popup(alignment = Alignment.TopStart, offset = offset, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.surface4,
            shadowElevation = 8.dp,
            modifier = Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(8.dp))
        ) {
            Column(Modifier.widthIn(min = 200.dp, max = 320.dp).padding(vertical = 4.dp)) {
                state.items.forEachIndexed { index, item ->
                    val selected = index == state.selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { onSelect(item) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.label,
                            fontFamily = JAutomataFonts.code,
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = item.detail,
                            fontFamily = JAutomataFonts.code,
                            fontSize = 11.sp,
                            color = when (item.kind) {
                                CompletionKind.IDENTIFIER -> colors.syntax.identifier
                                CompletionKind.KEYWORD -> colors.syntax.keyword
                                CompletionKind.LIBRARY -> colors.syntax.mainMethod
                            }.copy(alpha = 0.8f)
                        )
                    }
                }
                HorizontalDivider(color = colors.border, modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = "↑↓ elegir · Enter/Tab aceptar · Esc cerrar",
                    fontSize = 10.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** Globo con la justificación de uno o más errores (al pasar el cursor o al tocar el ícono del margen). */
@Composable
internal fun DiagnosticPopup(
    diagnostics: List<EditorDiagnostic>,
    offset: IntOffset,
    onDismiss: (() -> Unit)? = null
) {
    if (diagnostics.isEmpty()) return
    val colors = JAutomataTheme.colors
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.surface4,
            shadowElevation = 10.dp,
            modifier = Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(10.dp))
        ) {
            Column(
                modifier = Modifier.widthIn(min = 240.dp, max = 460.dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                diagnostics.take(MAX_DIAGNOSTICS_IN_POPUP).forEachIndexed { index, diagnostic ->
                    if (index > 0) HorizontalDivider(color = colors.border)
                    DiagnosticDetails(diagnostic)
                }
                if (diagnostics.size > MAX_DIAGNOSTICS_IN_POPUP) {
                    Text(
                        "y ${diagnostics.size - MAX_DIAGNOSTICS_IN_POPUP} errores más en esta línea",
                        fontSize = 12.sp,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticDetails(diagnostic: EditorDiagnostic) {
    val colors = JAutomataTheme.colors
    val typeColor = colors.error(diagnostic.type)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ErrorTypeMarker(diagnostic.type)
            Text(
                text = buildString {
                    append("Error ")
                    append(diagnostic.type.displayName.lowercase())
                    diagnostic.number?.let { append(" #").append(it) }
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = typeColor
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Lín ${diagnostic.line}, Col ${diagnostic.column}",
                fontFamily = JAutomataFonts.code,
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
        if (diagnostic.lexeme.isNotEmpty()) {
            Text(
                text = diagnostic.lexeme,
                fontFamily = JAutomataFonts.code,
                fontSize = 12.sp,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(typeColor.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Text(text = diagnostic.message, fontSize = 13.sp, lineHeight = 18.sp, color = colors.textPrimary)
        diagnostic.rule?.let {
            Text(text = "Regla: $it", fontSize = 11.sp, color = colors.textSecondary)
        }
        if (diagnostic.number == null) {
            Text(
                text = "Detectado mientras escribes · compila para ver todos los errores",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
    }
}

private const val MAX_DIAGNOSTICS_IN_POPUP = 4
