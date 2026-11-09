package edu.itssg.compiler.ui.workspace

import edu.itssg.compiler.examples.ClassExample
import edu.itssg.compiler.examples.ClassExamples

/** Una sección del menú "Ejemplos" (sin Compose, para poder probarla). */
data class ExampleMenuSection(val title: String, val examples: List<ClassExample>)

object ExampleMenuModel {

    /** Primero los ejemplos que compilan sin errores, ordenados por unidad; luego los de la Tabla de Errores. */
    fun sections(examples: List<ClassExample> = ClassExamples.all): List<ExampleMenuSection> {
        val (withErrors, valid) = examples.partition { it.expectsErrors }
        return listOf(
            ExampleMenuSection("Láminas de clase", valid.sortedBy { it.unit }),
            ExampleMenuSection("Para la Tabla de Errores", withErrors.sortedBy { it.unit })
        ).filter { it.examples.isNotEmpty() }
    }

    /** Renglón principal del menú, p. ej. "U2 · Clase Uno". */
    fun label(example: ClassExample): String = "${example.unit} · ${example.title}"

    /** Aviso que se muestra al abrir un ejemplo. */
    fun openedMessage(example: ClassExample): String =
        if (example.expectsErrors) "Ejemplo “${example.title}”: revisa la Tabla de Errores."
        else "Ejemplo “${example.title}” (${example.unit}) abierto y compilado."
}
