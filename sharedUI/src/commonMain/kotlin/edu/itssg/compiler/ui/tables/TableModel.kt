package edu.itssg.compiler.ui.tables

/**
 * Modelo puro (sin Compose) de las tablas del panel de resultados: búsqueda, filtros por categoría, orden por
 * columna y copia al portapapeles. Se prueba en commonTest sin interfaz.
 */

/** Tablas que guardan su búsqueda, filtros y orden en el estado del espacio de trabajo. */
enum class DataTableKind { TOKENS, SYMBOLS, ERRORS }

enum class SortDirection { ASCENDING, DESCENDING }

/** Orden activo: columna ([columnKey]) y sentido. */
data class TableSort(val columnKey: String, val direction: SortDirection)

/**
 * Consulta de una tabla.
 *
 * @property search Texto a buscar (sin distinguir mayúsculas ni acentos) en las columnas buscables.
 * @property filters Claves de los filtros de categoría activos; vacío = sin filtrar (se muestran todas).
 * @property sort `null` = orden original (el de la fase que generó la tabla).
 */
data class TableQuery(
    val search: String = "",
    val filters: Set<String> = emptySet(),
    val sort: TableSort? = null
) {
    val isFiltering: Boolean get() = search.isNotBlank() || filters.isNotEmpty()

    /** Activa o desactiva un filtro. */
    fun toggleFilter(key: String): TableQuery =
        copy(filters = if (key in filters) filters - key else filters + key)

    /** Clic en el encabezado: ascendente → descendente → orden original. */
    fun cycleSort(columnKey: String): TableQuery = copy(
        sort = when {
            sort?.columnKey != columnKey -> TableSort(columnKey, SortDirection.ASCENDING)
            sort.direction == SortDirection.ASCENDING -> TableSort(columnKey, SortDirection.DESCENDING)
            else -> null
        }
    )

    companion object {
        /** Tope de la búsqueda: evita trabajo innecesario si se pega un texto enorme en el campo. */
        const val MAX_SEARCH_LENGTH: Int = 120
    }
}

/**
 * Columna de una tabla.
 *
 * @property key Identificador estable de la columna (para el orden guardado en [TableSort]).
 * @property title Encabezado; también es el encabezado del texto copiado.
 * @property text Valor que se muestra, se busca y se copia.
 * @property sortKey Valor para ordenar. Si devuelve números, se ordena numéricamente; si es `null`, por [text].
 * @property searchable Si la búsqueda la toma en cuenta.
 */
class TableColumnSpec<T>(
    val key: String,
    val title: String,
    val text: (T) -> String,
    val sortKey: ((T) -> Comparable<*>?)? = null,
    val searchable: Boolean = true
)

/** Filtro de categoría: [matches] decide si una fila pertenece a la categoría [key]. */
class TableFilterSpec<T>(
    val key: String,
    val label: String,
    val matches: (T) -> Boolean
)

/**
 * Aplica búsqueda, filtros y orden. Varios filtros activos se combinan con "o" (la fila pertenece a alguna de las
 * categorías elegidas); la búsqueda se combina con "y". El orden es estable: a igual valor se respeta el original.
 */
fun <T> applyTableQuery(
    rows: List<T>,
    columns: List<TableColumnSpec<T>>,
    query: TableQuery,
    filters: List<TableFilterSpec<T>> = emptyList()
): List<T> {
    val activeFilters = filters.filter { it.key in query.filters }
    val needle = normalizeForSearch(query.search.take(TableQuery.MAX_SEARCH_LENGTH).trim())
    val searchable = columns.filter { it.searchable }

    var result = rows.asSequence()
    if (activeFilters.isNotEmpty()) {
        result = result.filter { row -> activeFilters.any { it.matches(row) } }
    }
    if (needle.isNotEmpty()) {
        result = result.filter { row -> searchable.any { normalizeForSearch(it.text(row)).contains(needle) } }
    }
    val filtered = result.toList()

    val sort = query.sort ?: return filtered
    val column = columns.firstOrNull { it.key == sort.columnKey } ?: return filtered
    val comparator = columnComparator(column)
    return if (sort.direction == SortDirection.ASCENDING) {
        filtered.sortedWith(comparator)
    } else {
        filtered.sortedWith(comparator.reversed())
    }
}

/** Cuántas filas tiene cada categoría (para mostrar el conteo junto a cada filtro). */
fun <T> countByFilter(rows: List<T>, filters: List<TableFilterSpec<T>>): Map<String, Int> =
    filters.associate { filter -> filter.key to rows.count(filter.matches) }

/**
 * Texto para el portapapeles: encabezados y filas separados por tabuladores (se pega como tabla en Excel, Word o
 * Google Docs). Los saltos de línea y tabuladores dentro de una celda se cambian por espacios.
 */
fun <T> rowsToTsv(columns: List<TableColumnSpec<T>>, rows: List<T>): String = buildString {
    append(columns.joinToString("\t") { cleanCell(it.title) })
    for (row in rows) {
        append('\n')
        append(columns.joinToString("\t") { cleanCell(it.text(row)) })
    }
}

private fun cleanCell(value: String): String =
    value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()

/** Minúsculas y sin acentos: "Léxico" se encuentra buscando "lexico". */
internal fun normalizeForSearch(value: String): String {
    val lower = value.lowercase()
    if (lower.none { it.code > 127 }) return lower
    val sb = StringBuilder(lower.length)
    for (c in lower) {
        sb.append(
            when (c) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                else -> c
            }
        )
    }
    return sb.toString()
}

@Suppress("UNCHECKED_CAST")
private fun <T> columnComparator(column: TableColumnSpec<T>): Comparator<T> {
    val keyOf = column.sortKey
    if (keyOf == null) {
        return Comparator { a, b -> naturalCompare(column.text(a), column.text(b)) }
    }
    return Comparator { a, b ->
        val ka = keyOf(a)
        val kb = keyOf(b)
        when {
            ka == null && kb == null -> 0
            ka == null -> 1 // Los vacíos van al final.
            kb == null -> -1
            ka is String && kb is String -> naturalCompare(ka, kb)
            ka::class == kb::class -> (ka as Comparable<Any>).compareTo(kb)
            else -> naturalCompare(ka.toString(), kb.toString())
        }
    }
}

/**
 * Comparación "natural": sin distinguir mayúsculas y con los números por su valor, de modo que `ID2` va antes que
 * `ID10`. Las celdas vacías van al final.
 */
internal fun naturalCompare(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return when {
        a.isEmpty() && b.isEmpty() -> 0
        a.isEmpty() -> 1
        else -> -1
    }
    val x = normalizeForSearch(a)
    val y = normalizeForSearch(b)
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val cx = x[i]
        val cy = y[j]
        if (cx.isDigit() && cy.isDigit()) {
            val startI = i
            val startJ = j
            while (i < x.length && x[i].isDigit()) i++
            while (j < y.length && y[j].isDigit()) j++
            val nx = x.substring(startI, i).trimStart('0')
            val ny = y.substring(startJ, j).trimStart('0')
            if (nx.length != ny.length) return nx.length - ny.length
            val cmp = nx.compareTo(ny)
            if (cmp != 0) return cmp
        } else {
            if (cx != cy) return cx.compareTo(cy)
            i++
            j++
        }
    }
    return (x.length - i) - (y.length - j)
}
