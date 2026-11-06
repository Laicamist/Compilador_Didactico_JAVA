package edu.itssg.compiler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import edu.itssg.compiler.core.error.ErrorPhaseType
import edu.itssg.compiler.core.lexer.TokenCategory
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.state.CompilerPhase
import org.jetbrains.compose.resources.Font
import proyecto_automatas.sharedui.generated.resources.Res
import proyecto_automatas.sharedui.generated.resources.alice_regular
import proyecto_automatas.sharedui.generated.resources.sora_bold
import proyecto_automatas.sharedui.generated.resources.sora_medium
import proyecto_automatas.sharedui.generated.resources.sora_regular
import proyecto_automatas.sharedui.generated.resources.sora_semibold

/**
 * Paleta de J-Autómata Studio (especificación del 31 oct 2026): fondo neutro y un color por fase que se repite
 * en la barra de fases, las animaciones, las hojas de ayuda y los errores.
 */
@Immutable
data class JAutomataColors(
    val isDark: Boolean,
    val background: Color,
    /** Superficies de la más baja a la más alta (paneles, tarjetas, menús). */
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val surface5: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    // Fases
    val lexical: Color,
    val syntactic: Color,
    val semantic: Color,
    val intermediate: Color,
    val optimization: Color,
    // Errores
    val errorLexical: Color,
    val errorSyntactic: Color,
    val errorSemantic: Color,
    // Editor
    val editorBackground: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val gutterTextActive: Color,
    val currentLine: Color,
    val selection: Color,
    val findMatch: Color,
    val findMatchCurrent: Color,
    val syntax: SyntaxColors
) {
    fun phase(phase: CompilerPhase): Color = when (phase) {
        CompilerPhase.LEXICAL -> lexical
        CompilerPhase.SYNTACTIC -> syntactic
        CompilerPhase.SEMANTIC -> semantic
        CompilerPhase.INTERMEDIATE_CODE -> intermediate
        CompilerPhase.OPTIMIZATION -> optimization
    }

    fun error(type: ErrorPhaseType): Color = when (type) {
        ErrorPhaseType.LEXICAL -> errorLexical
        ErrorPhaseType.SYNTACTIC -> errorSyntactic
        ErrorPhaseType.SEMANTIC -> errorSemantic
    }
}

/** Colores del resaltado de sintaxis, agrupados por familia de token de la tabla oficial. */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val constant: Color,
    val mainMethod: Color,
    val operator: Color,
    val punctuation: Color,
    val identifier: Color,
    val comment: Color
) {
    fun forCategory(category: TokenCategory): Color = when (category) {
        TokenCategory.ID -> identifier
        TokenCategory.TD, TokenCategory.STR, TokenCategory.TM, TokenCategory.TDD -> type
        TokenCategory.VTS, TokenCategory.VTC -> string
        TokenCategory.VTE, TokenCategory.VTD, TokenCategory.VTF -> number
        TokenCategory.VTB, TokenCategory._NU -> constant
        TokenCategory.PMAIN -> mainMethod
        TokenCategory.ODA, TokenCategory.OPA, TokenCategory.OPL, TokenCategory.OPR, TokenCategory.OPT,
        TokenCategory.INC, TokenCategory.DEC, TokenCategory.OPB, TokenCategory.XOR, TokenCategory.OPN -> operator
        TokenCategory.FI, TokenCategory.LLA, TokenCategory.LLC, TokenCategory.PA, TokenCategory.PC,
        TokenCategory.COA, TokenCategory.COC, TokenCategory.DOP, TokenCategory.CA, TokenCategory.CO -> punctuation
        TokenCategory.EOF -> identifier
        else -> keyword // MA, CL, PRIF, PRW, RTN, NW, ST, ... (palabras reservadas)
    }
}

/**
 * Familias tipográficas de la especificación: Alice (texto), Sora (títulos) y JetBrains Mono (código).
 *
 * Alice y Sora vienen de `composeResources/font` (licencia SIL OFL 1.1, en `composeResources/files/licencias`).
 * Las fuentes de recursos se cargan dentro de la composición, por eso [text] y [title] son `@Composable` y
 * se leen de [LocalJAutomataFontFamilies], que llena [JAutomataTheme].
 *
 * [code] sigue siendo la monoespaciada del sistema hasta agregar JetBrains Mono. Es un valor normal (no
 * `@Composable`) porque el editor arma su `TextStyle` dentro de `remember`.
 */
