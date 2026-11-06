package edu.itssg.compiler.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Barra de Buscar / Reemplazar del editor (Ctrl+F / Ctrl+H).
 * Enter = siguiente, Shift+Enter = anterior, Esc = cerrar.
 */
@Composable
internal fun FindReplaceBar(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    showReplace: Boolean,
    onToggleReplace: () -> Unit,
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
    wholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    matchCount: Int,
    currentMatch: Int,
    limitReached: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    queryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val keyHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> {
                    if (event.isShiftPressed) onPrevious() else onNext()
                    true
                }
                Key.Escape -> {
                    onClose()
                    true
                }
                else -> false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface3)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BarButton(if (showReplace) "▾" else "▸", onClick = onToggleReplace)
            CompactField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Buscar",
                onKey = keyHandler,
                modifier = Modifier.weight(1f).focusRequester(queryFocusRequester)
            )
            ToggleChip("Aa", selected = caseSensitive, onClick = onToggleCaseSensitive)
            ToggleChip("ab|", selected = wholeWord, onClick = onToggleWholeWord)
            Text(
                text = when {
                    query.isEmpty() -> ""
                    matchCount == 0 -> "Sin resultados"
                    else -> "${(currentMatch + 1).coerceAtLeast(0)} de $matchCount${if (limitReached) "+" else ""}"
                },
                fontSize = 12.sp,
                color = if (query.isNotEmpty() && matchCount == 0) colors.errorLexical else colors.textSecondary,
                modifier = Modifier.widthIn(min = 72.dp)
            )
            BarButton("↑", onClick = onPrevious, enabled = matchCount > 0)
            BarButton("↓", onClick = onNext, enabled = matchCount > 0)
            BarButton("✕", onClick = onClose)
        }
        if (showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(24.dp))
                CompactField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = "Reemplazar por",
                    onKey = { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            onReplace()
                            true
                        } else {
                            keyHandler(event)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                BarButton("Reemplazar", onClick = onReplace, enabled = matchCount > 0)
                BarButton("Todos", onClick = onReplaceAll, enabled = matchCount > 0)
            }
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "").take(MAX_QUERY_LENGTH)) },
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontFamily = JAutomataFonts.code),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.onPreviewKeyEvent(onKey),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .background(colors.surface1, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.borderStrong, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = colors.textMuted)
                inner()
            }
        }
    )
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    Text(
        text = label,
        fontFamily = JAutomataFonts.code,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) colors.accent else colors.textSecondary,
        modifier = Modifier
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(5.dp))
            .border(1.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
internal fun BarButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    Text(
        text = label,
        fontSize = 12.sp,
        color = if (enabled) colors.textPrimary else colors.textMuted,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    )
}

private const val MAX_QUERY_LENGTH = 500
