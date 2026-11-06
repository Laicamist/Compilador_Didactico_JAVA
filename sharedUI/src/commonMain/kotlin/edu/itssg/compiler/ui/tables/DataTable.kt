package edu.itssg.compiler.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Columna visual de una [DataTable].
 *
 * @property minWidth Ancho mínimo; si la tabla no cabe, aparece desplazamiento horizontal.
 * @property weight Parte del espacio sobrante que toma la columna (0 = ancho fijo).
 * @property maxLines Renglones visibles de la celda (la justificación de un error ocupa varios).
 * @property color Color del texto por fila (p. ej. el color del token o del tipo de error).
 * @property cell Contenido propio de la celda; reemplaza al texto de [TableColumnSpec.text].
 */
class TableColumn<T>(
    val spec: TableColumnSpec<T>,
    val minWidth: Dp,
    val weight: Float = 0f,
    val monospace: Boolean = false,
    val maxLines: Int = 1,
    val align: TextAlign = TextAlign.Start,
    val bold: Boolean = false,
    val color: (@Composable (T) -> Color)? = null,
    val cell: (@Composable (T) -> Unit)? = null
)

/** Filtro de categoría tal como se muestra en la barra de la tabla. */
data class FilterChipOption(val key: String, val label: String, val count: Int, val color: Color? = null)

/**
 * Tabla con encabezado fijo, orden por columna (clic en el encabezado: ▲ → ▼ → original), casillas para elegir
 * filas que copiar y fila activa resaltada. Las filas se dibujan bajo demanda ([LazyColumn]), así que admite
 * miles de tokens.
 *
 * @param rows Filas ya filtradas y ordenadas ([applyTableQuery]).
 * @param rowKey Clave estable y guardable (Int o String) de cada fila.
 * @param selection Claves de las filas marcadas.
 * @param activeKey Fila del último clic (la que se resaltó en el editor).
 * @param rowStripe Color de la franja izquierda de cada fila (`null` = sin franja).
 */
