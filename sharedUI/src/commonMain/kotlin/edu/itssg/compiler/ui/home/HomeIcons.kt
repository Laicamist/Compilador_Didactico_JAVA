package edu.itssg.compiler.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.data.ThemeMode
import edu.itssg.compiler.ui.theme.JAutomataTheme

/**
 * Íconos del encabezado del Inicio (trazo de 1.8 sobre 24 × 24, como en el boceto). Se dibujan en negro y
 * [Icon] los tiñe con el color del tema.
 */
internal object HomeIcons {

    /** Reloj con flecha de regreso. */
    val History: ImageVector by lazy {
        icon("Historial", strokes = listOf("M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5", "M12 7v5l3 2"))
    }

    /** Engrane. */
    val Settings: ImageVector by lazy {
        icon(
            "Ajustes",
            strokes = listOf(
                "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
                "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
                        "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                        "a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8" +
                        "l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1" +
                        "a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0" +
                        "a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
            )
        )
    }

    /** Tema del sistema: media luna (rellena) a la izquierda y medio sol a la derecha. */
    val ThemeSystem: ImageVector by lazy {
        icon(
            "Tema del sistema",
            fills = listOf("M12 3A9 9 0 0 0 12 21A11 11 0 0 1 12 3Z"),
            strokes = listOf(
                "M12 7.5A4.5 4.5 0 0 1 12 16.5",
                "M18.5 12H21",
                "M17.63 8.75L19.79 7.5",
                "M17.63 15.25L19.79 16.5",
                "M15.25 6.37L16.5 4.21",
                "M15.25 17.63L16.5 19.79"
            ),
            thinStrokes = listOf("M12 2.5V21.5")
        )
    }

    /** Tema oscuro: luna. */
    val ThemeDark: ImageVector by lazy {
        icon("Tema oscuro", strokes = listOf("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"))
    }

    /** Tema claro: sol. */
    val ThemeLight: ImageVector by lazy {
        icon(
            "Tema claro",
            strokes = listOf(
                "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z",
                "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2",
                "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
                "M4.93 19.07l1.41-1.41", "M17.66 6.34l1.41-1.41"
            )
        )
    }

    fun forTheme(mode: ThemeMode): ImageVector = when (mode) {
        ThemeMode.SYSTEM -> ThemeSystem
        ThemeMode.LIGHT -> ThemeLight
        ThemeMode.DARK -> ThemeDark
    }

    private fun icon(
        name: String,
        strokes: List<String> = emptyList(),
        fills: List<String> = emptyList(),
        thinStrokes: List<String> = emptyList()
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        val ink = SolidColor(Color.Black)
        for (d in fills) builder.addPath(pathData = addPathNodes(d), fill = ink)
        for ((paths, width) in listOf(strokes to 1.8f, thinStrokes to 1.2f)) {
            for (d in paths) {
                builder.addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = ink,
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }
        return builder.build()
    }
}

/** Forma común de los botones del encabezado (borde redondeado, como en el boceto). */
private val HeaderButtonShape = RoundedCornerShape(12.dp)

/** Altura de los botones del encabezado (objetivo táctil de 44 dp). */
internal val HeaderButtonSize: Dp = 44.dp

@Composable
private fun headerButtonColor(): Color {
    val colors = JAutomataTheme.colors
    return if (colors.isDark) colors.surface4 else colors.surface1
}

/** Botón cuadrado de ícono con borde redondeado (Ajustes, tema). */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    Surface(
        onClick = onClick,
        shape = HeaderButtonShape,
        color = headerButtonColor(),
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.borderStrong),
        modifier = modifier.size(HeaderButtonSize)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Botón de tema: el ícono muestra el modo **actual** (medio sol / media luna = Sistema, luna = Oscuro, sol = Claro) y
 * al tocarlo pasa al siguiente ([nextThemeMode]). El cambio de ícono gira y se desvanece.
 */
@Composable
internal fun ThemeModeButton(
    mode: ThemeMode,
    animationsEnabled: Boolean,
    onChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = JAutomataTheme.colors
    val next = nextThemeMode(mode)
    Surface(
        onClick = { onChange(next) },
        shape = HeaderButtonShape,
        color = headerButtonColor(),
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.borderStrong),
        modifier = modifier.size(HeaderButtonSize)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val description = "Tema: ${mode.displayName}. Cambiar a ${next.displayName.lowercase()}"
            if (animationsEnabled) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.6f)) togetherWith
                                (fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.6f))
                    },
                    label = "themeIcon"
                ) { shown ->
                    Icon(HomeIcons.forTheme(shown), contentDescription = description, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                }
            } else {
                Icon(HomeIcons.forTheme(mode), contentDescription = description, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Botón con ícono y texto (Historial); en pantallas angostas queda solo el ícono. */
@Composable
internal fun HeaderLabeledButton(
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showLabel) {
        HeaderIconButton(icon = icon, description = label, onClick = onClick, modifier = modifier)
        return
    }
    val colors = JAutomataTheme.colors
    Surface(
        onClick = onClick,
        shape = HeaderButtonShape,
        color = headerButtonColor(),
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.borderStrong),
        modifier = modifier.height(HeaderButtonSize)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(19.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
        }
    }
}
