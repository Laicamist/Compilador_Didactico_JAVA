package edu.itssg.compiler.ui.files

import edu.itssg.compiler.core.security.CompilerSecurityConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceFileLoaderTest {

    private val config = CompilerSecurityConfig()

    private fun load(name: String, bytes: ByteArray, cfg: CompilerSecurityConfig = config) =
        SourceFileLoader.load(PickedSourceFile(name, bytes), cfg)

    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun loadsUtf8JavaFile() {
        val result = assertIs<SourceLoadResult.Success>(load("Main.java", "int año = 5;".encodeToByteArray()))
        assertEquals("int año = 5;", result.text)
        assertEquals("Main.java", result.title)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun stripsUtf8BomAndNormalizesLineBreaks() {
        val bytes = bytesOf(0xEF, 0xBB, 0xBF) + "a;\r\nb;\rc;".encodeToByteArray()
        val result = assertIs<SourceLoadResult.Success>(load("p.txt", bytes))
        assertEquals("a;\nb;\nc;", result.text)
    }

    @Test
    fun fallsBackToWindows1252() {
        // "año" guardado como ANSI: ñ = 0xF1, que no es UTF-8 válido.
        val bytes = bytesOf('a'.code, 0xF1, 'o'.code, 0x93, 'x'.code, 0x94)
        val result = assertIs<SourceLoadResult.Success>(load("Main.java", bytes))
        assertEquals("año\u201Cx\u201D", result.text)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun decodesUtf16WithBom() {
        val bytes = bytesOf(0xFF, 0xFE, 'i'.code, 0, 'n'.code, 0, 't'.code, 0)
        val result = assertIs<SourceLoadResult.Success>(load("Main.java", bytes))
        assertEquals("int", result.text)
    }

    @Test
    fun rejectsOtherExtensions() {
        val failure = assertIs<SourceLoadResult.Failure>(load("foto.png", "x".encodeToByteArray()))
        assertTrue(failure.message.contains(".java"))
        assertNotNull(SourceFileLoader.validateName("Main.class"))
        assertNull(SourceFileLoader.validateName("C:\\tareas\\Main.JAVA"))
    }

    @Test
    fun rejectsBinaryContent() {
        assertIs<SourceLoadResult.Failure>(load("Main.java", bytesOf('a'.code, 0, 'b'.code)))
        val controls = ByteArray(40) { 0x01 }
        assertIs<SourceLoadResult.Failure>(load("Main.java", controls))
    }

    @Test
    fun removesAFewStrayControlCharacters() {
        val result = assertIs<SourceLoadResult.Success>(load("Main.java", bytesOf('a'.code, 0x07, 'b'.code)))
        assertEquals("ab", result.text)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun rejectsEmptyAndBlankFiles() {
        assertIs<SourceLoadResult.Failure>(load("Main.java", ByteArray(0)))
        assertIs<SourceLoadResult.Failure>(load("Main.java", "  \n\t ".encodeToByteArray()))
    }

    @Test
    fun enforcesByteAndCharacterLimits() {
        val small = CompilerSecurityConfig(maxSourceLength = 10)
        assertEquals(40, SourceFileLoader.maxFileBytes(small))
        assertIs<SourceLoadResult.Failure>(load("Main.java", "a".repeat(41).encodeToByteArray(), small))
        assertIs<SourceLoadResult.Failure>(load("Main.java", "a".repeat(11).encodeToByteArray(), small))
        assertIs<SourceLoadResult.Success>(load("Main.java", "a".repeat(10).encodeToByteArray(), small))
    }

    @Test
    fun declaredSizeIsCheckedEvenIfFewerBytesWereRead() {
        val file = PickedSourceFile("Main.java", "int x;".encodeToByteArray(), declaredSize = 50L * 1024 * 1024)
        assertIs<SourceLoadResult.Failure>(SourceFileLoader.load(file, config))
    }

    @Test
    fun displayNameDropsFolders() {
        assertEquals("Main.java", SourceFileLoader.displayName("/storage/emulated/0/Download/Main.java"))
        assertEquals("Main.java", SourceFileLoader.displayName("C:\\Users\\alumno\\Main.java"))
    }
}
