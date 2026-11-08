package edu.itssg.compiler

import edu.itssg.compiler.core.CompilationReport
import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.CompilerNoteKind
import edu.itssg.compiler.core.error.ErrorPhaseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regresiones de la revisión del 7 nov 2026 (Etapa A): reasignaciones, ámbitos de bloque, observaciones didácticas
 * (estructuras de control, división entre cero, recursión) y mensajes de literales que la GLC v7 no contempla.
 */
class SemanticRegressionTest {

    private fun compileMain(body: String, members: String = ""): CompilationReport =
        JavaDidacticCompiler().compileSynchronously(
            "public class P{\n$members\npublic static void main(String []args){\n$body\n}}"
        )

    private fun quads(r: CompilationReport) = r.intermediateCode.quadruples.map { it.formatAcademic() }

    private fun semanticErrors(r: CompilationReport) =
        r.errors.filter { it.type == ErrorPhaseType.SEMANTIC }.map { it.justification }

    // ------------------------------------------------------------------ B1: reasignaciones

    @Test
    fun reasignacionEnElMainCambiaElValorQueSeImprime() {
        val r = compileMain("int x = 1;\nx = 5;\nSystem.out.println(x);")
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        assertEquals(listOf("1. (ID3, =, 1, 1)", "2. (ID3, =, 5, 5)", "3. (PMAIN, SOUT, [2], 5)"), quads(r))
    }

    @Test
    fun reasignacionQueUsaSuPropioValor() {
        val r = compileMain("int x = 1;\nx = x + 2;\nx = x * 3;\nSystem.out.println(x);")
        assertEquals(
            listOf(
                "1. (ID3, =, 1, 1)",
                "2. ([1], +, 2, 3)",
                "3. (ID3, =, [2], 3)",
                "4. ([3], *, 3, 9)",
                "5. (ID3, =, [4], 9)",
                "6. (PMAIN, SOUT, [5], 9)"
            ),
            quads(r)
        )
    }

    @Test
    fun cadaImpresionUsaElValorVigente() {
        val r = compileMain("int x = 1;\nSystem.out.println(x);\nx = 7;\nSystem.out.println(x);")
        assertEquals("2. (PMAIN, SOUT, [1], 1)", quads(r)[1])
        assertEquals("4. (PMAIN, SOUT, [3], 7)", quads(r)[3])
    }

    @Test
    fun elDestinoDeUnaAsignacionLlevaSuIdEnLaTablaLexica() {
        val r = compileMain("int x = 1;\nx = 5;\nSystem.out.println(x);")
        val xs = r.tokens.filter { it.lexeme == "x" }
        assertEquals(listOf(3, 3, 3), xs.map { it.symbolId })
        val row = r.lexicalTableRows.first { it.line == 5 && it.column == 1 } // `x = 5;`
        assertEquals("ID3", row.lexemeColumn)
    }

    @Test
    fun incrementoComoInstruccionDelMain() {
        val r = compileMain("int x = 1;\nx++;\nSystem.out.println(x);")
        assertEquals(listOf("1. (ID3, =, 1, 1)", "2. (1, +, [1], 2)", "3. (PMAIN, SOUT, [2], 2)"), quads(r))
    }

    @Test
    fun asignacionesDentroDeUnMetodo() {
        val atributo = compileMain("new P().m();", "int a = 2;\npublic void m(){\na = a * 5;\nSystem.out.println(a);}")
        assertEquals("4. (ID3, SOUT, [3], 10)", quads(atributo)[3])

        val parametro = compileMain("System.out.println(new P().f(3));", "public int f(int v){\nv = v + 1;\nreturn v * 2;}")
        assertEquals("7. (PMAIN, SOUT, [6], 8)", quads(parametro).last())

        val incremento = compileMain("new P().m();", "public void m(){\nint b = 4;\nb++;\nSystem.out.println(b);}")
        assertEquals("4. (ID2, SOUT, [3], 5)", quads(incremento)[3])
    }

    @Test
    fun unaDeclaracionConservaElValorDeSuMomento() {
        // b se calculó con a = 2; reasignar a después no cambia b.
        val r = compileMain("System.out.println(new P().f(1));", "public int f(int v){\nint a = 2;\nint b = a + 1;\na = 100;\nreturn b;}")
        assertEquals("6. (PMAIN, SOUT, [5], 3)", quads(r).last())
    }

    // ------------------------------------------------------------------ B2: ámbitos de bloque

    @Test
    fun loDeclaradoEnUnBloqueNoExisteAfuera() {
        val ifBlock = compileMain("int x = 1;\nif (x > 0) { int y = 2; }\nSystem.out.println(y);")
        assertTrue(semanticErrors(ifBlock).any { it.startsWith("El ID y no ha sido declarado") }, "${ifBlock.errors}")

        val whileBlock = compileMain("int x = 1;\nwhile (x < 3) { int y = 2; x = x + 1; }\nSystem.out.println(y);")
        assertTrue(semanticErrors(whileBlock).any { it.startsWith("El ID y no ha sido declarado") })

        val forVar = compileMain("for (int i = 0; i < 3; i++) { }\nSystem.out.println(i);")
        assertTrue(semanticErrors(forVar).any { it.startsWith("El ID i no ha sido declarado") })
    }

