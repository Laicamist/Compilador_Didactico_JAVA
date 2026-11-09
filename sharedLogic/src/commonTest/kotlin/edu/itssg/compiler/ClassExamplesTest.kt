package edu.itssg.compiler

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.examples.ClassExamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validación contra los ejemplos resueltos en las láminas de la materia (U1, U2 y U3).
 * Cada prueba indica qué parte coincide renglón por renglón con la lámina.
 */
class ClassExamplesTest {

    private fun compile(source: String): CompilationReport = JavaDidacticCompiler().compileSynchronously(source)

    private fun quads(r: CompilationReport) = r.intermediateCode.quadruples.map { it.formatAcademic() }

    private fun threeAddress(r: CompilationReport) = r.intermediateCode.threeAddress.map { it.formatAcademic() }

    // ------------------------------------------------------------------ U2: clase Uno

    private val uno = ClassExamples.UNO.source

    @Test
    fun unoCuadruplosIgualQueLaLamina() {
        val r = compile(uno)
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        assertEquals(
            listOf(
                "1. (ID2, =, 3, 3.0f)",
                "2. ([1], +, 1, 4.0f)",
                "3. (ID5, =, '@', 64)",
                "4. (ID3, =, [2], 4.0f)",
                "5. ([3], *, [4], 256.0f)",
                "6. (45.0, +, [5], 301.0)",
                "7. (ID4, SOUT, [6], 301.0)",
                "8. (ID1, ., [7], 301.0)"
            ),
            quads(r)
        )
        assertEquals("ID1 PMAIN -> [8]", r.intermediateCode.quadruplesClosing)
    }

    @Test
    fun unoTriplosYTresDirecciones() {
        val r = compile(uno)
        assertEquals("1. (ID2, =, 3)", r.intermediateCode.triples.first().formatAcademic())
        assertEquals(
            listOf("ID2 = 3", "T1. (ID2, +, 1)", "ID5 = '@'", "ID3 = T1", "T1. (ID5, *, ID3)", "T2. (45.0, +, T1)", "ID4 SOUT T2"),
            threeAddress(r).take(7).map { it.substringBefore(" -->") }
        )
    }

    // ------------------------------------------------------------------ U2: clase Nueva

    private val nueva = ClassExamples.NUEVA.source

    @Test
    fun nuevaCuadruplosYTresDireccionesIgualQueLaLamina() {
        val r = compile(nueva)
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        assertEquals(
            listOf(
                "1. (ID2, =, 0.0f, 0.0f)",
                "2. (1, +, [1], 1.0f)",
                "3. (ID3, =, [2], 1.0)",
                "4. (15, /, 4, 3)",
                "5. ([3], +, [4], 4.0)",
                "6. (ID4, =, [5], 4.0)",
                "7. (ID1, ., [6], 4.0)",
                "8. (PMAIN, SOUT, [7], 4.0)"
            ),
            quads(r)
        )
        assertEquals(
            listOf("ID2 = 0.0f", "T1. (1, +, ID2)", "ID3 = T1", "T1. (15, /, 4)", "T2. (ID3, +, T1)", "ID4 = T2",
                "T1. (ID1, ., ID4)", "T2. (PMAIN, SOUT, T1)"),
            threeAddress(r).map { it.substringBefore(" -->") }
        )
        assertEquals("ID1 PMAIN -> T2", r.intermediateCode.threeAddressClosing)
    }

    @Test
    fun nuevaNotacionDeLaExpresionPrincipal() {
        val r = compile(nueva.replace("println(new Nueva().total)", "println(\"El resultado es: \"+new Nueva().total)"))
        val notations = r.principalExpressionNotations
        assertTrue(notations.contains("Expresión principal: \"El resultado es: \"+(1+0.0f+15/4)"), notations.toString())
        assertTrue(notations.contains("Prefija: + \"El resultado es: \" + + 1 0.0f / 15 4"), notations.toString())
    }

    // ------------------------------------------------------------------ U2: clase A (26 renglones)

    @Test
    fun claseAOrdenDeEjecucionPorNiveles() {
        val r = compile(
            ClassExamples.A_NIVELES.source
        )
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        val triples = r.intermediateCode.triples.map { it.formatAcademic() }
        assertEquals(26, triples.size)
        assertEquals("1. (35, /, 4)", triples[0])
        assertEquals("3. (35, /, 4)", triples[2])
        assertEquals("7. (ID2, =, [5])", triples[6])
        assertEquals("8. (ID2, =, [6])", triples[7])
        assertEquals("9. (1, +, [7])", triples[8])
        assertEquals("11. (\"hola\", ., length())", triples[10])
        assertEquals("24. (ID6, =, [23])", triples[23])
        assertEquals("26. (PMAIN, SOUT, [25])", triples[25])
        // Tres direcciones: nunca más de 3 temporales, con operaciones pendientes como en la lámina
        val lines = threeAddress(r)
        assertTrue(lines.none { it.startsWith("T4") }, lines.toString())
        assertTrue(lines.any { it.startsWith("P3") } && lines.any { it.startsWith("P4") }, lines.toString())
    }

