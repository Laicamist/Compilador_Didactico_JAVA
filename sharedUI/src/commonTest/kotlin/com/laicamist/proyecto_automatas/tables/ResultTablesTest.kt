package edu.itssg.compiler.ui.tables

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.ir.IrFormatKind
import edu.itssg.compiler.core.ir.OptimizationTag
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.core.symboltable.SymbolRole
import edu.itssg.compiler.ui.state.CompilerUiState
import edu.itssg.compiler.ui.state.intermediateCodeBlockReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Paso 3.3: lógica de las tablas del panel de resultados, con el compilador real. */
class ResultTablesTest {

    private val subexpressions = """
        public class Rs{
        public static void main(String [] x){
        double b= Math.random();
        double a=b+5*2;
        double c=a+5*2+8;
        System.out.println(c);}}
    """.trimIndent()

    private val withErrors = "public class A{ public static void main(String []a){ int x = @; y = 3 } }"

    private fun compile(source: String) = JavaDidacticCompiler().compileSynchronously(source)

    //Modelo genérico

    private data class Item(val name: String, val n: Int)

    private val itemColumns = listOf(
        TableColumnSpec<Item>("name", "Nombre", { it.name }),
        TableColumnSpec("n", "Número", { it.n.toString() }, { it.n })
    )

    @Test
    fun ordenCiclaAscendenteDescendenteYOriginal() {
        val q0 = TableQuery()
        val q1 = q0.cycleSort("n")
        assertEquals(TableSort("n", SortDirection.ASCENDING), q1.sort)
        val q2 = q1.cycleSort("n")
        assertEquals(SortDirection.DESCENDING, q2.sort?.direction)
        assertNull(q2.cycleSort("n").sort)
        assertEquals("name", q2.cycleSort("name").sort?.columnKey)
    }

    @Test
    fun ordenNumericoYNatural() {
        val rows = listOf(Item("ID10", 10), Item("ID2", 2), Item("id1", 1), Item("", 5))
        val byNumber = applyTableQuery(rows, itemColumns, TableQuery(sort = TableSort("n", SortDirection.DESCENDING)))
        assertEquals(listOf(10, 5, 2, 1), byNumber.map { it.n })
        val byName = applyTableQuery(rows, itemColumns, TableQuery(sort = TableSort("name", SortDirection.ASCENDING)))
        assertEquals(listOf("id1", "ID2", "ID10", ""), byName.map { it.name }) // vacíos al final
        assertTrue(naturalCompare("T2", "T10") < 0)
    }

    @Test
    fun busquedaSinAcentosNiMayusculas() {
        val rows = listOf(Item("Léxico", 1), Item("Semántico", 2))
        assertEquals(listOf("Léxico"), applyTableQuery(rows, itemColumns, TableQuery(search = "LEXICO")).map { it.name })
        assertEquals(2, applyTableQuery(rows, itemColumns, TableQuery(search = "   ")).size)
    }

    @Test
    fun copiaComoTablaSeparadaPorTabuladores() {
        val tsv = rowsToTsv(itemColumns, listOf(Item("a\tb\nc", 3)))
        assertEquals("Nombre\tNúmero\na b c\t3", tsv)
    }

    //Tokens

    @Test
    fun tablaDeTokensConColumnasDeLaMateria() {
        val report = compile(subexpressions)
        val rows = buildTokenRows(report)
        assertEquals(report.tokens.count { it.category != TokenCategory.EOF }, rows.size)
        val rs = rows.first { it.lexeme == "Rs" }
        assertEquals("1", rs.idColumn)
        assertEquals("ID", rs.token)
        assertEquals(1, rs.line)
        assertEquals(14, rs.column)
        // Un identificador ya registrado se escribe como IDn y sin número en la columna ID.
        val reuse = rows.last { it.category == TokenCategory.ID }
        assertTrue(reuse.lexeme.startsWith("ID"), reuse.toString())
        assertEquals("", reuse.idColumn)
        // El rango apunta al lexema real en el código.
        assertEquals("Rs", subexpressions.substring(rs.span.start.offset, rs.span.end.offset))
    }

