package edu.itssg.compiler.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.symboltable.SymbolRole
import edu.itssg.compiler.ui.editor.ErrorTypeMarker
import edu.itssg.compiler.ui.theme.JAutomataColors
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Tabla con su barra de búsqueda/filtros. La selección y la fila activa se reinician con [resetKey] (cada
 * compilación); la consulta (búsqueda, filtros y orden) vive en el ViewModel para no perderse al cambiar de pestaña.
 *
 * @param onRowClick Recibe la fila y si ya era la activa (para recorrer apariciones con clics repetidos).
 */
@Composable
internal fun <T> FilterableTable(
    allRows: List<T>,
    specs: List<TableColumnSpec<T>>,
    columns: List<TableColumn<T>>,
    filters: List<TableFilterSpec<T>>,
    query: TableQuery,
    onQueryChange: (TableQuery) -> Unit,
    rowKey: (T) -> Any,
    resetKey: Any?,
    searchPlaceholder: String,
    emptyText: String,
    onRowClick: (row: T, wasActive: Boolean) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    filterColor: (String) -> Color? = { null },
    rowStripe: ((T) -> Color?)? = null
) {
    val visible = remember(allRows, query) { applyTableQuery(allRows, specs, query, filters) }
    val counts = remember(allRows) { countByFilter(allRows, filters) }
    var selection by remember(resetKey) { mutableStateOf(emptySet<Any>()) }
    var activeKey by remember(resetKey) { mutableStateOf<Any?>(null) }
    val clipboard = rememberTextClipboard()
    val selectedVisible = remember(visible, selection) { visible.count { rowKey(it) in selection } }

    Column(modifier) {
        TableToolbar(
            query = query,
            onQueryChange = onQueryChange,
            searchPlaceholder = searchPlaceholder,
            filters = filters.map { FilterChipOption(it.key, it.label, counts[it.key] ?: 0, filterColor(it.key)) },
            visibleCount = visible.size,
            totalCount = allRows.size,
            selectedCount = selectedVisible,
            onCopy = {
                val rows = rowsToCopy(visible, selection, rowKey)
                val ok = clipboard.copy(rowsToTsv(specs, rows))
                onMessage(copiedMessage(rows.size, ok), !ok)
            },
            onClearSelection = { selection = emptySet() }
        )
        HorizontalDivider(color = JAutomataTheme.colors.border)
        DataTable(
            rows = visible,
            columns = columns,
            rowKey = rowKey,
            sort = query.sort,
            onSortClick = { onQueryChange(query.cycleSort(it)) },
            selection = selection,
            onSelectionChange = { selection = it },
            activeKey = activeKey,
            onRowClick = { row ->
                val key = rowKey(row)
                val wasActive = key == activeKey
                activeKey = key
                onRowClick(row, wasActive)
            },
            emptyText = if (allRows.isEmpty()) emptyText else "Ninguna fila coincide con la búsqueda o los filtros.",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            rowStripe = rowStripe
        )
    }
}

//Tabla de Tokens

@Composable
fun TokensPanel(
    report: CompilationReport,
    isStale: Boolean,
    query: TableQuery,
    onQueryChange: (TableQuery) -> Unit,
    onFocus: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val rows = remember(report) { buildTokenRows(report) }
    val specs = TokenTable.columns
    val columns = remember(colors) {
        listOf(
            TableColumn(specs[0], 44.dp, monospace = true, align = TextAlign.End, color = { colors.textMuted }),
            TableColumn(specs[1], 42.dp, monospace = true, align = TextAlign.End, bold = true, color = { colors.accent }),
            TableColumn(specs[2], 120.dp, weight = 1f, monospace = true),
            TableColumn(specs[3], 68.dp, monospace = true, bold = true, color = { row -> colors.syntax.forCategory(row.category) }),
            TableColumn(specs[4], 190.dp, weight = 1.6f, maxLines = 2, color = { colors.textSecondary }),
            TableColumn(specs[5], 52.dp, monospace = true, align = TextAlign.End, color = { colors.textSecondary }),
            TableColumn(specs[6], 66.dp, monospace = true, align = TextAlign.End, color = { colors.textSecondary })
        )
    }
    Column(modifier.background(colors.surface1)) {
        if (isStale) StaleNotice()
        FilterableTable(
            allRows = rows,
            specs = specs,
            columns = columns,
            filters = TokenTable.filters,
            query = query,
            onQueryChange = onQueryChange,
            rowKey = { it.number },
            resetKey = report,
            searchPlaceholder = "Buscar lexema, token (OPA, ID…) o descripción",
            emptyText = "El Analizador Léxico no generó tokens.",
            onRowClick = { row, _ -> onFocus(row.span) },
            onMessage = onMessage,
            modifier = Modifier.weight(1f),
            filterColor = { key -> TokenGroup.entries.firstOrNull { it.name == key }?.let { tokenGroupColor(it, colors) } }
        )
    }
}

