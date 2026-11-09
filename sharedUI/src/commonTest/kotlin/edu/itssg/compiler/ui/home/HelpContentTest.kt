package edu.itssg.compiler.ui.home

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.state.ResultTab
import edu.itssg.compiler.ui.viewmodel.AppViewModel
import edu.itssg.compiler.ui.viewmodel.HelpTopic
import edu.itssg.compiler.ui.viewmodel.TourStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpContentTest {

    private val demo = JavaDidacticCompiler().compileSynchronously(AppViewModel.DEMO_SOURCE)

    @Test
    fun cadaTemaTieneExplicacionYSalidaReal() {
        for (topic in HelpTopic.entries) {
            val content = helpContent(topic, demo)
            assertTrue(content.paragraphs.isNotEmpty(), "$topic sin explicación")
            assertTrue(content.outputLines.isNotEmpty(), "$topic sin salida")
        }
    }

    @Test
    fun salidasDelEjemploIntX() {
        assertEquals(
            listOf("int  → TD", "x    → ID  (ID3)", "=    → ODA", "5    → VTE", "+    → OPA", "3    → VTE", ";    → FI"),
            helpContent(HelpTopic.LEXICAL, demo).outputLines
        )
        assertEquals(
            listOf("1. (5, +, 3, 8)", "2. (ID3, =, [1], 8)", "3. (PMAIN, SOUT, [2], 8)"),
            helpContent(HelpTopic.INTERMEDIATE_CODE, demo).outputLines
        )
        val optimization = helpContent(HelpTopic.OPTIMIZATION, demo).outputLines
        assertTrue(optimization.first().startsWith("FO:"), "$optimization")
        assertEquals("→ 1. (PMAIN, SOUT, 8, 8)", optimization.last())
        assertTrue(helpContent(HelpTopic.SYNTACTIC, demo).outputLines.any { it.startsWith("DEC → TY ID ODA EXP FI") })
        assertEquals(ResultTab.INTERMEDIATE_CODE, helpContent(HelpTopic.OPTIMIZATION, demo).exampleTab)
    }

    @Test
    fun sinReporteSoloExplicacion() {
        val content = helpContent(HelpTopic.LEXICAL, null)
        assertTrue(content.outputLines.isEmpty())
        assertEquals(AppViewModel.DEMO_STATEMENT, content.input)
    }

    @Test
    fun procesoDelInicioConDatosReales() {
        val stages = pipelineStages(demo)
        assertEquals(listOf("Lex", "Sin", "Árb", "Sem", "CI", "Opt"), stages.map { it.abbr })
        assertEquals("int · x · = · 5\n+ · 3 · ;", stages[0].sample)
        assertEquals("DEC → TY ID\nODA EXP FI", stages[1].sample)
        assertEquals("x : int\nint ← int ✓", stages[3].sample)
        assertEquals("(5, +, 3, 8)\n3 cuádruplos", stages[4].sample)
        assertEquals("(PMAIN, SOUT, 8, 8)\n3 → 1", stages[5].sample)
        // Sin reporte (el ejemplo aún compila) se muestran los mismos textos fijos.
        assertEquals(stages.map { it.sample }, pipelineStages(null).map { it.sample })
    }

    @Test
    fun produccionEnDosRenglones() {
        assertEquals("DEC → TY ID\nODA EXP FI", splitProduction("DEC → TY ID ODA EXP FI"))
        assertEquals("S → a b", splitProduction("S → a b"))
        assertEquals("sin flecha", splitProduction("sin flecha"))
    }

    @Test
    fun temaYTour() {
        assertEquals(ThemeMode.LIGHT, nextThemeMode(ThemeMode.SYSTEM))
        assertEquals(ThemeMode.SYSTEM, nextThemeMode(ThemeMode.DARK))
        assertTrue(TourStep.entries.all { tourText(it).isNotBlank() })
    }
}