@Composable
fun <T> DataTable(
    rows: List<T>,
    columns: List<TableColumn<T>>,
    rowKey: (T) -> Any,
    sort: TableSort?,
    onSortClick: (String) -> Unit,
    selection: Set<Any>,
    onSelectionChange: (Set<Any>) -> Unit,
    activeKey: Any?,
    onRowClick: (T) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    rowStripe: ((T) -> Color?)? = null,
    listState: LazyListState = rememberLazyListState()
) {
    val colors = JAutomataTheme.colors
    BoxWithConstraints(modifier.background(colors.surface1)) {
        val viewportWidth = maxWidth
        val widths = remember(columns, viewportWidth) { computeColumnWidths(columns, viewportWidth) }
        val tableWidth = SELECT_COLUMN_WIDTH + STRIPE_WIDTH + widths.fold(0.dp) { acc, w -> acc + w }

        Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(maxOf(tableWidth, viewportWidth)).fillMaxHeight()) {
                val visibleKeys = rows.map(rowKey)
                val selectedVisible = visibleKeys.count { it in selection }
                TableHeader(
                    columns = columns,
                    widths = widths,
                    sort = sort,
                    onSortClick = onSortClick,
                    allSelected = rows.isNotEmpty() && selectedVisible == rows.size,
                    someSelected = selectedVisible > 0,
                    onToggleAll = {
                        onSelectionChange(
                            if (rows.isNotEmpty() && selectedVisible == rows.size) selection - visibleKeys.toSet()
                            else selection + visibleKeys
                        )
                    }
                )
                HorizontalDivider(color = colors.border)
                if (rows.isEmpty()) {
                    Box(Modifier.width(viewportWidth).weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(emptyText, fontSize = 13.sp, color = colors.textSecondary, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(rows, key = { rowKey(it) }) { row ->
                            val key = rowKey(row)
                            TableRow(
                                row = row,
                                columns = columns,
                                widths = widths,
                                selected = key in selection,
                                active = key == activeKey,
                                stripe = rowStripe?.invoke(row),
                                onToggle = { onSelectionChange(if (key in selection) selection - key else selection + key) },
                                onClick = { onRowClick(row) }
                            )
                            HorizontalDivider(color = colors.border.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> TableHeader(
    columns: List<TableColumn<T>>,
    widths: List<Dp>,
    sort: TableSort?,
    onSortClick: (String) -> Unit,
    allSelected: Boolean,
    someSelected: Boolean,
    onToggleAll: () -> Unit
) {
    val colors = JAutomataTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface2).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(STRIPE_WIDTH))
        Box(Modifier.width(SELECT_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            SelectBox(checked = allSelected, partial = !allSelected && someSelected, onToggle = onToggleAll)
        }
        columns.forEachIndexed { i, column ->
            val active = sort?.columnKey == column.spec.key
            Row(
                modifier = Modifier
                    .width(widths[i])
                    .fillMaxHeight()
                    .clickable { onSortClick(column.spec.key) }
                    .padding(horizontal = CELL_PADDING, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (column.align == TextAlign.End) Arrangement.End else Arrangement.Start
            ) {
                Text(
                    text = column.spec.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (active) {
                    Text(
                        text = if (sort?.direction == SortDirection.DESCENDING) " ▼" else " ▲",
                        fontSize = 10.sp,
                        color = colors.accent
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> TableRow(
    row: T,
    columns: List<TableColumn<T>>,
    widths: List<Dp>,
    selected: Boolean,
    active: Boolean,
    stripe: Color?,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val colors = JAutomataTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        active -> colors.selection
        selected -> colors.accent.copy(alpha = 0.10f)
        hovered -> colors.surface3
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 30.dp)
            .background(background)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(STRIPE_WIDTH).fillMaxHeight().background(stripe ?: Color.Transparent))
        Box(Modifier.width(SELECT_COLUMN_WIDTH), contentAlignment = Alignment.Center) {
            SelectBox(checked = selected, partial = false, onToggle = onToggle)
        }
        columns.forEachIndexed { i, column ->
            Box(
                modifier = Modifier.width(widths[i]).padding(horizontal = CELL_PADDING, vertical = 5.dp),
                contentAlignment = if (column.align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                val custom = column.cell
                if (custom != null) {
                    custom(row)
                } else {
                    Text(
                        text = column.spec.text(row).ifEmpty { if (column.monospace) "" else "—" },
                        fontFamily = if (column.monospace) JAutomataFonts.code else null,
                        fontSize = if (column.monospace) 12.5.sp else 12.sp,
                        fontWeight = if (column.bold) FontWeight.SemiBold else FontWeight.Normal,
                        color = column.color?.invoke(row) ?: colors.textPrimary,
                        maxLines = column.maxLines,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = column.align,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

/** Casilla compacta*/
@Composable
internal fun SelectBox(checked: Boolean, partial: Boolean, onToggle: () -> Unit) {
    val colors = JAutomataTheme.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(shape)
                .background(if (checked || partial) colors.accent else Color.Transparent)
                .border(1.dp, if (checked || partial) colors.accent else colors.borderStrong, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", fontSize = 10.sp, lineHeight = 10.sp, color = colors.onAccent, fontWeight = FontWeight.Bold)
            if (partial) Box(Modifier.width(7.dp).height(2.dp).background(colors.onAccent))
        }
    }
}

/**
 * Barra superior de una tabla: búsqueda, filtros por categoría (con su conteo), cuántas filas se ven y el botón de
 * copiar (las filas marcadas o, si no hay, todas las visibles).
 */
@Composable
fun TableToolbar(
    query: TableQuery,
    onQueryChange: (TableQuery) -> Unit,
    searchPlaceholder: String,
    filters: List<FilterChipOption>,
    visibleCount: Int,
    totalCount: Int,
    selectedCount: Int,
    onCopy: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface1).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                SearchField(
                    value = query.search,
                    onValueChange = { onQueryChange(query.copy(search = it.take(TableQuery.MAX_SEARCH_LENGTH))) },
                    placeholder = searchPlaceholder,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                )
            }
            Text(
                text = if (visibleCount == totalCount) "$totalCount filas" else "$visibleCount de $totalCount",
                fontSize = 11.sp,
                color = colors.textMuted,
                maxLines = 1
            )
            if (selectedCount > 0) {
                SmallAction("✕ $selectedCount", colors.textSecondary, onClearSelection)
            }
            SmallAction(
                text = if (selectedCount > 0) "⧉ Copiar ($selectedCount)" else "⧉ Copiar",
                color = colors.accent,
                onClick = onCopy,
                enabled = visibleCount > 0
            )
        }
        val shown = filters.filter { it.count > 0 || it.key in query.filters }
        if (shown.size > 1 || query.filters.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip("Todas", null, selected = query.filters.isEmpty(), color = null) {
                    onQueryChange(query.copy(filters = emptySet()))
                }
                shown.forEach { option ->
                    CategoryChip(option.label, option.count, selected = option.key in query.filters, color = option.color) {
                        onQueryChange(query.toggleFilter(option.key))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "")) },
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.surface3)
                    .border(1.dp, colors.border, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("⌕", fontSize = 13.sp, color = colors.textMuted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 13.sp, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Text(
                        "✕",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onValueChange("") }.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun CategoryChip(label: String, count: Int?, selected: Boolean, color: Color?, onClick: () -> Unit) {
    val colors = JAutomataTheme.colors
    val tint = color ?: colors.accent
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) tint else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (color != null) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, fontSize = 12.sp, color = if (selected) colors.textPrimary else colors.textSecondary, maxLines = 1)
        if (count != null) Text("$count", fontSize = 11.sp, color = colors.textMuted)
    }
}

/** Botón de texto compacto para barras de herramientas. */
@Composable
internal fun SmallAction(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = JAutomataTheme.colors
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) color else colors.textMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

/** Etiqueta pequeña de color (código de optimización, tipo de error…). */
@Composable
internal fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = JAutomataFonts.code,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** Aviso de una línea sobre la tabla (p. ej. "el código cambió desde la última compilación"). */
@Composable
internal fun TableNotice(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        modifier = modifier.fillMaxWidth().background(color.copy(alpha = 0.09f)).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Anchos finales: el mínimo de cada columna más su parte del espacio sobrante. */
private fun <T> computeColumnWidths(columns: List<TableColumn<T>>, viewport: Dp): List<Dp> {
    val fixed = SELECT_COLUMN_WIDTH + STRIPE_WIDTH
    val minTotal = columns.fold(fixed) { acc, c -> acc + c.minWidth }
    val extra = (viewport - minTotal).coerceAtLeast(0.dp)
    val totalWeight = columns.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight <= 0f) return columns.map { it.minWidth }
    return columns.map { it.minWidth + extra * (it.weight / totalWeight) }
}

/** Ordena según la consulta y conserva solo las filas marcadas, en el orden visible. */
internal fun <T> rowsToCopy(visible: List<T>, selection: Set<Any>, rowKey: (T) -> Any): List<T> =
    if (selection.isEmpty()) visible else visible.filter { rowKey(it) in selection }.ifEmpty { visible }

/** Mensaje de confirmación tras copiar. */
internal fun copiedMessage(count: Int, ok: Boolean): String = when {
    !ok -> "No se pudo copiar al portapapeles."
    count == 1 -> "Se copió 1 fila (pégala en Excel, Word o Docs)."
    else -> "Se copiaron $count filas (pégalas en Excel, Word o Docs)."
}

private val SELECT_COLUMN_WIDTH = 34.dp
private val STRIPE_WIDTH = 3.dp
private val CELL_PADDING = 8.dp
