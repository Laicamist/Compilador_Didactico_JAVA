package edu.itssg.compiler.ui.tables

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.error.CompilerError
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.core.ir.OptimizationTag
import edu.itssg.compiler.core.lexer.LexicalAnomaly
import edu.itssg.compiler.core.lexer.Token
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.ir.displayCodeFor
import edu.itssg.compiler.core.optimizer.OptimizationTableRow
import edu.itssg.compiler.core.security.SourcePosition
import edu.itssg.compiler.core.security.SourceSpan
import edu.itssg.compiler.core.semantic.SemanticAstNode
import edu.itssg.compiler.core.symboltable.SymbolEntry
import edu.itssg.compiler.core.symboltable.SymbolRole

/**
 * Filas de las tablas del panel de resultados, construidas a partir del [CompilationReport] (lógica pura).
 * Cada tabla define aquí sus columnas ([TableColumnSpec]) y sus filtros ([TableFilterSpec]); la parte visual está
 * en los paneles Compose del mismo paquete.
 */

//Tabla de Tokens

/** Familias de tokens de la tabla oficial, para el filtro por categoría. */
enum class TokenGroup(val label: String) {
    RESERVED("Palabras reservadas"),
    TYPES("Tipos"),
    IDENTIFIERS("Identificadores"),
    LITERALS("Literales"),
    OPERATORS("Operadores"),
    DELIMITERS("Delimitadores"),
    OTHER("Otros")
}

private val TYPE_CATEGORIES = setOf(TokenCategory.TD, TokenCategory.STR, TokenCategory.TM)
private val LITERAL_CATEGORIES = setOf(
    TokenCategory.VTB, TokenCategory.VTS, TokenCategory.VTE, TokenCategory.VTD, TokenCategory.VTF,
    TokenCategory.VTC, TokenCategory._NU
)
private val OPERATOR_CATEGORIES = setOf(
    TokenCategory.ODA, TokenCategory.OPA, TokenCategory.OPT, TokenCategory.OPL, TokenCategory.OPR,
    TokenCategory.CA, TokenCategory.DEC, TokenCategory.INC, TokenCategory.OPB, TokenCategory.XOR, TokenCategory.OPN
)
private val DELIMITER_CATEGORIES = setOf(
    TokenCategory.FI, TokenCategory.LLA, TokenCategory.LLC, TokenCategory.PA, TokenCategory.PC,
    TokenCategory.COA, TokenCategory.COC, TokenCategory.DOP, TokenCategory.CO
)

fun TokenCategory.group(): TokenGroup = when {
    this == TokenCategory.ID -> TokenGroup.IDENTIFIERS
    this == TokenCategory.EOF -> TokenGroup.OTHER
    this in TYPE_CATEGORIES -> TokenGroup.TYPES
    this in LITERAL_CATEGORIES -> TokenGroup.LITERALS
    this in OPERATOR_CATEGORIES -> TokenGroup.OPERATORS
    this in DELIMITER_CATEGORIES -> TokenGroup.DELIMITERS
    this in TokenCategory.KEYWORDS.values || this == TokenCategory.PMAIN || this == TokenCategory.TDD -> TokenGroup.RESERVED
    else -> TokenGroup.OTHER
}

/**
 * Fila de la Tabla de Tokens con las columnas de la materia (ID | LEXEMA | TOKEN), más la descripción del token y
 * su posición.
 *
 * @property number Orden de aparición (1-indexado); es la clave de la fila.
 * @property idColumn Número de ID solo en la primera aparición de un identificador (como en las láminas).
 * @property lexeme Lexema tal como se escribe en la tabla (`IDn` para identificadores ya registrados).
 */
data class TokenRow(
    val number: Int,
    val idColumn: String,
    val lexeme: String,
    val token: String,
    val category: TokenCategory,
    val line: Int,
    val column: Int,
    val span: SourceSpan
) {
    val group: TokenGroup get() = category.group()
    val description: String get() = category.description
}

/**
 * Usa las filas que el compilador ya construyó ([CompilationReport.lexicalTableRows]) y las une con sus tokens para
 * conocer la ubicación exacta. Si por alguna razón no coinciden, se construyen desde los tokens.
 */