    @Test
    fun filtroDeTokensPorFamilia() {
        val rows = buildTokenRows(compile(subexpressions))
        val counts = countByFilter(rows, TokenTable.filters)
        assertTrue((counts[TokenGroup.LITERALS.name] ?: 0) >= 4)
        val onlyOperators = applyTableQuery(rows, TokenTable.columns, TableQuery(filters = setOf(TokenGroup.OPERATORS.name)), TokenTable.filters)
        assertTrue(onlyOperators.isNotEmpty())
        assertTrue(onlyOperators.all { it.group == TokenGroup.OPERATORS })
        // Dos familias a la vez se combinan con "o".
        val both = applyTableQuery(
            rows, TokenTable.columns,
            TableQuery(filters = setOf(TokenGroup.OPERATORS.name, TokenGroup.DELIMITERS.name)), TokenTable.filters
        )
        assertEquals((counts[TokenGroup.OPERATORS.name] ?: 0) + (counts[TokenGroup.DELIMITERS.name] ?: 0), both.size)
        // Buscar por mnemónico.
        assertTrue(applyTableQuery(rows, TokenTable.columns, TableQuery(search = "pmain")).any { it.category == TokenCategory.PMAIN })
    }

    @Test
    fun todasLasCategoriasTienenFamilia() {
        val sinFamilia = TokenCategory.entries.filter { it != TokenCategory.EOF && it.group() == TokenGroup.OTHER }
        assertTrue(sinFamilia.isEmpty(), sinFamilia.toString())
        assertEquals(TokenGroup.RESERVED, TokenCategory.PMAIN.group())
        assertEquals(TokenGroup.TYPES, TokenCategory.STR.group())
        assertEquals(TokenGroup.OPERATORS, TokenCategory.CA.group())
    }

    //Símbolos

    @Test
    fun tablaDeSimbolosConApariciones() {
        val report = compile(subexpressions)
        val rows = buildSymbolRows(report)
        val b = rows.first { it.lexeme == "b" }
        assertEquals("ID3", b.referenceCode)
        assertEquals("double", b.dataType)
        assertEquals(SymbolRole.VARIABLE, b.entry.role)
        assertEquals(2, b.occurrences.size) // declaración y uso en a = b + 5*2
        assertTrue(b.occurrences.all { subexpressions.substring(it.start.offset, it.end.offset) == "b" })
        val classes = applyTableQuery(rows, SymbolTableSpec.columns, TableQuery(filters = setOf(SymbolRole.CLASS.name)), SymbolTableSpec.filters)
        assertEquals(listOf("Rs"), classes.map { it.lexeme })
    }

    //Errores

