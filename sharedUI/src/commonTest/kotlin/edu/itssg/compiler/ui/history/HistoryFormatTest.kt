package edu.itssg.compiler.ui.history

import edu.itssg.compiler.ui.data.SavedSessionSummary
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.PhaseStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryFormatTest {

    /** Ciudad de México (UTC−6, sin horario de verano desde 2022). */
    private val mexico: (Long) -> Long = { -6 * 3_600_000L }

    private val now = 1_794_097_560_000L // 7 nov 2026, 18:26 (UTC−6)

    private fun session(
        hasErrors: Boolean = false,
        lexical: Int = 0,
        syntactic: Int = 0,
        semantic: Int = 0,
        original: Int = 6,
        optimized: Int = 3
    ) = SavedSessionSummary(
        id = 1, title = "Uno.java", sourceCode = "public class Uno{}", createdAtMillis = now,
        hasErrors = hasErrors, lexicalErrorCount = lexical, syntacticErrorCount = syntactic,
        semanticErrorCount = semantic, originalInstructionCount = original, optimizedInstructionCount = optimized
    )

    @Test
    fun partesDeFechaLocal() {
        val p = localParts(now, -6 * 3_600_000L)
        assertEquals(listOf(2026, 11, 7, 18, 26), listOf(p.year, p.month, p.day, p.hour, p.minute))
        val bisiesto = localParts(1_709_219_100_000L, -6 * 3_600_000L)
        assertEquals(listOf(2024, 2, 29, 9, 5), listOf(bisiesto.year, bisiesto.month, bisiesto.day, bisiesto.hour, bisiesto.minute))
        val antesDe1970 = localParts(-3_600_000L, 0)
        assertEquals(listOf(1969, 12, 31, 23, 0), listOf(antesDe1970.year, antesDe1970.month, antesDe1970.day, antesDe1970.hour, antesDe1970.minute))
    }

    @Test
    fun hoyAyerYFechaCompleta() {
        assertEquals("Hoy, 18:26", formatSessionDate(now, now, mexico))
        assertEquals("Ayer, 23:59", formatSessionDate(1_794_031_140_000L, now, mexico))
        assertEquals("29 feb 2024, 09:05", formatSessionDate(1_709_219_100_000L, now, mexico))
    }

    @Test
    fun marcasDeFases() {
        assertEquals(List(5) { PhaseStatus.SUCCESS }, historyPhaseMarks(session()).map { it.second })

        val semantica = historyPhaseMarks(session(hasErrors = true, semantic = 2)).toMap()
        assertEquals(PhaseStatus.SUCCESS, semantica[CompilerPhase.SYNTACTIC])
        assertEquals(PhaseStatus.FAILED, semantica[CompilerPhase.SEMANTIC])
        assertEquals(PhaseStatus.BLOCKED, semantica[CompilerPhase.INTERMEDIATE_CODE])
        assertEquals(PhaseStatus.BLOCKED, semantica[CompilerPhase.OPTIMIZATION])

        // Detenida por los límites de seguridad: con errores, pero ninguno contado por fase.
        val abortada = historyPhaseMarks(session(hasErrors = true)).toMap()
        assertEquals(PhaseStatus.FAILED, abortada[CompilerPhase.LEXICAL])
        assertEquals(PhaseStatus.BLOCKED, abortada[CompilerPhase.SYNTACTIC])
    }

    @Test
    fun resumenes() {
        assertEquals("6 → 3 cuádruplos", instructionSummary(session()))
        assertEquals("1 cuádruplo", instructionSummary(session(original = 1, optimized = 1)))
        assertEquals("Sin código intermedio", instructionSummary(session(hasErrors = true, lexical = 1)))
        assertEquals("Sin errores", errorSummary(session()))
        assertEquals("1 léxico · 2 semánticos", errorSummary(session(hasErrors = true, lexical = 1, semantic = 2)))
        assertEquals("Detenida por límites de seguridad", errorSummary(session(hasErrors = true)))
    }
}