fun buildTokenRows(report: CompilationReport): List<TokenRow> {
    val tokens = report.tokens.filter { it.category != TokenCategory.EOF }
    val tableRows = report.lexicalTableRows
    if (tableRows.size == tokens.size) {
        return tokens.mapIndexed { i, token ->
            val row = tableRows[i]
            TokenRow(i + 1, row.idColumn, row.lexemeColumn, row.tokenColumn, token.category, row.line, row.column, token.span)
        }
    }
    return tokens.mapIndexed { i, token -> tokenRowOf(i + 1, token) }
}

private fun tokenRowOf(number: Int, token: Token) = TokenRow(
    number = number,
    idColumn = if (token.category == TokenCategory.ID && token.isFirstDeclaration && token.symbolId != null) {
        token.symbolId.toString()
    } else {
        ""
    },
    lexeme = token.tableDisplayLexeme,
    token = token.category.mnemonic,
    category = token.category,
    line = token.span.start.line,
    column = token.span.start.column,
    span = token.span
)

object TokenTable {
    val columns: List<TableColumnSpec<TokenRow>> = listOf(
        TableColumnSpec("n", "#", { it.number.toString() }, { it.number }, searchable = false),
        TableColumnSpec("id", "ID", { it.idColumn }, { it.idColumn.toIntOrNull() }),
        TableColumnSpec("lexeme", "LEXEMA", { it.lexeme }),
        TableColumnSpec("token", "TOKEN", { it.token }),
        TableColumnSpec("description", "Descripción", { it.description }),
        TableColumnSpec("line", "Línea", { it.line.toString() }, { it.line * 100_000L + it.column }, searchable = false),
        TableColumnSpec("column", "Columna", { it.column.toString() }, { it.column }, searchable = false)
    )

    val filters: List<TableFilterSpec<TokenRow>> = TokenGroup.entries
        .filter { it != TokenGroup.OTHER }
        .map { group -> TableFilterSpec(group.name, group.label) { row -> row.group == group } }
}

//Tabla de Símbolos

/**
 * Fila de la Tabla de Símbolos (la que el Analizador Semántico deja numerada en definitiva).
 *
 * @property occurrences Todas las apariciones del identificador en el código, en orden; un clic repetido en la fila
 *   recorre una por una.
 */
data class SymbolRow(
    val entry: SymbolEntry,
    val occurrences: List<SourceSpan>
) {
    val symbolId: Int get() = entry.symbolId
    val referenceCode: String get() = entry.referenceCode
    val lexeme: String get() = entry.lexeme
    val roleLabel: String get() = entry.role.label
    val dataType: String get() = entry.dataType ?: "—"
    val scope: String get() = entry.scope
    val line: Int get() = entry.firstSeen.line
    val column: Int get() = entry.firstSeen.column
}

fun buildSymbolRows(report: CompilationReport): List<SymbolRow> {
    val byId = HashMap<Int, MutableList<SourceSpan>>()
    for (token in report.tokens) {
        val id = token.symbolId
        if (token.category == TokenCategory.ID && id != null) byId.getOrPut(id) { ArrayList() }.add(token.span)
    }
    return report.symbolEntries.map { entry ->
        val spans = byId[entry.symbolId]?.sortedBy { it.start.offset }
            ?: listOf(singleLineSpan(entry.firstSeen, entry.lexeme.length))
        SymbolRow(entry, spans)
    }
}

object SymbolTableSpec {
    val columns: List<TableColumnSpec<SymbolRow>> = listOf(
        TableColumnSpec("id", "ID", { it.referenceCode }, { it.symbolId }),
        TableColumnSpec("lexeme", "Lexema", { it.lexeme }),
        TableColumnSpec("role", "Rol", { it.roleLabel }),
        TableColumnSpec("type", "TD", { it.dataType }),
        TableColumnSpec("scope", "Ámbito", { it.scope }),
        TableColumnSpec("line", "Línea", { it.line.toString() }, { it.line * 100_000L + it.column }, searchable = false),
        TableColumnSpec("uses", "Apariciones", { it.occurrences.size.toString() }, { it.occurrences.size }, searchable = false)
    )

    val filters: List<TableFilterSpec<SymbolRow>> = SymbolRole.entries.map { role ->
        TableFilterSpec(role.name, roleFilterLabel(role)) { row -> row.entry.role == role }
    }

