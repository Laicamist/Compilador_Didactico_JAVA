package edu.itssg.compiler

import edu.itssg.compiler.core.JavaDidacticCompiler
import edu.itssg.compiler.core.ir.ThreeAddressInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paso 3.4: datos de Tres Direcciones para el recorrido animado del visor de árboles
 * (`sourceStep`, `isPending`, `registersAfter`). No cambian el texto de las láminas.
 */
class ThreeAddressTraceTest {

    private fun compile(source: String) = JavaDidacticCompiler().compileSynchronously(source)

    private val uno = """
        public class Uno{
        float dos = 3;
        float tres = dos+ 1;
        public void cuatro(int cinco){
        System.out.println( 45.0 + ( cinco * tres) );}
        public static void main(String []args){
        new Uno().cuatro('@');}}
    """.trimIndent()

    private val claseA = """
        public class A{
        public int a= 35/4+10/3 ;
        int uno = ++a;
        private double dos= uno*(5-10);
        double tres= "hola".length()*30;
        public float cuatro=(float)( dos+tres*dos);
        public static void main(String [] q){
        System.out.println(new A().cuatro);}
        }
    """.trimIndent()

    @Test
    fun unoCadaInstruccionSabeDeQuePasoSaleYComoQuedanLosTemporales() {
        val tac = compile(uno).intermediateCode.threeAddress
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), tac.map { it.sourceStep })
        assertEquals(
            listOf(
                listOf(null, null, null), // ID2 = 3
                listOf(2, null, null),    // T1. (ID2, +, 1)
                listOf(2, null, null),    // ID5 = '@'
                listOf(null, null, null), // ID3 = T1 --> free all
                listOf(5, null, null),    // T1. (ID5, *, ID3)
                listOf(null, 6, null),    // T2. (45.0, +, T1) --> free T1
                listOf(null, null, null), // ID4 SOUT T2 --> free all
                listOf(8, null, null)     // T1. (ID1, ., ID4): resultado final
            ),
            tac.map { it.registersAfter }
        )
        assertTrue(tac.none { it.isPending })
    }

    @Test
    fun claseAMarcaLosPendientesYSuTrazaCoincideConLasLiberaciones() {
        val r = compile(claseA)
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val tac = r.intermediateCode.threeAddress
        val steps = r.intermediateCode.quadruples.map { it.stepNumber }.toSet()

        // Cada renglón dice de qué paso sale; los pendientes Pn se completan después con el mismo paso.
        assertTrue(tac.all { it.sourceStep in steps }, tac.toString())
        val pending = tac.filter { it.isPending }
        assertTrue(pending.map { it.targetLabel }.containsAll(listOf("P3", "P4")), pending.toString())
        for (p in pending) {
            assertEquals("P${p.sourceStep}", p.targetLabel)
            assertTrue(tac.any { !it.isPending && it.sourceStep == p.sourceStep && it.index > p.index }, "P${p.sourceStep} sin completar")
        }
        // Todos los pasos aparecen completados exactamente una vez.
        assertEquals(steps.sorted(), tac.filter { !it.isPending }.mapNotNull { it.sourceStep }.sorted())

        // La traza coincide con lo que dice cada renglón: Tn toma el temporal y "free" lo suelta.
        var occupied = listOf<Int?>(null, null, null)
        for (instruction in tac) {
            assertEquals(3, instruction.registersAfter.size)
            occupied = replay(occupied, instruction)
            assertEquals(occupied, instruction.registersAfter, "Renglón ${instruction.formatAcademic()}")
        }
    }

    /** Aplica un renglón al estado de los temporales leyendo solo su texto académico. */
    private fun replay(before: List<Int?>, instruction: ThreeAddressInstruction): List<Int?> {
        val next = before.toMutableList()
        val temp = Regex("^T(\\d+)$").find(instruction.targetLabel)?.groupValues?.get(1)?.toInt()
        if (!instruction.isDirectAssignmentOrReturn && temp != null && temp <= 3) {
            next[temp - 1] = instruction.sourceStep
        }
        val note = instruction.freeNote.orEmpty()
        if ("free all" in note) {
            for (i in next.indices) next[i] = null
        } else {
            Regex("free ((?:T\\d+(?:, )?)+)").find(note)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim().removePrefix("T").toInt() }
                ?.forEach { if (it <= 3) next[it - 1] = null }
        }
        return next
    }
}
