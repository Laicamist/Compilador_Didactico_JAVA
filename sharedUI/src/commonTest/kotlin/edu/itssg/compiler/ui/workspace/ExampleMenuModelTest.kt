package edu.itssg.compiler.ui.workspace

import edu.itssg.compiler.examples.ClassExamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleMenuModelTest {

    @Test
    fun seccionesSeparanLaminasDeErrores() {
        val sections = ExampleMenuModel.sections()
        assertEquals(listOf("Láminas de clase", "Para la Tabla de Errores"), sections.map { it.title })
        assertTrue(sections[0].examples.none { it.expectsErrors })
        assertTrue(sections[1].examples.all { it.expectsErrors })
        assertEquals(ClassExamples.all.size, sections.sumOf { it.examples.size })
        // Dentro de cada sección, por unidad
        sections.forEach { section -> assertEquals(section.examples.sortedBy { it.unit }, section.examples) }
    }

    @Test
    fun seccionVaciaNoSeMuestra() {
        val onlyValid = ExampleMenuModel.sections(ClassExamples.all.filterNot { it.expectsErrors })
        assertEquals(listOf("Láminas de clase"), onlyValid.map { it.title })
    }

    @Test
    fun textosDelMenu() {
        assertEquals("U2 · Clase Uno", ExampleMenuModel.label(ClassExamples.UNO))
        assertTrue(ExampleMenuModel.openedMessage(ClassExamples.FACILITO).contains("Tabla de Errores"))
        assertTrue(ExampleMenuModel.openedMessage(ClassExamples.FACIL).contains("U3"))
    }
}