    private fun roleFilterLabel(role: SymbolRole): String = when (role) {
        SymbolRole.PACKAGE -> "Paquetes"
        SymbolRole.CLASS -> "Clases"
        SymbolRole.METODO -> "Métodos"
        SymbolRole.VARIABLE -> "Variables"
        SymbolRole.PARAMETER -> "Parámetros"
        SymbolRole.OBJECT -> "Objetos"
        SymbolRole.EXTERNAL_MEMBER -> "Miembros externos"
        SymbolRole.UNKNOWN -> "Sin clasificar"
    }
}

//Tabla de Errores

/**
 * Fila de la Tabla de Errores con las columnas de la materia: Línea | Error | Tipo | Justificación.
 *
 * @property number Número del error en la tabla del compilador; `null` en los errores léxicos que el editor detecta
 *   al escribir, antes de compilar.
 * @property key Clave estable de la fila.
 */
data class ErrorTableRow(
    val number: Int?,
    val type: ErrorPhaseType,
    val line: Int,
    val column: Int,
    val lexeme: String,
    val justification: String,
    val span: SourceSpan
) {
    val key: String get() = "${type.name}:${span.start.offset}:${number ?: 0}:${justification.hashCode()}"
}

fun errorRowsFromReport(errors: List<CompilerError>): List<ErrorTableRow> = errors.map {
    ErrorTableRow(it.index, it.type, it.line, it.column, it.offendingLexeme, it.justification, it.span)
}

fun errorRowsFromAnomalies(anomalies: List<LexicalAnomaly>): List<ErrorTableRow> = anomalies.map {
    ErrorTableRow(null, ErrorPhaseType.LEXICAL, it.span.start.line, it.span.start.column, it.lexeme, it.justification, it.span)
}

object ErrorTableSpec {
    private val typeOrder = ErrorPhaseType.entries

    /** Columnas exactas de la Tabla de Errores de la materia (también al copiar). */
    val columns: List<TableColumnSpec<ErrorTableRow>> = listOf(
        TableColumnSpec("line", "Línea", { it.line.toString() }, { it.line * 100_000L + it.column }),
        TableColumnSpec("error", "Error", { it.lexeme }),
        TableColumnSpec("type", "Tipo", { it.type.displayName }, { typeOrder.indexOf(it.type) }),
        TableColumnSpec("justification", "Justificación", { it.justification })
    )

    val filters: List<TableFilterSpec<ErrorTableRow>> = ErrorPhaseType.entries.map { type ->
        TableFilterSpec(type.name, type.displayName) { row -> row.type == type }
    }
}

//Código intermedio: comparación original / optimizado

/**
 * Técnica de optimización aplicada a un renglón (bitácora de la U3).
 *
 * @property sourceStep Renglón original optimizado.
 * @property affectedStep Renglón original al que pasa el resultado (`FO 4`), o `null` (ej. `CM`).
 * @property repeatsStep Renglón original que se repite (solo RS/RSD).
 */
data class AppliedTechnique(
    val tag: OptimizationTag,
    val isDerived: Boolean,
    val sourceStep: Int,
    val affectedStep: Int?,
    val explanation: String,
    val repeatsStep: Int? = null
) {
    val code: String get() = tag.codeFor(isDerived)

    /** Código para la tabla: `RS[1]` en Reducción de Subexpresiones; igual a [code] en las demás. */
    val displayCode: String get() = tag.displayCodeFor(isDerived, repeatsStep)
    val description: String get() = tag.descriptionFor(isDerived)
}

/**
 * Renglón de la comparación lado a lado. Cuando la comparación está alineada, cada renglón original queda frente al
 * renglón optimizado que le corresponde (o frente a "eliminado").
 *
 * @property originalNote Nota del original (`[modificado: …]` o `free T1` en Tres Direcciones).
 * @property applied Técnica que se aplicó a este renglón (su marca `FO 4 -> ELIMINAR`).
 * @property received Optimizaciones de renglones anteriores cuyo resultado llegó a este renglón.
 * @property isRemoved El renglón no aparece en el código optimizado.
 * @property span Fragmento del código fuente que genera el renglón (para resaltarlo en el editor).
 */
data class IrComparisonRow(
    val originalStep: Int?,
    val originalText: String?,
    val originalNote: String?,
    val applied: AppliedTechnique?,
    val received: List<AppliedTechnique>,
    val isRemoved: Boolean,
    val optimizedStep: Int?,
    val optimizedText: String?,
    val optimizedNote: String?,
    val span: SourceSpan?
) {
    val key: String get() = "${originalStep ?: 0}:${optimizedStep ?: 0}"
}

