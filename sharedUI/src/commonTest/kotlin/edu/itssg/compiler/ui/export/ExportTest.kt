package edu.itssg.compiler.ui.export

import edu.itssg.compiler.core.JavaDidacticCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportTest {

    private val uno = """
        public class Uno{
        float dos = 3;
        float tres = dos+ 1;
        public void cuatro(int cinco){
        System.out.println( 45.0 + ( cinco * tres) );}
        public static void main(String []args){
        new Uno().cuatro('@');}}
    """.trimIndent()

    private val mexico: (Long) -> Long = { -6 * 3_600_000L }

    @Test
    fun nombresDeArchivoYCarpeta() {
        assertEquals("Uno", exportBaseName("Uno.java"))
        assertEquals("Mi_tarea_1", exportBaseName("  Mi tarea 1.java "))
        assertEquals("a_b_c", exportBaseName("a/b:c"))
        assertEquals("Sesion", exportBaseName("..."))
        assertEquals("Uno_2026-11-07_1826", exportFolderName("Uno.java", 1_794_097_560_000L, mexico))
        assertEquals("7/11/2026 18:26", reportDateText(1_794_097_560_000L, mexico))
    }

    @Test
    fun celdasCsv() {
        assertEquals("simple", csvCell("simple"))
        assertEquals("\"a,b\"", csvCell("a,b"))
        assertEquals("\"di \"\"hola\"\"\"", csvCell("di \"hola\""))
        assertEquals("\"dos\nrenglones\"", csvCell("dos\nrenglones"))
        val bytes = csvOf(listOf("Línea", "Error"), listOf(listOf("3", "@")))
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF }, "UTF-8 con BOM para Excel")
        assertEquals("Línea,Error\r\n3,@\r\n", bytes.drop(3).toByteArray().decodeToString())
    }

    @Test
    fun textoWinAnsiParaPdf() {
        assertEquals("Ma\\361ana \\(f\\341cil\\)", pdfString("Mañana (fácil)"))
        assertEquals("6 -> 3 OK (4)", toWinAnsi("6 → 3 ✓ ④").decodeToString())
        assertEquals(0x93, toWinAnsi("“")[0].toInt() and 0xFF)
    }

    @Test
    fun pdfBienFormado() {
        val report = JavaDidacticCompiler().compileSynchronously(uno)
        val pdf = buildReportPdf(report, "Uno.java", "7/11/2026 18:26")
        val text = pdf.decodeToString()
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        // La tabla xref apunta al inicio exacto de cada objeto.
        val xrefAt = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        assertTrue(text.substring(xrefAt).startsWith("xref"))
        val offsets = Regex("(\\d{10}) 00000 n").findAll(text.substring(xrefAt)).map { it.groupValues[1].toInt() }.toList()
        offsets.forEachIndexed { i, offset ->
            assertTrue(pdf.copyOfRange(offset, offset + 12).decodeToString().startsWith("${i + 1} 0 obj"), "objeto ${i + 1}")
        }
        assertTrue("(Tabla de tokens) Tj" in text)
        assertTrue("(Tabla de optimizaci\\363n \\(U3\\) \\267 Cu\\341druplos) Tj" in text)
    }

    @Test
    fun tablaMonoespaciadaCabeEnElAncho() {
        val lines = monoTable(
            listOf("Línea", "Justificación"),
            listOf(listOf("3", "No cumple la regla DEC, se esperaba un FI y se encuentra un TD (int) en la línea")),
            maxChars = 40
        )
        assertTrue(lines.all { it.length <= 40 }, lines.joinToString("\n"))
        assertTrue(lines.size > 3, "la justificación larga pasa a renglones de continuación")
    }

    @Test
    fun paqueteCompletoYBloqueoDelCodigoIntermedio() {
        val ok = JavaDidacticCompiler().compileSynchronously(uno)
        val all = buildExportBundle(ok, "Uno.java", ExportKind.entries.toSet(), 0L, "x", "Uno_x") { ByteArray(4) }
        val names = all.files.map { it.name }
        for (expected in listOf(
            "Uno_reporte.pdf", "Uno_tokens.csv", "Uno_cuadruplos.csv", "Uno_optimizacion_cuadruplos.csv",
            "Uno.java", "Uno_arbol_sintactico.png", "Uno_arbol_semantico.svg"
        )) assertTrue(expected in names, "$expected en $names")
        assertTrue(all.warnings.isEmpty())

        val withErrors = JavaDidacticCompiler().compileSynchronously("public class P{ int x = \"a\"; }")
        val csv = buildExportBundle(withErrors, "P", setOf(ExportKind.CSV_TABLES), 0L, "x", "P_x").files.map { it.name }
        assertTrue("P_errores.csv" in csv)
        assertTrue(csv.none { "cuadruplos" in it }, "con errores no se exporta el código intermedio: $csv")
    }

    @Test
    fun svgDelArbol() {
        val report = JavaDidacticCompiler().compileSynchronously(uno)
        val tree = edu.itssg.compiler.ui.tree.VisualTreeBuilder.fromSemantic(report.semanticTree, report.errors, report.symbolEntries)
        val picture = buildTreePicture(tree, "Árbol <semántico> & más")
        val svg = picture.toSvg()
        assertTrue(svg.startsWith("<?xml"))
        assertTrue("Árbol &lt;semántico&gt; &amp; más" in svg)
        assertEquals(tree.size, Regex("<rect x=").findAll(svg).count())
        assertTrue(picture.width > 0 && picture.height > 0)
    }
}
