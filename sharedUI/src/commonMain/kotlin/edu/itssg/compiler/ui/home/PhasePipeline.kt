package edu.itssg.compiler.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.state.CompilerPhase
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Una etapa del proceso animado del Inicio.
 *
 * @property abbr Texto dentro del círculo (Lex, Sin, Árb…).
 * @property sample Muestra de lo que produce la etapa con `int x = 5 + 3;` (sale de la compilación real, ver
 *   [pipelineStages]).
 */
@Immutable
internal data class PipelineStage(
    val abbr: String,
    val name: String,
    val phase: CompilerPhase,
    val sample: String
)

/** Tiempo que tarda el token en ir de la primera a la última etapa. */
private const val TRAVEL_MS = 6_000

/** Ciclo completo: viaje + pausa con todo encendido + desvanecido antes de reiniciar. */
private const val CYCLE_MS = 7_400
private const val FADE_MS = 500

/** Ancho (en fracción del recorrido) del pulso de cada círculo cuando lo alcanza el token. */
private const val PULSE_WIDTH = 0.14f

/**
 * Reloj de la animación. Se lee **solo en las fases de dibujo** (`drawBehind`, `graphicsLayer`, `Canvas`), así cada
 * cuadro redibuja sin recomponer. Sin animaciones ([time] = `null`) todo queda encendido y sin token.
 */
private class PipelineClock(private val time: State<Float>?) {
    val animated: Boolean get() = time != null

    /** 0..1: posición del token entre el centro de la primera etapa y el de la última. */
    val progress: Float get() = time?.let { (it.value / TRAVEL_MS).coerceIn(0f, 1f) } ?: 1f

    /** 1 casi todo el ciclo; baja a 0 al final para que el reinicio no dé un salto. */
    val fade: Float
        get() = time?.let { 1f - ((it.value - (CYCLE_MS - FADE_MS)) / FADE_MS).coerceIn(0f, 1f) } ?: 1f

    fun position(index: Int, count: Int): Float = if (count <= 1) 0f else index / (count - 1f)

    /** 0..1: qué tan "encendida" está la etapa (ya la pasó el token). */
    fun reached(index: Int, count: Int): Float =
        ((progress - position(index, count) + 0.015f) / 0.03f).coerceIn(0f, 1f) * fade

    /** 0..1..0 mientras el token acaba de llegar a la etapa. */
    fun pulse(index: Int, count: Int): Float {
        if (!animated) return 0f
        val d = progress - position(index, count)
        if (d < 0f || d > PULSE_WIDTH) return 0f
        return sin(PI * d / PULSE_WIDTH).toFloat() * fade
    }
}

/** Color del recorrido en [p] (0..1), mezclando los colores de las etapas vecinas. */
private fun colorAt(stageColors: List<Color>, p: Float): Color {
    if (stageColors.size == 1) return stageColors[0]
    val x = p * (stageColors.size - 1)
    val i = x.toInt().coerceIn(0, stageColors.size - 2)
    return lerp(stageColors[i], stageColors[i + 1], (x - i).coerceIn(0f, 1f))
}

/**
 * Proceso del compilador del Inicio (boceto del 31 oct 2026): círculos grandes por etapa unidos por una barra; un
 * token luminoso recorre la barra, la va llenando con el color de cada fase y cada círculo pulsa al recibirlo.
 * Debajo de cada círculo van el nombre y lo que produce la etapa con el ejemplo.
 *
 * Tamaños: 76 dp en pantallas anchas, 62 dp en medianas y 46 dp en teléfonos (ahí solo quedan los círculos).
 */