/** Conteo de una técnica para la leyenda ("FO Folding ×2"). */
data class TechniqueCount(val tag: OptimizationTag, val isDerived: Boolean, val count: Int) {
    val code: String get() = tag.codeFor(isDerived)
    val description: String get() = tag.descriptionFor(isDerived)
}

/**
 * Comparación completa para un formato de código intermedio.
 *
 * @property includesOptimized Se muestra el código optimizado: `false` en Tres Direcciones (no se optimiza) y en
 *   Paso a paso antes de llegar a la fase de Optimización.
 * @property isOptimizable El formato admite optimización (Triplos y Cuádruplos; Tres Direcciones no).
 * @property isAligned `true` si cada renglón original quedó frente a su renglón optimizado; si el número de
 *   renglones no permite alinear, se muestran uno junto al otro en orden.
 */
data class IrComparison(
    val format: IrFormatKind,
    val header: String,
    val originalClosing: String,
    val optimizedClosing: String,
    val rows: List<IrComparisonRow>,
    val originalCount: Int,
    val optimizedCount: Int,
    val includesOptimized: Boolean,
    val isOptimizable: Boolean,
    val isAligned: Boolean,
    val techniques: List<TechniqueCount>,
    val log: List<AppliedTechnique>,
    val optimizationTable: List<OptimizationTableRow>
) {
    val removedCount: Int get() = (originalCount - optimizedCount).coerceAtLeast(0)
    val reductionPercent: Int get() = if (originalCount == 0) 0 else removedCount * 100 / originalCount
}

private class IrLine(val step: Int, val text: String, val note: String?)

/**
 * Indicación del autor de las reglas del compilador: la optimización se aplica a Triplos y Cuádruplos; el código de
 * Tres Direcciones no se optimiza.
 */
val IrFormatKind.isOptimizable: Boolean get() = this != IrFormatKind.THREE_ADDRESS

/**
 * @param optimizationRevealed La fase de Optimización ya se muestra (siempre, salvo en Paso a paso antes de llegar a
 *   ella). En Tres Direcciones no se muestra columna optimizada aunque sea `true`.
 */
