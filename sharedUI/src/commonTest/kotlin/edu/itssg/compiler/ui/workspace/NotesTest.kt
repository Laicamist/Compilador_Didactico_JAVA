package edu.itssg.compiler.ui.workspace

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.error.CompilerNoteKind
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.state.CompilerUiState
import edu.itssg.compiler.ui.state.StepByStepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesTest {

    private val source = """
        public class P{
        public static void main(String []args){
        int x = 1;
        if (x > 0) { x = 9; }
        System.out.println(x);
        }}
    """.trimIndent()

    private val report = JavaDidacticCompiler().compileSynchronously(source)

    @Test
    fun lasObservacionesAparecenConLaFaseSemantica() {
        val state = CompilerUiState(sourceCode = source, report = report, compiledSource = source)
        assertEquals(listOf(CompilerNoteKind.CONTROL_FLOW_OMITTED), state.visibleNotes.map { it.kind })

        val syntacticOnly = state.copy(stepByStep = StepByStepState(CompilerPhase.SYNTACTIC.ordinal))
        assertTrue(syntacticOnly.visibleNotes.isEmpty())
        val semantic = state.copy(stepByStep = StepByStepState(CompilerPhase.SEMANTIC.ordinal))
        assertEquals(1, semantic.visibleNotes.size)
    }

    @Test
    fun avisoDelCodigoIntermedio() {
        assertTrue(intermediateCodeNotice(report.notes)!!.startsWith("Este código no incluye 1 estructura de control"))
        val sinControl = JavaDidacticCompiler().compileSynchronously(
            "public class P{\npublic static void main(String []args){\nint z = 0;\nSystem.out.println(5 / z);\n}}"
        )
        assertEquals(listOf(CompilerNoteKind.DIVISION_BY_ZERO), sinControl.notes.map { it.kind })
        assertNull(intermediateCodeNotice(sinControl.notes))
    }
}
