package edu.itssg.compiler.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.itssg.compiler.ui.theme.JAutomataFonts
import edu.itssg.compiler.ui.theme.JAutomataTheme
import edu.itssg.compiler.ui.viewmodel.TourStep
import kotlin.math.roundToInt

/**
 * Tour de bienvenida: oscurece la pantalla, deja ver y remarca el elemento [target] (coordenadas de la pantalla de
 * Inicio) y pone el globo debajo o encima de él. Si todavía no se conoce [target], el globo va centrado.
 */
@Composable
internal fun TourOverlay(step: TourStep, target: Rect?, onNext: () -> Unit, onSkip: () -> Unit) {
    val colors = JAutomataTheme.colors
    val density = LocalDensity.current
    val isLast = step.ordinal == TourStep.entries.lastIndex
    var cardHeight by remember { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val pad = with(density) { 6.dp.toPx() }
        val gap = with(density) { 14.dp.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        val cardWidthDp = minOf(340.dp, maxWidth - 32.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val accent = colors.accent

        // Fondo oscuro con un hueco sobre el elemento señalado; también bloquea los toques al resto de la pantalla.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            if (target != null) {
                val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
                val radius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                drawRoundRect(Color.Transparent, hole.topLeft, Size(hole.width, hole.height), radius, blendMode = BlendMode.Clear)
                drawRoundRect(accent, hole.topLeft, Size(hole.width, hole.height), radius, style = Stroke(width = 2.dp.toPx()))
            }
        }

        val x: Float
        val y: Float
        if (target == null) {
            x = (widthPx - cardWidthPx) / 2f
            y = (heightPx - cardHeight) / 2f
        } else {
            x = (target.center.x - cardWidthPx / 2f).coerceIn(margin, (widthPx - cardWidthPx - margin).coerceAtLeast(margin))
            val below = target.bottom + pad + gap
            y = if (below + cardHeight <= heightPx - margin || target.center.y < heightPx / 2f) {
                below.coerceAtMost((heightPx - cardHeight - margin).coerceAtLeast(margin))
            } else {
                (target.top - pad - gap - cardHeight).coerceAtLeast(margin)
            }
        }

        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(cardWidthDp)
                .onSizeChanged { cardHeight = it.height }
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface1)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "${step.ordinal + 1} de ${TourStep.entries.size}",
                fontSize = 12.sp,
                color = colors.textMuted
            )
            Text(step.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = JAutomataFonts.title, color = colors.textPrimary)
            Text(tourText(step), fontSize = 14.sp, color = colors.textSecondary)
            Row {
                TextButton(onClick = onSkip) { Text("Omitir", color = colors.textSecondary) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) { Text(if (isLast) "Terminar" else "Siguiente") }
            }
        }
    }
}