object JAutomataFonts {
    val text: FontFamily
        @Composable @ReadOnlyComposable
        get() = LocalJAutomataFontFamilies.current.text

    val title: FontFamily
        @Composable @ReadOnlyComposable
        get() = LocalJAutomataFontFamilies.current.title

    val code: FontFamily = FontFamily.Monospace
}

@Immutable
class JAutomataFontFamilies(val text: FontFamily, val title: FontFamily)

/** Valor por defecto (fuentes del sistema) para vistas previas o pantallas fuera de [JAutomataTheme]. */
val LocalJAutomataFontFamilies = staticCompositionLocalOf {
    JAutomataFontFamilies(text = FontFamily.Serif, title = FontFamily.SansSerif)
}

/** Carga Alice y los pesos de Sora (Regular 400, Medium 500, SemiBold 600, Bold 700) una sola vez. */
@Composable
private fun rememberJAutomataFontFamilies(): JAutomataFontFamilies {
    val alice = Font(Res.font.alice_regular, FontWeight.Normal)
    val soraRegular = Font(Res.font.sora_regular, FontWeight.Normal)
    val soraMedium = Font(Res.font.sora_medium, FontWeight.Medium)
    val soraSemiBold = Font(Res.font.sora_semibold, FontWeight.SemiBold)
    val soraBold = Font(Res.font.sora_bold, FontWeight.Bold)
    return remember(alice, soraRegular, soraMedium, soraSemiBold, soraBold) {
        JAutomataFontFamilies(
            text = FontFamily(alice),
            title = FontFamily(soraRegular, soraMedium, soraSemiBold, soraBold)
        )
    }
}

/**
 * Tipografía de Material con las familias de la app: títulos y encabezados en Sora; texto y etiquetas
 * (botones, campos, menús) en Alice. Así todo `Text` sin `fontFamily` explícito usa Alice.
 */
private fun jAutomataTypography(fonts: JAutomataFontFamilies): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fonts.title),
        displayMedium = base.displayMedium.copy(fontFamily = fonts.title),
        displaySmall = base.displaySmall.copy(fontFamily = fonts.title),
        headlineLarge = base.headlineLarge.copy(fontFamily = fonts.title),
        headlineMedium = base.headlineMedium.copy(fontFamily = fonts.title),
        headlineSmall = base.headlineSmall.copy(fontFamily = fonts.title),
        titleLarge = base.titleLarge.copy(fontFamily = fonts.title),
        titleMedium = base.titleMedium.copy(fontFamily = fonts.title),
        titleSmall = base.titleSmall.copy(fontFamily = fonts.title),
        bodyLarge = base.bodyLarge.copy(fontFamily = fonts.text),
        bodyMedium = base.bodyMedium.copy(fontFamily = fonts.text),
        bodySmall = base.bodySmall.copy(fontFamily = fonts.text),
        labelLarge = base.labelLarge.copy(fontFamily = fonts.text),
        labelMedium = base.labelMedium.copy(fontFamily = fonts.text),
        labelSmall = base.labelSmall.copy(fontFamily = fonts.text)
    )
}

val DarkJAutomataColors = JAutomataColors(
    isDark = true,
    background = Color(0xFF0A0A0B),
    surface1 = Color(0xFF0D0D0F),
    surface2 = Color(0xFF101012),
    surface3 = Color(0xFF131315),
    surface4 = Color(0xFF161618),
    surface5 = Color(0xFF1A1A1D),
    border = Color(0xFF232327),
    borderStrong = Color(0xFF2A2A2F),
    textPrimary = Color(0xFFE8E6E1),
    textSecondary = Color(0xFFA6A4A0),
    textMuted = Color(0xFF6E6D6A),
    accent = Color(0xFF5B9BFF),
    onAccent = Color(0xFF06101F),
    lexical = Color(0xFF5B9BFF),
    syntactic = Color(0xFFA78BFA),
    semantic = Color(0xFF2DD4BF),
    intermediate = Color(0xFFF5B841),
    optimization = Color(0xFF4ADE80),
    errorLexical = Color(0xFFF87171),
    errorSyntactic = Color(0xFFFB923C),
    errorSemantic = Color(0xFFF472B6),
    editorBackground = Color(0xFF0D0D0F),
    gutterBackground = Color(0xFF0A0A0B),
    gutterText = Color(0xFF55545A),
    gutterTextActive = Color(0xFFC9C7C2),
    currentLine = Color(0x0FFFFFFF),
    selection = Color(0x555B9BFF),
    findMatch = Color(0x40F5B841),
    findMatchCurrent = Color(0x90F5B841),
    syntax = SyntaxColors(
        keyword = Color(0xFFC4A7FF),
        type = Color(0xFF5ED3C7),
        string = Color(0xFFE6C07B),
        number = Color(0xFFF2A65A),
        constant = Color(0xFFFF8FA3),
        mainMethod = Color(0xFF82AAFF),
        operator = Color(0xFF89DDFF),
        punctuation = Color(0xFF9A9894),
        identifier = Color(0xFFE8E6E1),
        comment = Color(0xFF6B6B76)
    )
)