private fun tokenGroupColor(group: TokenGroup, colors: JAutomataColors): Color = when (group) {
    TokenGroup.RESERVED -> colors.syntax.keyword
    TokenGroup.TYPES -> colors.syntax.type
    TokenGroup.IDENTIFIERS -> colors.syntax.identifier
    TokenGroup.LITERALS -> colors.syntax.number
    TokenGroup.OPERATORS -> colors.syntax.operator
    TokenGroup.DELIMITERS -> colors.syntax.punctuation
    TokenGroup.OTHER -> colors.textMuted
}

//Tabla de Símbolos

@Composable
fun SymbolsPanel(
    report: CompilationReport,
    isStale: Boolean,
    query: TableQuery,
    onQueryChange: (TableQuery) -> Unit,
    onFocus: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val rows = remember(report) { buildSymbolRows(report) }
    var activeSymbol by remember(report) { mutableStateOf<Int?>(null) }
    var occurrence by remember(report) { mutableIntStateOf(0) }
    val specs = SymbolTableSpec.columns
    val columns = remember(colors) {
        listOf(
            TableColumn(specs[0], 56.dp, monospace = true, bold = true, color = { colors.accent }),
            TableColumn(specs[1], 110.dp, weight = 1f, monospace = true),
            TableColumn(specs[2], 112.dp, bold = true, color = { row -> roleColor(row.entry.role, colors) ?: colors.textSecondary }),
            TableColumn(specs[3], 90.dp, weight = 0.5f, monospace = true, color = { colors.syntax.type }),
            TableColumn(specs[4], 90.dp, weight = 0.5f, color = { colors.textSecondary }),
            TableColumn(specs[5], 52.dp, monospace = true, align = TextAlign.End, color = { colors.textSecondary }),
            TableColumn(specs[6], 96.dp, align = TextAlign.End, cell = { row ->
                val total = row.occurrences.size
                val isActive = row.symbolId == activeSymbol && total > 1
                Text(
                    text = if (isActive) "${occurrence + 1} de $total ↻" else "$total",
                    fontSize = 12.sp,
                    fontFamily = JAutomataFonts.code,
                    color = if (isActive) colors.accent else colors.textSecondary,
                    maxLines = 1
                )
            })
        )
    }
    Column(modifier.background(colors.surface1)) {
        if (isStale) StaleNotice()
        if (report.semanticErrorCount > 0) {
            TableNotice(
                "El Analizador Semántico encontró errores: la tabla muestra lo que alcanzó a registrar.",
                colors.errorSemantic
            )
        }
        FilterableTable(
            allRows = rows,
            specs = specs,
            columns = columns,
            filters = SymbolTableSpec.filters,
            query = query,
            onQueryChange = onQueryChange,
            rowKey = { it.symbolId },
            resetKey = report,
            searchPlaceholder = "Buscar ID, lexema, TD o ámbito",
            emptyText = "El programa no declara identificadores.",
            onRowClick = { row, wasActive ->
                val total = row.occurrences.size.coerceAtLeast(1)
                occurrence = if (wasActive && row.symbolId == activeSymbol) (occurrence + 1) % total else 0
                activeSymbol = row.symbolId
                row.occurrences.getOrNull(occurrence)?.let(onFocus)
            },
            onMessage = onMessage,
            modifier = Modifier.weight(1f),
            filterColor = { key -> SymbolRole.entries.firstOrNull { it.name == key }?.let { roleColor(it, colors) } }
        )
        Text(
            "Clic en una fila: resalta el identificador en el editor · clic otra vez: siguiente aparición.",
            fontSize = 11.sp,
            color = colors.textMuted,
            modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

private fun roleColor(role: SymbolRole, colors: JAutomataColors): Color? = when (role) {
    SymbolRole.CLASS, SymbolRole.PACKAGE -> colors.syntactic
    SymbolRole.METODO -> colors.semantic
    SymbolRole.VARIABLE -> colors.lexical
    SymbolRole.PARAMETER -> colors.intermediate
    SymbolRole.OBJECT -> colors.optimization
    SymbolRole.EXTERNAL_MEMBER, SymbolRole.UNKNOWN -> null
}

@Composable
private fun StaleNotice() {
    TableNotice(
        "El código cambió desde la última compilación: la tabla corresponde a la versión compilada (F5 para actualizar).",
        JAutomataTheme.colors.intermediate
    )
}

//Tabla de Errores

/**
 * Tabla de Errores con el formato (Línea | Error | Tipo | Justificación), plegable en pantallas
 * anchas. Con un reporte vigente muestra los errores de la compilación (respetando Paso a paso); si el código cambió
 * o aún no se compila, los errores léxicos que se detectan al escribir.
 *
 * @param isLive Las filas son errores léxicos en vivo, no la tabla de la compilación.
 * @param hasCurrentReport Hay una compilación que corresponde al código del editor.
 */
@Composable
fun ErrorTablePanel(
    rows: List<ErrorTableRow>,
    isLive: Boolean,
    isStale: Boolean,
    hasCurrentReport: Boolean,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
    query: TableQuery,
    onQueryChange: (TableQuery) -> Unit,
    onFocus: (SourceSpan) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    resetKey: Any?,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val specs = ErrorTableSpec.columns
    val columns = remember(colors) {
        listOf(
            TableColumn(specs[0], 70.dp, cell = { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ErrorTypeMarker(row.type, size = 9.dp)
                    Text("${row.line}", fontSize = 12.5.sp, fontFamily = JAutomataFonts.code, color = colors.textPrimary)
                    Text(":${row.column}", fontSize = 10.5.sp, fontFamily = JAutomataFonts.code, color = colors.textMuted)
                }
            }),
            TableColumn(specs[1], 96.dp, monospace = true, bold = true, color = { row -> colors.error(row.type) }),
            TableColumn(specs[2], 90.dp, color = { row -> colors.error(row.type) }),
            TableColumn(specs[3], 300.dp, weight = 1f, maxLines = 4)
        )
    }
    Column(modifier.background(colors.surface1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .clickable(enabled = collapsible, onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (collapsible) Text(if (expanded) "▾" else "▸", color = colors.textSecondary, fontSize = 12.sp)
            Text(
                "Tabla de Errores (${rows.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = JAutomataFonts.title,
                color = colors.textPrimary
            )
            for (type in ErrorPhaseType.entries) {
                val count = rows.count { it.type == type }
                if (count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ErrorTypeMarker(type, size = 8.dp)
                        Text("$count ${type.displayName.lowercase()}", fontSize = 12.sp, color = colors.error(type))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    isLive && rows.isNotEmpty() -> "Errores léxicos detectados al escribir · compila para ver todos"
                    isLive && isStale -> "El código cambió: compila de nuevo"
                    isLive -> "Compila para generar la Tabla de Errores"
                    else -> ""
                },
                fontSize = 11.sp,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            FilterableTable(
                allRows = rows,
                specs = specs,
                columns = columns,
                filters = ErrorTableSpec.filters,
                query = query,
                onQueryChange = onQueryChange,
                rowKey = { it.key },
                resetKey = resetKey,
                searchPlaceholder = "Buscar en la justificación, el lexema o el tipo",
                emptyText = when {
                    hasCurrentReport -> "Sin errores: el programa cumple las reglas léxicas, sintácticas y semánticas ✓"
                    isLive && isStale -> "Sin errores léxicos al escribir. Compila para revisar las demás fases."
                    else -> "Compila (F5) para generar la Tabla de Errores."
                },
                onRowClick = { row, _ -> onFocus(row.span) },
                onMessage = onMessage,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                filterColor = { key -> ErrorPhaseType.entries.firstOrNull { it.name == key }?.let { colors.error(it) } },
                rowStripe = { row -> colors.error(row.type) }
            )
        }
    }
}