fun buildIrComparison(report: CompilationReport, format: IrFormatKind, optimizationRevealed: Boolean): IrComparison {
    val includeOptimized = optimizationRevealed && format.isOptimizable
    val original = report.intermediateCode
    val optimization = report.optimizationResult
    val optimized = optimization.optimizedBundle

    val log = optimization.appliedOptimizations.map {
        AppliedTechnique(it.tag, it.isDerived, it.stepNumber, it.affectedStep, it.afterAction, it.repeatsStep)
    }
    val logByStep = log.associateBy { it.sourceStep }
    val receivedByStep = log.filter { it.affectedStep != null }.groupBy { it.affectedStep!! }
    val removedSteps = optimization.annotatedQuadruples.filter { it.isMarkedForDeletion }.map { it.stepNumber }.toSet()
    val stepSpans = stepSpansOf(report.semanticTree)

    val originalLines: List<IrLine> = when (format) {
        IrFormatKind.TRIPLE -> optimization.annotatedTriples.map {
            IrLine(it.stepNumber, "(${it.val1}, ${it.operator}, ${it.val2})", it.annotation.takeIf { _ -> logByStep[it.stepNumber] == null })
        }
        IrFormatKind.QUADRUPLE -> optimization.annotatedQuadruples.map {
            IrLine(it.stepNumber, "(${it.val1}, ${it.operator}, ${it.val2}, ${it.result})", it.annotation.takeIf { _ -> logByStep[it.stepNumber] == null })
        }
        IrFormatKind.THREE_ADDRESS -> original.threeAddress.map { threeAddressLine(it.index, it) }
    }
    val optimizedLines: List<IrLine> = when (format) {
        IrFormatKind.TRIPLE -> optimized.triples.map { IrLine(it.stepNumber, "(${it.val1}, ${it.operator}, ${it.val2})", it.annotation) }
        IrFormatKind.QUADRUPLE -> optimized.quadruples.map {
            IrLine(it.stepNumber, "(${it.val1}, ${it.operator}, ${it.val2}, ${it.result})", it.annotation)
        }
        IrFormatKind.THREE_ADDRESS -> optimized.threeAddress.map { threeAddressLine(it.index, it) }
    }

    val (header, originalClosing, optimizedClosing) = when (format) {
        IrFormatKind.TRIPLE -> Triple(original.rootHeader, original.triplesClosing, optimized.triplesClosing)
        IrFormatKind.QUADRUPLE -> Triple(original.rootHeader, original.quadruplesClosing, optimized.quadruplesClosing)
        IrFormatKind.THREE_ADDRESS -> Triple(original.rootHeader, original.threeAddressClosing, optimized.threeAddressClosing)
    }

    // Renglones que sobreviven, en orden: el i-ésimo superviviente es el renglón i del código optimizado.
    val keptSteps = original.quadruples.map { it.stepNumber }.filter { it !in removedSteps }
    val aligned = includeOptimized &&
            keptSteps.size == optimized.quadruples.size &&
            originalLines.size == original.quadruples.size &&
            optimizedLines.size == optimized.quadruples.size

    val rows: List<IrComparisonRow> = if (!includeOptimized) {
        originalLines.map { line ->
            IrComparisonRow(line.step, line.text, line.note, null, emptyList(), false, null, null, null, stepSpans[line.step])
        }
    } else if (aligned) {
        val optimizedPosition = keptSteps.withIndex().associate { (i, step) -> step to i }
        originalLines.mapIndexed { i, line ->
            val originalStep = original.quadruples[i].stepNumber
            val opt = optimizedPosition[originalStep]?.let { optimizedLines[it] }
            IrComparisonRow(
                originalStep = line.step,
                originalText = line.text,
                originalNote = line.note,
                applied = logByStep[originalStep],
                received = receivedByStep[originalStep].orEmpty(),
                isRemoved = originalStep in removedSteps,
                optimizedStep = opt?.step,
                optimizedText = opt?.text,
                optimizedNote = opt?.note,
                span = stepSpans[originalStep]
            )
        }
    } else {
        // Sin correspondencia segura entre renglones: se muestran uno junto al otro, en orden.
        (0 until maxOf(originalLines.size, optimizedLines.size)).map { i ->
            val o = originalLines.getOrNull(i)
            val p = optimizedLines.getOrNull(i)
            IrComparisonRow(
                originalStep = o?.step,
                originalText = o?.text,
                originalNote = o?.note,
                applied = o?.let { logByStep[it.step] },
                received = emptyList(),
                isRemoved = false,
                optimizedStep = p?.step,
                optimizedText = p?.text,
                optimizedNote = p?.note,
                span = o?.let { stepSpans[it.step] }
            )
        }
    }

    val techniques = if (includeOptimized) {
        log.groupBy { it.tag to it.isDerived }
            .map { (key, list) -> TechniqueCount(key.first, key.second, list.size) }
            .sortedWith(compareBy({ it.tag.ordinal }, { it.isDerived }))
    } else {
        emptyList()
    }

    return IrComparison(
        format = format,
        header = header,
        originalClosing = originalClosing,
        optimizedClosing = optimizedClosing,
        rows = rows,
        originalCount = originalLines.size,
        optimizedCount = if (includeOptimized) optimizedLines.size else originalLines.size,
        includesOptimized = includeOptimized,
        isOptimizable = format.isOptimizable,
        isAligned = aligned,
        techniques = techniques,
        log = if (includeOptimized) log else emptyList(),
        optimizationTable = if (includeOptimized && optimization.appliedOptimizations.isNotEmpty()) {
            optimization.optimizationTable
        } else {
            emptyList()
        }
    )
}

private fun threeAddressLine(index: Int, instruction: edu.itssg.compiler.core.ir.ThreeAddressInstruction): IrLine {
    val core = if (instruction.isDirectAssignmentOrReturn) {
        instruction.targetLabel
    } else {
        "${instruction.targetLabel}. (${instruction.val1}, ${instruction.operator}, ${instruction.val2})"
    }
    return IrLine(index, core, instruction.freeNote?.takeIf { it.isNotBlank() })
}