    // ------------------------------------------------------------------ U2: clase Lunes / U1: Viernes

    @Test
    fun lunesTablaDeSimbolosYValores() {
        val r = compile(
            ClassExamples.LUNES.source
        )
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        val symbols = r.symbolEntries.associate { it.symbolId to it.lexeme }
        assertEquals(mapOf(1 to "Lunes", 2 to "a", 3 to "b", 4 to "c", 5 to "qwerty", 6 to "numero", 7 to "a", 8 to "arreglo"), symbols)
        val results = r.intermediateCode.quadruples.map { it.result }
        assertTrue(results.containsAll(listOf("9", "TAB", "10", "\"110\"", "3", "70", "82", "'R'")), results.toString())
        assertEquals("'R'", r.intermediateCode.quadruples.last().result)
    }

    @Test
    fun viernesTablaDeSimbolos() {
        val r = compile(
            ClassExamples.VIERNES.source
        )
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        assertEquals(
            listOf("asesorias", "Viernes", "a", "x", "y", "variable", "a", "num"),
            r.symbolEntries.sortedBy { it.symbolId }.map { it.lexeme }
        )
        assertTrue(r.intermediateCode.quadruples.any { it.formatAcademic().contains("(Math, ., random(), x)") })
    }

    // ------------------------------------------------------------------ U3: clase Facil

    @Test
    fun facilTablaDeOptimizacion() {
        val r = compile(
            ClassExamples.FACIL.source
        )
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        val q = quads(r)
        assertEquals("1. (99, +, 10, 109)", q[0])
        assertEquals("2. ([1], /, 1, 109)", q[1])
        assertEquals("3. (ID4, =, [2], 109.0)", q[2])
        assertEquals("4. (33, +, 2, 35)", q[3])

        val log = r.optimizationResult.appliedOptimizations.associateBy { it.stepNumber }
        assertEquals("FO", log.getValue(1).code)
        assertEquals(2, log.getValue(1).affectedStep)
        assertEquals("SE", log.getValue(2).code) // "/1" ya estaba en el cuádruplo original
        assertEquals(3, log.getValue(2).affectedStep)
        assertEquals("PCD", log.getValue(3).code) // derivada: [2] se volvió 109 por el cambio previo
        assertEquals("FO", log.getValue(4).code)

        val optimized = r.optimizationResult.optimizedBundle.quadruples.map { it.formatAcademic() }
        assertEquals(
            listOf("1. (ID2, RTN, 362.0, 362.0)", "2. (ID1, ., [1], 362.0)", "3. (PMAIN, SOUT, [2], 362.0)"),
            optimized
        )
    }

    // ------------------------------------------------------------------ U1: tablas de errores

    @Test
    fun erroresDeLaUnidad1() {
        val r = compile(
            ClassExamples.FACILITO.source
        )
        val byType = r.errors.groupBy { it.type }
        assertTrue(byType[ErrorPhaseType.LEXICAL].orEmpty().any { it.offendingLexeme == "@" &&
            it.justification.startsWith("En Java no existe ningún TOKEN para el @") })
        assertTrue(byType[ErrorPhaseType.SYNTACTIC].orEmpty().any { it.offendingLexeme == "pakage" &&
            it.justification.contains("DPACKAGE::=PCK ID FI") })
        assertTrue(byType[ErrorPhaseType.SEMANTIC].orEmpty().any {
            it.justification == "El ID length no se encuentra como atributo en la clase String de Java"
        })
        // La tabla está ordenada por línea
        assertEquals(r.errors.sortedBy { it.line }.map { it.line }, r.errors.map { it.line })
    }

    @Test
    fun erroresSintacticosEndFin() {
        val r = compile(
            ClassExamples.END_FIN.source
        )
        val syntactic = r.errors.filter { it.type == ErrorPhaseType.SYNTACTIC }.map { it.justification }
        assertTrue(syntactic.any { it.startsWith("No cumple la regla CLASE, se esperaba un LLA y se encuentra un ID") })
        assertTrue(syntactic.any { it.startsWith("No cumple la regla D_GLOB, se esperaba un ID y se encuentra un MA") })
        assertTrue(syntactic.any { it.startsWith("No cumple la regla DEC, se esperaba un FI y se encuentra un LLC") })
        assertTrue(syntactic.any { it.contains("estáticas") })
        assertTrue(r.errors.any { it.type == ErrorPhaseType.LEXICAL && it.offendingLexeme == "9x" })
    }

    @Test
    fun erroresSemanticosDeTipos() {
        val r = compile(
            ClassExamples.A_ERRORES.source
        )
        val semantic = r.errors.filter { it.type == ErrorPhaseType.SEMANTIC }
        assertTrue(semantic.any { it.offendingLexeme == "uno" })
        assertTrue(semantic.any { it.offendingLexeme == "tres" && it.justification.contains("String") })
        assertTrue(semantic.any { it.offendingLexeme == "cuatro" && it.justification.contains("double") })
    }
}
