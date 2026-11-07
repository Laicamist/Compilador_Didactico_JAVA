package edu.itssg.compiler

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.ErrorPhaseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Métodos básicos de Java: Scanner, String, Math y conversiones. Los valores que no se conocen al compilar se
 * representan con letras (x, y, z…) como en los ejercicios de la U3.
 */
class JavaMethodsTest {

    private val compiler = JavaDidacticCompiler()

    private fun quads(source: String): List<String> {
        val r = compiler.compileSynchronously(source.trimIndent())
        assertTrue(r.errors.isEmpty(), "Sin errores: ${r.errors}")
        return r.intermediateCode.quadruples.map { it.formatAcademic() }
    }

    @Test
    fun importConVariosNivelesYComodin() {
        for (imp in listOf("import java.util.Scanner;", "import java.util.*;", "import java.Scanner;")) {
            val r = compiler.compileSynchronously(
                """
                $imp
                public class A{
                public static void main(String [] args){
                Scanner sc = new Scanner(System.in);
                int n = sc.nextInt();
                System.out.println(n);}}
                """.trimIndent()
            )
            assertTrue(r.errors.isEmpty(), "$imp: ${r.errors}")
        }
    }

    @Test
    fun scannerLeeValoresDesconocidosConLetras() {
        val q = quads(
            """
            public class A{
            public static void main(String [] args){
            Scanner sc = new Scanner(System.in);
            String nombre = sc.nextLine();
            int edad = sc.nextInt();
            double peso = sc.nextDouble();
            int doble = edad * 2 + 30;
            System.out.println(nombre + doble + peso);}}
            """
        )
        assertEquals("1. (ID3, =, new Scanner(), Scanner)", q[0])
        assertEquals("2. ([1], ., nextLine(), x)", q[1])
        assertEquals("4. ([1], ., nextInt(), y)", q[3])
        assertEquals("6. ([1], ., nextDouble(), z)", q[5])
        assertTrue(q.contains("9. ([8], +, 30, 2y+30)"), q.toString())
        // String desconocido + número: el resultado también es desconocido y toma la siguiente letra.
        assertTrue(q.any { it.endsWith(", a)") }, q.toString())
    }

    @Test
    fun scannerEnLineaComoEnLaLamina() {
        val r = compiler.compileSynchronously(
            """
            public class A{
            public static void main(String [] args){
            String w = new Scanner(System.in).nextLine();
            String v = new Scanner(System.in).nextLine();
            System.out.println(w + v);}}
            """.trimIndent()
        )
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val q = r.intermediateCode.quadruples.map { it.formatAcademic() }
        assertEquals("1. (new Scanner, ., nextLine(), x)", q[0])
        // La misma expresión usa la misma letra y se reduce como subexpresión común: RS[1].
        assertEquals("3. (new Scanner, ., nextLine(), x)", q[2])
        val rs = r.optimizationResult.optimizationTable.first { it.stepNumber == 3 }
        assertEquals("RS[1]", rs.displayCode)
    }

    @Test
    fun metodosDeStringConArgumentosYValorCalculado() {
        val q = quads(
            """
            public class A{
            public static void main(String [] args){
            String s = "Hola Mundo";
            char c = s.charAt(0);
            int n = s.length();
            String t = s.substring(0, 4);
            boolean e = s.equals("Hola");
            int i = s.indexOf("M");
            String m = s.toUpperCase();
            System.out.println(c + n + t + e + i + m);}}
            """
        )
        assertEquals("2. ([1], ., charAt(0), 'H')", q[1])
        assertEquals("4. ([1], ., length(), 10)", q[3])
        assertEquals("6. ([1], ., substring(0,4), \"Hola\")", q[5])
        assertEquals("8. ([1], ., equals(\"Hola\"), false)", q[7])
        assertEquals("10. ([1], ., indexOf(\"M\"), 5)", q[9])
        assertEquals("12. ([1], ., toUpperCase(), \"HOLA MUNDO\")", q[11])
    }