/** Renglón del código intermedio (`evaluationStepNumber`) → fragmento del código que lo genera. */
fun stepSpansOf(root: SemanticAstNode): Map<Int, SourceSpan> {
    val result = HashMap<Int, SourceSpan>()
    val stack = ArrayDeque<SemanticAstNode>()
    stack.addLast(root)
    var visited = 0
    while (stack.isNotEmpty() && visited < MAX_TREE_NODES) {
        val node = stack.removeLast()
        visited++
        node.evaluationStepNumber?.let { step -> result.getOrPut(step) { node.span } }
        for (child in node.children) stack.addLast(child)
    }
    return result
}

/**
 * Texto para copiar el formato elegido: código original (con sus marcas de optimización), código optimizado y la
 * tabla de optimización, con el formato de las láminas.
 */
fun irComparisonToText(comparison: IrComparison): String = buildString {
    val name = comparison.format.displayName
    appendLine("$name — original")
    appendLine(comparison.header)
    comparison.rows.filter { it.originalText != null }.forEach { row ->
        append(row.originalStep).append(". ").append(row.originalText)
        row.applied?.let { applied ->
            append("  ").append(applied.displayCode)
            applied.affectedStep?.let { append(' ').append(it) }
            if (row.isRemoved) append(" -> ELIMINAR")
        }
        row.originalNote?.let { note -> append(if (comparison.format == IrFormatKind.THREE_ADDRESS) " --> " else "  ").append(note) }
        appendLine()
    }
    appendLine(comparison.originalClosing)
    if (!comparison.includesOptimized) return@buildString

    appendLine()
    appendLine("$name — optimizado")
    appendLine(comparison.header)
    comparison.rows.filter { it.optimizedText != null }.forEach { row ->
        append(row.optimizedStep).append(". ").append(row.optimizedText)
        row.optimizedNote?.let { note -> append(if (comparison.format == IrFormatKind.THREE_ADDRESS) " --> " else "  ").append(note) }
        appendLine()
    }
    appendLine(comparison.optimizedClosing)
    append("Resumen: ${comparison.originalCount} → ${comparison.optimizedCount} instrucciones (−${comparison.reductionPercent} %)")

    if (comparison.optimizationTable.isNotEmpty()) {
        appendLine()
        appendLine()
        append(rowsToTsv(OptimizationTableSpec.columns(comparison.format), comparison.optimizationTable))
    }
}

/**
 * Tabla de optimización con el formato de los ejercicios: un renglón por cada cuádruplo original.
 * `~` = sin cambio; los eliminados se escriben `n. (…) -> Eliminado`.
 */
object OptimizationTableSpec {
    const val UNCHANGED: String = "~"

    fun instructionName(format: IrFormatKind): String = if (format == IrFormatKind.TRIPLE) "Triplo" else "Cuádruplo"

    fun originalText(row: OptimizationTableRow, format: IrFormatKind): String =
        "${row.stepNumber}. ${if (format == IrFormatKind.TRIPLE) row.originalTriple else row.originalQuadruple}"

    fun modifiedInstruction(row: OptimizationTableRow, format: IrFormatKind): String =
        "${row.modifiedNumber}. ${if (format == IrFormatKind.TRIPLE) row.modifiedTriple else row.modifiedQuadruple}"

    /** Columna "modificado": `~`, `n. (…)` (final) o `n. (…) -> Eliminado`. */
    fun modifiedText(row: OptimizationTableRow, format: IrFormatKind): String = when {
        row.isUnchanged -> UNCHANGED
        row.isFinal -> modifiedInstruction(row, format)
        else -> "${modifiedInstruction(row, format)} -> Eliminado"
    }

    fun columns(format: IrFormatKind): List<TableColumnSpec<OptimizationTableRow>> {
        val name = instructionName(format)
        return listOf(
            TableColumnSpec("original", "$name original", { originalText(it, format) }),
            TableColumnSpec("optimization", "Optimización", { it.displayCode ?: UNCHANGED }),
            TableColumnSpec("affected", "$name afectado", { it.affectedStep?.toString() ?: UNCHANGED }),
            TableColumnSpec("modified", "$name modificado", { modifiedText(it, format) })
        )
    }
}

/** Rango de una sola línea que empieza en [start] y mide [length] caracteres. */
internal fun singleLineSpan(start: SourcePosition, length: Int): SourceSpan {
    val safe = length.coerceAtLeast(0)
    return SourceSpan(start, SourcePosition(start.line, start.column + safe, start.offset + safe))
}

private const val MAX_TREE_NODES = 200_000