    @Test
    fun tablaDeErroresConFormatoDeLaMateria() {
        val report = compile(withErrors)
        val rows = errorRowsFromReport(report.errors)
        assertEquals(listOf("Línea", "Error", "Tipo", "Justificación"), ErrorTableSpec.columns.map { it.title })
        assertEquals(setOf(ErrorPhaseType.LEXICAL, ErrorPhaseType.SYNTACTIC, ErrorPhaseType.SEMANTIC), rows.map { it.type }.toSet())
        val semantic = applyTableQuery(rows, ErrorTableSpec.columns, TableQuery(filters = setOf(ErrorPhaseType.SEMANTIC.name)), ErrorTableSpec.filters)
        assertEquals(listOf("y"), semantic.map { it.lexeme })
        val byType = applyTableQuery(rows, ErrorTableSpec.columns, TableQuery(sort = TableSort("type", SortDirection.ASCENDING)))
        assertEquals(ErrorPhaseType.LEXICAL, byType.first().type)
        assertEquals(ErrorPhaseType.SEMANTIC, byType.last().type)
        // La justificación de dos líneas se copia en una sola celda.
        val tsv = rowsToTsv(ErrorTableSpec.columns, rows)
        assertEquals(rows.size + 1, tsv.lines().size)
        assertTrue(tsv.lines()[1].startsWith("1\t@\tLéxico\tEn Java no existe"), tsv)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    //Código intermedio

    @Test
    fun comparacionDeCuadruplosAlineada() {
        val report = compile(subexpressions)
        val c = buildIrComparison(report, IrFormatKind.QUADRUPLE, optimizationRevealed = true)
        assertTrue(c.isAligned)
        assertEquals(10, c.originalCount)
        assertEquals(8, c.optimizedCount)
        assertEquals(20, c.reductionPercent)

        val folded = c.rows.first { it.originalStep == 3 }
        assertEquals("(5, *, 2, 10)", folded.originalText)
        assertEquals(OptimizationTag.FOLDING, folded.applied?.tag)
        assertEquals(4, folded.applied?.affectedStep)
        assertTrue(folded.isRemoved)
        assertNull(folded.optimizedText)

        val receiver = c.rows.first { it.originalStep == 4 }
        assertEquals(listOf("FO"), receiver.received.map { it.code })
        assertEquals(3, receiver.optimizedStep)
        assertEquals("([2], +, 10, x+10.0)", receiver.optimizedText)

        val rs = c.rows.first { it.originalStep == 6 }
        assertEquals("RS", rs.applied?.code)
        assertEquals("RS[3]", rs.applied?.displayCode)
        assertEquals(10, c.optimizationTable.size)
        assertEquals("Triplo original", OptimizationTableSpec.columns(IrFormatKind.TRIPLE).first().title)
        assertEquals(listOf("FO", "RS"), c.techniques.map { it.code })

        // Cada renglón apunta al fragmento del código que lo genera.
        val sout = c.rows.first { it.originalStep == 10 }
        assertEquals(6, sout.span?.start?.line)
        assertEquals(8, sout.optimizedStep)
    }

    @Test
    fun comparacionDeTresDireccionesYTriplos() {
        val report = compile(subexpressions)
        val tac = buildIrComparison(report, IrFormatKind.THREE_ADDRESS, optimizationRevealed = true)
        // Tres Direcciones no se optimiza: solo el original, sin marcas ni técnicas.
        assertFalse(tac.isOptimizable)
        assertFalse(tac.includesOptimized)
        assertEquals(10, tac.rows.size)
        assertTrue(tac.rows.all { it.optimizedText == null && it.applied == null && !it.isRemoved && it.received.isEmpty() })
        assertTrue(tac.techniques.isEmpty() && tac.optimizationTable.isEmpty())
        assertEquals(tac.originalCount, tac.optimizedCount)
        assertFalse(irComparisonToText(tac).contains("optimizado"))
        assertEquals("ID3 = T1", tac.rows[1].originalText)
        assertEquals("free all", tac.rows[1].originalNote)
        assertEquals("ID1 PMAIN -> T1", tac.originalClosing)
        val triples = buildIrComparison(report, IrFormatKind.TRIPLE, optimizationRevealed = true)
        assertEquals("(5, *, 2)", triples.rows[2].originalText)
        assertTrue(triples.rows[2].isRemoved)
        // La nota "[modificado: …]" se conserva en el original.
        assertNotNull(triples.rows[3].originalNote)
    }

    @Test
    fun antesDeLaOptimizacionSoloSeMuestraElOriginal() {
        val c = buildIrComparison(compile(subexpressions), IrFormatKind.QUADRUPLE, optimizationRevealed = false)
        assertFalse(c.includesOptimized)
        assertTrue(c.rows.all { it.optimizedText == null && it.applied == null && !it.isRemoved })
        assertTrue(c.techniques.isEmpty())
        assertFalse(irComparisonToText(c).contains("optimizado"))
    }

    @Test
    fun textoCopiadoConFormatoDeLasLaminas() {
        val text = irComparisonToText(buildIrComparison(compile(subexpressions), IrFormatKind.QUADRUPLE, true))
        assertTrue(text.contains("3. (5, *, 2, 10)  FO 4 -> ELIMINAR"), text)
        assertTrue(text.contains("ID1 PMAIN -> [8]"), text)
        assertTrue(text.contains("Resumen: 10 → 8 instrucciones (−20 %)"), text)
        assertTrue(text.contains("Cuádruplo original\tOptimización\tCuádruplo afectado\tCuádruplo modificado"), text)
        assertTrue(text.contains("6. (5, *, 2, 10)  RS[3] 7 -> ELIMINAR"), text)
        assertTrue(text.contains("1. (Math, ., random(), x)\t~\t~\t~"), text)
        assertTrue(text.contains("3. (5, *, 2, 10)\tFO\t4\t3. (5, *, 2, 10) -> Eliminado"), text)
        assertTrue(text.contains("6. (5, *, 2, 10)\tRS[3]\t7\t5. (5, *, 2, 10) -> Eliminado"), text)
        assertTrue(text.contains("10. (PMAIN, SOUT, [9], x+28.0)\t~\t~\t8. (PMAIN, SOUT, [7], x+28.0)"), text)
    }

    @Test
    fun codigoIntermedioBloqueadoConErroresYConsultasPorDefecto() {
        assertNull(compile(subexpressions).intermediateCodeBlockReason())
        assertNotNull(compile(withErrors).intermediateCodeBlockReason())
        assertEquals(TableQuery(), CompilerUiState().tableQuery(DataTableKind.ERRORS))
    }
}
