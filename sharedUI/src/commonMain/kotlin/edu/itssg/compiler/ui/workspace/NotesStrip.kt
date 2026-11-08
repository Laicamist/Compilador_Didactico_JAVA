package edu.itssg.compiler.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.error.CompilerNote
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Franja plegable con las observaciones de la compilación ([CompilerNote]): avisos que **no son errores** (estructuras
 * de control que no se representan, división entera entre cero, recursión cortada). Va junto a la Tabla de Errores,
 * con el color neutro de la fase semántica para no confundirse con un error. Tocar una observación lleva al código.
 *
 * No dibuja nada si no hay observaciones.
 */
@Composable
internal fun NotesStrip(
    notes: List<CompilerNote>,
    onFocus: (SourceSpan) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notes.isEmpty()) return
    val colors = JAutomataTheme.colors
    val tint = colors.semantic
    var expanded by remember(notes) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().background(tint.copy(alpha = 0.08f))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(16.dp).clip(CircleShape).background(tint),
                contentAlignment = Alignment.Center
            ) {
                Text("i", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.background)
            }
            Text(
                text = if (notes.size == 1) "1 observación" else "${notes.size} observaciones",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
            Text(
                text = "No son errores: el código intermedio sí se genera.",
                fontSize = 12.sp,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "▲" else "▼", fontSize = 11.sp, color = colors.textSecondary)
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = MAX_EXPANDED_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp)
            ) {
                notes.forEach { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFocus(note.span) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "L${note.line}:${note.column}",
                            fontSize = 12.sp,
                            fontFamily = JAutomataFonts.code,
                            color = colors.textSecondary,
                            modifier = Modifier.width(64.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(note.kind.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
                            Text(note.message, fontSize = 12.sp, color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

private val MAX_EXPANDED_HEIGHT = 180.dp

