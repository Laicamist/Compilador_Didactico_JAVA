package edu.itssg.compiler.ui.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsCodecTest {

    @Test
    fun idaYVueltaConservaTodosLosAjustes() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            editorFontSizeSp = 18,
            animationsEnabled = false,
            tourCompleted = true
        )
        assertEquals(settings, AppSettingsCodec.decode(AppSettingsCodec.encode(settings)))
    }

    @Test
    fun baseVaciaDaLosValoresPorDefecto() {
        assertEquals(AppSettings(), AppSettingsCodec.decode(emptyMap()))
    }

    @Test
    fun valoresInvalidosUsanElDefectoSoloEnEseAjuste() {
        val decoded = AppSettingsCodec.decode(
            mapOf(
                AppSettingsCodec.KEY_THEME_MODE to "MORADO",
                AppSettingsCodec.KEY_EDITOR_FONT_SIZE to "99",
                AppSettingsCodec.KEY_ANIMATIONS_ENABLED to "quizá",
                AppSettingsCodec.KEY_TOUR_COMPLETED to "true",
                "ajuste_de_una_version_futura" to "x"
            )
        )
        assertEquals(
            AppSettings(
                themeMode = ThemeMode.SYSTEM,
                editorFontSizeSp = AppSettings.MAX_EDITOR_FONT_SP,
                animationsEnabled = true,
                tourCompleted = true
            ),
            decoded
        )
    }
}