    @Test
    fun argumentosQueSonVariablesOExpresiones() {
        val q = quads(
            """
            public class A{
            public static void main(String [] args){
            String s = "Programa";
            int i = 2;
            char c = s.charAt(i+1);
            System.out.println(c);}}
            """
        )
        // El argumento i+1 se calcula antes, en su propio renglón.
        assertEquals("3. ([2], +, 1, 3)", q[2])
        assertEquals("4. ([1], ., charAt([3]), 'g')", q[3])
    }

    @Test
    fun foldingDeMetodoSobreConstante() {
        val r = compiler.compileSynchronously(
            """
            public class A{
            public static void main(String [] args){
            char c = "NCQM".charAt(0);
            System.out.println(c);}}
            """.trimIndent()
        )
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals("1. (\"NCQM\", ., charAt(0), 'N')", r.intermediateCode.quadruples[0].formatAcademic())
        assertEquals("FO", r.optimizationResult.optimizationTable[0].displayCode)
    }

    @Test
    fun metodosDeMathYConversiones() {
        val q = quads(
            """
            public class A{
            public static void main(String [] args){
            Scanner sc = new Scanner(System.in);
            int n = Integer.parseInt(sc.nextLine());
            double p = Math.pow(2, 3);
            double a = Math.abs(-5);
            int k = Integer.parseInt("42");
            double d = Double.parseDouble("2.5");
            String t = String.valueOf(25);
            double r = Math.random();
            double r2 = Math.random() * 3 + 5;
            System.out.println(n + p + a + k + d + t + r + r2);}}
            """
        )
        // La llamada anidada se calcula antes y se referencia: parseInt([2]).
        assertEquals("2. ([1], ., nextLine(), x)", q[1])
        assertEquals("3. (Integer, ., parseInt([2]), y)", q[2])
        assertTrue(q.contains("5. (Math, ., pow(2,3), 8.0)"), q.toString())
        assertTrue(q.contains("7. (Math, ., abs(-5), 5)"), q.toString())
        assertTrue(q.contains("9. (Integer, ., parseInt(\"42\"), 42)"), q.toString())
        assertTrue(q.contains("11. (Double, ., parseDouble(\"2.5\"), 2.5)"), q.toString())
        assertTrue(q.contains("13. (String, ., valueOf(25), \"25\")"), q.toString())
        // Todas las llamadas a Math.random() son la misma letra.
        assertTrue(q.contains("15. (Math, ., random(), z)"), q.toString())
        assertTrue(q.contains("17. (Math, ., random(), z)"), q.toString())
        assertTrue(q.any { it.contains("3z+5.0") }, q.toString())
    }

    @Test
    fun operacionesLinealesConVariasLetras() {
        val q = quads(
            """
            public class A{
            public static void main(String [] args){
            Scanner sc = new Scanner(System.in);
            int a = sc.nextInt();
            int b = sc.nextLine().length();
            int c = 3*b - 30 + 2*a - a;
            int d = a * b;
            int e = c - c;
            System.out.println(c + d + e);}}
            """
        )
        // a = x (nextInt), sc.nextLine() = y, b = z (length)
        assertTrue(q.any { it.endsWith(", x+3z-30)") }, q.toString())
        // a*b no es lineal: letra nueva.
        assertTrue(q.any { it.contains("*, [") && it.endsWith(", a)") }, q.toString())
        // c - c = 0: las letras se cancelan.
        assertTrue(q.any { it.contains("-, [") && it.endsWith(", 0)") }, q.toString())
    }

    @Test
    fun erroresSemanticosDeMetodosDeJava() {
        val r = compiler.compileSynchronously(
            """
            public class A{
            public static void main(String [] args){
            Scanner sc = new Scanner(System.in);
            int edad = sc.nextLine();
            String s = "abc";
            char c = s.charAt();
            int r = sc.leer();
            double q = Math.pow(2);
            System.out.println(edad);}}
            """.trimIndent()
        )
        val semantic = r.errors.filter { it.type == ErrorPhaseType.SEMANTIC }.map { it.offendingLexeme }
        assertEquals(listOf("edad", "charAt", "leer", "pow"), semantic)
        assertTrue(r.errors.first { it.offendingLexeme == "charAt" }.justification.contains("recibe 1 argumento"))
    }
}
