package edu.itssg.compiler.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.data.SavedSessionSummary
import edu.itssg.compiler.ui.state.PhaseStatus
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.HistoryFilter
import edu.itssg.compiler.ui.viewmodel.HistorySort
import edu.itssg.compiler.ui.viewmodel.HistoryUiState

/**
 * Pantalla de Historial: las sesiones guardadas con SQLDelight, con búsqueda (título o código), filtro
 * (Todas / Con errores / Sin errores) y orden. Cada tarjeta muestra el nombre, la fecha, un fragmento del código,
 * las fases (✓ / ✗ / bloqueada), los errores por tipo y los cuádruplos original → optimizado.
 *
 * @param onOpen Reabrir la sesión en el espacio de trabajo (la app confirma antes si hay cambios sin guardar).
 * @param nowMillis Hora actual, para "Hoy" / "Ayer".
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    nowMillis: Long,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onSortChange: (HistorySort) -> Unit,
    onRetry: () -> Unit,
    onOpen: (SavedSessionSummary) -> Unit,
    onRequestDelete: (SavedSessionSummary) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    Column(modifier.fillMaxSize().background(colors.background)) {
        // Barra superior
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface1).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Volver", color = colors.textSecondary) }
            Text(
                "Historial",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary
            )
            Spacer(Modifier.size(10.dp))
            if (!state.isLoading && state.allSessions.isNotEmpty()) {
                Text(sessionCountText(state.allSessions.size), fontSize = 13.sp, color = colors.textMuted)
            }
        }
        HorizontalDivider(color = colors.border)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
                if (state.allSessions.isNotEmpty()) {
                    HistoryControls(state, onQueryChange, onFilterChange, onSortChange)
                }
                when {
                    state.isLoading && state.allSessions.isEmpty() -> CenteredBox {
                        CircularProgressIndicator(color = colors.accent)
                    }
                    state.loadError != null -> CenteredBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(state.loadError, color = colors.errorLexical, fontSize = 14.sp, textAlign = TextAlign.Center)
                            OutlinedButton(onClick = onRetry) { Text("Reintentar") }
                        }
                    }
                    state.isEmpty -> CenteredBox {
                        EmptyMessage(
                            title = "Aún no hay sesiones guardadas",
                            detail = "En el espacio de trabajo usa Guardar (Ctrl+S): cada versión guardada aparece aquí " +
                                "con sus fases, errores y código intermedio."
                        )
                    }
                    state.visibleSessions.isEmpty() -> CenteredBox {
                        EmptyMessage(
                            title = "Ninguna sesión coincide",
                            detail = "Prueba con otra búsqueda o cambia el filtro."
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.visibleSessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                nowMillis = nowMillis,
                                onOpen = { onOpen(session) },
                                onDelete = { onRequestDelete(session) }
                            )
                        }
                    }
                }
            }
        }
    }

    state.pendingDeletion?.let { session ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Eliminar sesión") },
            text = { Text("Se eliminará “${session.title}” del historial con sus tablas y su código intermedio. No se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.errorLexical, contentColor = Color.White)
                ) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun HistoryControls(
    state: HistoryUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onSortChange: (HistorySort) -> Unit
) {
    val colors = JAutomataTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar por nombre o código…", fontSize = 14.sp) },
            singleLine = true,
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) { Text("✕", color = colors.textSecondary) }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HistoryFilter.entries.forEach { filter ->
                Pill(text = filter.displayName, selected = filter == state.filter, onClick = { onFilterChange(filter) })
            }
            Spacer(Modifier.size(12.dp))
            Text("Orden:", fontSize = 12.sp, color = colors.textMuted)
            HistorySort.entries.forEach { sort ->
                Pill(text = sort.displayName, selected = sort == state.sort, onClick = { onSortChange(sort) })
            }
        }
        if (state.query.isNotBlank() || state.filter != HistoryFilter.ALL) {
            Text(
                "${state.visibleSessions.size} de ${sessionCountText(state.allSessions.size)}",
                fontSize = 12.sp,
                color = colors.textMuted
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SavedSessionSummary,
    nowMillis: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .background(colors.surface2)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(formatSessionDate(session.createdAtMillis, nowMillis), fontSize = 12.sp, color = colors.textMuted)
        }
        if (session.snippet.isNotEmpty()) {
            Text(
                session.snippet,
                fontSize = 12.sp,
                fontFamily = JAutomataFonts.code,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.surface1).padding(8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            historyPhaseMarks(session).forEach { (phase, status) ->
                val (mark, markColor) = when (status) {
                    PhaseStatus.SUCCESS -> "✓" to colors.optimization
                    PhaseStatus.FAILED -> "✗" to colors.errorLexical
                    else -> "—" to colors.textMuted
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (status == PhaseStatus.BLOCKED) colors.textMuted else colors.phase(phase),
                            CircleShape
                        )
                    )
                    Text(phase.shortName, fontSize = 12.sp, color = colors.textSecondary)
                    Text(mark, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = markColor)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                errorSummary(session),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (session.hasErrors) colors.errorLexical else colors.optimization
            )
            Text(instructionSummary(session), fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Eliminar", color = colors.errorLexical, fontSize = 13.sp) }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
            ) { Text("Abrir", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.onAccent else colors.textSecondary,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, if (selected) colors.accent else colors.border, shape)
            .background(if (selected) colors.accent else colors.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EmptyMessage(title: String, detail: String) {
    val colors = JAutomataTheme.colors
    Column(
        modifier = Modifier.widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
        Text(detail, fontSize = 14.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

private fun sessionCountText(count: Int): String = if (count == 1) "1 sesión" else "$count sesiones"

private val MAX_CONTENT_WIDTH = 920.dp