    @Test
    fun bloquesHermanosPuedenUsarElMismoNombre() {
        val r = compileMain("int x = 1;\nif (x > 0) { int y = 2; } else { int y = 3; }\nfor (int i = 0; i < 2; i++) { }\nfor (int i = 0; i < 2; i++) { }")
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
    }

    @Test
    fun noSePuedeOcultarUnaVariableLocal() {
        val r = compileMain("int x = 1;\nif (x > 0) { int x = 2; }")
        assertEquals(listOf("El ID x ya fue declarado previamente en el ámbito main"), semanticErrors(r))
    }

    @Test
    fun losCaseCompartenElBloqueDelSwitch() {
        val r = compileMain("int x = 2;\nswitch (x) { case 1: int y = 1; break; case 2: y = 2; break; }")
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
    }

    @Test
    fun asignacionDefinitivaComoEnJava() {
        val ifElse = compileMain("int x;\nint c = 1;\nif (c > 0) { x = 1; } else { x = 2; }\nSystem.out.println(x);")
        assertTrue(ifElse.errors.isEmpty(), "if/else inicializa en ambos brazos: ${ifElse.errors}")

        val soloIf = compileMain("int x;\nint c = 1;\nif (c > 0) { x = 1; }\nSystem.out.println(x);")
        assertEquals(listOf("El ID x se usa sin haber recibido un valor (no fue inicializado)"), semanticErrors(soloIf))

        val doWhile = compileMain("int x;\ndo { x = 1; } while (x < 0);\nSystem.out.println(x);")
        assertTrue(doWhile.errors.isEmpty(), "do-while ejecuta su cuerpo al menos una vez: ${doWhile.errors}")
    }

    // ------------------------------------------------------------------ B3–B5: observaciones

    @Test
    fun estructuraDeControlDejaObservacionYValorDesconocido() {
        val r = compileMain("int x = 1;\nif (x > 0) { x = 9; }\nSystem.out.println(x + 1);\nx = 4;\nSystem.out.println(x);")
        assertTrue(r.errors.isEmpty())
        val note = r.notes.single()
        assertEquals(CompilerNoteKind.CONTROL_FLOW_OMITTED, note.kind)
        assertEquals(5, note.line)
        assertTrue("ID3 (x) = x" in note.message, note.message)
        assertEquals(
            listOf(
                "1. (ID3, =, 1, 1)",
                "2. (ID3, +, 1, x+1)",
                "3. (PMAIN, SOUT, [2], x+1)",
                "4. (ID3, =, 4, 4)",
                "5. (PMAIN, SOUT, [4], 4)"
            ),
            quads(r)
        )
    }

    @Test
    fun lasVariablesDelBloqueNoSeListanComoDesconocidas() {
        val r = compileMain("for (int i = 0; i < 3; i++) { int t = i; }")
        assertTrue("valor desconocido" !in r.notes.single().message, r.notes.single().message)
    }

    @Test
    fun estructuraDeControlDentroDeUnMetodo() {
        val r = compileMain(
            "System.out.println(new P().f(1));",
            "public int f(int v){\nint r = v;\nif (v > 0) { r = 10; }\nreturn r + 1;}"
        )
        assertEquals("1. (ID4, +, 1, x+1)", quads(r).first())
        assertEquals(CompilerNoteKind.CONTROL_FLOW_OMITTED, r.notes.single().kind)
    }

    @Test
    fun divisionEnteraEntreCeroDejaObservacion() {
        val r = compileMain("int z = 0;\nint x = 5 / z;\nSystem.out.println(x);")
        assertTrue(r.errors.isEmpty(), "Java lo compila: ${r.errors}")
        assertEquals(CompilerNoteKind.DIVISION_BY_ZERO, r.notes.single().kind)
        assertEquals("2. (5, /, [1], ?)", quads(r)[1])
    }

    @Test
    fun divisionDecimalEntreCeroEsInfinityComoEnJava() {
        val r = compileMain("float x = 5.0f / 0;\nSystem.out.println(x);")
        assertEquals("1. (5.0f, /, 0, Infinity)", quads(r).first())
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun recursionDejaObservacion() {
        val r = compileMain("System.out.println(new P().f(1));", "public int f(int v){\nreturn f(v);}")
        assertEquals(listOf(CompilerNoteKind.CALL_DEPTH_LIMIT), r.notes.map { it.kind })
    }

    // ------------------------------------------------------------------ B6: literales fuera de la GLC v7

    @Test
    fun literalesNumericosDeJavaTienenMensajePropio() {
        val r = compileMain("long a = 10L;\ndouble b = 1e3;\nint c = 0x1F;\nint d = 1_000;\nint f = 4a;")
        val lexical = r.errors.filter { it.type == ErrorPhaseType.LEXICAL }.associate { it.offendingLexeme to it.justification }
        for (literal in listOf("10L", "1e3", "0x1F", "1_000")) {
            assertTrue(lexical.getValue(literal).startsWith("El literal $literal es un número válido en Java"), literal)
        }
        assertTrue(lexical.getValue("4a").contains("no cumple con las características para ser un ID"))
    }
}
