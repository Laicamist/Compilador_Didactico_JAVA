package edu.itssg.compiler.ui.settings

import edu.itssg.compiler.core.security.CompilerSecurityConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFormatTest {

    @Test
    fun separadorDeMiles() {
        assertEquals("0", groupDigits(0))
        assertEquals("999", groupDigits(999))
        assertEquals("1,000", groupDigits(1_000))
        assertEquals("1,000,000", groupDigits(1_000_000))
        assertEquals("-12,345", groupDigits(-12_345))
    }

    @Test
    fun limitesPorDefecto() {
        val rows = securityLimitRows(CompilerSecurityConfig()).associate { it.label to it.value }
        assertEquals("1,000,000 caracteres", rows["Tamaño del código"])
        assertEquals("100,000", rows["Tokens por compilación"])
        assertEquals("256 niveles", rows["Anidamiento de reglas"])
        assertEquals("32 niveles", rows["Paréntesis anidados"])
    }
}