@Composable
internal fun PhasePipeline(
    stages: List<PipelineStage>,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (stages.isEmpty()) return
    val colors = JAutomataTheme.colors
    val time: State<Float>? = if (animationsEnabled) {
        rememberInfiniteTransition(label = "pipeline").animateFloat(
            initialValue = 0f,
            targetValue = CYCLE_MS.toFloat(),
            animationSpec = infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "pipelineClock"
        )
    } else {
        null
    }
    val clock = remember(time) { PipelineClock(time) }
    val stageColors = stages.map { colors.phase(it.phase) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        val diameter = when {
            compact -> 46.dp
            maxWidth < 860.dp -> 62.dp
            else -> 76.dp
        }
        val count = stages.size
        val trackColor = if (colors.isDark) colors.surface5 else colors.surface4
        val trackEdge = colors.border
        val coreColor = if (colors.isDark) Color.White else colors.surface1

        Box(Modifier.fillMaxWidth()) {
            // ------------------------------------------------ Barra (detrás de los círculos)
            Canvas(Modifier.fillMaxWidth().height(diameter)) {
                val columnWidth = size.width / count
                val x0 = columnWidth / 2f
                val x1 = size.width - columnWidth / 2f
                val y = size.height / 2f
                val thickness = (if (compact) 5.dp else 8.dp).toPx()

                // Riel: borde fino + relleno, para que se lea en ambos temas.
                drawLine(trackEdge, Offset(x0, y), Offset(x1, y), strokeWidth = thickness + 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(trackColor, Offset(x0, y), Offset(x1, y), strokeWidth = thickness, cap = StrokeCap.Round)

                val p = clock.progress
                val fade = clock.fade
                val xp = x0 + (x1 - x0) * p
                if (xp > x0 + 0.5f && fade > 0f) {
                    val stops = stageColors.mapIndexed { i, c -> clock.position(i, count) to c }.toTypedArray()
                    val brush = Brush.horizontalGradient(*stops, startX = x0, endX = x1)
                    drawLine(brush, Offset(x0, y), Offset(xp, y), strokeWidth = thickness, cap = StrokeCap.Round, alpha = fade)
                    // Brillo suave sobre la parte llena.
                    drawLine(
                        Color.White.copy(alpha = if (colors.isDark) 0.18f else 0.35f),
                        Offset(x0, y - thickness * 0.18f),
                        Offset(xp, y - thickness * 0.18f),
                        strokeWidth = thickness * 0.28f,
                        cap = StrokeCap.Round,
                        alpha = fade
                    )
                }
                if (clock.animated && fade > 0f) {
                    // Token: halo de dos capas con el color de la fase y núcleo claro.
                    val c = colorAt(stageColors, p)
                    val center = Offset(xp, y)
                    drawCircle(c.copy(alpha = 0.16f * fade), radius = thickness * 2.6f, center = center)
                    drawCircle(c.copy(alpha = 0.38f * fade), radius = thickness * 1.6f, center = center)
                    drawCircle(c.copy(alpha = fade), radius = thickness * 0.95f, center = center)
                    drawCircle(coreColor.copy(alpha = fade), radius = thickness * 0.5f, center = center)
                }
            }

            // ------------------------------------------------ Etapas
            Row(Modifier.fillMaxWidth()) {
                stages.forEachIndexed { index, stage ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        StageCircle(
                            abbr = stage.abbr,
                            color = stageColors[index],
                            index = index,
                            count = count,
                            clock = clock,
                            diameter = diameter
                        )
                        if (!compact) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stage.name,
                                fontSize = if (diameter < 70.dp) 13.sp else 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stage.sample,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontFamily = JAutomataFonts.code,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Círculo de una etapa: fondo sólido (tapa la barra), anillo del color de la fase, relleno al encenderse y pulso. */
@Composable
private fun StageCircle(
    abbr: String,
    color: Color,
    index: Int,
    count: Int,
    clock: PipelineClock,
    diameter: Dp
) {
    val colors = JAutomataTheme.colors
    val base = colors.surface3
    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer {
                val k = 1f + 0.08f * clock.pulse(index, count)
                scaleX = k
                scaleY = k
            }
            .drawBehind {
                val r = size.minDimension / 2f
                val ring = 2.5.dp.toPx()
                val pulse = clock.pulse(index, count)
                if (pulse > 0f) {
                    // Onda que sale del círculo al recibir el token.
                    drawCircle(color.copy(alpha = 0.10f * pulse), radius = r + 16.dp.toPx() * pulse)
                    drawCircle(color.copy(alpha = 0.18f * pulse), radius = r + 8.dp.toPx() * pulse)
                }
                drawCircle(base, radius = r)
                val reached = clock.reached(index, count)
                if (reached > 0f) drawCircle(color.copy(alpha = 0.20f * reached), radius = r)
                drawCircle(color, radius = r - ring / 2f, style = Stroke(width = ring))
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            abbr,
            fontFamily = JAutomataFonts.code,
            fontWeight = FontWeight.Bold,
            fontSize = (diameter.value * 0.22f).sp,
            color = color
        )
    }
}

/** Recuadro "ENTRADA  int x = 5 + 3;" que va arriba del proceso, con los colores del editor. */
@Composable
internal fun DemoInputChip(modifier: Modifier = Modifier) {
    val colors = JAutomataTheme.colors
    val syntax = colors.syntax
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (colors.isDark) colors.surface4 else colors.surface1)
            .border(1.dp, colors.borderStrong, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text("ENTRADA", fontSize = 11.sp, letterSpacing = 1.sp, color = colors.textSecondary)
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.type)) { append("int") }
                withStyle(SpanStyle(color = syntax.identifier)) { append(" x ") }
                withStyle(SpanStyle(color = syntax.operator)) { append("=") }
                append(" ")
                withStyle(SpanStyle(color = syntax.number)) { append("5") }
                withStyle(SpanStyle(color = syntax.operator)) { append(" + ") }
                withStyle(SpanStyle(color = syntax.number)) { append("3") }
                withStyle(SpanStyle(color = syntax.punctuation)) { append(";") }
            },
            fontSize = 15.sp,
            fontFamily = JAutomataFonts.code,
            color = colors.textPrimary
        )
    }
}
