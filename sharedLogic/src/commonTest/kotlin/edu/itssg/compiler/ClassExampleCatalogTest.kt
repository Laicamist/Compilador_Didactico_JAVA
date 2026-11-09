package edu.itssg.compiler

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.examples.ClassExamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La biblioteca del menú "Ejemplos": cada programa compila como dice su ficha. */
class ClassExampleCatalogTest {

    private val compiler = JavaDidacticCompiler()

    @Test
    fun cadaEjemploCompilaComoIndicaSuFicha() {
        for (example in ClassExamples.all) {
            val report = compiler.compileSynchronously(example.source)
            if (example.expectsErrors) {
                assertTrue(report.errors.isNotEmpty(), "${example.title} debería llenar la Tabla de Errores")
            } else {
                assertTrue(report.errors.isEmpty(), "${example.title} no debería tener errores: ${report.errors}")
                assertTrue(report.intermediateCode.quadruples.isNotEmpty(), "${example.title} sin cuádruplos")
            }
        }
    }

    @Test
    fun identificadoresYArchivosUnicos() {
        val all = ClassExamples.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.fileName }.toSet().size)
        assertTrue(all.all { it.fileName.endsWith(".java") && it.unit in setOf("U1", "U2", "U3") })
        assertTrue(all.all { ClassExamples.byId(it.id) == it })
        // Primero los que compilan, al final los de errores (así se muestran en el menú).
        assertEquals(all.sortedBy { it.expectsErrors }, all)
    }

    @Test
    fun laClaseDeclaradaCoincideConLaFicha() {
        for (example in ClassExamples.all) {
            assertTrue(
                example.source.contains("class ${example.className.substringBefore(" (")}"),
                "${example.title}: la clase no coincide con ${example.className}"
            )
        }
    }
}