val LightJAutomataColors = JAutomataColors(
    isDark = false,
    background = Color(0xFFF7F5F0),
    surface1 = Color(0xFFFBFAF7),
    surface2 = Color(0xFFF2F0EA),
    surface3 = Color(0xFFECE9E2),
    surface4 = Color(0xFFE6E2DA),
    surface5 = Color(0xFFDFDBD2),
    border = Color(0xFFDAD6CD),
    borderStrong = Color(0xFFCBC6BB),
    textPrimary = Color(0xFF1C1B19),
    textSecondary = Color(0xFF55524C),
    textMuted = Color(0xFF8A877F),
    accent = Color(0xFF2563EB),
    onAccent = Color(0xFFFFFFFF),
    lexical = Color(0xFF2563EB),
    syntactic = Color(0xFF7C3AED),
    semantic = Color(0xFF0D9488),
    intermediate = Color(0xFFB45309),
    optimization = Color(0xFF16A34A),
    errorLexical = Color(0xFFDC2626),
    errorSyntactic = Color(0xFFEA580C),
    errorSemantic = Color(0xFFDB2777),
    editorBackground = Color(0xFFFBFAF7),
    gutterBackground = Color(0xFFF2F0EA),
    gutterText = Color(0xFFA9A59C),
    gutterTextActive = Color(0xFF3A3833),
    currentLine = Color(0x0A000000),
    selection = Color(0x402563EB),
    findMatch = Color(0x50F5B841),
    findMatchCurrent = Color(0xA0F59E0B),
    syntax = SyntaxColors(
        keyword = Color(0xFF7C3AED),
        type = Color(0xFF0F766E),
        string = Color(0xFFA16207),
        number = Color(0xFFC2410C),
        constant = Color(0xFFBE185D),
        mainMethod = Color(0xFF1D4ED8),
        operator = Color(0xFF0369A1),
        punctuation = Color(0xFF57534E),
        identifier = Color(0xFF1C1B19),
        comment = Color(0xFF8A877F)
    )
)

val LocalJAutomataColors = staticCompositionLocalOf { DarkJAutomataColors }

object JAutomataTheme {
    val colors: JAutomataColors
        @Composable get() = LocalJAutomataColors.current
}

/** `true` si el tema efectivo es oscuro (resuelve [ThemeMode.SYSTEM] con el sistema operativo). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun JAutomataTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val colors = if (themeMode.isDark()) DarkJAutomataColors else LightJAutomataColors
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.syntactic,
            tertiary = colors.semantic,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface2,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface4,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerLowest = colors.surface1,
            surfaceContainerLow = colors.surface2,
            surfaceContainer = colors.surface3,
            surfaceContainerHigh = colors.surface4,
            surfaceContainerHighest = colors.surface5,
            outline = colors.borderStrong,
            outlineVariant = colors.border,
            error = colors.errorLexical
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.syntactic,
            tertiary = colors.semantic,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface1,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerLowest = colors.surface1,
            surfaceContainerLow = colors.surface2,
            surfaceContainer = colors.surface3,
            surfaceContainerHigh = colors.surface4,
            surfaceContainerHighest = colors.surface5,
            outline = colors.borderStrong,
            outlineVariant = colors.border,
            error = colors.errorLexical
        )
    }
    val selectionColors = TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.selection)

    val fonts = rememberJAutomataFontFamilies()
    val typography = remember(fonts) { jAutomataTypography(fonts) }

    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalJAutomataColors provides colors,
            LocalJAutomataFontFamilies provides fonts,
            LocalTextSelectionColors provides selectionColors,
            content = content
        )
    }
}
