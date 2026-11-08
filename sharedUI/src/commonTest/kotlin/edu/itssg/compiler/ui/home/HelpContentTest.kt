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
    fun temaYTour() {
        assertEquals(ThemeMode.LIGHT, nextThemeMode(ThemeMode.SYSTEM))
        assertEquals(ThemeMode.SYSTEM, nextThemeMode(ThemeMode.DARK))
        assertTrue(TourStep.entries.all { tourText(it).isNotBlank() })
    }
}
