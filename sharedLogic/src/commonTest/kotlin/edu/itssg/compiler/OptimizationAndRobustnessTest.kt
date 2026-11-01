package edu.itssg.compiler

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.ir.OptimizationTag
import edu.itssg.compiler.core.security.CompilerSecurityConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimizationAndRobustnessTest {

    private val compiler = JavaDidacticCompiler()

    // ------------------------------------------------------------------ Etiquetas de optimización (U3)

    @Test
    fun etiquetasOficialesYDerivadas() {
        assertEquals(listOf("SE", "FO", "PC", "RS", "CM"), OptimizationTag.entries.map { it.code })
        assertEquals(listOf("SED", "FOD", "PCD", "RSD", "CMD"), OptimizationTag.entries.map { it.derivedCode })
        assertEquals(OptimizationTag.CONSTANT_PROPAGATION, OptimizationTag.parse("PCD")?.tag)
        assertEquals(true, OptimizationTag.parse("pcd")?.isDerived)
        assertEquals(false, OptimizationTag.parse("PC")?.isDerived)
        assertNull(OptimizationTag.parse("SIMP"))
    }

    @Test
    fun propagacionDeConstantesEjemploPi() {
        val r = compiler.compileSynchronously(
            """
            public class Pi{
            public double calc(){
            double PI = 3.14;
            double a= (180.0/PI)*Math.random();
            return a;}
            public static void main(String [] x){
            System.out.println(new Pi().calc());}}
            """.trimIndent()
        )
        val log = r.optimizationResult.appliedOptimizations.associateBy { it.stepNumber }
        assertEquals("PC", log.getValue(1).code)  // (ID3, =, 3.14) → PC
        assertEquals("FOD", log.getValue(2).code) // (180.0, /, 3.14) solo es constante gracias a PC
        val optimized = r.optimizationResult.optimizedBundle.quadruples.map { it.formatAcademic() }
        assertEquals("1. (Math, ., random(), X.0)", optimized[0])
        assertTrue(optimized[1].startsWith("2. (57.3248, *, [1]"), optimized.toString())
    }

    @Test
    fun reduccionDeSubexpresionesComunes() {
        val r = compiler.compileSynchronously(
            """
            public class Rs{
            public static void main(String [] x){
            double b= Math.random();
            double a=b+5*2;
            double c=a+5*2+8;
            System.out.println(c);}}
            """.trimIndent()
        )
        val quads = r.intermediateCode.quadruples.map { it.formatAcademic() }
        assertEquals("4. ([2], +, [3], 10+X.0)", quads[3])
        assertEquals("7. ([5], +, [6], 20+X.0)", quads[6])
        assertEquals("8. ([7], +, 8, 28+X.0)", quads[7])
        val log = r.optimizationResult.appliedOptimizations.associateBy { it.stepNumber }
        assertEquals("FO", log.getValue(3).code)
        assertEquals("RS", log.getValue(6).code)
    }

    @Test
    fun simplificacionDeExpresiones() {
        val r = compiler.compileSynchronously(
            """
            public class Se{
            int x = 7;
            int s = x/1+10;
            public static void main(String [] y){
            System.out.println(new Se().s * 1 - 0);}}
            """.trimIndent()
        )
        val codes = r.optimizationResult.appliedOptimizations.map { it.code }
        assertTrue(codes.count { it == "SE" } >= 3, codes.toString())
        assertEquals(2, r.optimizationResult.optimizedBundle.quadruples.size)
    }

    @Test
    fun codigoMuertoSinSalida() {
        val r = compiler.compileSynchronously(
            """
            public class M{
            public static void main(String [] y){
            double r = Math.random();
            double z = r * 2;
            System.out.println(r);}}
            """.trimIndent()
        )
        val codes = r.optimizationResult.appliedOptimizations.map { it.code }
        assertTrue("CM" in codes, codes.toString()) // z no impacta la salida
    }

    @Test
    fun randomNuncaSeReducePorRS() {
        val r = compiler.compileSynchronously(
            """
            public class R{
            public static void main(String [] y){
            System.out.println(Math.random() + Math.random());}}
            """.trimIndent()
        )
        assertTrue(r.optimizationResult.appliedOptimizations.none { it.tag == OptimizationTag.SUBEXPRESSION_REDUCTION })
    }

    // ------------------------------------------------------------------ Seguridad y casos límite

    @Test
    fun entradasVaciasOCorruptasNoLanzanExcepciones() {
        val inputs = listOf("", "   ", "}", "{{{{", "public class", "/* sin cerrar", "\"sin cerrar", "'a", "@@@@",
            "public class A { public static void main(String[] a) { System.out.println(; } }",
            "\u0000\u0001﻿", "😀 public class 😀 {}")
        for (src in inputs) {
            val r = compiler.compileSynchronously(src)
            assertNotNull(r.syntaxTree)
            assertTrue(r.hasErrors, "Debe reportar errores para: $src")
        }
    }

    @Test
    fun mutacionesAleatoriasDeEjemplos() {
        val base = """
            public class Lunes{
            int a= 'F';
            private char b= 99/10;
            int c= b+1;
            public char qwerty(int numero){
            String a= "1" ;
            return (char)(b + (a+c).length()+numero);}
            public static void main(String [] arreglo){
            System.out.println(new Lunes().qwerty(a));}}
        """.trimIndent()
        val alphabet = "ab9 ;{}()[]=+-*/<>!&|.,'\"@\nint class new return"
        val rnd = Random(2026)
        repeat(300) {
            val sb = StringBuilder(base)
            repeat(1 + rnd.nextInt(6)) {
                if (rnd.nextBoolean() && sb.isNotEmpty()) sb.deleteCharAt(rnd.nextInt(sb.length))
                else sb.insert(rnd.nextInt(sb.length + 1), alphabet[rnd.nextInt(alphabet.length)])
            }
            compiler.compileSynchronously(sb.toString()) // no debe lanzar
        }
    }

    @Test
    fun antiDoSAnidamientoProfundoYCadenasLargas() {
        val parens = "public class P{ public static void main(String[] a){ int x = " +
            "(".repeat(5000) + "1" + ")".repeat(5000) + "; System.out.println(x);}}"
        assertTrue(compiler.compileSynchronously(parens).errors.any { it.justification.contains("paréntesis anidados") })

        val chain = "public class P{ public static void main(String[] a){ int x = " +
            (1..20000).joinToString("+") + "; System.out.println(x);}}"
        assertTrue(compiler.compileSynchronously(chain).hasErrors)

        val ifs = "public class P{ public static void main(String[] a){ int x = 1; " +
            "if (x > 0) { ".repeat(3000) + "x = 2;" + " }".repeat(3000) + " }}"
        assertTrue(compiler.compileSynchronously(ifs).hasErrors)
    }

    @Test
    fun antiDoSExpansionExponencialYRecursion() {
        // f25 = f24 + f24 = ... → 2^25 nodos si no hubiera límite
        val exponential = "public class P{ int f0 = 1;\n" + (1..25).joinToString("\n") { "int f$it = f${it - 1} + f${it - 1};" } +
            "\npublic static void main(String[] a){ System.out.println(new P().f25);}}"
        val r = compiler.compileSynchronously(exponential)
        assertTrue(r.errors.any { it.justification.contains("límite seguro") })

        val recursion = "public class P{ public int r(int n){ return r(n) + 1; } " +
            "public static void main(String[] a){ System.out.println(new P().r(3));}}"
        compiler.compileSynchronously(recursion) // termina gracias al límite de llamadas anidadas

        val cycle = "public class P{ int a = b + 1; int b = a + 1; public static void main(String[] q){ System.out.println(new P().a);}}"
        assertTrue(compiler.compileSynchronously(cycle).errors.any { it.offendingLexeme == "b" })
    }

    @Test
    fun cuotasDeEntrada() {
        val tiny = JavaDidacticCompiler(CompilerSecurityConfig(maxSourceLength = 100))
        val r = tiny.compileSynchronously("public class A { }".repeat(50))
        assertNotNull(r.quotaAbortedMessage)

        val garbage = compiler.compileSynchronously("@".repeat(200_000))
        assertTrue(garbage.errors.size <= CompilerSecurityConfig.DEFAULT_MAX_ERROR_COUNT)
    }

    @Test
    fun tresDireccionesUsaComoMaximoTresTemporalesCuandoAlcanzan() {
        // Necesita 3 registros: debe resolverse con T1..T3 (con pendientes o reutilizando un operando)
        val r = compiler.compileSynchronously(
            "public class P{ public static void main(String[] a){ System.out.println(" +
                "((1*2)+(3*4))*((5*6)+(7*8)));}}"
        )
        assertTrue(r.intermediateCode.threeAddress.none { it.targetLabel.startsWith("T4") },
            r.intermediateCode.threeAddress.joinToString("\n") { it.formatAcademic() })

        // Árbol binario completo de 4 niveles: requiere 4 registros; se indica explícitamente en la nota
        val wide = compiler.compileSynchronously(
            "public class P{ public static void main(String[] a){ System.out.println(" +
                "((1*2)+(3*4))*((5*6)+(7*8)) + ((9*10)+(11*12))*((13*14)+(15*16)));}}"
        )
        val overflow = wide.intermediateCode.threeAddress.filter { it.targetLabel.startsWith("T4") }
        assertTrue(overflow.all { it.freeNote.orEmpty().contains("excede") })
    }
}
